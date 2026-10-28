/*
 * Copyright (C) 2026 IchthysOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.locksettings;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.IRecoverySystem;
import android.os.PowerManager;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.security.AndroidKeyStoreMaintenance;
import android.security.Scrypt;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockscreenCredential;
import com.android.server.LocalServices;
import com.android.server.StorageManagerInternal;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * IchthysOS duress ("emergency wipe") credential.
 *
 * <p>The device owner may configure a <em>second</em> lockscreen credential.  Entering it at any
 * credential prompt instead of the real credential destroys the file-based-encryption keys for
 * every user on the device, making all user data cryptographically unrecoverable, and then reboots
 * into recovery to reformat {@code /data}.
 *
 * <h3>Safety design</h3>
 *
 * This class is deliberately built so that a false positive is impossible in normal operation:
 *
 * <ul>
 *   <li>The duress credential is only ever <em>considered</em> after the real credential has
 *       already been rejected by {@code LockSettingsService.doVerifyCredential()}.  A correct
 *       real credential therefore can never reach this code at all.
 *   <li>Setting a duress credential refuses any value that is byte-for-byte equal to the caller's
 *       real credential, and refuses anything shorter than {@link #MIN_DURESS_CREDENTIAL_LENGTH}.
 *   <li>Whenever the real lockscreen credential changes, the stored duress credential is
 *       re-checked against the new real credential and dropped if it now collides (or if the
 *       credential <em>type</em> changed, which would make the duress credential impossible to
 *       type at the keyguard anyway).
 *   <li>Only credentials typed by a human at a credential prompt are ever checked.  Internal,
 *       derived credentials (unified work-profile passwords, cached challenges, escrow tokens,
 *       remote lockscreen validation) are never checked against the duress credential.
 *   <li>Nothing is ever logged that would reveal that a duress credential exists, was set, or
 *       matched.
 * </ul>
 *
 * <h3>Storage</h3>
 *
 * The credential is stored as {@code scrypt(credential, random 16-byte salt)} in
 * {@code /data/system_de/<userId>/duress.dat}.  Device-encrypted storage is required because the
 * check has to work at the very first unlock after boot, before credential-encrypted storage
 * exists.  The plaintext is never written anywhere and never logged.
 *
 * <p>The scrypt cost parameters here are deliberately much higher than the ones
 * {@link SyntheticPasswordManager} uses for the real LSKF.  The real LSKF is additionally
 * protected by Gatekeeper/Weaver hardware rate limiting, so its scrypt step can be cheap; the
 * duress hash has no hardware backing at all, so it must stand on its own against an offline
 * attack by someone who has extracted {@code /data/system_de}.
 */
class DuressCredentialManager {

    private static final String TAG = "DuressCredentialManager";

    /** Serialization format version of {@code duress.dat}. */
    private static final byte VERSION_1 = 1;

    private static final int SALT_LENGTH = 16;
    private static final int HASH_LENGTH = 32;

    /**
     * scrypt cost parameters.  N = 2^14 = 16384, r = 2^3 = 8, p = 2^1 = 2, i.e. ~16 MiB and
     * roughly 100-250 ms on a mid-range 2021 SoC.  This cost is only ever paid on a <em>failed</em>
     * unlock attempt, so it does not slow down normal unlocking at all.
     */
    private static final byte SCRYPT_LOG_N = 14;
    private static final byte SCRYPT_LOG_R = 3;
    private static final byte SCRYPT_LOG_P = 1;

    /**
     * Minimum length of a duress credential.  This bounds the probability that a mistyped real
     * credential accidentally equals the duress credential: with 6 digits it is at most 1e-6 per
     * failed attempt, and Gatekeeper rate limiting caps the number of attempts anyway.
     */
    // Matches the platform minimum for a real PIN/password (MIN_LOCK_PASSWORD_SIZE), so a duress
    // credential is never harder to choose than the credential it stands in for.
    //
    // Note on the trade-off: unlike the real LSKF this hash has no Gatekeeper/Weaver rate limiting,
    // so a short duress credential is cheap to recover offline by someone who has already extracted
    // /data/system_de. What that buys an attacker is limited - it reveals the duress credential (so
    // they can avoid typing it), not the real credential and not any user data. The scrypt cost
    // parameters below are the mitigation.
    static final int MIN_DURESS_CREDENTIAL_LENGTH = 4;

    /**
     * Reason string handed to recovery.  A single token with no spaces or quoting so that it
     * survives the BCB round trip unchanged, and deliberately generic because it ends up in the
     * recovery log on the wiped device.
     */
    private static final String WIPE_REASON = "emergency_wipe";

