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

package com.android.server.ichthys;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Binder;
import android.os.Environment;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.util.AtomicFile;
import android.util.Slog;

import com.android.internal.ichthys.IIchthysConfig;
import com.android.internal.ichthys.config.Binding;
import com.android.internal.ichthys.config.ConfigDiff;
import com.android.internal.ichthys.config.NixParseException;
import com.android.internal.ichthys.config.NixParser;
import com.android.internal.ichthys.config.Option;
import com.android.internal.ichthys.config.OptionSchema;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.DumpUtils;
import com.android.server.SystemService;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * IchthysOS declarative configuration ("ichthys.nix"), NixOS style.
 *
 * <ul>
 *   <li><b>apply</b>: evaluate a config against the {@link OptionSchema} (unknown options and
 *       wrong types are errors, with line:col), compute the change set, write it atomically (a
 *       failure part-way restores every option already written), then record a new
 *       <i>generation</i>. Declarative by default: options the file doesn't mention go back to
 *       their defaults. With {@code merge}, only the options it mentions change.</li>
 *   <li><b>generations</b>: /data/system/ichthys/&lt;user&gt;/gen-N.nix is the live state right after
 *       apply N (non-default options). Generation 0 is the state before the first apply. The
 *       last {@value #KEEP_GENERATIONS} are kept.</li>
 *   <li><b>rollback N</b>: apply gen-N declaratively as a new generation (like nixos-rebuild
 *       --rollback, nothing is ever rewritten in place).</li>
 *   <li><b>theme.preset</b>: a preset's theme.* values fill in wherever the file sets none.</li>
 * </ul>
 * Exposed as the "ichthys_config" binder service (Settings' Theme Studio) and
 * {@code cmd ichthys_config}.
 */
public final class IchthysConfigService extends SystemService {
    static final String TAG = "IchthysConfig";
    static final String SERVICE_NAME = "ichthys_config";
    static final String PERMISSION = "android.permission.MANAGE_ICHTHYS_CONFIG";
    static final int KEEP_GENERATIONS = 30;
    private static final File PRESETS_DIR = new File("/product/etc/ichthys/presets");

    private final Object mLock = new Object();
    private final OptionSchema mSchema;
    private final ConfigBackend mBackend;
    private final ThemeApplier mTheme;

    public IchthysConfigService(Context context) {
        super(context);
        mSchema = OptionSchema.create();
        mBackend = new ConfigBackend(context);
        mTheme = new ThemeApplier(context);
    }

    @Override
    public void onStart() {
        publishBinderService(SERVICE_NAME, new BinderService());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_BOOT_COMPLETED) {
            applyTokensAsync(ActivityManager.getCurrentUser());
        }
    }

    @Override
    public void onUserSwitching(TargetUser from, TargetUser to) {
        applyTokensAsync(to.getUserIdentifier());
    }

    OptionSchema schema() {
        return mSchema;
    }

    // ---------------------------------------------------------------------------------------------
    // Core operations (callers hold the right permission and have cleared the binder identity)

    /** Live state: every option's value, or only those that differ from the default. */
    LinkedHashMap<String, Object> liveState(int userId, boolean all) {
        final LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        for (Option o : mSchema.all()) {
            final Object v = mBackend.read(o, userId);
            if (all ? v != null || o.defaultValue != null : v != null
                    && !Objects.equals(v, o.defaultValue)) {
                out.put(o.path, v != null ? v : o.defaultValue);
            }
        }
        return out;
    }

    String export(int userId, boolean all) {
        final String header = "IchthysOS configuration (ichthys.nix)\n"
                + "exported " + now() + (all ? ", all options" : ", options changed from default")
                + "\napply with: adb shell cmd ichthys_config apply <file>, or Settings > Theme Studio";
        return mSchema.print(liveState(userId, all), header);
    }

    /** Evaluates text to {path → value}; IllegalArgumentException carries the position. */
    LinkedHashMap<String, Object> evaluate(String text) {
        try {
            return mSchema.evaluate(NixParser.parse(text));
        } catch (NixParseException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    /** What the device should look like after applying {@code declared}. */
    private LinkedHashMap<String, Object> target(LinkedHashMap<String, Object> declared,
            boolean merge) {
        final LinkedHashMap<String, Object> wanted = new LinkedHashMap<>(declared);
        // A declarative apply that doesn't name a preset gets the default one (koi), like any
        // other option left out; --merge only touches what the file names.
        final Object preset = declared.containsKey("theme.preset") ? declared.get("theme.preset")
                : merge ? null : mSchema.get("theme.preset").defaultValue;
        if (preset instanceof String && !"none".equals(preset)) {
            for (Map.Entry<String, Object> e : loadPreset((String) preset).entrySet()) {
                if (e.getKey().startsWith("theme.") && !wanted.containsKey(e.getKey())) {
                    wanted.put(e.getKey(), e.getValue());
                }
            }
        }
        if (merge) {
            return wanted;
        }
        final LinkedHashMap<String, Object> full = new LinkedHashMap<>();
        for (Option o : mSchema.all()) {
            if (wanted.containsKey(o.path)) {
                full.put(o.path, wanted.get(o.path));
            } else if (o.binding.isSettingsTable()
                    || o.binding.store == Binding.Store.THEME_JSON) {
                full.put(o.path, null); // not in the file: back to the default
            }
            // Roles and lock screen slots have no "default" to go back to (resetting would
            // leave the phone without a dialer, or record an explicit empty shortcut): an
            // unmentioned one is left as it is.
        }
        return full;
    }

    /** The options whose effective value changes (null counts as the option's default). */
    private ConfigDiff changes(Map<String, Object> target, int userId) {
        final LinkedHashMap<String, Object> before = new LinkedHashMap<>();
        final LinkedHashMap<String, Object> after = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : target.entrySet()) {
            final Option o = mSchema.get(e.getKey());
            final Object live = mBackend.read(o, userId);
            final Object liveEff = live != null ? live : o.defaultValue;
            final Object wantEff = e.getValue() != null ? e.getValue() : o.defaultValue;
            if (!Objects.equals(liveEff, wantEff)) {
                before.put(o.path, live);
                after.put(o.path, e.getValue());
            }
        }
        return ConfigDiff.between(before, after);
    }

    String diff(String text, boolean merge, int userId) {
        synchronized (mLock) {
            return changes(target(evaluate(text), merge), userId).format();
        }
    }

    /** @return the new generation number, or the current one if nothing changed */
    int apply(String text, boolean merge, String source, int userId) {
        synchronized (mLock) {
            final ConfigDiff diff = changes(target(evaluate(text), merge), userId);
            final Generations gens = generations(userId);
            if (gens.list().isEmpty()) {
                gens.save(0, export(userId, false), "state before the first apply", "initial");
            }
            if (diff.isEmpty()) {
                return gens.current();
            }
            final List<ConfigDiff.Change> done = new ArrayList<>();
            try {
                for (ConfigDiff.Change c : diff.changes) {
                    mBackend.write(mSchema.get(c.path), c.after, userId);
                    done.add(c);
                }
            } catch (RuntimeException e) {
                Collections.reverse(done);
                for (ConfigDiff.Change c : done) {
                    try {
                        mBackend.write(mSchema.get(c.path), c.before, userId);
                    } catch (RuntimeException ignored) {
                        Slog.w(TAG, "could not restore " + c.path, ignored);
                    }
                }
                final String failed = diff.changes.get(done.size()).path;
                throw new IllegalArgumentException("applying " + failed + " failed: "
                        + e.getMessage() + " (all changes rolled back)");
            }
            if (touchesTokens(diff)) {
                applyTokens(userId, false);
            }
            final int gen = gens.next();
            gens.save(gen, export(userId, false), diff.summary(), source);
            Slog.i(TAG, "user " + userId + " generation " + gen + ": " + diff.summary());
            return gen;
        }
    }

    int rollback(int generation, int userId) {
        final String text;
        synchronized (mLock) {
            final Generations gens = generations(userId);
            final List<Integer> list = gens.list();
            final int target = generation >= 0 ? generation
                    : (list.size() >= 2 ? list.get(1) : -1);
            if (target < 0 || !list.contains(target)) {
                throw new IllegalArgumentException("no generation " + generation);
            }
            text = gens.read(target);
            if (text == null) {
                throw new IllegalArgumentException("generation " + target + " is unreadable");
            }
            return apply(text, false, "rollback to " + target, userId);
        }
    }

    /** Options that feed ThemeApplier's token overlays. */
    private static boolean touchesTokens(ConfigDiff diff) {
        for (ConfigDiff.Change c : diff.changes) {
            if (c.path.startsWith("theme.shape.") || c.path.startsWith("theme.font.")
                    || c.path.equals("theme.preset") || c.path.equals("theme.surface")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rebuilds the token overlays (radius, fonts) from the live settings. Runs after every apply
     * that touches them and, with {@code onlyIfStale}, at boot and on user switch, so a freshly
     * flashed device (or one whose overlay state was wiped) gets its tokens without an apply.
     */
    void applyTokens(int userId, boolean onlyIfStale) {
        final Object radius = effective("theme.shape.radius", userId);
        final Object style = effective("theme.shape.style", userId);
        final Object surface = effective("theme.surface", userId);
        final ThemeApplier.Tokens t = new ThemeApplier.Tokens(
                radius instanceof Long ? (Long) radius : null,
                style instanceof String ? (String) style : null,
                stringOrNull(effective("theme.font.display", userId)),
                stringOrNull(effective("theme.font.text", userId)),
                stringOrNull(effective("theme.font.mono", userId)),
                "stock".equals(surface),
                effective("theme.font.body", userId) != null);
        mTheme.applyTokens(t, userId, onlyIfStale);
    }

    /** The option's live value, or its default when unset. */
    private Object effective(String path, int userId) {
        final Option o = mSchema.get(path);
        final Object v = mBackend.read(o, userId);
        return v != null ? v : o.defaultValue;
    }

    private static String stringOrNull(Object o) {
        return o instanceof String ? (String) o : null;
    }

    private void applyTokensAsync(int userId) {
        BackgroundThread.getHandler().post(() -> {
            try {
                synchronized (mLock) {
                    applyTokens(userId, true);
                }
            } catch (RuntimeException e) {
                Slog.w(TAG, "user " + userId + ": tokens not applied", e);
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Presets

    String[] listPresets() {
        final String[] names = PRESETS_DIR.list((d, n) -> n.endsWith(".nix"));
        if (names == null) {
            return new String[0];
        }
        final String[] out = new String[names.length];
        for (int i = 0; i < names.length; i++) {
            out[i] = names[i].substring(0, names[i].length() - 4);
        }
        java.util.Arrays.sort(out);
        return out;
    }

    String presetText(String name) {
        if (name == null || !name.matches("[a-z0-9_-]+")) {
            throw new IllegalArgumentException("bad preset name " + name);
        }
        try {
            return new String(Files.readAllBytes(new File(PRESETS_DIR, name + ".nix").toPath()),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("no preset " + name);
        }
    }

    private LinkedHashMap<String, Object> loadPreset(String name) {
        return evaluate(presetText(name));
    }

    // ---------------------------------------------------------------------------------------------
    // Generations

    Generations generations(int userId) {
        return new Generations(new File(Environment.getDataSystemDirectory(),
                "ichthys/" + userId));
    }

    /** gen-N.nix + gen-N.meta files in one per-user directory. */
    static final class Generations {
        private final File mDir;

        Generations(File dir) {
            mDir = dir;
        }

        /** Generation numbers, newest first. */
        List<Integer> list() {
            final List<Integer> out = new ArrayList<>();
            final String[] names = mDir.list();
            if (names != null) {
                for (String n : names) {
                    if (n.startsWith("gen-") && n.endsWith(".nix")) {
                        try {
                            out.add(Integer.parseInt(n.substring(4, n.length() - 4)));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
            out.sort(Collections.reverseOrder());
            return out;
        }

        int current() {
            final List<Integer> l = list();
            return l.isEmpty() ? 0 : l.get(0);
        }

        int next() {
            return current() + 1;
        }

        String read(int gen) {
            try {
                return new String(new AtomicFile(file(gen, ".nix")).readFully(),
                        StandardCharsets.UTF_8);
            } catch (IOException e) {
                return null;
            }
        }

        Properties meta(int gen) {
            final Properties p = new Properties();
            try {
                p.load(new java.io.StringReader(new String(new AtomicFile(file(gen, ".meta"))
                        .readFully(), StandardCharsets.UTF_8)));
            } catch (IOException ignored) {
            }
            return p;
        }

        void save(int gen, String text, String summary, String source) {
            if (!mDir.isDirectory() && !mDir.mkdirs()) {
                Slog.e(TAG, "cannot create " + mDir);
                return;
            }
            write(file(gen, ".nix"), text);
            final Properties p = new Properties();
            p.setProperty("time", String.valueOf(System.currentTimeMillis()));
            p.setProperty("summary", summary == null ? "" : summary);
            p.setProperty("source", source == null ? "" : source);
            final java.io.StringWriter sw = new java.io.StringWriter();
            try {
                p.store(sw, null);
            } catch (IOException ignored) {
            }
            write(file(gen, ".meta"), sw.toString());
            prune();
        }

        private void prune() {
            final List<Integer> l = list();
            for (int i = KEEP_GENERATIONS; i < l.size(); i++) {
                file(l.get(i), ".nix").delete();
                file(l.get(i), ".meta").delete();
            }
        }

        private File file(int gen, String ext) {
            return new File(mDir, "gen-" + gen + ext);
        }

        private static void write(File f, String text) {
            final AtomicFile af = new AtomicFile(f);
            FileOutputStream out = null;
            try {
                out = af.startWrite();
                out.write(text.getBytes(StandardCharsets.UTF_8));
                af.finishWrite(out);
            } catch (IOException e) {
                af.failWrite(out);
                Slog.e(TAG, "cannot write " + f, e);
            }
        }
    }

    /** "N\ttime\tsummary\tsource\tcurrent" per generation, newest first. */
    String[] describeGenerations(int userId) {
        final Generations gens = generations(userId);
        final List<Integer> list = gens.list();
        final String[] out = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            final int g = list.get(i);
            final Properties m = gens.meta(g);
            out[i] = g + "\t" + m.getProperty("time", "0") + "\t" + m.getProperty("summary", "")
                    + "\t" + m.getProperty("source", "") + "\t" + (i == 0 ? "1" : "0");
        }
        return out;
    }

    static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date());
    }

    // ---------------------------------------------------------------------------------------------
    // Binder

    private final class BinderService extends IIchthysConfig.Stub {

        private int checkCaller(int userId) {
            final int uid = Binder.getCallingUid();
            if (!ConfigBackend.isSystemOrShell(uid)) {
                getContext().enforceCallingOrSelfPermission(PERMISSION, TAG);
            }
            final int calling = UserHandle.getUserId(uid);
            final int user = userId == UserHandle.USER_CURRENT
                    ? ActivityManager.getCurrentUser() : userId;
            if (user != calling) {
                getContext().enforceCallingOrSelfPermission(
                        android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, TAG);
            }
            return user;
        }

        @Override
        public String exportConfig(boolean all, int userId) {
            final int user = checkCaller(userId);
            final long token = Binder.clearCallingIdentity();
            try {
                return export(user, all);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public String diff(String config, boolean merge, int userId) {
            final int user = checkCaller(userId);
            final long token = Binder.clearCallingIdentity();
            try {
                return IchthysConfigService.this.diff(config, merge, user);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public int apply(String config, boolean merge, String source, int userId) {
            final int user = checkCaller(userId);
            final long token = Binder.clearCallingIdentity();
            try {
                return IchthysConfigService.this.apply(config, merge,
                        source == null ? "api" : source, user);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public int rollback(int generation, int userId) {
            final int user = checkCaller(userId);
            final long token = Binder.clearCallingIdentity();
            try {
                return IchthysConfigService.this.rollback(generation, user);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public String[] listGenerations(int userId) {
            final int user = checkCaller(userId);
            final long token = Binder.clearCallingIdentity();
            try {
                return describeGenerations(user);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public String getGeneration(int generation, int userId) {
            final int user = checkCaller(userId);
            final long token = Binder.clearCallingIdentity();
            try {
                final String s = generations(user).read(generation);
                if (s == null) {
                    throw new IllegalArgumentException("no generation " + generation);
                }
                return s;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public String describeOptions(String prefix) {
            checkCaller(UserHandle.USER_CURRENT);
            return mSchema.docs(prefix == null ? "" : prefix);
        }

        @Override
        public String[] listPresets() {
            checkCaller(UserHandle.USER_CURRENT);
            return IchthysConfigService.this.listPresets();
        }

        @Override
        public String getPreset(String name) {
            checkCaller(UserHandle.USER_CURRENT);
            return presetText(name);
        }

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
                String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
            new IchthysConfigShellCommand(IchthysConfigService.this).exec(this, in, out, err,
                    args, callback, resultReceiver);
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, pw)) {
                return;
            }
            final int user = ActivityManager.getCurrentUser();
            pw.println("IchthysConfigService: " + mSchema.all().size() + " options");
            pw.println("user " + user + " generations:");
            for (String g : describeGenerations(user)) {
                pw.println("  " + g.replace('\t', ' '));
            }
        }
    }
}
