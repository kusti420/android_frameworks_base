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

import static com.android.internal.ichthys.config.Binding.Store.GLOBAL;
import static com.android.internal.ichthys.config.Binding.Store.LINEAGE_SECURE;
import static com.android.internal.ichthys.config.Binding.Store.LINEAGE_SYSTEM;
import static com.android.internal.ichthys.config.Binding.Store.SECURE;
import static com.android.internal.ichthys.config.Binding.Store.SYSTEM;
import static com.android.internal.ichthys.config.Binding.setting;

/**
 * Hand-written options: the theme engine, IchthysOS features and settings that crDroidSettings
 * manages in fragment code (so the generator can't see them). Keys match their consumers; see
 * each feature's source for the meaning.
 *
 * <p><b>Defaults.</b> The defaults here are the IchthysOS baseline, the same values as
 * /product/etc/ichthys/default.nix (applied as generation 1 of every new user) with its theme
 * preset (presets/koi.nix) and privacy level (privacy/normal.nix) filled in; IchthysConfigTest
 * holds the three files and this class to each other. A consumer must treat an unset key as the
 * default given here, so that "reset to default" and "never set" look the same.
 */
final class IchthysOptions {
    private IchthysOptions() {}

    /** Theme presets shipped in /product/etc/ichthys/presets/&lt;name&gt;.nix. */
    public static final String[] PRESETS = {"none", "koi", "abyss", "reef", "kelp", "paper"};
    /** Privacy levels shipped in /product/etc/ichthys/privacy/&lt;name&gt;.nix. */
    public static final String[] PRIVACY_LEVELS = {"relaxed", "normal", "strict"};

    static void register(OptionSchema s) {
        registerTheme(s);
        registerStatusBar(s);
        registerPrivacy(s);
        registerBackground(s);
        registerUi(s);
        registerApps(s);
        registerBluetooth(s);
    }

    // ---------------------------------------------------------------------------------------------
    // theme.*