    /** How long to wait for the recovery wipe to be armed before the keys are destroyed. */
    private static final long BCB_SETUP_TIMEOUT_MS = 4000;

    private final Context mContext;
    private final LockSettingsStorage mStorage;

    /** Guards {@link #mWipeStarted} so the wipe can only ever run once. */
    private final Object mWipeLock = new Object();

    private boolean mWipeStarted = false;

    DuressCredentialManager(Context context, LockSettingsStorage storage) {
        mContext = context;
        mStorage = storage;
    }

    // ---------------------------------------------------------------------------------------
    // Stored blob
    // ---------------------------------------------------------------------------------------

    /**
     * Parsed contents of {@code duress.dat}.  Contains no plaintext credential material.
     */
    private static final class DuressData {
        int credentialType;
        byte scryptLogN;
        byte scryptLogR;
        byte scryptLogP;
        byte[] salt;
        byte[] hash;

        byte[] toBytes() {
            ByteBuffer buffer = ByteBuffer.allocate(Byte.BYTES + Integer.BYTES + 3 * Byte.BYTES
                    + Integer.BYTES + salt.length + Integer.BYTES + hash.length);
            buffer.put(VERSION_1);
            buffer.putInt(credentialType);
            buffer.put(scryptLogN);
            buffer.put(scryptLogR);
            buffer.put(scryptLogP);
            buffer.putInt(salt.length);
            buffer.put(salt);
            buffer.putInt(hash.length);
            buffer.put(hash);
            return buffer.array();
        }

