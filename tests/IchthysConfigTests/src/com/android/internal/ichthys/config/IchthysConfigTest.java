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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;
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
        assertEquals(Boolean.TRUE, flat.get("privacy.contactsLockdown"));
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
            assertTrue(e.getMessage(), e.getMessage().contains("privacy.contactsLockdown"));
        }
    }

    @Test
    public void typeErrors() throws Exception {
        final OptionSchema s = OptionSchema.create();
        expectEvalError(s, "{ theme.shape.radius = 99; }", "between 0 and 40");
        expectEvalError(s, "{ theme.accent = \"teal\"; }", "wallpaper");
        expectEvalError(s, "{ statusBar.layout = \"diagonal\"; }", "one of single, zigzag");
        expectEvalError(s, "{ privacy.allow.clipboard = [ \"not a package\" ]; }", "package");
        expectEvalError(s, "{ privacy.contactsLockdown = 1; }", "boolean");
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
        assertTrue(printed, printed.contains("# Only the phone, contacts and SMS apps"));
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
        final Option bool = s.get("privacy.sensorShield");
        assertEquals("1", bool.toStored(Boolean.TRUE));
        assertEquals(Boolean.FALSE, bool.fromStored("0"));
        final Option accent = s.get("theme.accent");
        assertEquals("wallpaper", accent.normalize("wallpaper"));
    }

    @Test
    public void diffReportsChanges() {
        final Map<String, Object> before = new LinkedHashMap<>();
        before.put("theme.accent", "wallpaper");
        before.put("privacy.sensorShield", Boolean.TRUE);
        final Map<String, Object> after = new LinkedHashMap<>();
        after.put("theme.accent", "#5cc8c2");
        after.put("privacy.sensorShield", Boolean.TRUE);
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

    @Test
    public void presetsAreValid() throws Exception {
        final OptionSchema s = OptionSchema.create();
        for (String name : new String[] {"abyss", "reef", "kelp", "paper"}) {
            final String path = System.getProperty("ichthys.presets",
                    "frameworks/base/data/etc/ichthys/presets") + "/" + name + ".nix";
            final java.io.File f = new java.io.File(path);
            if (!f.exists()) {
                continue; // not run from the source tree
            }
            final String text = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            for (String key : s.evaluate(NixParser.parse(text)).keySet()) {
                assertTrue(name + ": " + key, key.startsWith("theme."));
            }
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

    /** Every shipped preset parses and type-checks (koi, abyss, ...). */
    @Test
    public void shippedPresetsAreValid() throws Exception {
        final java.io.File dir = new java.io.File("frameworks/base/data/etc/ichthys/presets");
        if (!dir.isDirectory()) {
            return; // not run from the tree root (e.g. atest on device)
        }
        final OptionSchema s = OptionSchema.create();
        final java.io.File[] files = dir.listFiles((d, n) -> n.endsWith(".nix"));
        assertTrue(files != null && files.length >= 5);
        for (java.io.File f : files) {
            final String text = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            final LinkedHashMap<String, Object> flat = s.evaluate(NixParser.parse(text));
            assertTrue(f.getName(), flat.keySet().stream().allMatch(k -> k.startsWith("theme.")));
        }
    }

    @Test
    public void shoalDefaults() throws Exception {
        final OptionSchema s = OptionSchema.create();
        assertEquals("koi", s.get("theme.preset").defaultValue);
        assertEquals("hairline", s.get("theme.surface").defaultValue);
        assertEquals(Boolean.TRUE, s.get("theme.iconBadges").defaultValue);
        expectEvalError(s, "{ theme.surface = \"glass\"; }", "hairline");
    }
}