    private static void registerTheme(OptionSchema s) {
        s.add(Option.enumeration("theme.preset", PRESETS, PRESETS, "koi",
                "Theme preset. Its theme.* values apply wherever this file doesn't set them.",
                setting(SECURE, "ichthys_theme_preset")));
        s.presetFamily("theme.preset", "theme.", "presets", "none");
        s.add(Option.color("theme.accent", "#ff6b3d",
                "Accent color: \"wallpaper\" (Monet picks it) or a seed color \"#rrggbb\".",
                Binding.themeJson("accent")).orWallpaper());
        s.add(Option.enumeration("theme.style",
                new String[] {"tonalSpot", "vibrant", "expressive", "spritz", "rainbow",
                        "fruitSalad", "content", "fidelity", "monochrome", "neutral"},
                new String[] {"TONAL_SPOT", "VIBRANT", "EXPRESSIVE", "SPRITZ", "RAINBOW",
                        "FRUIT_SALAD", "CONTENT", "FIDELITY", "MONOCHROMATIC", "NEUTRAL"},
                "tonalSpot", "How the palette is derived from the accent (Monet style).",
                Binding.themeJson("style")));
        s.add(Option.bool("theme.pitchBlack", Boolean.FALSE,
                "Darkest surface is pure #000 (OLED).", setting(SECURE, "custom_pitch_black")));
        s.add(Option.enumeration("theme.neutral", new String[] {"tinted", "neutral"},
                new String[] {"tinted", "neutral"}, "neutral",
                "Surfaces tinted by the accent (its hue nudged cool, low chroma), or the preset's "
                        + "own neutral ink ramp.",
                setting(SECURE, "ichthys_theme_neutral")));
        s.add(Option.integer("theme.surfaces", 4L, 2, 5,
                "Number of distinct surface elevation levels in panels and cards (2: ground + "
                        + "card; 3: + raised; 4-5: + tile).",
                setting(SECURE, "ichthys_theme_surfaces")));
        s.add(Option.integer("theme.shape.radius", 22L, 0, 40,
                "Corner radius in dp for dialogs, cards, QS panel and notifications.",
                setting(SECURE, "ichthys_theme_radius")));
        s.add(Option.enumeration("theme.shape.style", new String[] {"rounded", "pill", "squircle"},
                new String[] {"rounded", "pill", "squircle"}, "pill",
                "Corner style: rounded, pill (fully round ends) or squircle.",
                setting(SECURE, "ichthys_theme_shape")));
        s.removed("theme.density", "nothing ever read it");
        s.add(Option.packageName("theme.font.body", null,
                "System font overlay package (android.theme.customization.font), null for default.",
                Binding.themeJson("font")));
        // Font families: named families from fonts_customization.xml. Unset means the koi faces
        // (the defaults below, which are also the android:string/ichthys_font_* resource
        // defaults); "sans-serif" etc. picks a system family instead.
        s.add(Option.string("theme.font.display", "syne",
                "Font family for large titles and clocks (e.g. \"syne\", \"outfit\"). Unless "
                        + "theme.surface = \"stock\" or theme.font.body is set, it is also the "
                        + "system headline font.",
                setting(SECURE, "ichthys_theme_font_display")));
        s.add(Option.string("theme.font.text", "instrument-sans",
                "Font family for body text (e.g. \"instrument-sans\", \"ibm-plex-sans\", "
                        + "\"sans-serif\"). Unless theme.surface = \"stock\" or theme.font.body "
                        + "is set, it is also the system body font.",
                setting(SECURE, "ichthys_theme_font_text")));
        s.add(Option.string("theme.font.mono", "jetbrains-mono",
                "Font family for numbers in IchthysOS UI (e.g. \"jetbrains-mono\").",
                setting(SECURE, "ichthys_theme_font_mono")));
        // "stock" turns the whole Shoal palette off (plain Monet colours, stock fonts); the
        // palette itself is derived in system_server (ShoalPalette).
        s.add(Option.enumeration("theme.surface", new String[] {"stock", "hairline", "tonal"},
                new String[] {"stock", "hairline", "tonal"}, "hairline",
                "Surface style: \"hairline\" (Koi: ink ground, 1dp-outlined panels), \"tonal\" "
                        + "(Abyss: filled panels, no outline) or \"stock\" (plain Monet, no "
                        + "Shoal palette or fonts).",
                setting(SECURE, "ichthys_theme_surface")));
        s.add(Option.bool("theme.iconBadges", Boolean.TRUE,
                "Draw Settings category icons on badge tiles (false: plain icons).",
                setting(SECURE, "ichthys_settings_icon_badges")));
        s.add(Option.enumeration("theme.iconStyle", new String[] {"mono", "colour"},
                new String[] {"mono", "colour"}, "mono",
                "Badge tile style: mono (off-white glyph on a quiet tile, accent only for accent "
                        + "items) or colour (outlined badges in per-category colours).",
                setting(SECURE, "ichthys_settings_icon_style")));
    }

    // ---------------------------------------------------------------------------------------------
    // statusBar.*, quickSettings.* (IchthysOS parts)

    private static final String[] BATTERY_STYLES = {"classic", "circle", "text", "dottedCircle",
            "landscape", "filledCircle", "hidden"};
    private static final String[] BATTERY_STYLE_VALUES = {"0", "1", "2", "3", "4", "5", "6"};
    private static final String[] PERCENT = {"hidden", "inside", "left", "right"};
    private static final String[] PERCENT_VALUES = {"0", "1", "2", "3"};