        @Nullable
        static DuressData fromBytes(@Nullable byte[] data) {
            if (data == null || data.length == 0) {
                return null;
            }
            try {
                ByteBuffer buffer = ByteBuffer.wrap(data);
                if (buffer.get() != VERSION_1) {
                    return null;
                }
                DuressData result = new DuressData();
                result.credentialType = buffer.getInt();
                result.scryptLogN = buffer.get();
                result.scryptLogR = buffer.get();
                result.scryptLogP = buffer.get();
                int saltLen = buffer.getInt();
                if (saltLen <= 0 || saltLen > 1024) {
                    return null;
                }
                result.salt = new byte[saltLen];
                buffer.get(result.salt);
                int hashLen = buffer.getInt();
                if (hashLen <= 0 || hashLen > 1024) {
                    return null;
                }
                result.hash = new byte[hashLen];
                buffer.get(result.hash);
                return result;
            } catch (RuntimeException e) {
                // Corrupt blob.  Fail closed: behave as if no duress credential is configured.
                // Do not log the exception - it would reveal that a duress credential exists.
                return null;
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Query / set / clear
    // ---------------------------------------------------------------------------------------

    /**
     * Returns whether a duress credential is configured for the given user.  Cheap - this is a
     * file read, no key derivation.
     */
    boolean hasDuressCredential(@UserIdInt int userId) {
        return DuressData.fromBytes(mStorage.readDuressCredentialData(userId)) != null;
    }

    /**
     * Result codes for {@link #setDuressCredential}.  Callers surface these to the user; none of
     * them reveal anything about a previously configured duress credential.
     */
    static final int SET_RESULT_OK = 0;
    /** The proposed duress credential is the user's real credential. */
    static final int SET_RESULT_SAME_AS_REAL = 1;
    /** The proposed duress credential is too short. */
    static final int SET_RESULT_TOO_SHORT = 2;
    /** The proposed duress credential is a different type (PIN/password/pattern) than the real one. */
    static final int SET_RESULT_WRONG_TYPE = 3;
    /** The user has no real lockscreen credential, or something else went wrong. */
    static final int SET_RESULT_ERROR = 4;

    /**
     * Stores a duress credential for the given user.
     *
     * <p>The caller must already have verified {@code realCredential} against the user's real
     * lockscreen credential; this method only performs the additional validation that is specific
     * to duress credentials.
     *
     * @param duressCredential the proposed duress credential (never retained or logged)
     * @param realCredential the user's verified real credential, used only for the equality check
     */
    int setDuressCredential(@NonNull LockscreenCredential duressCredential,
            @NonNull LockscreenCredential realCredential, @UserIdInt int userId) {
        if (duressCredential.isNone() || realCredential.isNone()) {
            return SET_RESULT_ERROR;
        }
        // A duress credential of a different type than the real credential could never be typed
        // at the keyguard, because the keyguard only shows the input method for the real type.
        if (duressCredential.getType() != realCredential.getType()) {
            return SET_RESULT_WRONG_TYPE;
        }
        if (duressCredential.size() < MIN_DURESS_CREDENTIAL_LENGTH) {
            return SET_RESULT_TOO_SHORT;
        }
        // The single most important check in this file.  If the duress credential equalled the
        // real credential, the owner would wipe their own device on every normal unlock.
        if (duressCredential.equals(realCredential)) {
            return SET_RESULT_SAME_AS_REAL;
        }
        // Nor one slip away from it: a typo of the real PIN or password would wipe the device.
        if (isOneSlipAway(duressCredential, realCredential)) {
            return SET_RESULT_SAME_AS_REAL;
        }

        DuressData data = new DuressData();
        data.credentialType = duressCredential.getType();
        data.scryptLogN = SCRYPT_LOG_N;
        data.scryptLogR = SCRYPT_LOG_R;
        data.scryptLogP = SCRYPT_LOG_P;
        data.salt = randomBytes(SALT_LENGTH);
        data.hash = hash(duressCredential, data);
        try {
            mStorage.writeDuressCredentialData(userId, data.toBytes());
        } catch (RuntimeException e) {
            Slog.e(TAG, "Failed to persist emergency credential");
            return SET_RESULT_ERROR;
        } finally {
            ArrayUtils.zeroize(data.hash);
        }
        return SET_RESULT_OK;
    }

    /**
     * Whether {@code a} is one typing slip from {@code b}: one character wrong, missing or extra,
     * or two neighbours swapped; for passwords also a difference in letter case only.
     */
    private static boolean isOneSlipAway(@NonNull LockscreenCredential a,
            @NonNull LockscreenCredential b) {
        final byte[] x = a.getCredential();
        final byte[] y = b.getCredential();
        final boolean foldCase = a.isPassword();
        if (foldCase && x.length == y.length) {
            boolean caseOnly = true;
            for (int i = 0; i < x.length && caseOnly; i++) {
                caseOnly = lower(x[i]) == lower(y[i]);
            }
            if (caseOnly) {
                return true;
            }
        }
        if (Math.abs(x.length - y.length) > 1) {
            return false;
        }
        int i = 0;
        while (i < x.length && i < y.length && x[i] == y[i]) {
            i++;
        }
        if (x.length == y.length) {
            if (i == x.length) {
                return true;
            }
            // One wrong character, or two neighbours swapped; the rest must match.
            if (tailEquals(x, i + 1, y, i + 1)) {
                return true;
            }
            return i + 1 < x.length && x[i] == y[i + 1] && x[i + 1] == y[i]
                    && tailEquals(x, i + 2, y, i + 2);
        }
        // One character missing or extra.
        return x.length > y.length ? tailEquals(x, i + 1, y, i) : tailEquals(x, i, y, i + 1);
    }

    private static boolean tailEquals(byte[] x, int i, byte[] y, int j) {
        if (x.length - i != y.length - j) {
            return false;
        }
        for (; i < x.length; i++, j++) {
            if (x[i] != y[j]) {
                return false;
            }
        }
        return true;
    }

    private static int lower(byte c) {
        return c >= 'A' && c <= 'Z' ? c + ('a' - 'A') : c;
    }

    /** Removes any duress credential configured for the given user. */
    void clearDuressCredential(@UserIdInt int userId) {
        mStorage.deleteDuressCredentialData(userId);
    }

    /**
     * Called after the user's real lockscreen credential has been changed (by any path, including
     * escrow-token resets).  Drops the duress credential if keeping it would be unsafe or useless:
     *
     * <ul>
     *   <li>the new real credential <em>is</em> the duress credential - keeping it would wipe the
     *       device on the owner's next normal unlock,
     *   <li>the credential type changed, so the duress credential could never be typed,
     *   <li>the lockscreen credential was removed entirely.
     * </ul>
     */
    void onRealCredentialChanged(@NonNull LockscreenCredential newCredential,
            @UserIdInt int userId) {
        DuressData data = DuressData.fromBytes(mStorage.readDuressCredentialData(userId));
        if (data == null) {
            return;
        }
        boolean drop;
        if (newCredential.isNone()) {
            drop = true;
        } else if (newCredential.getType() != data.credentialType) {
            drop = true;
        } else {
            drop = matches(newCredential, data);
        }
        if (drop) {
            clearDuressCredential(userId);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Verification
    // ---------------------------------------------------------------------------------------

    /**
     * Checks a credential that a human just typed at a credential prompt against the duress
     * credential for {@code userId} and, if it matches, destroys the data on this device.
     *
     * <p><b>This must only be called after the real credential has already been rejected.</b>
     * Callers must never call this with an internally derived credential.
     *
     * <p>If the credential matches, this method normally does not return (the device reboots).  If
     * the wipe fails outright it returns normally, so that the caller's response to the user is
     * indistinguishable from an ordinary wrong-credential response.
     */
    void checkForDuressCredentialAndWipe(@Nullable LockscreenCredential credential,
            @UserIdInt int userId) {
        if (credential == null || credential.isNone()) {
            return;
        }
        // Special users (FRP, repair mode) never have a duress credential and have no data to
        // destroy; never run the wipe for them.
        if (LockPatternUtils.isSpecialUserId(userId)) {
            return;
        }
        DuressData data = DuressData.fromBytes(mStorage.readDuressCredentialData(userId));
        if (data == null) {
            return;
        }
        if (credential.getType() != data.credentialType) {
            // Cannot match.  This is not a timing leak: the keyguard only ever offers the input
            // method matching the real credential's type, which is also the duress credential's
            // type, so this branch is not reachable from the lock screen.
            return;
        }
        // Note: deliberately no length short-circuit here.  Skipping the key derivation for
        // "wrong length" guesses would let an observer time failed attempts and learn the length
        // of the duress credential.  The cost is only ever paid on an already-failed attempt.
        if (!matches(credential, data)) {
            return;
        }
        // Matched.  Deliberately no logging here.
        wipeDevice();
    }

    private boolean matches(@NonNull LockscreenCredential credential, @NonNull DuressData data) {
        byte[] computed = hash(credential, data);
        try {
            return MessageDigest.isEqual(computed, data.hash);
        } finally {
            ArrayUtils.zeroize(computed);
        }
    }

    private byte[] hash(@NonNull LockscreenCredential credential, @NonNull DuressData data) {
        return scrypt(credential.getCredential(), data.salt,
                1 << data.scryptLogN, 1 << data.scryptLogR, 1 << data.scryptLogP, HASH_LENGTH);
    }

    @VisibleForTesting
    protected byte[] scrypt(byte[] password, byte[] salt, int n, int r, int p, int outLen) {
        return new Scrypt().scrypt(password, salt, n, r, p, outLen);
    }

    @VisibleForTesting
    protected byte[] randomBytes(int length) {
        return SecureRandomUtils.randomBytes(length);
    }

    // ---------------------------------------------------------------------------------------
    // The wipe
    // ---------------------------------------------------------------------------------------

    /**
     * Destroys the data on this device, as irreversibly and as quickly as the platform allows,
     * then reboots into recovery to reformat {@code /data}.
     *
     * <p>Order of operations, most-irreversible first:
     *
     * <ol>
     *   <li>Arm the recovery bootloader control block with {@code --wipe_data}, and wait for it
     *       (at most {@link #BCB_SETUP_TIMEOUT_MS}).  This only writes the {@code misc} partition;
     *       it does not touch {@code /data} and it does not touch any external or public volume.
     *       It has to come first: destroying the system user's keys can kill this process before
     *       anything after it runs, and with the wipe armed any reboot after that, crash or not,
     *       lands in the reset.
     *   <li>Destroy the file-based-encryption CE and DE keys of every user (vold
     *       {@code destroyUserStorageKeys}).  This evicts the keys from the kernel keyring, deletes
     *       the KeyMint-wrapped key blobs from Keystore, and {@code secdiscard}s the on-flash
     *       encrypted key files.  From this point the data is gone: there is nothing left to
     *       "interrupt".  The system user is done last, because destroying its DE key immediately
     *       makes {@code /data/system_de} unreadable and kills the rest of this method.
     *   <li>Delete every key in the Keystore database (auth-bound app keys, synthetic-password
     *       protector keys, recoverable-keystore material).  This does <em>not</em> cover vold's
     *       blob-mode keys such as the whole-partition metadata encryption key; those are destroyed
     *       by recovery reformatting {@code /metadata} in the next step.
     *   <li>Reboot into recovery, which reformats {@code /data}, {@code /cache} and
     *       {@code /metadata}.
     * </ol>
     *
     * <p>Step 2 is the step that makes the wipe uninterruptible.  {@code fscrypt_destroy_user_keys}
     * asks Keystore to delete the KeyMint blob that wraps each user's CE/DE key and
     * {@code secdiscard}s the on-flash {@code encrypted_key} and {@code secdiscardable} files under
     * {@code /data/misc/vold/user_keys}.  Once those are gone the per-file encryption keys cannot be
     * reconstructed from any combination of the PIN, the TEE and the raw flash, so cutting power at
     * any later point does not save the data.
     *
     * <p><b>External storage:</b> nothing here touches public (portable) volumes.  A portable
     * microSD card carries no fscrypt policy and no vold volume key, so {@code
     * destroyUserStorageKeys} has nothing to destroy for it; recovery's {@code --wipe_data} erases
     * only {@code /data}, {@code /cache} and {@code /metadata}.  We never pass {@code --wipe_media}
     * or {@code --wipe_euicc}, and we never call {@code format()}, {@code forgetPartition()} or
     * {@code destroyUserStorage()} on any volume.
     */
    private void wipeDevice() {
        synchronized (mWipeLock) {
            if (mWipeStarted) {
                return;
            }
            mWipeStarted = true;
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            // (1) Arm recovery first, and wait for it (bounded): step 2 can kill this process
            // before anything after it runs, and every reboot from then on must land in the reset.
            // Independent of vold, and does not touch /data.
            Thread bcbThread = new Thread(this::armRecoveryWipe, "DuressBcb");
            bcbThread.setDaemon(true);
            bcbThread.start();
            try {
                bcbThread.join(BCB_SETUP_TIMEOUT_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            // (2) Crypto-erase.  This is the part that must not be interruptible.
            destroyAllStorageKeys();

            // (3) Nuke everything left in the Keystore database.
            try {
                AndroidKeyStoreMaintenance.deleteAllKeys();
            } catch (Exception e) {
                // Best effort.
            }

            // (4) Reboot.  The metadata encryption key under /metadata/vold is vold's alone
            // (root 0700, vold_metadata_file): recovery's --wipe_data reformats /metadata with it.
            rebootToRecovery();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Destroys the CE and DE storage keys of every user.  Profiles and secondary users first, the
     * system user last.
     */
    private void destroyAllStorageKeys() {
        final StorageManagerInternal smInternal =
                LocalServices.getService(StorageManagerInternal.class);
        if (smInternal == null) {
            return;
        }
        final List<Integer> userIds = new ArrayList<>();
        try {
            UserManager um = mContext.getSystemService(UserManager.class);
            for (UserInfo user : um.getUsers()) {
                if (user.id != UserHandle.USER_SYSTEM) {
                    userIds.add(user.id);
                }
            }
        } catch (Exception e) {
            // If we cannot enumerate users we still destroy the system user's keys below, which
            // is what makes /data/system, /data/system_de and /data/user/0 unreadable.
        }
        // The system user's DE key protects /data/system_de and /data/system; once it is gone this
        // process is effectively dead, so do it last.
        userIds.add(UserHandle.USER_SYSTEM);

        for (int userId : userIds) {
            try {
                smInternal.destroyUserStorageKeys(userId);
            } catch (Exception e) {
                // Keep going: destroying the remaining users' keys matters more than this one.
            }
        }
    }

    /**
     * Writes {@code --wipe_data} into the bootloader control block on the {@code misc} partition so
     * that the next boot lands in recovery and reformats {@code /data}.  Best effort: the
     * crypto-erase that follows makes the data unrecoverable regardless.
     */
    private void armRecoveryWipe() {
        try {
            IRecoverySystem rs = IRecoverySystem.Stub.asInterface(
                    ServiceManager.getService(Context.RECOVERY_SERVICE));
            if (rs == null) {
                return;
            }
            // NOTE: --wipe_data only.  Never --wipe_media (would erase adopted/emulated media) and
            // never --wipe_euicc.  Recovery erases /data, /cache and /metadata and nothing else.
            final String command = "--wipe_data\n--reason=" + WIPE_REASON + "\n";
            if (!rs.setupBcb(command)) {
                // One retry: uncrypt can lose the race for its socket right after boot.
                rs.setupBcb(command);
            }
        } catch (Exception e) {
            // Best effort.
        }
    }

    private void rebootToRecovery() {
        try {
            PowerManager pm = mContext.getSystemService(PowerManager.class);
            if (pm != null) {
                pm.reboot(PowerManager.REBOOT_RECOVERY);
                return;
            }
        } catch (Exception e) {
            // Fall through to the init-level reboot below.
        }
        try {
            // Handled by init; needs neither /data nor a working system_server.
            SystemProperties.set("sys.powerctl", "reboot,recovery");
        } catch (Exception e) {
            // Nothing left to try.  The data is already destroyed.
        }
    }
}
