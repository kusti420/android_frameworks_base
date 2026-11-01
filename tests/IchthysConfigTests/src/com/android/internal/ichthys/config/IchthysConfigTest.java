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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RunWith(JUnit4.class)
public class IchthysConfigTest {

    private static final String SAMPLE = ""
            + "# /data/system/ichthys/ichthys.nix\n"
            + "{\n"
            + "  theme = {\n"
            + "    preset = \"abyss\";\n"
            + "    accent = \"#5CC8C2\"; /* upper case is fine */\n"
            + "    shape.radius = 22;\n"
            + "  };\n"
            + "  statusBar.layout = \"zigzag\";\n"
            + "  privacy = {\n"
            + "    contactsLockdown = true;\n"
            + "    allow.clipboard = [ \"org.keepassxc\" \"com.example.pw\" ];\n"
            + "  };\n"
            + "  background.unrestricted = \"selected\";\n"
            + "  statusBar.battery.fillColor = null;\n"
            + "}\n";

    @Test
    public void parsesNestedAndDottedPaths() throws Exception {
        final NixParser.Result r = NixParser.parse(SAMPLE);
        final Map<String, Object> theme = map(r.root.get("theme"));
        assertEquals("abyss", theme.get("preset"));
        assertEquals(22L, map(theme.get("shape")).get("radius"));
        assertEquals("zigzag", map(r.root.get("statusBar")).get("layout"));
        assertEquals(Arrays.asList("org.keepassxc", "com.example.pw"),
                map(map(r.root.get("privacy")).get("allow")).get("clipboard"));
        assertEquals("4:5", r.where("theme.preset"));
        assertEquals("8:3", r.where("statusBar.layout"));
    }

    @Test
    public void evaluatesAgainstSchema() throws Exception {
        final OptionSchema s = OptionSchema.create();
        final LinkedHashMap<String, Object> flat = s.evaluate(NixParser.parse(SAMPLE));
        assertEquals("#5cc8c2", flat.get("theme.accent"));
        // SAMPLE uses the pre-shields names; evaluating gives the current paths.
        assertEquals(Boolean.TRUE, flat.get("privacy.shields.contacts.enable"));
        assertEquals(Arrays.asList("org.keepassxc", "com.example.pw"),
                flat.get("privacy.shields.clipboard.allow"));
        assertFalse(flat.containsKey("privacy.contactsLockdown"));
        assertEquals("selected", flat.get("background.unrestricted"));
        assertTrue(flat.containsKey("statusBar.battery.fillColor"));
        assertNull(flat.get("statusBar.battery.fillColor"));
    }

    @Test
    public void unknownOptionPointsAtLineAndSuggests() throws Exception {
        final OptionSchema s = OptionSchema.create();
        try {
            s.evaluate(NixParser.parse("{\n  privacy.contactLockdown = true;\n}"));
            fail();
        } catch (NixParseException e) {
            assertEquals(2, e.line);
            // The closest name is a former one; the suggestion is what it is called now.
            assertTrue(e.getMessage(),
                    e.getMessage().contains("did you mean 'privacy.shields.contacts.enable'"));
        }
    }

    @Test
    public void typeErrors() throws Exception {
        final OptionSchema s = OptionSchema.create();
        expectEvalError(s, "{ theme.shape.radius = 99; }", "between 0 and 40");
        expectEvalError(s, "{ theme.accent = \"teal\"; }", "wallpaper");
        expectEvalError(s, "{ statusBar.layout = \"diagonal\"; }", "one of single, zigzag");
        expectEvalError(s, "{ privacy.allow.clipboard = [ \"not a package\" ]; }", "package");
        // The message names the option the way the file wrote it.
        expectEvalError(s, "{ privacy.contactsLockdown = 1; }",
                "privacy.contactsLockdown must be a boolean");
    }

    @Test
    public void syntaxErrors() {
        expectParseError("{ a = 1 }", "expected ';'");
        expectParseError("{ a = 1; a = 2; }", "already defined");
        expectParseError("{ a = foo; }", "unknown identifier");
        expectParseError("{ a = \"x${y}\"; }", "interpolation");
        expectParseError("{ a = [ 1 2 ; }", "expected a value");
        expectParseError("x = 1;", "attribute set");
        expectParseError("{ a = 1; } trailing", "after the closing");
        expectParseError("{ /* open", "unterminated block comment");
    }