    private static void registerStatusBar(OptionSchema s) {
        s.add(Option.enumeration("statusBar.layout", new String[] {"single", "zigzag"},
                new String[] {"single", "zigzag"}, "zigzag",
                "Status icon layout: one row, or the two-row zig-zag lattice of circles.",
                setting(SECURE, "ichthys_statusbar_layout")));
        s.add(Option.stringList("statusBar.hiddenIcons",
                "Status bar icon slots to hide (e.g. \"volume\" \"alarm_clock\").",
                setting(SECURE, "icon_blacklist")));

        // Notification dot and privacy indicator (SystemUI statusbar/ichthys/indicator). Consumers
        // treat an unset key as the default below.
        s.add(Option.bool("statusBar.notificationDot.enabled", Boolean.TRUE,
                "Show a dot in the status bar corner while there are unseen, non-silent "
                        + "notifications.",
                setting(SYSTEM, "ichthys_notif_dot_enabled")));
        s.add(Option.bool("statusBar.notificationDot.aod", Boolean.TRUE,
                "Keep the notification dot on the always-on display (dimmed, with burn-in "
                        + "drift).",
                setting(SYSTEM, "ichthys_notif_dot_aod")));
        s.add(Option.enumeration("statusBar.notificationDot.color",
                new String[] {"accent", "white", "app"}, new String[] {"0", "1", "2"}, "accent",
                "Notification dot colour: the theme accent, white (the status bar icon tint), or "
                        + "the newest notifying app's colour.",
                setting(SYSTEM, "ichthys_notif_dot_color")));
        s.add(Option.bool("statusBar.notificationDot.replaceIcons", Boolean.FALSE,
                "Hide the status bar notification icons and show only the dot.",
                setting(SYSTEM, "ichthys_notif_dot_replace_icons")));
        s.add(Option.enumeration("statusBar.privacyIndicator.style",
                new String[] {"cutoutRing", "dot"}, new String[] {"0", "1"}, "cutoutRing",
                "Camera / microphone / location in-use indicator: a ring around the front camera "
                        + "cutout, or the stock corner dot (also used when the display has no "
                        + "cutout). With \"dot\" the notification dot sits just inside it.",
                setting(SYSTEM, "ichthys_privacy_indicator_style")));
        s.add(Option.bool("statusBar.privacyIndicator.colorByType", Boolean.FALSE,
                "Colour the privacy ring by what is in use (camera, microphone, location) "
                        + "instead of one colour.",
                setting(SYSTEM, "ichthys_privacy_indicator_color_by_type")));
        // No default: unset means "as wide as the display's cutout ring asks for" (the consumer's
        // dimen), which no number here can say. A default of 2 could never be applied, because
        // the unset key already counted as 2.
        s.add(Option.integer("statusBar.privacyIndicator.ringWidth", null, 1, 4,
                "Stroke width of the privacy ring in dp (null: sized for the display).",
                setting(SYSTEM, "ichthys_privacy_indicator_ring_width")));
        s.add(Option.bool("statusBar.weather.anchorToCutout", Boolean.TRUE,
                "Keep the status bar weather locked in place right after the camera cutout "
                        + "instead of moving with the icons (only with a centred cutout).",
                setting(SYSTEM, "ichthys_statusbar_weather_anchor_cutout")));

        s.add(Option.enumeration("statusBar.battery.style", BATTERY_STYLES, BATTERY_STYLE_VALUES,
                "classic", "Battery icon style.",
                setting(LINEAGE_SYSTEM, "status_bar_battery_style")));
        s.add(Option.enumeration("statusBar.battery.percent", PERCENT, PERCENT_VALUES, "hidden",
                "Battery percentage placement.",
                setting(LINEAGE_SYSTEM, "status_bar_show_battery_percent")));
        s.add(Option.bool("statusBar.battery.percentChargingOnly", Boolean.FALSE,
                "Show the percentage only while charging.",
                setting(SYSTEM, "ichthys_battery_percent_charging_only")));
        s.add(Option.bool("statusBar.battery.percentBold", Boolean.FALSE,
                "Bold percentage text.", setting(SYSTEM, "ichthys_battery_percent_bold")));
        s.add(Option.bool("statusBar.battery.chargingIndicator", Boolean.TRUE,
                "Show the charging bolt.", setting(SYSTEM, "ichthys_battery_charging_indicator")));
        s.add(Option.bool("statusBar.battery.powerSaveIndicator", Boolean.TRUE,
                "Show the power-save mark.",
                setting(SYSTEM, "ichthys_battery_powersave_indicator")));
        s.add(Option.enumeration("statusBar.battery.fillMode",
                new String[] {"tint", "custom", "accent"}, new String[] {"0", "1", "2"}, "tint",
                "Battery fill color source.", setting(SYSTEM, "ichthys_battery_fill_mode")));
        // No default: an unset key means the theme accent, which no colour here can say.
        s.add(Option.color("statusBar.battery.fillColor", null,
                "Custom battery fill color (fillMode = custom; null: the theme accent).",
                setting(SYSTEM, "ichthys_battery_fill_color")).storedAsInt());
        s.add(Option.enumeration("statusBar.battery.stateColors",
                new String[] {"off", "theme", "custom"}, new String[] {"0", "1", "2"}, "theme",
                "Colors for charging / power save / low states.",
                setting(SYSTEM, "ichthys_battery_state_colors")));
        s.add(Option.color("statusBar.battery.chargingColor", "#18cc47",
                "Charging color (stateColors = custom).",
                setting(SYSTEM, "ichthys_battery_charging_color")).storedAsInt());
        s.add(Option.color("statusBar.battery.powerSaveColor", "#ffc917",
                "Power save color (stateColors = custom).",
                setting(SYSTEM, "ichthys_battery_powersave_color")).storedAsInt());
        s.add(Option.color("statusBar.battery.lowColor", "#ff0e01",
                "Low battery color (stateColors = custom).",
                setting(SYSTEM, "ichthys_battery_low_color")).storedAsInt());
        s.add(Option.integer("statusBar.battery.lowLevel", 20L, 5, 30,
                "Level (%) at which the battery counts as low.",
                setting(SYSTEM, "ichthys_battery_low_level")));
        s.add(Option.integer("statusBar.battery.scale", 100L, 80, 130,
                "Battery icon scale in percent.", setting(SYSTEM, "ichthys_battery_scale")));
        s.add(Option.integer("statusBar.battery.frameThickness", 3L, 1, 5,
                "Battery outline thickness.", setting(SYSTEM, "ichthys_battery_frame_thickness")));

        final String[] qsStyles = new String[BATTERY_STYLES.length + 1];
        final String[] qsStyleValues = new String[BATTERY_STYLES.length + 1];
        qsStyles[0] = "followStatusBar";
        qsStyleValues[0] = "-1";
        System.arraycopy(BATTERY_STYLES, 0, qsStyles, 1, BATTERY_STYLES.length);
        System.arraycopy(BATTERY_STYLE_VALUES, 0, qsStyleValues, 1, BATTERY_STYLE_VALUES.length);
        s.add(Option.enumeration("quickSettings.battery.style", qsStyles, qsStyleValues,
                "followStatusBar", "Battery icon style in the QS header.",
                setting(SYSTEM, "qs_battery_style")));
        s.add(Option.enumeration("quickSettings.battery.percent",
                new String[] {"followStatusBar", "hidden", "inside", "left", "right"},
                new String[] {"-1", "0", "1", "2", "3"}, "followStatusBar",
                "Battery percentage in the QS header.", setting(SYSTEM, "qs_show_battery_percent")));

        // --- Docked media player (begin) ---
        // The strip above the footer actions of the Shoal quick settings (SystemUI
        // ShoalMediaDock.kt). Two per-user switches on Look > Quick settings; the reader counts an
        // unset key as off.
        s.add(Option.bool("quickSettings.mediaDock.reserveSpace", Boolean.FALSE,
                "Keep the docked media player's place empty while nothing plays, so the footer "
                        + "never moves (quickSettings.style = \"shoal\").",
                setting(SECURE, "ichthys_qs_media_dock_reserve")));
        s.add(Option.bool("quickSettings.mediaDock.customAction", Boolean.FALSE,
                "Fourth button on the docked media player: the app's first custom action "
                        + "(quickSettings.style = \"shoal\").",
                setting(SECURE, "ichthys_qs_media_dock_custom_action")));
        // --- Docked media player (end) ---

        s.add(Option.enumeration("quickSettings.style", new String[] {"grid", "shoal"},
                new String[] {"grid", "shoal"}, "shoal",
                "Tile layout: the standard grid, or the Shoal honeycomb of circles.",
                setting(SECURE, "ichthys_qs_style")));

        // --- Junction tiles (begin) ---
        // The tiles sitting where four big tiles of the Shoal quick settings meet (SystemUI
        // ShoalJunctions.kt). One entry per junction from the top down; "-" leaves a junction
        // free, so "rotation" "-" "location" fills the first and the third. An empty string
        // would not do: a stored list drops empty entries and the tiles after it would move up.
        // No default (no junction tiles) and preserved: it is the layout the user arranged in
        // the tile editor, which writes the same key. Which tiles are big has no option; that
        // set lives in SystemUI's own preferences.
        s.add(Option.stringList("quickSettings.junctions",
                "Tiles in the junctions between the big tiles, top to bottom, by tile spec "
                        + "(e.g. \"rotation\" \"-\" \"location\"; \"-\" leaves a junction "
                        + "free). A junction exists between two full rows of big tiles; a tile "
                        + "whose junction is missing shows as the first circle "
                        + "(quickSettings.style = \"shoal\").",
                setting(SECURE, "ichthys_qs_junctions")).preserved());
        // --- Junction tiles (end) ---
    }

