/*
 * IchthysOS: TrickyStore-compatible hardware attestation chain forger.
 *
 * Intercepts attestation certificate chains at two levels:
 *
 *   A) PRIMARY — KeyStoreSecurityLevel.generateKey() (the "generateKey path"):
 *      Called via maybeForgeChainDer() with the raw DER bytes from KeyMetadata.certificate
 *      and KeyMetadata.certificateChain. This is the hook that fires for Play Integrity /
 *      DroidGuard because microG's attestation flow goes through KeyPairGenerator →
 *      AndroidKeyStoreKeyPairGeneratorSpi → KeyStoreSecurityLevel, and the returned
 *      KeyMetadata carries the attestation certs. Forging here means every downstream
 *      consumer (including the keystore entry that getCertificateChain reads later) sees
 *      the forged chain automatically.
 *
 *   B) FALLBACK — AndroidKeyStoreSpi.engineGetCertificateChain() (the "getCertChain path"):
 *      Called via maybeForgeChain() on the parsed Certificate[]. Defense-in-depth for any
 *      code path that reads the cert chain from the keystore after generation.
 *
 * The forging process:
 *   1. Reads the keybox XML from Settings.Secure.spoof_trickystore_keybox (Base64-encoded).
 *   2. Parses the keybox into an ECDSA and an RSA key + chain.
 *   3. Rewrites the leaf certificate's key-attestation extension (OID 1.3.6.1.4.1.11129.2.1.17):
 *      - rootOfTrust.verifiedBootState → Verified (locked bootloader)
 *      - rootOfTrust.deviceLocked → true
 *      - attestationSecurityLevel → TrustedEnvironment (1)
 *      - keymasterSecurityLevel → TrustedEnvironment (1)
 *      - optionally overrides the OS patch level
 *   4. Re-signs the modified leaf with the keybox private key.
 *   5. Replaces the chain with the keybox intermediate + root certs.
 *
 * Settings keys:
 *   - spoof_trickystore_keybox: Base64-encoded keybox XML
 *   - spoof_trickystore_target: newline-separated package names (suffix '!' = cert, '?' = leaf)
 *   - spoof_trickystore_patch: YYYY-MM-DD security patch override
 *
 * @hide
 */
package com.android.internal.util.ichthys;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** @hide */
public final class KeyboxAttestation {
    private static final String TAG = "KeyboxAttestation";

    private static final String SETTING_KEYBOX = "spoof_trickystore_keybox";
    private static final String SETTING_TARGET = "spoof_trickystore_target";
    private static final String SETTING_PATCH = "spoof_trickystore_patch";

    /** OID for the Google key-attestation extension. */
    private static final String ATTESTATION_OID = "1.3.6.1.4.1.11129.2.1.17";

    // Lazy-initialized from Settings on first use; invalidated by a ContentObserver.
    private static volatile ParsedKeybox sCachedKeybox;
    private static volatile TargetConfig sCachedTargets;
    private static volatile String sCachedPatch;
    private static volatile boolean sSettingsRead;

    // -----------------------------------------------------------------------------------------
    // Public API (A) — called from KeyStoreSecurityLevel.generateKey [PRIMARY]
    // -----------------------------------------------------------------------------------------

    /**
     * Intercept the raw DER certificate bytes from KeyMetadata returned by generateKey().
     *
     * @param context   application context (used for package name + settings access)
     * @param leafDer   KeyMetadata.certificate — the attestation leaf cert (DER-encoded)
     * @param chainDer  KeyMetadata.certificateChain — remaining chain certs (concatenated DER),
     *                  may be null if no chain was returned
     * @return byte[2] { forgedLeafDer, forgedChainDer } if forging succeeded, null otherwise.
     *         The caller should replace metadata.certificate and metadata.certificateChain
     *         with the returned values.
     */
    public static byte[][] maybeForgeChainDer(Context context, byte[] leafDer, byte[] chainDer) {
        if (leafDer == null || leafDer.length == 0) return null;

        try {
            ensureSettings(context);

            final String callingPackage = context.getPackageName();

            breadcrumb(context, "genKey|" + callingPackage);

            final ParsedKeybox kb = sCachedKeybox;
            if (kb == null) {
                breadcrumb(context, "gk_skip|no_keybox|" + callingPackage);
                return null;
            }

            // Check targeting.
            final TargetConfig targets = sCachedTargets;
            if (targets != null && !targets.packages.isEmpty()
                    && !targets.packages.contains(callingPackage)) {
                breadcrumb(context, "gk_skip|not_targeted|" + callingPackage
                        + "|have=" + targets.packages);
                return null;
            }

            // Parse the leaf certificate.
            final CertificateFactory cf = CertificateFactory.getInstance("X.509");
            final X509Certificate leaf = (X509Certificate) cf.generateCertificate(
                    new ByteArrayInputStream(leafDer));

            // Only forge if the leaf has an attestation extension.
            final byte[] extValue = leaf.getExtensionValue(ATTESTATION_OID);
            if (extValue == null) {
                breadcrumb(context, "gk_skip|no_attest_ext|" + callingPackage);
                return null;
            }

            // Pick the keybox key that matches the leaf's algorithm.
            final String leafAlg = leaf.getPublicKey().getAlgorithm();
            final KeyboxKey kbKey;
            if ("EC".equals(leafAlg) || "ECDSA".equals(leafAlg)) {
                kbKey = kb.ecdsa;
            } else if ("RSA".equals(leafAlg)) {
                kbKey = kb.rsa;
            } else {
                Log.w(TAG, "Unsupported leaf algorithm: " + leafAlg);
                breadcrumb(context, "gk_skip|bad_alg|" + leafAlg);
                return null;
            }
            if (kbKey == null) {
                Log.w(TAG, "No keybox key for algorithm: " + leafAlg);
                breadcrumb(context, "gk_skip|no_kb_key|" + leafAlg);
                return null;
            }

            // Determine the mode for this package.
            int mode = MODE_AUTO;
            if (targets != null) {
                Integer m = targets.modes.get(callingPackage);
                if (m != null) mode = m;
            }

            // Forge the leaf certificate.
            final byte[] forgedLeafDer = forgeLeafCert(leaf, kbKey, mode);
            if (forgedLeafDer == null) {
                breadcrumb(context, "gk_skip|forge_null|" + callingPackage);
                return null;
            }

            // Build the forged chain: keybox intermediate + root certs, concatenated DER.
            final ByteArrayOutputStream chainOut = new ByteArrayOutputStream();
            for (X509Certificate cert : kbKey.chain) {
                final byte[] certDer = cert.getEncoded();
                chainOut.write(certDer, 0, certDer.length);
            }

            breadcrumb(context, "GK_FORGED|" + callingPackage
                    + "|alg=" + leafAlg + "|chain=" + (1 + kbKey.chain.length));

            return new byte[][] { forgedLeafDer, chainOut.toByteArray() };
        } catch (Exception e) {
            Log.e(TAG, "Failed to forge attestation chain (generateKey path)", e);
            breadcrumb(context, "GK_ERROR|" + e.getMessage());
            return null;
        }
    }

