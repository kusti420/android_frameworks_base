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

package com.android.internal.ichthys.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The one table of IchthysOS privacy shields: for each shield its ichthys.nix name, the settings
 * key of its switch, the settings key of its per-app list, the table each key lives in, and what
 * being on the list means.
 *
 * <p>A shield makes an app that holds a permission get the answer a fresh phone would give (an
 * empty address book, no location fix, a scan that finds nothing) instead of the real data. The
 * enforcement lives next to the data: the {@code IchthysShield} classes in ContactsProvider,
 * CalendarProvider, TelephonyProvider, Telecomm and frameworks/opt/telephony, ClipboardService,
 * the native SensorService, UsageStatsService, LocationManagerService and the Bluetooth, Wi-Fi,
 * NFC and UWB modules. Those copies can't link against this class (they ship in other APKs and
 * APEXes), so the key names are repeated there; this table is the reference they are checked
 * against, and what the config schema ({@code privacy.shields.<name>}), Settings and Ichthys
 * Guard should read instead of spelling keys out again.
 *
 * <p><b>Switch.</b> {@code 0} is off, anything else, <em>including an unset key</em>, is on:
 * {@link #isOn}. A shield without a switch key is always on.
 *
 * <p><b>List.</b> Comma-separated package names, per user. Its meaning is the shield's
 * {@link Polarity}: on an {@link Polarity#ALLOW} list an app gets the real data, on a
 * {@link Polarity#PROTECT} list it gets the protected answer (those shields protect nobody until
 * an app is listed). In the contacts and calendar lists an entry may be {@code package:picked}
 * ({@link #PICKED_SUFFIX}): the app sees only what the user picked for it, and counts as not
 * allowed the real data.
 *
 * <p>This is the successor of {@code com.android.server.pm.permission.ContactsLockdownPolicy},
 * whose key names live here now ({@link #CONTACTS}, {@link #CALENDAR}).
 */
public final class IchthysShields {

    /** The settings table a key lives in. Global keys are device-wide, Secure keys per user. */
    public enum Table { GLOBAL, SECURE }

    /** What being on a shield's list means for an app. */
    public enum Polarity {
        /** Listed apps get the real data; everyone else is shielded. */
        ALLOW,
        /** Listed apps are shielded; everyone else gets the real data. */
        PROTECT
    }

    /** Marks a "picked items only" entry in the contacts and calendar lists. */
    public static final String PICKED_SUFFIX = ":picked";

    /** One shield. Immutable. */
    public static final class Shield {
        /** Name in ichthys.nix ({@code privacy.shields.<name>}), e.g. "callLog". */
        public final String name;
        /** What the shield does, one line for docs and Settings. */
        public final String description;
        /** What being on the per-app list means, one line; null when there is no list. */
        public final String listDescription;
        /** Key of the on/off switch, or null when the shield has none (always on). */
        public final String switchKey;
        /** Table of {@link #switchKey}; null when there is no switch. */
        public final Table switchTable;
        /** Key of the per-app list, or null when the shield has none. */
        public final String listKey;
        /** Table of {@link #listKey} (always {@link Table#SECURE} today). */
        public final Table listTable;
        /** What being on the list means. */
        public final Polarity polarity;
        /** Whether list entries may carry {@link #PICKED_SUFFIX}. */
        public final boolean picked;

        Shield(String name, String description, String listDescription, String switchKey,
                Table switchTable, String listKey, Polarity polarity, boolean picked) {
            this.name = name;
            this.description = description;
            this.listDescription = listDescription;
            this.switchKey = switchKey;
            this.switchTable = switchTable;
            this.listKey = listKey;
            this.listTable = listKey == null ? null : Table.SECURE;
            this.polarity = polarity;
            this.picked = picked;
        }

        /** The attribute of the per-app list in ichthys.nix: "allow" or "protect". */
        public String listAttribute() {
            return polarity == Polarity.ALLOW ? "allow" : "protect";
        }
    }

    // The three shields that predate the ichthys_<feature>_shield naming keep their keys.
    public static final Shield CONTACTS = new Shield("contacts",
            "Contacts: apps read an empty address book. The phone, contacts and SMS apps "
                    + "always read the real one.",
            "Apps that read the real contacts (\"package:picked\": only the contacts picked "
                    + "for it).",
            "ichthys_contacts_lockdown", Table.GLOBAL,
            "ichthys_contacts_allowed_pkgs", Polarity.ALLOW, true);
    public static final Shield CLIPBOARD = new Shield("clipboard",
            "Clipboard: apps read it only right after you touch them.",
            "Apps that read the clipboard at any time.",
            "ichthys_clipboard_lockdown", Table.GLOBAL,
            "ichthys_clipboard_allowed_pkgs", Polarity.ALLOW, false);
    public static final Shield SENSORS = new Shield("sensors",
            "Motion sensors: apps get a generic sensor list, capped rates and rounded values, so "
                    + "the sensors can't identify the phone.",
            "Apps that get exact sensors.",
            "ichthys_sensor_fingerprint_protection", Table.GLOBAL,
            "ichthys_sensor_exempt_pkgs", Polarity.ALLOW, false);

    public static final Shield CONTACTS_WRITE = feature("contactsWrite", "contacts_write",
            "Saving contacts: changes report success but are not saved. An app allowed the real "
                    + "contacts may also change them.",
            "Apps whose changes to contacts are saved.");
    public static final Shield CALENDAR = new Shield("calendar",
            "Calendar: apps read an empty calendar.",
            "Apps that read the real calendar (\"package:picked\": only what was picked for "
                    + "it).",
            "ichthys_calendar_shield", Table.SECURE,
            "ichthys_calendar_allowed_pkgs", Polarity.ALLOW, true);
    public static final Shield CALENDAR_WRITE = feature("calendarWrite", "calendar_write",
            "Saving calendar events: changes report success but are not saved.",
            "Apps whose changes to the calendar are saved.");
    public static final Shield CALL_LOG = feature("callLog", "call_log",
            "Call log: apps read an empty call log.",
            "Apps that read the real call log.");
    public static final Shield CALL_LOG_WRITE = feature("callLogWrite", "call_log_write",
            "Changing the call log: changes report success but are not saved.",
            "Apps whose changes to the call log are saved.");
    public static final Shield SMS_READ = feature("smsRead", "sms_read",
            "Text messages: apps read an empty inbox.",
            "Apps that read the real text messages.");
    public static final Shield SMS_SEND = feature("smsSend", "sms_send",
            "Sending texts: you confirm every text an app sends.",
            "Apps that send texts without asking.");
    public static final Shield MMS_PUSH = feature("mmsPush", "mms_push",
            "MMS and WAP push: only the SMS app receives them.",
            "Apps that receive MMS and WAP push as well.");
    public static final Shield PHONE_NUMBER = feature("phoneNumber", "phone_number",
            "Phone number: apps get no phone number.",
            "Apps that get the phone number.");
    public static final Shield CALL = feature("call", "call",
            "Placing calls: you confirm every call an app places.",
            "Apps that place calls without asking.");
    public static final Shield ANSWER_CALLS = feature("answerCalls", "answer_calls",
            "Answering and ending calls: apps can't.",
            "Apps that may answer and end calls.");

    // Shields without a switch: always on, the list is the only control.
    public static final Shield USAGE_HISTORY = new Shield("usageHistory",
            "Usage history: apps with usage access see an empty history.",
            "Apps with usage access that see the real history.",
            null, null, "ichthys_usage_full_pkgs", Polarity.ALLOW, false);
    public static final Shield BLE_ADVERTISE = new Shield("bleAdvertise",
            "Bluetooth advertising: apps can't make the phone a beacon.",
            "Apps that may advertise over Bluetooth.",
            null, null, "ichthys_ble_advertise_pkgs", Polarity.ALLOW, false);
    public static final Shield WIFI_CONTROL = new Shield("wifiControl",
            "Wi-Fi control: apps can't switch Wi-Fi or change its networks.",
            "Apps that may switch Wi-Fi and change its networks.",
            null, null, "ichthys_wifi_control_pkgs", Polarity.ALLOW, false);
    public static final Shield UWB = new Shield("uwb",
            "UWB ranging: apps can't measure the distance to nearby devices over UWB (Bluetooth "
                    + "and Wi-Fi ranging aren't covered).",
            "Apps that may use UWB ranging.",
            null, null, "ichthys_uwb_pkgs", Polarity.ALLOW, false);

    // Shields that are per-app choices: nobody is protected until listed.
    public static final Shield SCANS = new Shield("scans",
            "Bluetooth, Wi-Fi and cell scans: for the listed apps, scans find nothing and "
                    + "they get no cell tower information.",
            "Apps whose Bluetooth, Wi-Fi and cell scans find nothing.",
            null, null, "ichthys_scan_empty_pkgs", Polarity.PROTECT, false);
    public static final Shield LOCATION = new Shield("location",
            "Location: the listed apps never get a fix, as if indoors.",
            "Apps that never get a location fix, as if indoors.",
            null, null, "ichthys_location_nofix_pkgs", Polarity.PROTECT, false);
    public static final Shield NFC = new Shield("nfc",
            "NFC: the listed apps can't read tags or emulate cards.",
            "Apps that can't read NFC tags or emulate cards.",
            null, null, "ichthys_nfc_blocked_pkgs", Polarity.PROTECT, false);

    private static final List<Shield> ALL;

    static {
        final List<Shield> all = new ArrayList<>();
        Collections.addAll(all, CONTACTS, CONTACTS_WRITE, CALENDAR, CALENDAR_WRITE, CALL_LOG,
                CALL_LOG_WRITE, SMS_READ, SMS_SEND, MMS_PUSH, PHONE_NUMBER, CALL, ANSWER_CALLS,
                CLIPBOARD, SENSORS, USAGE_HISTORY, BLE_ADVERTISE, WIFI_CONTROL, UWB, SCANS,
                LOCATION, NFC);
        ALL = Collections.unmodifiableList(all);
    }

    private IchthysShields() {}

    /**
     * A shield with the regular keys of a provider feature: per-user Secure
     * {@code ichthys_<feature>_shield} and {@code ichthys_<feature>_allowed_pkgs}.
     */
    private static Shield feature(String name, String feature, String description,
            String listDescription) {
        return new Shield(name, description, listDescription,
                "ichthys_" + feature + "_shield", Table.SECURE,
                "ichthys_" + feature + "_allowed_pkgs", Polarity.ALLOW, false);
    }

    /** Every shield, in the order Settings lists them. */
    public static List<Shield> all() {
        return ALL;
    }

    /** The shield with this ichthys.nix name, or null. */
    public static Shield byName(String name) {
        for (Shield s : ALL) {
            if (s.name.equals(name)) {
                return s;
            }
        }
        return null;
    }

    /** The shield that owns this settings key (switch or list), or null. */
    public static Shield byKey(String key) {
        for (Shield s : ALL) {
            if (key != null && (key.equals(s.switchKey) || key.equals(s.listKey))) {
                return s;
            }
        }
        return null;
    }

    /** Whether a stored switch value means on: only "0" is off, an unset key is on. */
    public static boolean isOn(String stored) {
        return stored == null || !"0".equals(stored.trim());
    }

    /**
     * Whether {@code packageName} is on a stored list. A {@link #PICKED_SUFFIX} entry does not
     * count: it stands for "picked items only", not for the list's own meaning.
     */
    public static boolean isListed(String stored, String packageName) {
        if (stored == null || packageName == null) {
            return false;
        }
        for (String entry : stored.split(",")) {
            if (packageName.equals(entry.trim())) {
                return true;
            }
        }
        return false;
    }
}