    // ---------------------------------------------------------------------------------------------
    // privacy.*

    private static void registerPrivacy(OptionSchema s) {
        // privacy.shields.<name> = { enable = <bool>; allow = [ pkgs ]; } for every shield in
        // IchthysShields, the one table of shield names, keys, tables and list meanings. The
        // options bind to the keys the enforcement code already reads; nothing is renamed.
        //   enable   the shield's switch. Unset counts as on, so the default is true. Shields
        //            without a switch key are always on and have no "enable".
        //   allow    apps that get the real data despite the shield.
        //   protect  for the three shields that are per-app choices (scans, location, nfc): the
        //            apps that get the protected answer. Nobody is protected until listed, so
        //            the attribute is named for what listing does, not "allow" with the meaning
        //            turned around.
        // The lists are kept unless a config mentions them: they are per-app decisions made in
        // Ichthys Guard and the permission dialog, not something a partial ichthys.nix (a theme,
        // say) should wipe.
        for (IchthysShields.Shield shield : IchthysShields.all()) {
            final String base = "privacy.shields." + shield.name;
            if (shield.switchKey != null) {
                s.add(Option.bool(base + ".enable", Boolean.TRUE, shield.description,
                        setting(store(shield.switchTable), shield.switchKey)));
            }
            if (shield.listKey != null) {
                final Option list = Option.packageList(base + "." + shield.listAttribute(),
                        shield.listDescription,
                        setting(store(shield.listTable), shield.listKey)).preserved();
                if (shield.picked) {
                    // "pkg:picked": the app sees only what the user picked for it. The
                    // permission dialog writes this form.
                    list.entryTags(IchthysShields.PICKED_SUFFIX.substring(1));
                }
                s.add(list);
            }
        }
        // The names these six options had before privacy.shields.
        s.alias("privacy.contactsLockdown", "privacy.shields.contacts.enable");
        s.alias("privacy.clipboardLockdown", "privacy.shields.clipboard.enable");
        s.alias("privacy.sensorShield", "privacy.shields.sensors.enable");
        s.alias("privacy.allow.contacts", "privacy.shields.contacts.allow");
        s.alias("privacy.allow.clipboard", "privacy.shields.clipboard.allow");
        s.alias("privacy.allow.sensors", "privacy.shields.sensors.allow");

        s.add(Option.enumeration("privacy.level", PRIVACY_LEVELS, PRIVACY_LEVELS, "normal",
                "Privacy level. Its privacy.* values apply wherever this file doesn't set them "
                        + "(never the per-app lists): relaxed keeps the shields that cost "
                        + "nothing, normal turns every shield on, strict adds the measures that "
                        + "cost battery or convenience.",
                setting(SECURE, "ichthys_privacy_level")));
        s.presetFamily("privacy.level", "privacy.", "privacy", null);

        s.add(Option.integer("privacy.clipboardClearMinutes", 5L, 0, 1440,
                "Clear the clipboard after this many minutes (0 = stock one hour).",
                setting(GLOBAL, "ichthys_clipboard_clear_minutes")));
        s.add(Option.bool("privacy.networkDecoy", Boolean.FALSE,
                "Advertise decoy services on the local network (mDNS), so device lists don't "
                        + "show what the phone is.",
                setting(GLOBAL, "network_decoy_enabled")));
    }