    // -----------------------------------------------------------------------------------------
    // Public API (B) — called from AndroidKeyStoreSpi.engineGetCertificateChain [FALLBACK]
    // -----------------------------------------------------------------------------------------

    /**
     * Possibly intercept the certificate chain returned by keystore.
     * Returns null if this caller should not be spoofed (no keybox, not targeted, etc.).
     * Otherwise returns the forged chain.
     */
    public static Certificate[] maybeForgeChain(Context context, Certificate[] original) {
        if (original == null || original.length == 0) return null;

        try {
            ensureSettings(context);

            final String callingPackage = context.getPackageName();

            // Diagnostic: record every call so we can verify the hook fires.
            // Readable from adb shell: settings get global ichthys_keybox_diag
            breadcrumb(context, "called|" + callingPackage
                    + "|chain=" + original.length);

            final ParsedKeybox kb = sCachedKeybox;
            if (kb == null) {
                breadcrumb(context, "skip|no_keybox|" + callingPackage);
                return null;
            }

            // Check targeting.
            final TargetConfig targets = sCachedTargets;
            if (targets != null && !targets.packages.isEmpty()
                    && !targets.packages.contains(callingPackage)) {
                breadcrumb(context, "skip|not_targeted|" + callingPackage
                        + "|have=" + targets.packages);
                return null;
            }

            // Only forge if the leaf has an attestation extension.
            if (!(original[0] instanceof X509Certificate)) {
                breadcrumb(context, "skip|not_x509|" + callingPackage);
                return null;
            }
            final X509Certificate leaf = (X509Certificate) original[0];
            final byte[] extValue = leaf.getExtensionValue(ATTESTATION_OID);
            if (extValue == null) {
                breadcrumb(context, "skip|no_attest_ext|" + callingPackage);
                return null;
            }

            // Pick the keybox key that matches the leaf's algorithm.
            final String leafAlg = leaf.getPublicKey().getAlgorithm();
            final KeyboxKey kbKey;
            if ("EC".equals(leafAlg) || "ECDSA".equals(leafAlg)) {
                kbKey = kb.ecdsa;
            } else if ("RSA".equals(leafAlg)) {
                kbKey = kb.rsa;
            } else {
                Log.w(TAG, "Unsupported leaf algorithm: " + leafAlg);
                breadcrumb(context, "skip|bad_alg|" + leafAlg);
                return null;
            }
            if (kbKey == null) {
                Log.w(TAG, "No keybox key for algorithm: " + leafAlg);
                breadcrumb(context, "skip|no_kb_key|" + leafAlg);
                return null;
            }

            // Determine the mode for this package.
            int mode = MODE_AUTO;
            if (targets != null) {
                Integer m = targets.modes.get(callingPackage);
                if (m != null) mode = m;
            }

            // Forge the leaf certificate.
            final byte[] forgedLeafDer = forgeLeafCert(leaf, kbKey, mode);
            if (forgedLeafDer == null) {
                breadcrumb(context, "skip|forge_null|" + callingPackage);
                return null;
            }

            // Build the final chain: forged leaf + keybox chain.
            final CertificateFactory cf = CertificateFactory.getInstance("X.509");
            final X509Certificate forgedLeaf = (X509Certificate) cf.generateCertificate(
                    new ByteArrayInputStream(forgedLeafDer));

            final Certificate[] result = new Certificate[1 + kbKey.chain.length];
            result[0] = forgedLeaf;
            System.arraycopy(kbKey.chain, 0, result, 1, kbKey.chain.length);

            breadcrumb(context, "FORGED|" + callingPackage
                    + "|alg=" + leafAlg + "|chain=" + result.length);
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Failed to forge attestation chain", e);
            breadcrumb(context, "ERROR|" + e.getMessage());
            return null;
        }
    }

    /** Write a diagnostic crumb to Settings.Global so we can read it from adb shell. */
    /**
     * IchthysOS: diagnostics only when {@code persist.sys.keybox.debug} is set. Unconditionally
     * this wrote "genKey|<package>" to Settings.Global (readable by every app) on every key
     * generation in every app, i.e. a cross-app activity log, plus a failing settings IPC for
     * callers that can't write Global settings.
     */
    private static final boolean DIAG =
            android.os.SystemProperties.getBoolean("persist.sys.keybox.debug", false);