    @Test
    public void printerRoundTrips() throws Exception {
        final OptionSchema s = OptionSchema.create();
        final LinkedHashMap<String, Object> flat = s.evaluate(NixParser.parse(SAMPLE));
        final String printed = s.print(flat, "header line");
        final LinkedHashMap<String, Object> again = s.evaluate(NixParser.parse(printed));
        assertEquals(flat, again);
        assertTrue(printed.startsWith("# header line\n{\n"));
        // Descriptions become comments.
        assertTrue(printed, printed.contains("# Contacts: apps read an empty address book."));
    }

    @Test
    public void escapesSurviveRoundTrip() throws Exception {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("s", "quote \" backslash \\ dollar $ newline \n end");
        final String text = NixPrinter.print(m, null, null);
        assertEquals(m.get("s"), NixParser.parse(text).root.get("s"));
    }

    @Test
    public void storedConversions() {
        final OptionSchema s = OptionSchema.create();
        final Option color = s.get("statusBar.battery.fillColor");
        assertEquals(String.valueOf(0xFF4CAF50), color.toStored("#4caf50"));
        assertEquals("#4caf50", color.fromStored(String.valueOf(0xFF4CAF50)));
        final Option mode = s.get("background.unrestricted");
        assertEquals("2", mode.toStored("everything"));
        assertEquals("selected", mode.fromStored("1"));
        assertNull(mode.fromStored("9"));
        final Option list = s.get("system.powerMenu.actions");
        assertEquals("power|restart", list.toStored(Arrays.asList("power", "restart")));
        assertEquals(Arrays.asList("power", "restart"), list.fromStored("power|restart"));
        final Option bool = s.get("privacy.shields.sensors.enable");
        assertEquals(bool, s.get("privacy.sensorShield")); // the former name
        assertEquals("1", bool.toStored(Boolean.TRUE));
        assertEquals(Boolean.FALSE, bool.fromStored("0"));
        final Option accent = s.get("theme.accent");
        assertEquals("wallpaper", accent.normalize("wallpaper"));
    }

    @Test
    public void diffReportsChanges() {
        final Map<String, Object> before = new LinkedHashMap<>();
        before.put("theme.accent", "wallpaper");
        before.put("privacy.shields.sensors.enable", Boolean.TRUE);
        final Map<String, Object> after = new LinkedHashMap<>();
        after.put("theme.accent", "#5cc8c2");
        after.put("privacy.shields.sensors.enable", Boolean.TRUE);
        final ConfigDiff d = ConfigDiff.between(before, after);
        assertEquals(1, d.changes.size());
        assertEquals("theme.accent: \"wallpaper\" -> \"#5cc8c2\"\n", d.format());
    }

    @Test
    public void schemaIsConsistent() {
        final OptionSchema s = OptionSchema.create();
        assertTrue(s.all().size() > 200);
        for (Option o : s.all()) {
            if (o.defaultValue != null) {
                // Every default must pass its own type check.
                assertEquals(o.path, o.defaultValue, o.normalize(o.defaultValue));
            }
            assertTrue(o.path, o.description != null && !o.description.isEmpty());
        }
    }