    private static Binding.Store store(IchthysShields.Table table) {
        return table == IchthysShields.Table.GLOBAL ? GLOBAL : SECURE;
    }

    // ---------------------------------------------------------------------------------------------
    // background.*

    private static void registerBackground(OptionSchema s) {
        s.add(Option.enumeration("background.unrestricted",
                new String[] {"off", "selected", "everything"}, new String[] {"0", "1", "2"},
                "off", "Unrestricted background: off, only the selected apps, or everything.",
                setting(GLOBAL, "ichthys_unrestricted_bg_mode")));
        s.add(Option.packageList("background.unrestrictedApps",
                "Apps kept unrestricted when background.unrestricted = selected.",
                setting(GLOBAL, "ichthys_unrestricted_bg_pkgs")).preserved());
        s.add(Option.bool("background.unrestrictedPersist", Boolean.FALSE,
                "Keep the unrestricted mode across reboots.",
                setting(GLOBAL, "ichthys_unrestricted_bg_persist")));
        s.add(Option.integer("background.backstageMaxHours", 0L, 0, 12,
                "Stop Backstage apps automatically after this many hours (0 = never).",
                setting(SECURE, "ichthys_backstage_max_hours")));
        // The Miscellaneous page had the same two keys as generated options. One key, one
        // option: two made a declarative apply set the key through one path and reset it through
        // the other, so applying the same file twice flipped the setting.
        s.removed("system.misc.ichthysUnrestrictedBgMode", "use background.unrestricted");
        s.removed("system.misc.ichthysUnrestrictedBgPersist",
                "use background.unrestrictedPersist");
    }

