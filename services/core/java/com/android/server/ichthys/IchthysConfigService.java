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
import android.app.AppGlobals;
import android.content.Context;
import android.os.Binder;
import android.os.Environment;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AtomicFile;
import android.util.Slog;

import com.android.internal.ichthys.IIchthysConfig;
import com.android.internal.ichthys.config.Binding;
import com.android.internal.ichthys.config.ConfigDiff;
import com.android.internal.ichthys.config.ConfigPlanner;
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
import java.util.Properties;

/**
 * IchthysOS declarative configuration ("ichthys.nix"), NixOS style.
 *
 * <ul>
 *   <li><b>apply</b>: evaluate a config against the {@link OptionSchema} (unknown options and
 *       wrong types are errors, with line:col), compute the change set, write it atomically (a
 *       failure part-way restores every option already written), then record a
 *       <i>generation</i>. Declarative by default: options the file doesn't mention go back to
 *       their defaults, except the per-app lists and app state, which are kept unless
 *       mentioned. With {@code merge}, only the options it mentions change. {@link
 *       ConfigPlanner} has the exact rules.</li>
 *   <li><b>baseline</b>: /product/etc/ichthys/default.nix is applied as generation 1 of every
 *       new user, so a fresh install has the IchthysOS look and privacy defaults stored
 *       explicitly, whatever a consumer would make of an unset key.</li>
 *   <li><b>generations</b>: /data/system/ichthys/&lt;user&gt;/gen-N.nix is a snapshot of every
 *       stored option right after apply N. Generation 0 is the state before the first apply. The
 *       last {@value #KEEP_GENERATIONS} are kept. Applies from the same interactive source (a
 *       run of taps in Theme Studio) within {@link #COALESCE_WINDOW_MS} of each other share one
 *       generation, so trying things out doesn't push the history out.</li>
 *   <li><b>rollback N</b>: restore the snapshot gen-N exactly, as a new generation (like
 *       nixos-rebuild --rollback, nothing is ever rewritten in place).</li>
 *   <li><b>presets</b>: theme.preset fills in theme.*, privacy.level fills in privacy.*,
 *       wherever the file sets nothing.</li>
 * </ul>
 * Exposed as the "ichthys_config" binder service (Settings' Theme Studio, Ichthys Apps) and
 * {@code cmd ichthys_config}.
 */
public final class IchthysConfigService extends SystemService {
    static final String TAG = "IchthysConfig";
    static final String SERVICE_NAME = "ichthys_config";
    static final String PERMISSION = "android.permission.MANAGE_ICHTHYS_CONFIG";
    static final int KEEP_GENERATIONS = 30;
    /**
     * An apply joins the current generation instead of starting a new one when it comes from the
     * same interactive source and this soon after that generation's last apply...
     */
    static final long COALESCE_WINDOW_MS = 60_000;
    /** ...but a generation never collects applies for longer than this. */
    static final long COALESCE_MAX_MS = 10 * 60_000;
    /** Generation source of the baseline. */
    static final String SOURCE_DEFAULT = "default";

    private static final File ETC_DIR = new File("/product/etc/ichthys");
    /** Theme presets: theme.preset = "&lt;name&gt;". */
    static final String PRESETS = "presets";
    /** Privacy levels: privacy.level = "&lt;name&gt;". */
    static final String PRIVACY_LEVELS = "privacy";
    private static final File DEFAULT_CONFIG = new File(ETC_DIR, "default.nix");

    private final Object mLock = new Object();
    private final OptionSchema mSchema;
    private final ConfigPlanner mPlanner;
    private final ConfigBackend mBackend;
    private final ThemeApplier mTheme;

