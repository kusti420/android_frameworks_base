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
 * manages in fragment code (so the generator can't see them). Keys and defaults match their
 * consumers; see each feature's source for the meaning.
 */
final class IchthysOptions {
    private IchthysOptions() {}

    /** Theme presets shipped in /product/etc/ichthys/presets/&lt;name&gt;.nix. */
    public static final String[] PRESETS = {"none", "abyss", "reef", "kelp", "paper"};

    static void register(OptionSchema s) {
        registerTheme(s);
        registerStatusBar(s);
        registerPrivacy(s);
        registerBackground(s);
        registerUi(s);
        registerApps(s);
    }

    // ---------------------------------------------------------------------------------------------
    // theme.*

    private static void registerTheme(OptionSchema s) {
        s.add(Option.enumeration("theme.preset", PRESETS, PRESETS, "none",
                "Theme preset. Its theme.* values apply wherever this file doesn't set them.",
                setting(SECURE, "ichthys_theme_preset")));
        s.add(Option.color("theme.accent", "wallpaper",
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
                new String[] {"tinted", "neutral"}, "tinted",
                "Surfaces tinted by the accent, or plain neutral grey.",
                setting(SECURE, "ichthys_theme_neutral")));
        s.add(Option.integer("theme.surfaces", 3L, 2, 5,
                "Number of distinct surface elevation levels in panels and cards.",
                setting(SECURE, "ichthys_theme_surfaces")));
        s.add(Option.integer("theme.shape.radius", null, 0, 40,
                "Corner radius in dp for dialogs, cards, QS panel and notifications.",
                setting(SECURE, "ichthys_theme_radius")));
        s.add(Option.enumeration("theme.shape.style", new String[] {"rounded", "pill", "squircle"},
                new String[] {"rounded", "pill", "squircle"}, "rounded",
                "Corner style: rounded, pill (fully round ends) or squircle.",
                setting(SECURE, "ichthys_theme_shape")));
        s.add(Option.enumeration("theme.density",
                new String[] {"compact", "standard", "comfortable"},
                new String[] {"compact", "standard", "comfortable"}, "standard",
                "Spacing density of IchthysOS panels.", setting(SECURE, "ichthys_theme_density")));
        s.add(Option.packageName("theme.font.body", null,
                "System font overlay package (android.theme.customization.font), null for default.",
                Binding.themeJson("font")));
        s.add(Option.string("theme.font.display", null,
                "Font family for large titles and clocks in IchthysOS UI, null for default.",
                setting(SECURE, "ichthys_theme_font_display")));
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
                new String[] {"single", "zigzag"}, "single",
                "Status icon layout: one row, or the two-row zig-zag lattice of circles.",
                setting(SECURE, "ichthys_statusbar_layout")));
        s.add(Option.stringList("statusBar.hiddenIcons",
                "Status bar icon slots to hide (e.g. \"volume\" \"alarm_clock\").",
                setting(SECURE, "icon_blacklist")));

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
        s.add(Option.color("statusBar.battery.fillColor", "#4caf50",
                "Custom battery fill color (fillMode = custom).",
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
        s.add(Option.enumeration("quickSettings.style", new String[] {"grid", "shoal"},
                new String[] {"grid", "shoal"}, "grid",
                "Tile layout: the standard grid, or the Shoal honeycomb of circles.",
                setting(SECURE, "ichthys_qs_style")));
    }

    // ---------------------------------------------------------------------------------------------
    // privacy.*

    private static void registerPrivacy(OptionSchema s) {
        s.add(Option.bool("privacy.contactsLockdown", Boolean.TRUE,
                "Only the phone, contacts and SMS apps can read contacts.",
                setting(GLOBAL, "ichthys_contacts_lockdown")));
        s.add(Option.bool("privacy.clipboardLockdown", Boolean.TRUE,
                "Apps read the clipboard only right after you touch them.",
                setting(GLOBAL, "ichthys_clipboard_lockdown")));
        s.add(Option.integer("privacy.clipboardClearMinutes", 5L, 0, 1440,
                "Clear the clipboard after this many minutes (0 = stock one hour).",
                setting(GLOBAL, "ichthys_clipboard_clear_minutes")));
        s.add(Option.bool("privacy.sensorShield", Boolean.TRUE,
                "Round, cap and anonymize motion sensors for non-system apps.",
                setting(GLOBAL, "ichthys_sensor_fingerprint_protection")));
        s.add(Option.packageList("privacy.allow.contacts",
                "Apps allowed past the contacts lockdown.",
                setting(SECURE, "ichthys_contacts_allowed_pkgs")));
        s.add(Option.packageList("privacy.allow.clipboard",
                "Apps allowed to read the clipboard at any time.",
                setting(SECURE, "ichthys_clipboard_allowed_pkgs")));
        s.add(Option.packageList("privacy.allow.sensors",
                "Apps that get exact sensors.", setting(SECURE, "ichthys_sensor_exempt_pkgs")));
        s.add(Option.bool("privacy.networkDecoy", Boolean.FALSE,
                "Send decoy network traffic to blur usage patterns.",
                setting(GLOBAL, "network_decoy_enabled")));
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
                setting(GLOBAL, "ichthys_unrestricted_bg_pkgs")));
        s.add(Option.bool("background.unrestrictedPersist", Boolean.FALSE,
                "Keep the unrestricted mode across reboots.",
                setting(GLOBAL, "ichthys_unrestricted_bg_persist")));
        s.add(Option.integer("background.backstageMaxHours", 0L, 0, 12,
                "Stop Backstage apps automatically after this many hours (0 = never).",
                setting(SECURE, "ichthys_backstage_max_hours")));
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
        s.add(Option.integer("system.torchLevel", 3L, 1, 5,
                "Flashlight brightness step used when the torch turns on.",
                setting(SECURE, "ichthys_torch_level")));
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
    }
}