    private static void expectEvalError(OptionSchema s, String text, String fragment)
            throws Exception {
        try {
            s.evaluate(NixParser.parse(text));
            fail("expected error for " + text);
        } catch (NixParseException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(fragment));
        }
    }

    private static void expectParseError(String text, String fragment) {
        try {
            NixParser.parse(text);
            fail("expected error for " + text);
        } catch (NixParseException e) {
            assertTrue(text + " -> " + e.getMessage(), e.getMessage().contains(fragment));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unused")
    private static List<Object> list(Object o) {
        return (List<Object>) o;
    }

    // ---------------------------------------------------------------------------------------------
    // Schema rules

    @Test
    public void twoOptionsOnOneKeyAreRejected() {
        final OptionSchema s = new OptionSchema();
        s.add(Option.bool("a.first", Boolean.FALSE, "first",
                Binding.setting(Binding.Store.GLOBAL, "shared_key")));
        // Same key in another table: a different value, fine.
        s.add(Option.bool("a.other", Boolean.FALSE, "other",
                Binding.setting(Binding.Store.SECURE, "shared_key")));
        try {
            s.add(Option.bool("b.second", Boolean.FALSE, "second",
                    Binding.setting(Binding.Store.GLOBAL, "shared_key")));
            fail("a second option on global:shared_key must be rejected");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("a.first"));
            assertTrue(e.getMessage(), e.getMessage().contains("b.second"));
            assertTrue(e.getMessage(), e.getMessage().contains("global:shared_key"));
        }
        assertNull(s.get("b.second"));
    }

    @Test
    public void shippedSchemaBindsEveryKeyOnce() {
        // create() would have thrown; this says which key if someone catches that later.
        final Map<Binding, String> seen = new HashMap<>();
        for (Option o : OptionSchema.create().all()) {
            final String other = seen.put(o.binding, o.path);
            assertNull(o.path + " and " + other + " share " + o.binding, other);
        }
        // The pair that used to share ichthys_unrestricted_bg_mode.
        assertNull(OptionSchema.create().get("system.misc.ichthysUnrestrictedBgMode"));
    }

    @Test
    public void removedOptionsAreIgnoredWithANote() throws Exception {
        final OptionSchema s = OptionSchema.create();
        final List<String> notes = new ArrayList<>();
        final Map<String, Object> flat = s.evaluate(NixParser.parse("{\n"
                + "  theme = { density = \"compact\"; surfaces = 2; };\n"
                + "  system.torchLevel = 5;\n"
                + "  system.misc.ichthysUnrestrictedBgMode = \"selectedApps\";\n"
                + "  system.misc.ichthysUnrestrictedBgPersist = true;\n"
                + "}"), false, notes);
        assertEquals(1, flat.size());
        assertEquals(2L, flat.get("theme.surfaces"));
        assertEquals(notes.toString(), 4, notes.size());
        assertTrue(notes.toString(), notes.get(0).startsWith("theme.density is no longer"));
    }

    @Test
    public void aliasAndCurrentNameMustAgree() throws Exception {
        final OptionSchema s = OptionSchema.create();
        s.evaluate(NixParser.parse("{ privacy.sensorShield = false;"
                + " privacy.shields.sensors.enable = false; }"));
        expectEvalError(s, "{ privacy.sensorShield = false;"
                + " privacy.shields.sensors.enable = true; }", "another name of");
        // A renamed generated option.
        assertEquals(s.get("statusBar.clock.position"), s.get("statusBar.clockEnabled"));
    }

    @Test
    public void lenientEvaluationSkipsWhatNoLongerFits() throws Exception {
        final OptionSchema s = OptionSchema.create();
        final String old = "{ theme.surfaces = 4; no.such.option = 1;"
                + " statusBar.layout = \"diagonal\"; }";
        expectEvalError(s, old, "does not exist");
        final List<String> notes = new ArrayList<>();
        final Map<String, Object> flat = s.evaluate(NixParser.parse(old), true, notes);
        assertEquals(1, flat.size());
        assertEquals(2, notes.size());
    }

    @Test
    public void pickedContactsRoundTrip() throws Exception {
        final OptionSchema s = OptionSchema.create();
        final Option contacts = s.get("privacy.allow.contacts");
        assertEquals("privacy.shields.contacts.allow", contacts.path);
        // What the permission dialog stores.
        final Object live = contacts.fromStored("org.example.app:picked,org.other.sync");
        assertEquals(Arrays.asList("org.example.app:picked", "org.other.sync"), live);
        final Map<String, Object> flat = new LinkedHashMap<>();
        flat.put(contacts.path, live);
        // Export, then evaluate again: this is what rollback and Theme Studio's refresh do.
        final Map<String, Object> again = s.evaluate(NixParser.parse(s.print(flat, null)));
        assertEquals(live, again.get(contacts.path));
        assertEquals("org.example.app:picked,org.other.sync",
                contacts.toStored(again.get(contacts.path)));
        // Only lists that have the mode accept the tag, and only that tag.
        expectEvalError(s, "{ privacy.shields.clipboard.allow = [ \"org.example.app:picked\" ]; }",
                "package names");
        expectEvalError(s, "{ privacy.shields.contacts.allow = [ \"org.example.app:all\" ]; }",
                ":picked");
    }

    @Test
    public void enumsAcceptFormerSpellings() {
        final OptionSchema s = OptionSchema.create();
        final Option haptics = s.get("lockscreen.pulse.bassHaptics");
        assertEquals("off", haptics.normalize("off"));
        assertEquals("off", haptics.normalize("v0"));                      // old placeholder
        assertEquals("whilePulseShows", haptics.normalize("onlyWhenPulseIsShowing"));
        for (Option o : s.all()) {
            for (String name : o.enumNames()) {
                assertFalse(o.path + " has placeholder enum name " + name,
                        name.matches("v(Minus)?\\d+_*"));
            }
            assertFalse(o.path + " is described by a resource name: " + o.description,
                    o.description.matches("[a-z0-9]+(_[a-z0-9]+)+"));
        }
    }

    @Test
    public void colorsHaveOneSpelling() {
        final OptionSchema s = OptionSchema.create();
        final Option color = s.get("lockscreen.clock.customClockCustomColor");
        assertEquals("#ffffff", color.normalize("#FFFFFFFF"));
        assertEquals("#80ffffff", color.normalize("#80FFFFFF"));
        // An opaque colour read from the device equals the same colour written in a file.
        assertEquals(color.normalize("#ffffffff"), color.fromStored(String.valueOf(0xFFFFFFFF)));
        for (Option o : s.all()) {
            if (o.type == Option.Type.COLOR && o.defaultValue != null) {
                assertEquals(o.path, o.defaultValue, o.normalize(o.defaultValue));
            }
        }
    }

    @Test
    public void shieldsRegistryMatchesTheOptions() {
        final OptionSchema s = OptionSchema.create();
        final Map<String, String> keys = new HashMap<>();
        for (IchthysShields.Shield shield : IchthysShields.all()) {
            assertEquals(shield, IchthysShields.byName(shield.name));
            final String base = "privacy.shields." + shield.name;
            if (shield.switchKey != null) {
                final Option enable = s.get(base + ".enable");
                assertNotNull(base, enable);
                assertEquals(shield.switchKey, enable.binding.key);
                assertEquals(shield.switchTable.name(), enable.binding.store.name());
                assertEquals("unset counts as on", Boolean.TRUE, enable.defaultValue);
                assertNull(shield.switchKey, keys.put(shield.switchKey, shield.name));
            } else {
                assertNull(s.get(base + ".enable"));
            }
            final Option list = s.get(base + "." + shield.listAttribute());
            assertNotNull(base, list);
            assertEquals(shield.listKey, list.binding.key);
            assertTrue(base, list.isPreserved());
            assertNull(shield.listKey, keys.put(shield.listKey, shield.name));
            assertEquals(shield, IchthysShields.byKey(shield.listKey));
        }
        // The three shields whose list means "protected", under that name.
        for (String name : new String[] {"scans", "location", "nfc"}) {
            assertNotNull(s.get("privacy.shields." + name + ".protect"));
            assertNull(s.get("privacy.shields." + name + ".allow"));
        }
        assertEquals("ichthys_contacts_lockdown", IchthysShields.CONTACTS.switchKey);
        assertEquals("ichthys_calendar_shield", IchthysShields.CALENDAR.switchKey);
        assertEquals("ichthys_answer_calls_allowed_pkgs", IchthysShields.ANSWER_CALLS.listKey);
        assertTrue(IchthysShields.isOn(null));
        assertTrue(IchthysShields.isOn("1"));
        assertFalse(IchthysShields.isOn("0"));
        assertTrue(IchthysShields.isListed("a.b, c.d", "c.d"));
        assertFalse(IchthysShields.isListed("a.b:picked", "a.b"));
    }

    // ---------------------------------------------------------------------------------------------
    // Applying (ConfigPlanner over an in-memory device)

    /** Settings tables, the theme JSON, roles and lock screen slots as one map of stored text. */
    private static final class Device implements ConfigPlanner.Store {
        final Map<Binding, String> stored = new HashMap<>();
        int writes;

        @Override
        public Object read(Option o) {
            final String s = stored.get(o.binding);
            if (o.binding.store == Binding.Store.THEME_JSON && "accent".equals(o.binding.key)) {
                return s == null ? "wallpaper" : s; // as ConfigBackend reads an empty theme JSON
            }
            return o.binding.isSettingsTable() || o.binding.store == Binding.Store.THEME_JSON
                    ? o.fromStored(s) : s;
        }

        void write(Option o, Object value) {
            writes++;
            if (value == null) {
                stored.remove(o.binding);
            } else {
                stored.put(o.binding, o.binding.isSettingsTable()
                        || o.binding.store == Binding.Store.THEME_JSON
                        ? o.toStored(value) : (String) value);
            }
        }

        void put(Option o, Object value) {
            write(o, o.normalize(value));
        }
    }

    private final OptionSchema mSchema = OptionSchema.create();
    private final ConfigPlanner mPlanner = new ConfigPlanner(mSchema, (dir, name) -> {
        final String text = etc(dir + "/" + name + ".nix");
        if (text == null) {
            throw new IllegalArgumentException("no " + dir + "/" + name + ".nix");
        }
        return text;
    });

    /** What IchthysConfigService.apply does, minus generations. */
    private ConfigDiff apply(Device device, String text, ConfigPlanner.Mode mode)
            throws Exception {
        final Map<String, Object> declared = mSchema.evaluate(NixParser.parse(text),
                mode == ConfigPlanner.Mode.SNAPSHOT, null);
        final ConfigDiff diff = mPlanner.changes(mPlanner.target(declared, mode), mode, device);
        for (ConfigDiff.Change c : diff.changes) {
            device.write(mSchema.get(c.path), mPlanner.valueToStore(c, mode));
        }
        return diff;
    }

    private String snapshot(Device device) {
        return mSchema.print(mPlanner.state(device, ConfigPlanner.State.STORED), null);
    }

    /** A shipped file: a test resource, or the source tree when run from its root. */
    private static String etc(String relative) {
        try (InputStream in = IchthysConfigTest.class.getClassLoader()
                .getResourceAsStream(relative)) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            final File f = new File("frameworks/base/data/etc/ichthys", relative);
            return f.exists() ? new String(Files.readAllBytes(f.toPath()),
                    StandardCharsets.UTF_8) : null;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private static String shipped(String relative) {
        final String text = etc(relative);
        assertNotNull(relative + " is neither a test resource nor in the working directory",
                text);
        return text;
    }

    @Test
    public void applyingTheSameFileTwiceChangesNothing() throws Exception {
        final String file = "{\n"
                + "  theme = { preset = \"abyss\"; shape.radius = 12; };\n"
                + "  statusBar.layout = \"single\";\n"
                + "  background = { unrestricted = \"selected\";"
                + " unrestrictedApps = [ \"com.termux\" ]; unrestrictedPersist = true; };\n"
                + "  privacy.shields.sensors = { enable = false;"
                + " allow = [ \"app.comaps.fdroid\" ]; };\n"
                + "  statusBar.networkTraffic.autohide = false;\n"
                + "}";
        for (ConfigPlanner.Mode mode : new ConfigPlanner.Mode[] {
                ConfigPlanner.Mode.DECLARATIVE, ConfigPlanner.Mode.MERGE}) {
            final Device device = new Device();
            // Something to reset, so the first declarative apply also takes values away.
            device.put(mSchema.get("quickSettings.style"), "grid");
            assertFalse(apply(device, file, mode).isEmpty());
            final Map<Binding, String> after = new HashMap<>(device.stored);
            // The setting two options used to share: set by one, reset by the other, so every
            // second apply turned it off again.
            assertEquals("1", device.stored.get(
                    Binding.setting(Binding.Store.GLOBAL, "ichthys_unrestricted_bg_mode")));
            for (int i = 0; i < 3; i++) {
                final ConfigDiff again = apply(device, file, mode);
                assertTrue(mode + " run " + (i + 2) + ": " + again.format(), again.isEmpty());
                assertEquals(after, device.stored);
            }
        }
    }

    @Test
    public void anOptionCanBeSetToItsOwnDefault() throws Exception {
        // The defaults as the code that reads each key has them.
        assertEquals(Boolean.FALSE,
                mSchema.get("statusBar.networkTraffic.autohide").defaultValue);
        assertEquals(Boolean.TRUE,
                mSchema.get("system.navigation.navigationbarKeyOrder").defaultValue);
        assertEquals(Boolean.TRUE, mSchema.get("system.powerMenu.advancedReboot").defaultValue);
        assertNull(mSchema.get("statusBar.privacyIndicator.ringWidth").defaultValue);

        // And whatever a reader makes of an unset key, a value a config states is stored.
        final Device device = new Device();
        final ConfigDiff diff = apply(device, "{\n"
                + "  statusBar.networkTraffic.autohide = false;\n"
                + "  system.navigation.navigationbarKeyOrder = true;\n"
                + "  system.powerMenu.advancedReboot = true;\n"
                + "  statusBar.privacyIndicator.ringWidth = 2;\n"
                + "  theme.surface = \"hairline\";\n"
                + "}", ConfigPlanner.Mode.MERGE);
        assertEquals(diff.format(), 5, diff.changes.size());
        assertEquals("0", device.stored.get(
                Binding.setting(Binding.Store.SYSTEM, "network_traffic_autohide")));
        assertEquals("1", device.stored.get(
                Binding.setting(Binding.Store.SECURE, "navigationbar_key_order")));
        assertEquals("2", device.stored.get(
                Binding.setting(Binding.Store.SYSTEM, "ichthys_privacy_indicator_ring_width")));
        assertEquals("hairline", device.stored.get(
                Binding.setting(Binding.Store.SECURE, "ichthys_theme_surface")));
    }

    @Test
    public void unsetMeansAccentForTheseColors() throws Exception {
        // Their readers take "no value" as "the theme accent". So they have no default, and
        // resetting one deletes the key instead of storing a colour.
        final String[] paths = {"statusBar.battery.fillColor", "statusBar.batteryBar.color",
                "lockscreen.edgeLight.customColor", "lockscreen.fingerprint.animColor",
                "lockscreen.fingerprint.pressedColor", "theme.gradient.customGradientStartColor",
                "theme.gradient.customGradientEndColor"};
        final Device device = new Device();
        for (String path : paths) {
            final Option o = mSchema.get(path);
            assertNotNull(path, o);
            assertNull(path, o.defaultValue);
            device.put(o, "#112233");
        }
        device.put(mSchema.get("statusBar.battery.lowLevel"), 15L);
        apply(device, "{ }", ConfigPlanner.Mode.DECLARATIVE);
        for (String path : paths) {
            assertFalse(path, device.stored.containsKey(mSchema.get(path).binding));
        }
        // An option with a default is reset by storing the default.
        assertEquals("20", device.stored.get(mSchema.get("statusBar.battery.lowLevel").binding));
    }

    @Test
    public void optionsWithoutASettingsRow() throws Exception {
        // Stock Settings shows the ambient display switches; they stay options.
        assertEquals("doze_always_on", mSchema.get("lockscreen.ambient.alwaysOn").binding.key);
        assertNotNull(mSchema.get("lockscreen.ambient.enabled"));
        assertNotNull(mSchema.get("lockscreen.ambient.pulseOnPickUp"));
        // IchthysOS projected clock: the placement is an option; its per-wallpaper quads are not.
        assertEquals("ichthys_clock_placement",
                mSchema.get("lockscreen.clock.placement").binding.key);
        assertNull(mSchema.get("lockscreen.clock.quads"));
        // The smartspace switch was dropped; a config that still has it applies.
        assertNull(mSchema.get("lockscreen.smartspaceEnabled"));
        final List<String> notes = new ArrayList<>();
        assertTrue(mSchema.evaluate(NixParser.parse("{ lockscreen.smartspaceEnabled = true; }"),
                false, notes).isEmpty());
        assertEquals(1, notes.size());
    }

    @Test
    public void partialFileKeepsPerAppListsAndAppState() throws Exception {
        final Device device = new Device();
        final String[][] kept = {
                {"privacy.shields.sensors.allow", "app.comaps.fdroid"},
                {"privacy.shields.contacts.allow", "org.example.sync"},
                {"privacy.shields.location.protect", "com.example.tracker"},
                {"background.unrestrictedApps", "com.termux"},
                {"apps.install", "zarchiver"},
        };
        for (String[] k : kept) {
            device.put(mSchema.get(k[0]), Arrays.asList(k[1]));
        }
        device.put(mSchema.get("apps.confirmed"), Boolean.TRUE);
        device.put(mSchema.get("apps.defaults.dialer"), "org.fossify.phone");
        device.put(mSchema.get("statusBar.layout"), "single");

        // A theme file, applied declaratively: ordinary options go back to their defaults...
        apply(device, "{ theme.accent = \"#112233\"; }", ConfigPlanner.Mode.DECLARATIVE);
        assertEquals("zigzag", device.read(mSchema.get("statusBar.layout")));
        // ...per-app lists, app state and the default apps stay.
        for (String[] k : kept) {
            assertEquals(k[0], Arrays.asList(k[1]), device.read(mSchema.get(k[0])));
        }
        assertEquals(Boolean.TRUE, device.read(mSchema.get("apps.confirmed")));
        assertEquals("org.fossify.phone", device.read(mSchema.get("apps.defaults.dialer")));

        // Mentioning one changes it, also to empty.
        apply(device, "{ privacy.shields.sensors.allow = [ ]; apps.install = null; }",
                ConfigPlanner.Mode.DECLARATIVE);
        assertEquals(new ArrayList<String>(),
                device.read(mSchema.get("privacy.shields.sensors.allow")));
        assertNull(device.read(mSchema.get("apps.install")));
        assertEquals(Arrays.asList("org.example.sync"),
                device.read(mSchema.get("privacy.shields.contacts.allow")));
    }

    @Test
    public void rollbackRestoresTheSnapshotExactly() throws Exception {
        final Device device = new Device();
        // Abyss says surface = "tonal"; the user then picks "hairline", which is the option
        // default: the value a diff-from-defaults snapshot used to leave out, so that a rollback
        // had the preset fill "tonal" back in.
        apply(device, "{ theme.preset = \"abyss\"; }", ConfigPlanner.Mode.MERGE);
        assertEquals("tonal", device.read(mSchema.get("theme.surface")));
        apply(device, "{ theme.surface = \"hairline\"; }", ConfigPlanner.Mode.MERGE);
        device.put(mSchema.get("privacy.shields.sensors.allow"),
                Arrays.asList("app.comaps.fdroid"));
        final String generation = snapshot(device);
        final Map<Binding, String> then = new HashMap<>(device.stored);
        assertTrue(generation, generation.contains("surface = \"hairline\""));

        // Things move on: another preset, a list grows, an option that was unset gets a value.
        apply(device, "{ theme.preset = \"kelp\"; quickSettings.style = \"grid\";"
                + " privacy.shields.sensors.allow = [ \"app.comaps.fdroid\" \"com.other\" ];"
                + " privacy.shields.nfc.protect = [ \"com.other\" ]; }",
                ConfigPlanner.Mode.MERGE);
        assertFalse(then.equals(device.stored));

        apply(device, generation, ConfigPlanner.Mode.SNAPSHOT);
        assertEquals(then, device.stored);
        assertEquals("hairline", device.read(mSchema.get("theme.surface")));
        assertEquals("abyss", device.read(mSchema.get("theme.preset")));
        assertTrue(apply(device, generation, ConfigPlanner.Mode.SNAPSHOT).isEmpty());

        // The export has the same property for a declarative apply on another device.
        final String export = mSchema.print(
                mPlanner.state(device, ConfigPlanner.State.CHANGED), null);
        assertTrue(export, export.contains("surface = \"hairline\""));
        final Device other = new Device();
        apply(other, export, ConfigPlanner.Mode.DECLARATIVE);
        assertEquals(mPlanner.state(device, ConfigPlanner.State.ALL),
                mPlanner.state(other, ConfigPlanner.State.ALL));
    }

    @Test
    public void baselineIsTheDefaults() throws Exception {
        final Map<String, Object> declared =
                mSchema.evaluate(NixParser.parse(shipped("default.nix")));
        assertEquals("koi", declared.get("theme.preset"));
        assertEquals("normal", declared.get("privacy.level"));
        // What the owner's phone runs.
        assertEquals("zigzag", declared.get("statusBar.layout"));
        assertEquals("shoal", declared.get("quickSettings.style"));
        assertEquals("shoal", declared.get("navBar.style"));
        assertEquals(Boolean.TRUE, declared.get("statusBar.clock.seconds"));
        assertEquals(Boolean.TRUE, declared.get("statusBar.notificationDot.enabled"));
        assertEquals("cutoutRing", declared.get("statusBar.privacyIndicator.style"));

        // With its preset and level filled in, the baseline is the option defaults: a fresh
        // install, "reset to default" and "never set" are one look.
        final Map<String, Object> target = mPlanner.target(declared, ConfigPlanner.Mode.MERGE);
        assertTrue(target.size() > declared.size() + 20);
        for (Map.Entry<String, Object> e : target.entrySet()) {
            final Option o = mSchema.get(e.getKey());
            assertEquals(e.getKey(), o.defaultValue, e.getValue());
            assertFalse(e.getKey() + " is state, not baseline", o.isPreserved());
            // Applied before the user's other providers are up: plain settings only.
            assertTrue(e.getKey() + " is bound to " + o.binding,
                    o.binding.store == Binding.Store.SYSTEM
                            || o.binding.store == Binding.Store.SECURE
                            || o.binding.store == Binding.Store.GLOBAL
                            || o.binding.store == Binding.Store.THEME_JSON);
        }
        // Every theme option and every shield switch is part of it.
        for (Option o : mSchema.all()) {
            if (o.path.startsWith("privacy.shields.") && o.path.endsWith(".enable")) {
                assertEquals(o.path, Boolean.TRUE, target.get(o.path));
            }
        }
        for (String path : new String[] {"theme.accent", "theme.neutral", "theme.surfaces",
                "theme.shape.radius", "theme.shape.style", "theme.surface", "theme.font.display",
                "theme.font.text", "theme.font.mono"}) {
            assertTrue(path, target.containsKey(path));
        }

        // Applied to a new user, every one of them is written, although each equals its default.
        final Device device = new Device();
        final ConfigDiff first = apply(device, shipped("default.nix"), ConfigPlanner.Mode.MERGE);
        assertEquals(target.size(), first.changes.size());
        assertEquals("neutral", device.stored.get(
                Binding.setting(Binding.Store.SECURE, "ichthys_theme_neutral")));
        assertEquals("1", device.stored.get(
                Binding.setting(Binding.Store.SECURE, "ichthys_calendar_shield")));
        assertEquals("0", device.stored.get(
                Binding.setting(Binding.Store.SYSTEM, "screen_on_memory_reclaim")));
        assertTrue(apply(device, shipped("default.nix"), ConfigPlanner.Mode.MERGE).isEmpty());
        // And an export of that user is empty: nothing differs from the defaults.
        assertTrue(mPlanner.state(device, ConfigPlanner.State.CHANGED).isEmpty());
    }

    @Test
    public void privacyLevelFillsInSwitchesOnly() throws Exception {
        for (String level : IchthysOptions.PRIVACY_LEVELS) {
            final Map<String, Object> flat =
                    mSchema.evaluate(NixParser.parse(shipped("privacy/" + level + ".nix")));
            for (String path : flat.keySet()) {
                assertTrue(level + ": " + path, path.startsWith("privacy."));
                assertFalse(level + " sets the per-app list " + path,
                        mSchema.get(path).isPreserved());
            }
            // A level decides every shield switch, so changing level never leaves one behind.
            for (IchthysShields.Shield shield : IchthysShields.all()) {
                if (shield.switchKey != null) {
                    assertTrue(level + " leaves out " + shield.name,
                            flat.containsKey("privacy.shields." + shield.name + ".enable"));
                }
            }
        }
        final Device device = new Device();
        device.put(mSchema.get("privacy.shields.sensors.allow"),
                Arrays.asList("app.comaps.fdroid"));
        apply(device, "{ privacy.level = \"relaxed\"; }", ConfigPlanner.Mode.MERGE);
        assertEquals(Boolean.FALSE, device.read(mSchema.get("privacy.shields.sensors.enable")));
        assertEquals(Boolean.TRUE, device.read(mSchema.get("privacy.shields.contacts.enable")));
        assertEquals(Arrays.asList("app.comaps.fdroid"),
                device.read(mSchema.get("privacy.shields.sensors.allow")));
        // What the file says wins over the level.
        apply(device, "{ privacy = { level = \"strict\"; shields.call.enable = false; }; }",
                ConfigPlanner.Mode.MERGE);
        assertEquals(Boolean.TRUE, device.read(mSchema.get("privacy.shields.sensors.enable")));
        assertEquals(Boolean.FALSE, device.read(mSchema.get("privacy.shields.call.enable")));
        assertEquals(1L, device.read(mSchema.get("privacy.clipboardClearMinutes")));
        // A level that doesn't exist is an error, not a silent "normal".
        expectEvalError(mSchema, "{ privacy.level = \"paranoid\"; }", "relaxed, normal, strict");
    }

    /** Every shipped theme preset parses, type-checks and stays inside theme.*. */
    @Test
    public void shippedPresetsAreValid() throws Exception {
        for (String name : IchthysOptions.PRESETS) {
            if ("none".equals(name)) {
                continue;
            }
            final LinkedHashMap<String, Object> flat =
                    mSchema.evaluate(NixParser.parse(shipped("presets/" + name + ".nix")));
            assertTrue(name, flat.keySet().stream().allMatch(k -> k.startsWith("theme.")));
            assertFalse(name + " sets theme.preset", flat.containsKey("theme.preset"));
        }
        // Koi is the default theme: the preset and the option defaults are the same values.
        for (Map.Entry<String, Object> e : mSchema.evaluate(
                NixParser.parse(shipped("presets/koi.nix"))).entrySet()) {
            assertEquals(e.getKey(), mSchema.get(e.getKey()).defaultValue, e.getValue());
        }
    }

    @Test
    public void shoalDefaults() throws Exception {
        final OptionSchema s = OptionSchema.create();
        assertEquals("koi", s.get("theme.preset").defaultValue);
        assertEquals("hairline", s.get("theme.surface").defaultValue);
        assertEquals(Boolean.TRUE, s.get("theme.iconBadges").defaultValue);
        assertEquals("mono", s.get("theme.iconStyle").defaultValue);
        expectEvalError(s, "{ theme.surface = \"glass\"; }", "hairline");
    }
}