    private static void breadcrumb(Context context, String msg) {
        if (!DIAG) return;
        try {
            Settings.Global.putString(context.getContentResolver(),
                    "ichthys_keybox_diag", msg);
        } catch (Exception ignored) {
            // Best-effort — don't let diagnostics break the attestation flow.
        }
    }

    // -----------------------------------------------------------------------------------------
    // Forge the leaf certificate
    // -----------------------------------------------------------------------------------------

    private static final int MODE_AUTO = 0;
    private static final int MODE_CERT_CHAIN = 1; // '!' suffix
    private static final int MODE_LEAF_ONLY = 2;  // '?' suffix

    /**
     * Forge a new leaf certificate:
     *  - Same TBSCertificate as the original, but with:
     *    a) rootOfTrust.verifiedBootState = Verified (0), deviceLocked = true
     *    b) attestationSecurityLevel = TrustedEnvironment (1)
     *    c) keymasterSecurityLevel = TrustedEnvironment (1)
     *    d) optionally patched OS version/patch level
     *  - Issuer replaced with the keybox leaf's subject
     *  - Signed with the keybox private key
     */
    private static byte[] forgeLeafCert(X509Certificate original, KeyboxKey kbKey, int mode)
            throws Exception {
        final byte[] origDer = original.getEncoded();

        // Parse the outer SEQUENCE: [tbsCertificate, signatureAlgorithm, signature]
        final DerParser outer = new DerParser(origDer);
        outer.expectTag(0x30); // SEQUENCE
        final byte[] outerContent = outer.readContent();

        final DerParser tbsParser = new DerParser(outerContent);
        final int tbsStart = tbsParser.offset;
        tbsParser.expectTag(0x30);
        final byte[] tbsContent = tbsParser.readContent();
        final int tbsHeaderLen = tbsParser.offset - tbsStart - tbsContent.length;
        final byte[] tbsOriginal = Arrays.copyOfRange(outerContent, tbsStart,
                tbsStart + tbsHeaderLen + tbsContent.length);

        // Modify the TBS: patch the attestation extension and issuer.
        final byte[] patchedTbs = patchTbs(tbsOriginal, kbKey);

        // Sign with the keybox key.
        final String sigAlg;
        if (kbKey.privateKey.getAlgorithm().equals("EC")) {
            sigAlg = "SHA256withECDSA";
        } else {
            sigAlg = "SHA256withRSA";
        }
        final Signature sig = Signature.getInstance(sigAlg);
        sig.initSign(kbKey.privateKey);
        sig.update(patchedTbs);
        final byte[] signature = sig.sign();

        // Encode the signatureAlgorithm.
        final byte[] sigAlgDer = encodeSignatureAlgorithm(kbKey.privateKey.getAlgorithm());

        // Encode the signature as a BIT STRING.
        final byte[] sigBitString = encodeBitString(signature);

        // Reassemble: SEQUENCE { patchedTbs, sigAlgDer, sigBitString }
        return encodeSequence(patchedTbs, sigAlgDer, sigBitString);
    }

    /**
     * Patch the TBSCertificate DER:
     *  - Replace the issuer field with the keybox leaf's subject
     *  - Modify the attestation extension
     */
    private static byte[] patchTbs(byte[] tbsDer, KeyboxKey kbKey) throws Exception {
        // The TBS has structure:
        //   SEQUENCE {
        //     [0] version
        //     serialNumber
        //     signature algorithm
        //     issuer           ← replace
        //     validity
        //     subject
        //     subjectPublicKeyInfo
        //     [extensions]     ← modify attestation extension
        //   }

        final DerParser tbs = new DerParser(tbsDer);
        tbs.expectTag(0x30);
        final byte[] tbsContent = tbs.readContent();

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final DerParser fields = new DerParser(tbsContent);

        // [0] version — copy as-is.
        copyField(fields, out);

        // serialNumber — copy as-is.
        copyField(fields, out);

        // signature algorithm — copy as-is.
        copyField(fields, out);

        // issuer — replace with keybox leaf's subject.
        skipField(fields); // skip original issuer
        final byte[] keyboxIssuer = extractSubject(kbKey.chain[0]);
        out.write(keyboxIssuer);

        // validity — copy as-is.
        copyField(fields, out);

        // subject — copy as-is.
        copyField(fields, out);

        // subjectPublicKeyInfo — copy as-is.
        copyField(fields, out);

        // Remaining fields: optional [1] issuerUniqueID, [2] subjectUniqueID, [3] extensions.
        // We only care about [3] extensions.
        while (fields.offset < tbsContent.length) {
            final int fieldStart = fields.offset;
            final int tag = tbsContent[fields.offset] & 0xFF;
            if (tag == 0xA3) { // [3] EXPLICIT extensions
                fields.offset++; // skip tag
                final byte[] extContainer = fields.readContent();

                // extContainer is a SEQUENCE of Extension entries.
                final byte[] patchedExts = patchExtensions(extContainer);

                // Re-encode [3] EXPLICIT { patchedExts }
                final byte[] reencoded = new byte[1 + encodeLengthSize(patchedExts.length)
                        + patchedExts.length];
                int pos = 0;
                reencoded[pos++] = (byte) 0xA3;
                pos += encodeLength(reencoded, pos, patchedExts.length);
                System.arraycopy(patchedExts, 0, reencoded, pos, patchedExts.length);
                out.write(reencoded);
            } else {
                // Copy any other optional field as-is.
                copyField(fields, out);
            }
        }

        // Re-wrap in SEQUENCE.
        final byte[] content = out.toByteArray();
        return wrapSequence(content);
    }

