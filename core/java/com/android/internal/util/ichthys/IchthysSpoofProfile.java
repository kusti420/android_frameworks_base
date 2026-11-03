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

package com.android.internal.util.ichthys;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.Configuration;
import android.os.LocaleList;
import android.system.ErrnoException;
import android.system.Os;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * IchthysOS: the app-process half of "Spoofed values" (Guard gear, tracker issue #60).
 *
 * <p>The fake place only ever replaced the GPS fix. An app without the location permission could
 * still tell where the phone really is from everything around the fix: the SIM and network
 * country, the carrier and PLMN, the mcc/mnc in its {@link Configuration}, the time zone, the
 * Samsung sales code. This class makes all of those agree with the fake place.
 *
 * <p>system_server decides who is spoofed (see {@code IchthysLocationSpoof}) and hands a spoofed
 * process its profile JSON through {@code ILocationManager.getIchthysSpoofProfile()}. The JSON is
 * written by Settings and looks like
 * <pre>
 * {"country":"US","tz":"America/New_York","mcc":"310","mnc":"260","operator":"T-Mobile",
 *  "locale":"en-US","sales":"XAA","vpn":true}
 * </pre>
 * {@code ActivityThread.handleBindApplication} calls {@link #install} once, as early as possible,
 * and from then on:
 *
 * <ul>
 *   <li>{@link #getPropertyOverride} answers {@code SystemProperties.get()} for the {@code gsm.*}
 *       operator/country properties that TelephonyManager reads in-process, for
 *       {@code persist.sys.timezone} (which libcore's default-time-zone supplier reads), for the
 *       sales code and the locale. The override map is consulted after the Play Integrity map in
 *       {@link PropImitationHooks}, so both can be live in GmsCore.</li>
 *   <li>The {@code TZ} environment variable is set so bionic's {@code localtime()} agrees with
 *       java.util/android.icu, and the Java default zone is reset so it is re-read.</li>
 *   <li>{@link #rewrite(Configuration)} stamps mcc/mnc (and the app language if the profile
 *       carries one) onto every Configuration that enters the process over Binder, via
 *       {@code Configuration.readFromParcel}.</li>
 *   <li>{@link #rewrite(ServiceState)} rewrites the operator names, PLMN, roaming state and the
 *       registered PLMN / cell identity of each registration as the ServiceState is unparceled.</li>
 *   <li>{@link #rewrite(SubscriptionInfo)} returns a copy with the profile's country, mcc/mnc,
 *       carrier name and HPLMNs when a SubscriptionInfo is unparceled.</li>
 * </ul>
 *
 * <p>All state is set once per process and read through single volatile fields, so a process
 * that is not spoofed (every system process, every app that sees the real place) pays one
 * volatile null/boolean check on the hot paths and nothing else. Nothing here ever throws: a
 * broken profile must not stop an app from starting.
 *
 * @hide
 */
public final class IchthysSpoofProfile {

    private static final String TAG = "IchthysSpoofProfile";

    /** The one non-system package that is spoofed even though it is a system app. */
    private static final String BROKER_GMS = "com.google.android.gms";

    // Profile JSON keys (schema owned by Settings' Spoofed values page).
    private static final String KEY_COUNTRY = "country";
    private static final String KEY_TZ = "tz";
    private static final String KEY_MCC = "mcc";
    private static final String KEY_MNC = "mnc";
    private static final String KEY_OPERATOR = "operator";
    private static final String KEY_LOCALE = "locale";
    private static final String KEY_SALES = "sales";
    private static final String KEY_VPN = "vpn";

    /** True once {@link #install} has run with a profile that carries at least one field. */
    private static volatile boolean sActive;
    /** True once {@link #install} has run at all, so a second call is a no-op. */
    private static volatile boolean sInstalled;

    /** System property overrides; null until active. */
    private static volatile Map<String, String> sPropertyOverrides;

    /** ISO 3166-1 alpha-2, lower case ("us"), as TelephonyManager reports it. Null if unset. */
    private static volatile String sCountryLower;
    /** Three-digit MCC string, or null. */
    private static volatile String sMcc;
    /** Two or three-digit MNC string, or null. */
    private static volatile String sMnc;
    /** mcc + mnc ("310260"), or null unless both are set. */
    private static volatile String sPlmn;
    /** Carrier display name, or null. */
    private static volatile String sOperator;
    /** Olson time zone id, or null. */
    private static volatile String sTimeZone;
    /** BCP-47 app language, or null. */
    private static volatile String sLocaleTag;
    /** Samsung sales code, or null. */
    private static volatile String sSalesCode;
    /** Whether spoofed apps are meant to be offline without a VPN (informational here). */
    private static volatile boolean sRequireVpn;

    /** Configuration.mcc value, or 0 when the profile has no mcc. */
    private static volatile int sConfigMcc;
    /** Configuration.mnc value ({@link Configuration#MNC_ZERO} for "00"), or 0 when unset. */
    private static volatile int sConfigMnc;
    /** Prebuilt single-locale list for {@link #rewrite(Configuration)}, or null. */
    private static volatile LocaleList sLocaleList;

    private IchthysSpoofProfile() {}

    /** Whether this process has a spoof profile with at least one value. One volatile read. */
    public static boolean isActive() {
        return sActive;
    }

    /** Whether the package is a broker: a system app that is spoofed like a user app. */
    public static boolean isBroker(@Nullable String packageName) {
        return BROKER_GMS.equals(packageName);
    }

    /**
     * Called by {@link PropImitationHooks#getPropertyOverride} after its own map. Returns the
     * spoofed value for a property the profile covers, or null to fall through to the real one.
     */
    @Nullable
    public static String getPropertyOverride(String key) {
        final Map<String, String> overrides = sPropertyOverrides;
        if (overrides == null) return null;
        return overrides.get(key);
    }

    /** The lower-case spoofed country, or null if the profile has none. */
    @Nullable
    public static String getCountryIso() {
        return sCountryLower;
    }

    /** The spoofed PLMN (mcc + mnc), or null if the profile has no complete mcc/mnc. */
    @Nullable
    public static String getPlmn() {
        return sPlmn;
    }

    /** The spoofed carrier name, or null. */
    @Nullable
    public static String getOperator() {
        return sOperator;
    }

    /** The spoofed time zone id, or null. */
    @Nullable
    public static String getTimeZoneId() {
        return sTimeZone;
    }

    /** The spoofed app language as a BCP-47 tag, or null. */
    @Nullable
    public static String getLocaleTag() {
        return sLocaleTag;
    }

    /** The spoofed Samsung sales code, or null. */
    @Nullable
    public static String getSalesCode() {
        return sSalesCode;
    }

    /** Whether the profile asks for spoofed apps to be offline unless a VPN is up. */
    public static boolean requiresVpn() {
        return sRequireVpn;
    }

    /**
     * Parses the profile JSON and installs every override it carries. Runs once per process; a
     * second call does nothing. {@code "{}"} (spoofed, but no profile written yet) installs
     * nothing and leaves {@link #isActive()} false, which keeps the GPS-only behaviour.
     *
     * <p>Never throws.
     */
    public static void install(@Nullable String json) {
        if (sInstalled) return;
        sInstalled = true;
        if (TextUtils.isEmpty(json)) return;
        try {
            doInstall(json.trim());
        } catch (RuntimeException e) {
            Log.e(TAG, "could not install the spoof profile", e);
        }
    }

    private static void doInstall(@NonNull String json) {
        final JSONObject obj;
        try {
            obj = new JSONObject(json);
        } catch (JSONException e) {
            Log.e(TAG, "spoof profile is not valid JSON", e);
            return;
        }

        final String country = normalizeCountry(optTrimmed(obj, KEY_COUNTRY));
        final String tz = normalizeTimeZone(optTrimmed(obj, KEY_TZ));
        final String mcc = normalizeDigits(optTrimmed(obj, KEY_MCC), 3, 3);
        final String mnc = normalizeDigits(optTrimmed(obj, KEY_MNC), 2, 3);
        final String operator = optTrimmed(obj, KEY_OPERATOR);
        final String localeTag = normalizeLocale(optTrimmed(obj, KEY_LOCALE));
        final String sales = optTrimmed(obj, KEY_SALES);
        final boolean vpn = obj.optBoolean(KEY_VPN, false);

        final String plmn = (mcc != null && mnc != null) ? mcc + mnc : null;
        if (country == null && tz == null && plmn == null && operator == null
                && localeTag == null && sales == null) {
            Log.w(TAG, "spoof profile carries no usable value; GPS-only spoof stays");
            return;
        }

        final Map<String, String> props = new HashMap<>();
        if (country != null) {
            // TelephonyManager.getSimCountryIso()/getNetworkCountryIso() read these in-process
            // (multi-SIM values are comma-joined; a single value is fine for the split).
            props.put("gsm.sim.operator.iso-country", country);
            props.put("gsm.operator.iso-country", country);
        }
        if (plmn != null) {
            props.put("gsm.sim.operator.numeric", plmn);
            props.put("gsm.operator.numeric", plmn);
            // ActivityTaskManagerService mirrors the configuration mcc/mnc here for tracing.
            props.put("debug.tracing.mcc", Integer.toString(parseIntOrZero(mcc)));
            props.put("debug.tracing.mnc", Integer.toString(toConfigMnc(mnc)));
        }
        if (operator != null) {
            props.put("gsm.sim.operator.alpha", operator);
            props.put("gsm.operator.alpha", operator);
        }
        // The fake place is "home" by definition.
        props.put("gsm.operator.isroaming", "false");
        if (tz != null) {
            // libcore's default-time-zone supplier (RuntimeInit) reads this property.
            props.put("persist.sys.timezone", tz);
        }
        if (sales != null) {
            props.put("ro.boot.sales_code", sales);
        }
        if (localeTag != null) {
            props.put("persist.sys.locale", localeTag);
        }

        sCountryLower = country;
        sMcc = mcc;
        sMnc = mnc;
        sPlmn = plmn;
        sOperator = operator;
        sTimeZone = tz;
        sLocaleTag = localeTag;
        sSalesCode = sales;
        sRequireVpn = vpn;
        sConfigMcc = plmn != null ? parseIntOrZero(mcc) : 0;
        sConfigMnc = plmn != null ? toConfigMnc(mnc) : 0;
        sLocaleList = localeTag != null
                ? new LocaleList(Locale.forLanguageTag(localeTag)) : null;
        // Publish the property map before flipping sActive so that a reader that sees
        // isActive() == true always finds the overrides in place.
        sPropertyOverrides = props;
        sActive = true;

        if (tz != null) {
            installTimeZone(tz);
        }
        Log.i(TAG, "spoof profile installed: country=" + country + " plmn=" + plmn
                + " operator=" + (operator != null) + " tz=" + tz + " locale=" + localeTag
                + " sales=" + (sales != null) + " vpn=" + vpn);
    }

    /**
     * Makes bionic and Java agree on the spoofed zone. bionic's localtime() honours TZ; the Java
     * default is re-read through the RuntimeHooks supplier, which now returns our override.
     */
    private static void installTimeZone(@NonNull String tz) {
        try {
            Os.setenv("TZ", tz, true);
        } catch (ErrnoException e) {
            Log.w(TAG, "could not set TZ", e);
        }
        TimeZone.setDefault(null);
    }

    /**
     * Stamps the profile's mcc/mnc and app language onto a Configuration. Called for every
     * Configuration unparceled in this process and once for the bind-time configuration.
     */
    public static void rewrite(@Nullable Configuration config) {
        if (!sActive || config == null) return;
        try {
            if (sPlmn != null) {
                config.mcc = sConfigMcc;
                config.mnc = sConfigMnc;
            }
            final LocaleList locales = sLocaleList;
            if (locales != null) {
                config.setLocales(locales);
            }
        } catch (RuntimeException e) {
            logOnce("could not rewrite a Configuration", e);
        }
    }

    /**
     * Rewrites an unparceled ServiceState in place: operator names and PLMN become the profile's,
     * roaming is cleared, and every registration is rebuilt with the profile's registered PLMN
     * and no cell identity. Domain, transport, registration state, radio technology, NR state
     * and the voice/data specific info are kept, so getState(), getDataNetworkType() and the
     * registration list keep working.
     */
    public static void rewrite(@Nullable ServiceState ss) {
        if (!sActive || ss == null) return;
        try {
            final String operator = sOperator;
            final String plmn = sPlmn;
            if (operator != null || plmn != null) {
                final String longName = operator != null ? operator : ss.getOperatorAlphaLong();
                final String shortName = operator != null ? operator : ss.getOperatorAlphaShort();
                final String numeric = plmn != null ? plmn : ss.getOperatorNumeric();
                ss.setOperatorName(longName, shortName, numeric);
            }
            if (operator != null) {
                ss.setOperatorAlphaLongRaw(operator);
                ss.setOperatorAlphaShortRaw(operator);
            }
            // getNetworkRegistrationInfoList() hands out copies; rebuild each and put it back
            // (addNetworkRegistrationInfo replaces the entry with the same domain + transport).
            final List<NetworkRegistrationInfo> nris = ss.getNetworkRegistrationInfoList();
            for (NetworkRegistrationInfo nri : nris) {
                ss.addNetworkRegistrationInfo(spoofedCopy(nri, plmn));
            }
        } catch (RuntimeException e) {
            logOnce("could not rewrite a ServiceState", e);
        }
    }

    /**
     * Copies a registration with the registered PLMN replaced, the cell identity dropped and
     * roaming cleared. The Builder copy constructor keeps domain, transport, state, technology,
     * reject cause, services and the voice/data specific info; the few mutable fields it does not
     * carry are copied by hand.
     */
    @NonNull
    private static NetworkRegistrationInfo spoofedCopy(@NonNull NetworkRegistrationInfo nri,
            @Nullable String plmn) {
        final NetworkRegistrationInfo.Builder b = new NetworkRegistrationInfo.Builder(nri)
                .setCellIdentity(null)
                .setRegisteredPlmn(plmn != null ? plmn : nri.getRegisteredPlmn());
        if (nri.isNetworkRoaming()) {
            // The raw network state is final after build(); map ROAMING to HOME here so that
            // isNetworkRoaming() is false too, not only the overridable roaming type.
            b.setRegistrationState(NetworkRegistrationInfo.REGISTRATION_STATE_HOME);
        }
        final NetworkRegistrationInfo copy = b.build();
        copy.setNrState(nri.getNrState());
        copy.setIsUsingCarrierAggregation(nri.isUsingCarrierAggregation());
        // Also flips an overridden ROAMING registration state back to HOME.
        copy.setRoamingType(ServiceState.ROAMING_TYPE_NOT_ROAMING);
        return copy;
    }

    /**
     * Returns a copy of a SubscriptionInfo carrying the profile's country, mcc/mnc, carrier name
     * and HPLMNs. A display name the user typed in is kept; one that came from the carrier or the
     * SIM becomes the spoofed carrier name. Returns the input unchanged when nothing applies.
     */
    @Nullable
    public static SubscriptionInfo rewrite(@Nullable SubscriptionInfo info) {
        if (!sActive || info == null) return info;
        try {
            final String country = sCountryLower;
            final String mcc = sMcc;
            final String mnc = sMnc;
            final String plmn = sPlmn;
            final String operator = sOperator;
            if (country == null && plmn == null && operator == null) return info;

            final SubscriptionInfo.Builder b = new SubscriptionInfo.Builder(info);
            if (country != null) {
                b.setCountryIso(country);
            }
            if (plmn != null) {
                b.setMcc(mcc);
                b.setMnc(mnc);
                b.setHplmns(new String[] {plmn});
                b.setEhplmns(new String[] {plmn});
            }
            if (operator != null) {
                b.setCarrierName(operator);
                if (isCarrierOrSimDisplayName(info.getDisplayNameSource())) {
                    b.setDisplayName(operator);
                }
            }
            return b.build();
        } catch (RuntimeException e) {
            logOnce("could not rewrite a SubscriptionInfo", e);
            return info;
        }
    }

    private static boolean isCarrierOrSimDisplayName(int source) {
        switch (source) {
            case SubscriptionManager.NAME_SOURCE_CARRIER_ID:
            case SubscriptionManager.NAME_SOURCE_SIM_SPN:
            case SubscriptionManager.NAME_SOURCE_CARRIER:
            case SubscriptionManager.NAME_SOURCE_SIM_PNN:
                return true;
            default:
                return false;
        }
    }

    // ---- parsing helpers ----

    @Nullable
    private static String optTrimmed(@NonNull JSONObject obj, @NonNull String key) {
        if (!obj.has(key) || obj.isNull(key)) return null;
        final String v = obj.optString(key, "").trim();
        return v.isEmpty() ? null : v;
    }

    /** Two ASCII letters -> lower case, anything else -> null. */
    @Nullable
    private static String normalizeCountry(@Nullable String v) {
        if (v == null || v.length() != 2) return null;
        for (int i = 0; i < 2; i++) {
            final char c = v.charAt(i);
            if ((c < 'a' || c > 'z') && (c < 'A' || c > 'Z')) return null;
        }
        return v.toLowerCase(Locale.ROOT);
    }

    /** Between {@code min} and {@code max} ASCII digits, else null. */
    @Nullable
    private static String normalizeDigits(@Nullable String v, int min, int max) {
        if (v == null || v.length() < min || v.length() > max) return null;
        for (int i = 0; i < v.length(); i++) {
            final char c = v.charAt(i);
            if (c < '0' || c > '9') return null;
        }
        return v;
    }

    /** A zone id that java.util.TimeZone recognises, else null. */
    @Nullable
    private static String normalizeTimeZone(@Nullable String v) {
        if (v == null) return null;
        final TimeZone zone = TimeZone.getTimeZone(v);
        // getTimeZone() falls back to GMT for an unknown id; only accept a real match.
        if (!v.equals(zone.getID())) {
            Log.w(TAG, "unknown time zone in spoof profile: " + v);
            return null;
        }
        return v;
    }

    /** A BCP-47 tag that resolves to a locale with a language, else null. */
    @Nullable
    private static String normalizeLocale(@Nullable String v) {
        if (v == null) return null;
        final Locale locale = Locale.forLanguageTag(v);
        if (TextUtils.isEmpty(locale.getLanguage())) {
            Log.w(TAG, "unusable locale in spoof profile: " + v);
            return null;
        }
        return locale.toLanguageTag();
    }

    private static int parseIntOrZero(@Nullable String digits) {
        if (digits == null) return 0;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Configuration.mnc convention (ActivityManagerService.updateMccMncConfiguration): an MNC
     * that parses to 0 ("00", "000") is stored as {@link Configuration#MNC_ZERO}, because 0 in a
     * Configuration means "undefined".
     */
    private static int toConfigMnc(@Nullable String mnc) {
        final int v = parseIntOrZero(mnc);
        return v == 0 ? Configuration.MNC_ZERO : v;
    }

    private static volatile boolean sLoggedRewriteFailure;

    private static void logOnce(@NonNull String what, @NonNull RuntimeException e) {
        if (sLoggedRewriteFailure) return;
        sLoggedRewriteFailure = true;
        Log.e(TAG, what, e);
    }
}
