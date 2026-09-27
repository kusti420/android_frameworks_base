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
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.ShellCommand;

import com.android.internal.ichthys.config.NixPrinter;
import com.android.internal.ichthys.config.Option;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * {@code adb shell cmd ichthys_config ...}. Files are opened by the shell (so paths like
 * /sdcard/ichthys.nix work with the shell's access), "-" reads stdin.
 */
final class IchthysConfigShellCommand extends ShellCommand {
    private final IchthysConfigService mService;

    IchthysConfigShellCommand(IchthysConfigService service) {
        mService = service;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(null);
        }
        final int uid = Binder.getCallingUid();
        if (!ConfigBackend.isSystemOrShell(uid)) {
            getErrPrintWriter().println("Error: shell or root only");
            return -1;
        }
        final PrintWriter pw = getOutPrintWriter();
        final PrintWriter err = getErrPrintWriter();
        int user = ActivityManager.getCurrentUser();
        boolean merge = false;
        boolean all = false;
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--user":
                    user = Integer.parseInt(getNextArgRequired());
                    break;
                case "--merge":
                    merge = true;
                    break;
                case "--all":
                    all = true;
                    break;
                default:
                    err.println("Error: unknown option " + opt);
                    return -1;
            }
        }
        final long token = Binder.clearCallingIdentity();
        try {
            switch (cmd) {
                case "apply": {
                    final String text = readFile(getNextArgRequired());
                    final String changes = mService.diff(text, merge, user);
                    final int gen = mService.apply(text, merge, "shell", user);
                    pw.print(changes);
                    pw.println("generation " + gen);
                    return 0;
                }
                case "diff":
                    pw.print(mService.diff(readFile(getNextArgRequired()), merge, user));
                    return 0;
                case "rollback": {
                    final String arg = getNextArg();
                    final int gen = mService.rollback(arg == null ? -1 : Integer.parseInt(arg),
                            user);
                    pw.println("generation " + gen);
                    return 0;
                }
                case "list":
                    for (String line : mService.describeGenerations(user)) {
                        final String[] f = line.split("\t", -1);
                        final String when = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
                                .format(new Date(Long.parseLong(f[1])));
                        pw.println(String.format(Locale.ROOT, "%s%-4s %s  %s  (%s)",
                                "1".equals(f[4]) ? "*" : " ", f[0], when, f[2], f[3]));
                    }
                    return 0;
                case "show-generation":
                    pw.print(mService.generations(user).read(Integer.parseInt(
                            getNextArgRequired())));
                    return 0;
                case "export":
                    pw.print(mService.export(user, all));
                    return 0;
                case "options":
                    pw.print(mService.schema().docs(getNextArg()));
                    return 0;
                case "show": {
                    final String path = getNextArgRequired();
                    final Option o = mService.schema().get(path);
                    if (o == null) {
                        err.println("Error: no option " + path);
                        return -1;
                    }
                    final Object v = mService.liveState(user, true).get(path);
                    pw.println(path + " = " + NixPrinter.value(v) + ";");
                    return 0;
                }
                case "presets":
                    for (String p : mService.listPresets()) {
                        pw.println(p);
                    }
                    return 0;
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            return 1;
        } catch (IOException e) {
            err.println("error: " + e.getMessage());
            return 1;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private String readFile(String path) throws IOException {
        final InputStream in;
        ParcelFileDescriptor pfd = null;
        if ("-".equals(path)) {
            in = getRawInputStream();
        } else {
            pfd = openFileForSystem(path, "r");
            if (pfd == null) {
                throw new IOException("cannot open " + path);
            }
            in = new FileInputStream(pfd.getFileDescriptor());
        }
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 1024 * 1024) {
                    throw new IOException("config larger than 1 MiB");
                }
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            if (pfd != null) {
                pfd.close();
            }
        }
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.println("IchthysOS configuration (ichthys.nix) commands:");
        pw.println("  apply [--merge] [--user U] FILE|-   apply a config as a new generation");
        pw.println("      declarative by default: options the file omits return to defaults;");
        pw.println("      --merge only changes the options the file sets");
        pw.println("  diff [--merge] FILE|-               show what apply would change");
        pw.println("  rollback [N]                        re-apply generation N (default: previous)");
        pw.println("  list                                list generations (* = current)");
        pw.println("  show-generation N                   print generation N");
        pw.println("  export [--all]                      print the live config");
        pw.println("  options [PREFIX]                    option reference");
        pw.println("  show PATH                           print one option's live value");
        pw.println("  presets                             list theme presets");
    }
}