    /**
     * Walk the extensions SEQUENCE, find the attestation OID, and patch it.
     */
    private static byte[] patchExtensions(byte[] extSeqDer) throws Exception {
        final DerParser seq = new DerParser(extSeqDer);
        seq.expectTag(0x30);
        final byte[] seqContent = seq.readContent();

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final DerParser exts = new DerParser(seqContent);

        while (exts.offset < seqContent.length) {
            final int extStart = exts.offset;
            exts.expectTag(0x30); // each Extension is a SEQUENCE
            final byte[] extContent = exts.readContent();
            final int extEnd = exts.offset;

            // Parse the OID from this extension.
            final DerParser extFields = new DerParser(extContent);
            extFields.expectTag(0x06); // OID
            final byte[] oidBytes = extFields.readContent();
            final String oid = decodeOid(oidBytes);

            if (ATTESTATION_OID.equals(oid)) {
                // Patch this extension.
                final byte[] patched = patchAttestationExtension(extContent);
                out.write(wrapSequence(patched));
            } else {
                // Copy as-is: re-wrap the content in a SEQUENCE tag (since we parsed it out).
                out.write(wrapSequence(extContent));
            }
        }

        return wrapSequence(out.toByteArray());
    }

    /**
     * Patch the attestation extension content.
     * The extension is: SEQUENCE { OID, OCTET STRING { attestation payload } }
     * The payload is a SEQUENCE with the attestation fields.
     */
    private static byte[] patchAttestationExtension(byte[] extContent) throws Exception {
        final DerParser p = new DerParser(extContent);

        // OID — keep as-is.
        p.expectTag(0x06);
        final byte[] oidContent = p.readContent();
        final int oidEnd = p.offset;

        // Optional BOOLEAN (critical) — check.
        byte[] criticalBytes = null;
        if (p.offset < extContent.length && (extContent[p.offset] & 0xFF) == 0x01) {
            final int critStart = p.offset;
            p.expectTag(0x01);
            p.readContent();
            criticalBytes = Arrays.copyOfRange(extContent, critStart, p.offset);
        }

        // OCTET STRING — contains the attestation payload.
        p.expectTag(0x04);
        final byte[] payload = p.readContent();

        // Patch the payload.
        final byte[] patchedPayload = patchAttestationPayload(payload);

        // Reassemble: OID [critical] OCTET_STRING(patchedPayload)
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        // OID
        out.write(Arrays.copyOf(extContent, oidEnd));
        // Critical (if present)
        if (criticalBytes != null) out.write(criticalBytes);
        // OCTET STRING
        out.write(encodeOctetString(patchedPayload));

        return out.toByteArray();
    }

    /**
     * Patch the attestation payload SEQUENCE.
     *
     * KeyDescription (from Google's attestation spec):
     *   SEQUENCE {
     *     [0] attestationVersion INTEGER,
     *     [1] attestationSecurityLevel SecurityLevel,   ← patch to TEE (1)
     *     [2] keymasterVersion INTEGER,
     *     [3] keymasterSecurityLevel SecurityLevel,     ← patch to TEE (1)
     *     [4] attestationChallenge OCTET_STRING,
     *     [5] uniqueId OCTET_STRING,
     *     [6] softwareEnforced AuthorizationList,
     *     [7] teeEnforced AuthorizationList              ← patch rootOfTrust inside
     *   }
     *
     * SecurityLevel: ENUMERATED { Software (0), TrustedEnvironment (1), StrongBox (2) }
     *
     * Inside teeEnforced, TAG 704 = rootOfTrust:
     *   RootOfTrust ::= SEQUENCE {
     *     verifiedBootKey OCTET_STRING,
     *     deviceLocked BOOLEAN,           ← set to TRUE
     *     verifiedBootState VerifiedBootState, ← set to Verified (0)
     *     verifiedBootHash OCTET_STRING
     *   }
     *   VerifiedBootState: ENUMERATED { Verified (0), SelfSigned (1), Unverified (2), Failed (3) }
     */
    private static byte[] patchAttestationPayload(byte[] payload) throws Exception {
        // We manipulate the raw DER bytes to keep this robust against version differences.
        // Strategy: parse the outer SEQUENCE, identify fields by position, patch in place.

        final DerParser seq = new DerParser(payload);
        seq.expectTag(0x30);
        final byte[] seqContent = seq.readContent();

        // We need to reconstruct the sequence with patched fields.
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final DerParser fields = new DerParser(seqContent);

        // Field 0: attestationVersion — copy.
        copyField(fields, out);

        // Field 1: attestationSecurityLevel — replace with TEE (1).
        skipField(fields);
        out.write(encodeEnumerated(1)); // TrustedEnvironment

        // Field 2: keymasterVersion — copy.
        copyField(fields, out);

        // Field 3: keymasterSecurityLevel — replace with TEE (1).
        skipField(fields);
        out.write(encodeEnumerated(1)); // TrustedEnvironment

        // Field 4: attestationChallenge — copy.
        copyField(fields, out);

        // Field 5: uniqueId — copy.
        copyField(fields, out);

        // Field 6: softwareEnforced — patch OS info if needed.
        final int sw6Start = fields.offset;
        fields.expectTag(0x30);
        final byte[] swContent = fields.readContent();
        final String patchLevel = sCachedPatch;
        if (patchLevel != null && !patchLevel.isEmpty()) {
            final byte[] patchedSw = patchAuthorizationListPatchLevel(swContent, patchLevel);
            out.write(wrapSequence(patchedSw));
        } else {
            out.write(wrapSequence(swContent));
        }

        // Field 7: teeEnforced — patch rootOfTrust.
        final int tee7Start = fields.offset;
        fields.expectTag(0x30);
        final byte[] teeContent = fields.readContent();
        final byte[] patchedTee = patchTeeEnforced(teeContent, patchLevel);
        out.write(wrapSequence(patchedTee));

        return wrapSequence(out.toByteArray());
    }