    // ---------------------------------------------------------------------------------------------
    // recents.*, volume.*, system.* (IchthysOS / fragment-managed parts)

    private static void registerUi(OptionSchema s) {
        s.add(Option.enumeration("recents.style",
                new String[] {"stock", "classic", "slim", "cascade", "grid", "stack", "list",
                        "slimList", "graceUx"},
                new String[] {"0", "1", "2", "3", "4", "5", "6", "7", "8"}, "stock",
                "Recents style (stock Quickstep, or one of the IchthysOS styles).",
                setting(SYSTEM, "recents_type")));
        s.add(Option.enumeration("volume.style", new String[] {"aosp", "oneui"},
                new String[] {"0", "1"}, "oneui", "Volume panel look.",
                setting(SYSTEM, "volume_panel_style")));
        s.add(Option.enumeration("navBar.style",
                new String[] {"stock", "shoal", "lattice", "mono", "gestures"},
                new String[] {"stock", "shoal", "lattice", "mono", "gestures"}, "shoal",
                "Navigation bar look: stock, shoal (outlined circles, accent home), lattice "
                        + "(small discs), mono (text labels) or gestures (accent gesture handle).",
                setting(SECURE, "ichthys_navbar_style")));
        s.removed("system.torchLevel", "the last flashlight brightness is runtime state");
        s.add(Option.stringList("system.powerMenu.actions",
                "Power menu entries, in order (e.g. \"power\" \"restart\" \"screenshot\").",
                setting(LINEAGE_SECURE, "power_menu_actions")).separator("|"));
        s.add(Option.enumeration("system.buttons.volumeKeyCursorControl",
                new String[] {"off", "volumeUpLeft", "volumeUpRight"},
                new String[] {"0", "1", "2"}, "off",
                "Move the text cursor with the volume keys while typing.",
                setting(SYSTEM, "volume_key_cursor_control")));
        s.add(Option.string("system.gestures.threeFingerSwipe", null,
                "Action for a three-finger swipe (Lineage action id).",
                setting(LINEAGE_SYSTEM, "key_three_fingers_swipe_action")));
        // The ambient display switches of stock Settings (Display > Lock screen). No crDroid
        // page has them, so they are written here. No defaults: the device's config decides
        // whether the display dozes at all.
        s.add(Option.bool("lockscreen.ambient.enabled", null,
                "Wake the screen for notifications (ambient display).",
                setting(SECURE, "doze_enabled")));
        s.add(Option.bool("lockscreen.ambient.alwaysOn", null,
                "Always-on display: always show time and info.",
                setting(SECURE, "doze_always_on")));
        s.add(Option.bool("lockscreen.ambient.pulseOnPickUp", null,
                "Wake the ambient display when the phone is picked up.",
                setting(SECURE, "doze_pulse_on_pick_up")));
        s.add(Option.string("lockscreen.shortcuts.start", null,
                "Bottom-left lock screen shortcut (affordance id, e.g. \"flashlight\"; null = none).",
                Binding.keyguardSlot("bottom_start")));
        s.add(Option.string("lockscreen.shortcuts.end", null,
                "Bottom-right lock screen shortcut (affordance id, e.g. \"calculator\"; null = none).",
                Binding.keyguardSlot("bottom_end")));
    }