    public IchthysConfigService(Context context) {
        super(context);
        mSchema = OptionSchema.create();
        mPlanner = new ConfigPlanner(mSchema, IchthysConfigService::presetFile);
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
    public void onUserUnlocked(TargetUser user) {
        // The baseline waits for the unlock: recording a generation reads every option, some
        // from providers that only run for an unlocked user. On a first boot there is no lock,
        // so this comes right after boot. Profiles share their parent's look and have no
        // configuration of their own.
        if (!user.isProfile()) {
            applyBaselineAsync(user.getUserIdentifier());
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

    private ConfigPlanner.Store store(int userId) {
        return o -> mBackend.read(o, userId);
    }

    /** Live state: every option's value, or only what an export needs to say. */
    LinkedHashMap<String, Object> liveState(int userId, boolean all) {
        return mPlanner.state(store(userId),
                all ? ConfigPlanner.State.ALL : ConfigPlanner.State.CHANGED);
    }

    String export(int userId, boolean all) {
        final String header = "IchthysOS configuration (ichthys.nix)\n"
                + "exported " + now() + (all ? ", all options"
                        : ", options changed from the defaults and presets")
                // IchthysOS: import moved from Theme Studio to the System page (tracker#59).
                + "\napply with: adb shell cmd ichthys_config apply <file>, or Settings > "
                + "IchthysOS > System > Import config";
        return mSchema.print(liveState(userId, all), header);
    }

    /** A generation snapshot: every option that has a stored value. */
    private String snapshot(int userId) {
        return mSchema.print(mPlanner.state(store(userId), ConfigPlanner.State.STORED),
                "IchthysOS generation snapshot: every stored option\nsaved " + now());
    }

    /** Evaluates text to {path → value}; IllegalArgumentException carries the position. */
    LinkedHashMap<String, Object> evaluate(String text) {
        return evaluate(text, false, null);
    }

    /**
     * @param lenient skip what the schema no longer accepts instead of failing (snapshots)
     * @param notes   receives a line per skipped setting; may be null
     */
    private LinkedHashMap<String, Object> evaluate(String text, boolean lenient,
            List<String> notes) {
        try {
            return mSchema.evaluate(NixParser.parse(text), lenient, notes);
        } catch (NixParseException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    /** What applying {@code text} would skip (settings of options that no longer exist). */
    List<String> notes(String text) {
        final List<String> notes = new ArrayList<>();
        evaluate(text, false, notes);
        return notes;
    }

    private static ConfigPlanner.Mode mode(boolean merge) {
        return merge ? ConfigPlanner.Mode.MERGE : ConfigPlanner.Mode.DECLARATIVE;
    }

    String diff(String text, boolean merge, int userId) {
        synchronized (mLock) {
            final ConfigPlanner.Mode mode = mode(merge);
            return mPlanner.changes(mPlanner.target(evaluate(text), mode), mode, store(userId))
                    .format();
        }
    }

    /** @return the generation number now current (unchanged if nothing changed) */
    int apply(String text, boolean merge, String source, int userId) {
        synchronized (mLock) {
            final ConfigPlanner.Mode mode = mode(merge);
            return applyTarget(mPlanner.target(evaluate(text), mode), mode, source, userId);
        }
    }

    /** Writes a target (see {@link ConfigPlanner#target}) and records the generation. */
    private int applyTarget(Map<String, Object> target, ConfigPlanner.Mode mode, String source,
            int userId) {
        synchronized (mLock) {
            final ConfigDiff diff = mPlanner.changes(target, mode, store(userId));
            final Generations gens = generations(userId);
            if (gens.list().isEmpty()) {
                gens.save(0, snapshot(userId), "state before the first apply", "initial", null,
                        0, null);
            }
            if (diff.isEmpty()) {
                return gens.current();
            }
            // IchthysOS: device-wide (Global) options belong to the system user, as for the
            // baseline: a secondary user's apply or rollback must not switch the owner's
            // shields or pm_downgrade_allowed.
            final List<ConfigDiff.Change> changes = new ArrayList<>();
            for (ConfigDiff.Change c : diff.changes) {
                if (userId != UserHandle.USER_SYSTEM && isGlobal(c.path)) {
                    Slog.w(TAG, "user " + userId + ": skipping device-wide " + c.path);
                    continue;
                }
                changes.add(c);
            }
            if (changes.isEmpty()) {
                return gens.current();
            }
            final List<ConfigDiff.Change> done = new ArrayList<>();
            String writing = null;
            try {
                for (ConfigDiff.Change c : changes) {
                    writing = c.path;
                    final Binding.Store store = mSchema.get(c.path).binding.store;
                    if (mode == ConfigPlanner.Mode.SNAPSHOT
                            && (store == Binding.Store.ROLE
                                    || store == Binding.Store.KEYGUARD_SLOT)) {
                        // IchthysOS: a rollback restores default apps and lock screen shortcuts
                        // best effort: an app uninstalled since must not make the whole
                        // rollback fail.
                        try {
                            mBackend.write(mSchema.get(c.path), mPlanner.valueToStore(c, mode),
                                    userId);
                            done.add(c);
                        } catch (RuntimeException e) {
                            Slog.w(TAG, "rollback: skipping " + c.path + ": " + e.getMessage());
                        }
                        continue;
                    }
                    mBackend.write(mSchema.get(c.path), mPlanner.valueToStore(c, mode), userId);
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
                final String failed = writing;
                throw new IllegalArgumentException("applying " + failed + " failed: "
                        + e.getMessage() + " (all changes rolled back)");
            }
            if (touchesTokens(diff)) {
                applyTokens(userId, false);
            }
            final int gen = gens.record(snapshot(userId), diff.paths(), source,
                    System.currentTimeMillis());
            Slog.i(TAG, "user " + userId + " generation " + gen + ": " + diff.summary());
            return gen;
        }
    }

    int rollback(int generation, int userId) {
        synchronized (mLock) {
            final Generations gens = generations(userId);
            final List<Integer> list = gens.list();
            final int target = generation >= 0 ? generation
                    : (list.size() >= 2 ? list.get(1) : -1);
            if (target < 0 || !list.contains(target)) {
                throw new IllegalArgumentException("no generation " + generation);
            }
            final String text = gens.read(target);
            if (text == null) {
                throw new IllegalArgumentException("generation " + target + " is unreadable");
            }
            // A snapshot is restored exactly: what it lists is written, everything else is
            // unset, and no preset fills in. It was valid when it was saved, so what the schema
            // dropped or renamed since is skipped (and logged), not an error.
            final List<String> notes = new ArrayList<>();
            final LinkedHashMap<String, Object> declared = evaluate(text, true, notes);
            for (String note : notes) {
                Slog.w(TAG, "rollback to " + target + ": " + note);
            }
            return applyTarget(mPlanner.target(declared, ConfigPlanner.Mode.SNAPSHOT),
                    ConfigPlanner.Mode.SNAPSHOT, SOURCE_ROLLBACK + target, userId);
        }
    }

    /** IchthysOS: options stored device-wide (Settings.Global, LineageSettings.Global). */
    private boolean isGlobal(String path) {
        final Binding.Store store = mSchema.get(path).binding.store;
        return store == Binding.Store.GLOBAL || store == Binding.Store.LINEAGE_GLOBAL;
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

    // ---------------------------------------------------------------------------------------------
    // Baseline and boot

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

    /** Applies the baseline off the main thread if {@code userId} is a new user. */
    private void applyBaselineAsync(int userId) {
        // Decided now, not on the background thread: by then a setup wizard may already have
        // marked the user as set up.
        if (!isNewUser(userId)) {
            return;
        }
        BackgroundThread.getHandler().post(() -> {
            try {
                synchronized (mLock) {
                    applyBaseline(userId);
                }
            } catch (RuntimeException e) {
                // Never fatal: the option defaults are the same values, the user only lacks
                // the explicit copy.
                Slog.e(TAG, "user " + userId + ": baseline not applied", e);
            }
        });
    }

    /**
     * Whether {@code userId} has never had a configuration and is new: nothing was ever applied
     * for it, and either this is the device's first boot or the user has not finished setup. A
     * user that existed before this build (set up, no generations) is left alone: its settings
     * are its own, and a baseline would overwrite the ones it names.
     */
    private boolean isNewUser(int userId) {
        if (!generations(userId).list().isEmpty()) {
            return false;
        }
        try {
            if (userId == UserHandle.USER_SYSTEM && AppGlobals.getPackageManager().isFirstBoot()) {
                return true;
            }
            return Settings.Secure.getIntForUser(getContext().getContentResolver(),
                    Settings.Secure.USER_SETUP_COMPLETE, 0, userId) != 1;
        } catch (RemoteException | RuntimeException e) {
            Slog.w(TAG, "user " + userId + ": can't tell whether it is new", e);
            return false;
        }
    }

    /**
     * Applies /product/etc/ichthys/default.nix as generation 1 (generation 0 being the untouched
     * state before it). Only the options the file and its presets name are written, each one
     * explicitly. For a user other than the system user the device-wide (Global) options are
     * left out: the first user's baseline set them, and a user added later must not undo what
     * the owner changed since.
     */
    private void applyBaseline(int userId) {
        if (!generations(userId).list().isEmpty()) {
            return;
        }
        final String text;
        try {
            text = new String(Files.readAllBytes(DEFAULT_CONFIG.toPath()),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            Slog.w(TAG, "no " + DEFAULT_CONFIG + ", user " + userId + " starts unconfigured");
            return;
        }
        final LinkedHashMap<String, Object> target =
                mPlanner.target(evaluate(text), ConfigPlanner.Mode.MERGE);
        if (userId != UserHandle.USER_SYSTEM) {
            target.keySet().removeIf(path -> {
                final Binding.Store store = mSchema.get(path).binding.store;
                return store == Binding.Store.GLOBAL || store == Binding.Store.LINEAGE_GLOBAL;
            });
        }
        final int gen = applyTarget(target, ConfigPlanner.Mode.MERGE, SOURCE_DEFAULT, userId);
        Slog.i(TAG, "user " + userId + ": baseline applied as generation " + gen);
    }

    /** The shipped baseline, for {@code cmd ichthys_config default}. */
    String defaultConfigText() {
        try {
            return new String(Files.readAllBytes(DEFAULT_CONFIG.toPath()),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("no " + DEFAULT_CONFIG);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Presets

    /** Names of the presets in /product/etc/ichthys/&lt;directory&gt;. */
    String[] listPresets(String directory) {
        final String[] names = new File(ETC_DIR, directory).list((d, n) -> n.endsWith(".nix"));
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

    /** The text of /product/etc/ichthys/&lt;directory&gt;/&lt;name&gt;.nix. */
    static String presetFile(String directory, String name) {
        if (name == null || !name.matches("[a-z0-9_-]+")) {
            throw new IllegalArgumentException("bad preset name " + name);
        }
        try {
            return new String(Files.readAllBytes(
                    new File(new File(ETC_DIR, directory), name + ".nix").toPath()),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("no " + directory + "/" + name + ".nix");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Generations

    Generations generations(int userId) {
        return new Generations(new File(Environment.getDataSystemDirectory(),
                "ichthys/" + userId));
    }

    /**
     * Whether applies from {@code source} may share a generation. Interactive sources do; the
     * shell, anonymous API callers, rollbacks and the baseline always get their own, so that
     * "apply a, apply b, rollback" goes back to a.
     */
    static boolean coalesces(String source) {
        return source != null && !source.isEmpty() && !"shell".equals(source)
                && !"api".equals(source) && !SOURCE_DEFAULT.equals(source)
                && !source.startsWith(SOURCE_ROLLBACK);
    }

    /** The source a rollback applies with; its detail is the generation restored. */
    static final String SOURCE_ROLLBACK = "rollback to ";

    /**
     * IchthysOS: the generation a rollback restored, from its source ("rollback to 7" is 7), or
     * -1 for any other apply. Kept in the meta file so the UI can say "Rolled back to #7" and
     * mark #7 as the one restored.
     */
    static int rollbackTarget(String source) {
        if (source == null || !source.startsWith(SOURCE_ROLLBACK)) {
            return -1;
        }
        try {
            return Integer.parseInt(source.substring(SOURCE_ROLLBACK.length()).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Who applied: the source without its detail ("Theme Studio: theme.preset"). */
    static String origin(String source) {
        final int colon = source.indexOf(':');
        return (colon < 0 ? source : source.substring(0, colon)).trim();
    }

    /** gen-N.nix + gen-N.meta files in one per-user directory. */
    static final class Generations {
        /** Changed paths kept in the meta file for the summary of a shared generation. */
        private static final int MAX_PATHS = 64;

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

        /**
         * Records the state after an apply: as a new generation, or, for a run of applies from
         * one interactive source ({@link #coalesces}), as the new content of the current one.
         * Generation N-1 then still is the state before the run and N the state after it.
         *
         * @param paths the options this apply changed
         * @return the generation number now current
         */
        int record(String snapshot, List<String> paths, String source, long now) {
            final int current = current();
            final Properties m = meta(current);
            final String origin = coalesces(source) ? origin(source) : null;
            final long last = parse(m.getProperty("time"));
            final long started = parse(m.getProperty("started"));
            if (origin != null && current > 0 && origin.equals(m.getProperty("origin"))
                    && now >= last && now - last <= COALESCE_WINDOW_MS
                    && now - started <= COALESCE_MAX_MS) {
                final List<String> all = new ArrayList<>();
                for (String p : m.getProperty("paths", "").split(",")) {
                    if (!p.isEmpty()) all.add(p);
                }
                for (String p : paths) {
                    if (!all.contains(p)) all.add(p);
                }
                save(current, snapshot, ConfigDiff.summary(all),
                        source.equals(m.getProperty("source")) ? source : origin, origin, started,
                        all);
                return current;
            }
            final int gen = current + 1;
            save(gen, snapshot, ConfigDiff.summary(paths), source, origin, now, paths);
            return gen;
        }

        private static long parse(String s) {
            try {
                return s == null ? 0 : Long.parseLong(s);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        /**
         * @param origin  who may add to this generation later ({@link #record}), or null
         * @param started when the generation's first apply happened
         * @param paths   the options changed in it, or null
         */
        void save(int gen, String text, String summary, String source, String origin,
                long started, List<String> paths) {
            if (!mDir.isDirectory() && !mDir.mkdirs()) {
                Slog.e(TAG, "cannot create " + mDir);
                return;
            }
            write(file(gen, ".nix"), text);
            final Properties p = new Properties();
            p.setProperty("time", String.valueOf(System.currentTimeMillis()));
            p.setProperty("summary", summary == null ? "" : summary);
            p.setProperty("source", source == null ? "" : source);
            final int rollbackTo = rollbackTarget(source);
            if (rollbackTo >= 0) {
                p.setProperty("rollback_to", String.valueOf(rollbackTo));
            }
            if (origin != null) {
                p.setProperty("origin", origin);
                p.setProperty("started", String.valueOf(started));
            }
            if (paths != null) {
                p.setProperty("paths", String.join(",",
                        paths.subList(0, Math.min(paths.size(), MAX_PATHS))));
            }
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

    /**
     * "N\ttime\tsummary\tsource\tcurrent\trollbackTo" per generation, newest first. rollbackTo
     * is the generation a rollback restored, else empty (IchthysOS: generations saved before the
     * meta file had it fall back to their source, "rollback to N").
     */
    String[] describeGenerations(int userId) {
        final Generations gens = generations(userId);
        final List<Integer> list = gens.list();
        final String[] out = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            final int g = list.get(i);
            final Properties m = gens.meta(g);
            final String source = m.getProperty("source", "");
            final int rollbackTo = m.containsKey("rollback_to")
                    ? parseInt(m.getProperty("rollback_to")) : rollbackTarget(source);
            out[i] = g + "\t" + m.getProperty("time", "0") + "\t" + m.getProperty("summary", "")
                    + "\t" + source + "\t" + (i == 0 ? "1" : "0")
                    + "\t" + (rollbackTo >= 0 ? String.valueOf(rollbackTo) : "");
        }
        return out;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
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
            return IchthysConfigService.this.listPresets(PRESETS);
        }

        @Override
        public String getPreset(String name) {
            checkCaller(UserHandle.USER_CURRENT);
            return presetFile(PRESETS, name);
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