    /**
     * Patch rootOfTrust inside teeEnforced AuthorizationList.
     * rootOfTrust is TAG 704, encoded as [704] EXPLICIT SEQUENCE.
     * TAG 704 in ASN.1 context class: class=CONTEXT(2), constructed(1), number=704.
     * 704 in low-tag encoding doesn't fit — it's 704 = 0x2C0, so uses high-tag form.
     */
    private static byte[] patchTeeEnforced(byte[] content, String patchLevel) throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final DerParser p = new DerParser(content);

        while (p.offset < content.length) {
            final int fieldStart = p.offset;
            final int tag = readTag(content, p);
            final int tagNum = tag & 0x1FFFFFFF; // strip class/constructed bits
            final byte[] fieldContent = p.readContent();
            final int fieldEnd = p.offset;

            // TAG 704 = rootOfTrust
            if (tagNum == 704) {
                // fieldContent is the EXPLICIT wrapper around a SEQUENCE.
                final byte[] patchedRot = patchRootOfTrust(fieldContent);
                // Re-encode with the original tag bytes.
                out.write(encodeContextTag(704, true, patchedRot));
            } else if (patchLevel != null && !patchLevel.isEmpty() && tagNum == 706) {
                // TAG 706 = OS patch level (YYYYMMDD integer) — override.
                final int patchInt = parsePatchLevelInt(patchLevel);
                if (patchInt > 0) {
                    out.write(encodeContextTag(706, true, encodeInteger(patchInt)));
                } else {
                    out.write(Arrays.copyOfRange(content, fieldStart, fieldEnd));
                }
            } else {
                // Copy as-is.
                out.write(Arrays.copyOfRange(content, fieldStart, fieldEnd));
            }
        }