    // ---------------------------------------------------------------------------------------------
    // apps.*

    private static void registerApps(OptionSchema s) {
        s.add(Option.packageName("apps.defaults.dialer", null, "Default phone app.",
                Binding.role("android.app.role.DIALER")));
        s.add(Option.packageName("apps.defaults.sms", null, "Default SMS app.",
                Binding.role("android.app.role.SMS")));
        s.add(Option.packageName("apps.defaults.browser", null, "Default browser.",
                Binding.role("android.app.role.BROWSER")));
        s.add(Option.packageName("apps.defaults.home", null, "Default launcher.",
                Binding.role("android.app.role.HOME")));
        s.add(Option.packageName("apps.defaults.assistant", null,
                "Default digital assistant (null = none).",
                Binding.role("android.app.role.ASSISTANT")));

        // Ichthys Apps (org.ichthys.apps): which catalog apps it installs and keeps updated.
        // apps.install has no default on purpose: the catalog's recommended ("default": true)
        // entries only pre-check Ichthys Apps' first-boot screen, and the app always stores the
        // confirmed list explicitly. A default would make a catalog change in an OTA silently
        // install or remove apps.
        s.add(Option.stringList("apps.install",
                "Ichthys Apps catalog ids to install and keep updated (e.g. \"zarchiver\" "
                        + "\"curve\"); see /product/etc/ichthys/apps-catalog.json. Removing an id "
                        + "uninstalls that app.",
                setting(SECURE, "ichthys_apps_install")).preserved());
        // Apps installed from elsewhere (F-Droid, Aurora, a sideload) that Ichthys Apps keeps
        // updated. No default and preserved, like apps.install: it is state the device built up.
        // The app escapes "%", "," and newline inside the source spec, so the "," separator holds.
        s.add(Option.stringList("apps.adopted",
                "Apps installed from elsewhere that Ichthys Apps keeps updated, one "
                        + "\"<package>[@<cert sha256>[+<sha256>...]]=<scheme>:<spec>\" each (e.g. "
                        + "\"org.example.app=fdroid:org.example.app\"). Removing an entry only "
                        + "stops the updates; the app stays installed.",
                setting(SECURE, "ichthys_apps_adopted")).preserved());
        s.add(Option.bool("apps.autoUpdate", Boolean.TRUE,
                "Ichthys Apps updates installed catalog apps in the background (daily).",
                setting(SECURE, "ichthys_apps_auto_update")));
        s.add(Option.bool("apps.wifiOnly", Boolean.FALSE,
                "Ichthys Apps downloads installs and updates only on Wi-Fi (unmetered networks). "
                        + "When off, background downloads over 100 MB still wait for an unmetered "
                        + "network.",
                setting(SECURE, "ichthys_apps_wifi_only")));
        s.add(Option.bool("apps.confirmed", Boolean.FALSE,
                "Set by Ichthys Apps' first-boot screen once the app list is confirmed; until "
                        + "then it offers the Recommended list instead of installing.",
                setting(SECURE, "ichthys_apps_confirmed")).preserved());
    }

    // ---------------------------------------------------------------------------------------------
    // bluetooth.*

    private static void registerBluetooth(OptionSchema s) {
        // SystemUI's IchthysPodBatteryController; the switches are in Settings > Connected
        // devices > Connection preferences > Bluetooth. An unset key is the default below.
        s.add(Option.bool("bluetooth.airpodsBattery", Boolean.TRUE,
                "Show the battery of each earbud and the case for connected AirPods, read from "
                        + "the earbuds over their own control channel.",
                setting(SECURE, "ichthys_airpods_battery")));
        s.add(Option.bool("bluetooth.airpodsBroadcastFallback", Boolean.FALSE,
                "When the control channel of the connected AirPods is unavailable, read the "
                        + "levels that nearby AirPods broadcast over Bluetooth LE instead (tens "
                        + "of percent; a broadcast cannot be tied to the connected pair, so it "
                        + "may be someone else's).",
                setting(SECURE, "ichthys_airpods_battery_broadcast_fallback")));
    }
}