        return out.toByteArray();
    }

    /**
     * Patch rootOfTrust SEQUENCE:
     *   verifiedBootKey, deviceLocked → TRUE, verifiedBootState → Verified (0), verifiedBootHash.
     */
    private static byte[] patchRootOfTrust(byte[] content) throws Exception {
        final DerParser seq = new DerParser(content);
        seq.expectTag(0x30);
        final byte[] seqContent = seq.readContent();

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final DerParser fields = new DerParser(seqContent);

        // verifiedBootKey — copy.
        copyField(fields, out);

        // deviceLocked — replace with TRUE.
        skipField(fields);
        out.write(new byte[] { 0x01, 0x01, (byte) 0xFF }); // BOOLEAN TRUE

        // verifiedBootState — replace with Verified (0).
        skipField(fields);
        out.write(encodeEnumerated(0)); // Verified

        // verifiedBootHash — copy (if present, it may not be in older versions).
        if (fields.offset < seqContent.length) {
            copyField(fields, out);
        }

        return wrapSequence(out.toByteArray());
    }

    /**
     * Patch OS patch level (TAG 706 in softwareEnforced) if present.
     */
    private static byte[] patchAuthorizationListPatchLevel(byte[] content, String patchLevel)
            throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final DerParser p = new DerParser(content);

        while (p.offset < content.length) {
            final int fieldStart = p.offset;
            final int tag = readTag(content, p);
            final int tagNum = tag & 0x1FFFFFFF;
            final byte[] fieldContent = p.readContent();
            final int fieldEnd = p.offset;

            if (tagNum == 706) {
                final int patchInt = parsePatchLevelInt(patchLevel);
                if (patchInt > 0) {
                    out.write(encodeContextTag(706, true, encodeInteger(patchInt)));
                    continue;
                }
            }
            out.write(Arrays.copyOfRange(content, fieldStart, fieldEnd));
        }

        return out.toByteArray();
    }

    // -----------------------------------------------------------------------------------------
    // Settings reading
    // -----------------------------------------------------------------------------------------

    private static void ensureSettings(Context context) {
        if (sSettingsRead) return;
        synchronized (KeyboxAttestation.class) {
            if (sSettingsRead) return;
            readSettings(context);
            sSettingsRead = true;
        }
    }

    /** Called when any of the three settings changes. */
    public static void invalidateCache() {
        sSettingsRead = false;
        sCachedKeybox = null;
        sCachedTargets = null;
        sCachedPatch = null;
    }

    private static void readSettings(Context context) {
        try {
            // Keybox.
            final String keyboxB64 = Settings.Secure.getString(
                    context.getContentResolver(), SETTING_KEYBOX);
            if (!TextUtils.isEmpty(keyboxB64)) {
                final byte[] xml = Base64.decode(keyboxB64, Base64.NO_WRAP);
                sCachedKeybox = parseKeyboxXml(new String(xml));
            }

            // Targets.
            final String targetStr = Settings.Secure.getString(
                    context.getContentResolver(), SETTING_TARGET);
            if (!TextUtils.isEmpty(targetStr)) {
                sCachedTargets = parseTargets(targetStr);
            }

            // Patch.
            sCachedPatch = Settings.Secure.getString(
                    context.getContentResolver(), SETTING_PATCH);
        } catch (Exception e) {
            Log.e(TAG, "Failed to read TrickyStore settings", e);
        }
    }

    // -----------------------------------------------------------------------------------------
    // Keybox XML parsing
    // -----------------------------------------------------------------------------------------

    private static final Pattern PEM_BLOCK = Pattern.compile(
            "-----BEGIN ([A-Z ]+)-----\\s*([A-Za-z0-9+/=\\s]+?)\\s*-----END \\1-----",
            Pattern.DOTALL);

    static ParsedKeybox parseKeyboxXml(String xml) throws Exception {
        ParsedKeybox result = new ParsedKeybox();

        // Split by <Key algorithm="..."> blocks.
        final Pattern keyBlock = Pattern.compile(
                "<Key\\s+algorithm=\"(\\w+)\">(.*?)</Key>", Pattern.DOTALL);
        final Matcher km = keyBlock.matcher(xml);

        while (km.find()) {
            final String alg = km.group(1).toLowerCase();
            final String body = km.group(2);

            // Extract private key PEM.
            final Matcher privMatch = Pattern.compile(
                    "<PrivateKey[^>]*>(.*?)</PrivateKey>", Pattern.DOTALL).matcher(body);
            if (!privMatch.find()) continue;
            final String privPem = privMatch.group(1).trim();

            // Extract certificate PEMs.
            final Matcher certBlock = Pattern.compile(
                    "<CertificateChain>(.*?)</CertificateChain>", Pattern.DOTALL).matcher(body);
            if (!certBlock.find()) continue;
            final String certChainXml = certBlock.group(1);

            // Parse private key.
            final Matcher privPemMatch = PEM_BLOCK.matcher(privPem);
            if (!privPemMatch.find()) continue;
            final String pemType = privPemMatch.group(1); // e.g. "EC PRIVATE KEY" or "PRIVATE KEY"
            final byte[] privDer = Base64.decode(
                    privPemMatch.group(2).replaceAll("\\s", ""), Base64.NO_WRAP);

            final String jcaAlg;
            if ("ecdsa".equals(alg) || "ec".equals(alg)) {
                jcaAlg = "EC";
            } else {
                jcaAlg = "RSA";
            }

            final PrivateKey privateKey;
            if ("EC PRIVATE KEY".equals(pemType) || "RSA PRIVATE KEY".equals(pemType)) {
                // SEC1 (EC) or PKCS#1 (RSA) format — need to wrap in PKCS#8 envelope.
                privateKey = KeyFactory.getInstance(jcaAlg)
                        .generatePrivate(new PKCS8EncodedKeySpec(wrapInPkcs8(privDer, jcaAlg)));
            } else {
                // Already PKCS#8 ("PRIVATE KEY").
                privateKey = KeyFactory.getInstance(jcaAlg)
                        .generatePrivate(new PKCS8EncodedKeySpec(privDer));
            }

            // Parse certificates.
            final List<X509Certificate> certs = new ArrayList<>();
            final Matcher certPem = PEM_BLOCK.matcher(certChainXml);
            final CertificateFactory cf = CertificateFactory.getInstance("X.509");
            while (certPem.find()) {
                final byte[] certDer = Base64.decode(
                        certPem.group(2).replaceAll("\\s", ""), Base64.NO_WRAP);
                final X509Certificate cert = (X509Certificate) cf.generateCertificate(
                        new ByteArrayInputStream(certDer));
                certs.add(cert);
            }

            if (certs.isEmpty()) continue;

            final KeyboxKey kbKey = new KeyboxKey();
            kbKey.privateKey = privateKey;
            kbKey.chain = certs.toArray(new X509Certificate[0]);

            if ("ecdsa".equals(alg) || "ec".equals(alg)) {
                result.ecdsa = kbKey;
            } else {
                result.rsa = kbKey;
            }
        }

        return result;
    }

    // -----------------------------------------------------------------------------------------
    // Target parsing
    // -----------------------------------------------------------------------------------------

    private static TargetConfig parseTargets(String targetStr) {
        final TargetConfig config = new TargetConfig();
        for (String line : targetStr.split("\n")) {
            final String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            if (trimmed.endsWith("!")) {
                final String pkg = trimmed.substring(0, trimmed.length() - 1).trim();
                config.packages.add(pkg);
                config.modes.put(pkg, MODE_CERT_CHAIN);
            } else if (trimmed.endsWith("?")) {
                final String pkg = trimmed.substring(0, trimmed.length() - 1).trim();
                config.packages.add(pkg);
                config.modes.put(pkg, MODE_LEAF_ONLY);
            } else {
                config.packages.add(trimmed);
                config.modes.put(trimmed, MODE_AUTO);
            }
        }
        return config;
    }

    // -----------------------------------------------------------------------------------------
    // DER encoding helpers
    // -----------------------------------------------------------------------------------------

    static byte[] encodeSequence(byte[]... items) {
        final ByteArrayOutputStream content = new ByteArrayOutputStream();
        for (byte[] item : items) {
            content.write(item, 0, item.length);
        }
        return wrapSequence(content.toByteArray());
    }

    static byte[] wrapSequence(byte[] content) {
        return wrapTag(0x30, content);
    }

    static byte[] wrapTag(int tag, byte[] content) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        writeDerLength(out, content.length);
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    static byte[] encodeEnumerated(int value) {
        return new byte[] { 0x0A, 0x01, (byte) value };
    }

    static byte[] encodeInteger(int value) {
        // Encode as a minimal signed DER integer.
        final byte[] bigIntBytes = BigInteger.valueOf(value).toByteArray();
        final byte[] result = new byte[2 + bigIntBytes.length];
        result[0] = 0x02; // INTEGER tag
        result[1] = (byte) bigIntBytes.length;
        System.arraycopy(bigIntBytes, 0, result, 2, bigIntBytes.length);
        return result;
    }

    static byte[] encodeBitString(byte[] data) {
        final byte[] result = new byte[3 + data.length];
        result[0] = 0x03; // BIT STRING
        // Length = data + 1 byte for unused-bits count
        final int len = data.length + 1;
        // For simplicity, handle lengths < 128 and > 127 separately
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x03);
        writeDerLength(out, len);
        out.write(0x00); // unused bits
        out.write(data, 0, data.length);
        return out.toByteArray();
    }

    static byte[] encodeOctetString(byte[] data) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x04);
        writeDerLength(out, data.length);
        out.write(data, 0, data.length);
        return out.toByteArray();
    }

    static byte[] encodeContextTag(int tagNum, boolean constructed, byte[] content) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Context class (0x80) | constructed (0x20) | high-tag indicator (0x1F)
        final int firstByte = 0x80 | (constructed ? 0x20 : 0x00);
        if (tagNum < 31) {
            out.write(firstByte | tagNum);
        } else {
            out.write(firstByte | 0x1F);
            // Encode tag number in base-128.
            writeBase128(out, tagNum);
        }
        writeDerLength(out, content.length);
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    /**
     * Wrap a SEC1 (EC) or PKCS#1 (RSA) private key in a PKCS#8 PrivateKeyInfo envelope.
     *
     * PKCS#8 PrivateKeyInfo ::= SEQUENCE {
     *   version INTEGER (0),
     *   privateKeyAlgorithm AlgorithmIdentifier,
     *   privateKey OCTET STRING (wrapping the raw key bytes)
     * }
     */
    static byte[] wrapInPkcs8(byte[] rawKey, String algorithm) {
        final byte[] algId;
        if ("EC".equals(algorithm)) {
            // We need to extract the curve OID from the SEC1 key to build the AlgId.
            // SEC1 ECPrivateKey ::= SEQUENCE { version, privateKey, [0] parameters, [1] publicKey }
            // The parameters field [0] contains the curve OID.
            byte[] curveOid = extractEcCurveOid(rawKey);
            if (curveOid == null) {
                // Fallback: assume P-256 (secp256r1 / prime256v1) — the most common for attestation.
                curveOid = new byte[] {
                    0x06, 0x08, 0x2A, (byte) 0x86, 0x48, (byte) 0xCE, 0x3D, 0x03, 0x01, 0x07
                };
            }
            // AlgorithmIdentifier: SEQUENCE { OID(id-ecPublicKey), curveOid }
            final byte[] ecOid = new byte[] {
                0x06, 0x07, 0x2A, (byte) 0x86, 0x48, (byte) 0xCE, 0x3D, 0x02, 0x01
            };
            algId = encodeSequence(ecOid, curveOid);
        } else {
            // RSA: AlgorithmIdentifier: SEQUENCE { OID(rsaEncryption), NULL }
            algId = new byte[] {
                0x30, 0x0D,
                0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7,
                0x0D, 0x01, 0x01, 0x01,
                0x05, 0x00
            };
        }

        // version INTEGER 0
        final byte[] version = encodeInteger(0);
        // privateKey OCTET STRING
        final byte[] privKeyOctet = encodeOctetString(rawKey);

        return encodeSequence(version, algId, privKeyOctet);
    }

    /**
     * Extract the curve OID from a SEC1 ECPrivateKey.
     * SEC1 ECPrivateKey ::= SEQUENCE { version INTEGER, privateKey OCTET_STRING,
     *                                  [0] EXPLICIT OID, [1] EXPLICIT BIT_STRING }
     */
    static byte[] extractEcCurveOid(byte[] sec1Key) {
        try {
            final DerParser seq = new DerParser(sec1Key);
            seq.expectTag(0x30);
            final byte[] seqContent = seq.readContent();
            final DerParser fields = new DerParser(seqContent);

            // version
            skipField(fields);
            // privateKey
            skipField(fields);

            // Look for [0] EXPLICIT (tag 0xA0)
            if (fields.offset < seqContent.length
                    && (seqContent[fields.offset] & 0xFF) == 0xA0) {
                fields.offset++; // skip tag
                final byte[] paramContent = fields.readContent();
                // paramContent should be an OID
                if (paramContent.length > 0 && (paramContent[0] & 0xFF) == 0x06) {
                    return paramContent; // the full OID TLV
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to extract EC curve OID from SEC1 key", e);
        }
        return null;
    }

    static byte[] encodeSignatureAlgorithm(String algorithm) {
        // ecdsa-with-SHA256: OID 1.2.840.10045.4.3.2
        // sha256WithRSAEncryption: OID 1.2.840.113549.1.1.11
        if ("EC".equals(algorithm)) {
            return new byte[] {
                0x30, 0x0A,
                0x06, 0x08,
                0x2A, (byte) 0x86, 0x48, (byte) 0xCE, 0x3D, 0x04, 0x03, 0x02
            };
        } else {
            return new byte[] {
                0x30, 0x0D,
                0x06, 0x09,
                0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B,
                0x05, 0x00 // NULL params
            };
        }
    }

    // -----------------------------------------------------------------------------------------
    // DER reading/writing utilities
    // -----------------------------------------------------------------------------------------

    static void writeDerLength(ByteArrayOutputStream out, int length) {
        if (length < 128) {
            out.write(length);
        } else if (length < 256) {
            out.write(0x81);
            out.write(length);
        } else if (length < 65536) {
            out.write(0x82);
            out.write((length >> 8) & 0xFF);
            out.write(length & 0xFF);
        } else if (length < 16777216) {
            out.write(0x83);
            out.write((length >> 16) & 0xFF);
            out.write((length >> 8) & 0xFF);
            out.write(length & 0xFF);
        } else {
            out.write(0x84);
            out.write((length >> 24) & 0xFF);
            out.write((length >> 16) & 0xFF);
            out.write((length >> 8) & 0xFF);
            out.write(length & 0xFF);
        }
    }

    static int encodeLengthSize(int length) {
        if (length < 128) return 1;
        if (length < 256) return 2;
        if (length < 65536) return 3;
        if (length < 16777216) return 4;
        return 5;
    }

    static int encodeLength(byte[] buf, int offset, int length) {
        if (length < 128) {
            buf[offset] = (byte) length;
            return 1;
        } else if (length < 256) {
            buf[offset] = (byte) 0x81;
            buf[offset + 1] = (byte) length;
            return 2;
        } else {
            buf[offset] = (byte) 0x82;
            buf[offset + 1] = (byte) ((length >> 8) & 0xFF);
            buf[offset + 2] = (byte) (length & 0xFF);
            return 3;
        }
    }

    static void writeBase128(ByteArrayOutputStream out, int value) {
        // Count the bytes needed.
        final byte[] temp = new byte[5];
        int pos = 4;
        temp[pos] = (byte) (value & 0x7F);
        value >>= 7;
        while (value > 0) {
            pos--;
            temp[pos] = (byte) ((value & 0x7F) | 0x80);
            value >>= 7;
        }
        out.write(temp, pos, 5 - pos);
    }

    static int readTag(byte[] data, DerParser p) {
        int tag = data[p.offset++] & 0xFF;
        if ((tag & 0x1F) == 0x1F) {
            // High-tag-number form.
            int tagNum = 0;
            int b;
            do {
                b = data[p.offset++] & 0xFF;
                tagNum = (tagNum << 7) | (b & 0x7F);
            } while ((b & 0x80) != 0);
            // Encode class + constructed bits into the result, plus the tag number.
            return (tag & 0xE0) << 24 | tagNum;
        }
        return tag;
    }

    static String decodeOid(byte[] oidBytes) {
        final StringBuilder sb = new StringBuilder();
        // First byte encodes first two components: first = byte/40, second = byte%40.
        sb.append(oidBytes[0] / 40).append('.').append(oidBytes[0] % 40);
        int val = 0;
        for (int i = 1; i < oidBytes.length; i++) {
            val = (val << 7) | (oidBytes[i] & 0x7F);
            if ((oidBytes[i] & 0x80) == 0) {
                sb.append('.').append(val);
                val = 0;
            }
        }
        return sb.toString();
    }

    static byte[] extractSubject(Certificate cert) throws Exception {
        // The subject is at a fixed position in the TBSCertificate, but the simplest approach
        // is to use the X509Certificate API and re-encode.
        final X509Certificate x509 = (X509Certificate) cert;
        return x509.getSubjectX500Principal().getEncoded();
    }

    static void copyField(DerParser parser, ByteArrayOutputStream out) {
        final int start = parser.offset;
        final int tag = parser.data[parser.offset] & 0xFF;
        if ((tag & 0x1F) == 0x1F) {
            // High tag.
            parser.offset++;
            while ((parser.data[parser.offset] & 0x80) != 0) parser.offset++;
            parser.offset++;
        } else {
            parser.offset++;
        }
        parser.readContent(); // advances offset past the content
        out.write(parser.data, start, parser.offset - start);
    }

    static void skipField(DerParser parser) {
        final int tag = parser.data[parser.offset] & 0xFF;
        if ((tag & 0x1F) == 0x1F) {
            parser.offset++;
            while ((parser.data[parser.offset] & 0x80) != 0) parser.offset++;
            parser.offset++;
        } else {
            parser.offset++;
        }
        parser.readContent();
    }

    static int parsePatchLevelInt(String patchLevel) {
        // YYYY-MM-DD → YYYYMMDD integer
        try {
            return Integer.parseInt(patchLevel.replace("-", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // -----------------------------------------------------------------------------------------
    // Minimal DER parser
    // -----------------------------------------------------------------------------------------

    static class DerParser {
        final byte[] data;
        int offset;

        DerParser(byte[] data) {
            this.data = data;
            this.offset = 0;
        }

        void expectTag(int expected) {
            final int tag = data[offset++] & 0xFF;
            if (tag != expected) {
                throw new IllegalStateException(
                        "Expected tag 0x" + Integer.toHexString(expected)
                        + " but got 0x" + Integer.toHexString(tag)
                        + " at offset " + (offset - 1));
            }
        }

        byte[] readContent() {
            int length = data[offset++] & 0xFF;
            if ((length & 0x80) != 0) {
                final int numBytes = length & 0x7F;
                length = 0;
                for (int i = 0; i < numBytes; i++) {
                    length = (length << 8) | (data[offset++] & 0xFF);
                }
            }
            final byte[] content = Arrays.copyOfRange(data, offset, offset + length);
            offset += length;
            return content;
        }
    }

    // -----------------------------------------------------------------------------------------
    // Data classes
    // -----------------------------------------------------------------------------------------

    static class ParsedKeybox {
        KeyboxKey ecdsa;
        KeyboxKey rsa;
    }

    static class KeyboxKey {
        PrivateKey privateKey;
        X509Certificate[] chain;
    }

    static class TargetConfig {
        final Set<String> packages = new HashSet<>();
        final java.util.HashMap<String, Integer> modes = new java.util.HashMap<>();
    }
}
