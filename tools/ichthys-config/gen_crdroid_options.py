#!/usr/bin/env python3
# Copyright (C) 2026 IchthysOS
# SPDX-License-Identifier: Apache-2.0
"""Generate CrDroidOptions.java (ichthys.nix option schema) from crDroidSettings' preference XMLs.

Every preference whose storage can be determined statically becomes one typed option:
  - typed crDroid/Lineage preferences (SystemSettingSwitchPreference, SecureSettingListPreference,
    LineageSystemSettingSwitchPreference, ...) carry their store in the class name;
  - plain SwitchPreferenceCompat/ListPreference/ColorPickerPreference/CustomSeekBarPreference are
    managed by their fragment, so the fragment sources are searched for the Settings call that
    reads the key.
Keys the settings audit found dead (tracker #49) and pages that are unlinked are skipped. IchthysOS
options (privacy.*, background.*, theme.*, ...) are hand-written in IchthysOptions.java.

Usage: gen_crdroid_options.py <crDroidSettings dir> <output .java>
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

A = '{http://schemas.android.com/apk/res/android}'
S = '{http://schemas.android.com/apk/res/com.android.settings}'

# Pages that are unlinked, dead, handled by hand (IchthysOptions) or not settings at all.
SKIP_FILES = {
    'crdroid_settings_about.xml', 'crdroid_settings_ichthys.xml', 'dynamic_bar.xml',
    'dynamic_bar_guide_prefs.xml', 'game_spoofing.xml', 'sensor_block_settings.xml',
    'qs_header_image_settings.xml', 'slim_recents_settings.xml', 'play_integrity_fix.xml',
    'tricky_store.xml', 'ichthys_backstage.xml', 'ichthys_duress_settings.xml',
    'theme_engine_index.xml', 'battery_light_settings.xml', 'notification_light_settings.xml',
    'battery_icon.xml', 'theme_volume_panel.xml',
}
# Keys the audit found dead, hidden for hardware reasons, or runtime state.
SKIP_KEYS = {
    'qs_show_volume_slider', 'qs_show_ringer_mode', 'qs_show_data_usage',
    'accent_background', 'bg_color', 'luminance_factor', 'chroma_factor', 'whole_palette',
    'tint_background', 'lockscreen_show_carrier', 'lockscreen_show_custom_carrier_text',
    'vibrate_on_connect', 'vibrate_on_callwaiting', 'vibrate_on_disconnect',
    'pulse_on_new_tracks', 'screen_off_animation', 'qs_brightness_slider_gradient',
    'volume_slider_gradient', 'hardware_keys_disable', 'swap_capacitive_keys', 'anbi_enabled',
    'pocket_judge', 'gaming_mode', 'sidebar', 'game_spoofing', 'sensor_block_settings',
    'display_cutout_force_fullscreen_settings', 'recents_type', 'volume_panel_style',
    'status_bar_battery_style', 'status_bar_show_battery_percent', 'qs_battery_style',
    'qs_show_battery_percent',
}
# Namespace per page.
NAMESPACE = {
    'crdroid_settings_statusbar.xml': 'statusBar',
    'status_bar_clock.xml': 'statusBar.clock',
    'status_bar_icons.xml': 'statusBar.icons',
    'network_traffic_settings.xml': 'statusBar.networkTraffic',
    'battery_bar.xml': 'statusBar.batteryBar',
    'crdroid_settings_quicksettings.xml': 'quickSettings',
    'qs_layout_settings.xml': 'quickSettings.layout',
    'theme_quicksettings.xml': 'quickSettings.theme',
    'crdroid_settings_lockscreen.xml': 'lockscreen',
    'lockscreen_clock_preview_settings.xml': 'lockscreen.clock',
    'always_on_display_schedule.xml': 'lockscreen.ambient.schedule',
    'doze_settings.xml': 'lockscreen.ambient',
    'media_art_settings.xml': 'lockscreen.mediaArt',
    'pulse_settings.xml': 'lockscreen.pulse',
    'edge_light_settings.xml': 'lockscreen.edgeLight',
    'crdroid_settings_button.xml': 'system.buttons',
    'power_menu.xml': 'system.powerMenu',
    'crdroid_settings_navigation.xml': 'system.navigation',
    'crdroid_settings_notifications.xml': 'system.notifications',
    'crdroid_settings_sound.xml': 'volume',
    'adaptive_playback_settings.xml': 'volume.adaptivePlayback',
    'crdroid_settings_ui.xml': 'system.ui',
    'gradient_settings.xml': 'theme.gradient',
    'settings_icons.xml': 'theme.settingsIcons',
    'smart_pixels.xml': 'system.smartPixels',
    'crdroid_settings_themes.xml': 'theme',
    'theme_global.xml': 'theme',
    'monet_engine.xml': 'theme.monet',
    'crdroid_settings_misc.xml': 'system.misc',
    'charging_control_settings.xml': 'system.chargingControl',
}
# Leading key words dropped when they only repeat the namespace.
STRIP = ['status_bar_', 'statusbar_', 'qs_', 'lockscreen_', 'lock_screen_', 'network_traffic_',
         'battery_bar_', 'statusbar_battery_bar_', 'edge_light_', 'pulse_', 'doze_', 'ambient_',
         'charging_control_', 'smart_pixels_', 'adaptive_playback_', 'power_menu_', 'monet_']

STORE_BY_CLASS = [
    ('LineageSystemSetting', 'LINEAGE_SYSTEM'), ('LineageSecureSetting', 'LINEAGE_SECURE'),
    ('LineageGlobalSetting', 'LINEAGE_GLOBAL'), ('DangerGlobal', 'GLOBAL'),
    ('SystemSetting', 'SYSTEM'), ('SecureSetting', 'SECURE'), ('GlobalSetting', 'GLOBAL'),
]


def load_values(root):
    strings, arrays, scalars = {}, {}, {}
    for f in glob.glob(os.path.join(root, 'res/values/*.xml')):
        try:
            t = ET.parse(f)
        except ET.ParseError:
            continue
        for el in t.getroot():
            name = el.get('name')
            if not name:
                continue
            if el.tag == 'string':
                strings[name] = ''.join(el.itertext()).strip().strip('"')
            elif el.tag in ('string-array', 'integer-array', 'array'):
                arrays[name] = [(''.join(i.itertext()) or '').strip() for i in el.findall('item')]
            elif el.tag in ('bool', 'integer', 'color', 'dimen'):
                scalars[name] = (el.text or '').strip()
    return strings, arrays, scalars


def resolve(ref, strings, scalars):
    if ref is None:
        return None
    if ref.startswith('@string/'):
        return strings.get(ref[8:], ref[8:])
    for p in ('@bool/', '@integer/', '@color/'):
        if ref.startswith(p):
            return scalars.get(ref[len(p):])
    if ref.startswith('@'):
        return None
    return ref


def camel(words):
    parts = [w for w in re.split(r'[^A-Za-z0-9]+', words) if w]
    if not parts:
        return None
    out = parts[0].lower() + ''.join(p[:1].upper() + p[1:].lower() for p in parts[1:])
    if out[0].isdigit():
        out = 'v' + out
    return out


def leaf_name(key):
    k = key
    for p in sorted(STRIP, key=len, reverse=True):
        if k.startswith(p) and len(k) > len(p):
            k = k[len(p):]
            break
    return camel(k)


def find_store_in_src(root, key):
    """Best effort: find the Settings table a fragment uses for this key."""
    const = None
    hits = []
    for f in glob.glob(os.path.join(root, 'src/**/*.java'), recursive=True) + \
            glob.glob(os.path.join(root, 'src/**/*.kt'), recursive=True):
        try:
            text = open(f, encoding='utf-8', errors='ignore').read()
        except OSError:
            continue
        if '"%s"' % key not in text:
            continue
        m = re.search(r'(\w+)\s*=\s*"%s"' % re.escape(key), text)
        names = ['"%s"' % key] + ([m.group(1)] if m else [])
        for line in text.splitlines():
            if any(n in line for n in names):
                mm = re.search(r'(LineageSettings\.(System|Secure|Global)|Settings\.(System|Secure|Global))'
                               r'\.(put|get)(Int|String|Long|Float)', line)
                if mm:
                    table = mm.group(2) or mm.group(3)
                    lineage = mm.group(0).startswith('Lineage')
                    kind = mm.group(5)
                    hits.append((('LINEAGE_' if lineage else '') + table.upper(), kind))
    return hits[0] if hits else None


def java_str(s):
    return '"' + (s or '').replace('\\', '\\\\').replace('"', '\\"').replace('\n', ' ') + '"'


def main(root, out):
    strings, arrays, scalars = load_values(root)
    opts = {}
    skipped = []
    for f in sorted(glob.glob(os.path.join(root, 'res/xml/*.xml'))):
        base = os.path.basename(f)
        if base in SKIP_FILES or base not in NAMESPACE:
            continue
        ns = NAMESPACE[base]
        for el in ET.parse(f).iter():
            key = el.get(A + 'key')
            if not key or key in SKIP_KEYS:
                continue
            cls = el.tag.split('.')[-1]
            if cls in ('PreferenceCategory', 'Preference', 'PreferenceScreen', 'FooterPreference',
                       'LayoutPreference', 'RemotePreference', 'IllustrationPreference',
                       'TopIntroPreference') or cls.endswith('Preference') and cls in (
                       'StartTimePreference', 'TargetTimePreference', 'FontsPickerPreference',
                       'LockClockFontsPreference', 'QSTileIconShapePreference',
                       'ClockBackgroundChipPreference', 'StatusBarLogoStylePreference',
                       'DeviceConfigSwitchPreference', 'SystemPropertySwitchPreference'):
                continue
            store = None
            for prefix, st in STORE_BY_CLASS:
                if cls.startswith(prefix) or ('.' + prefix) in el.tag:
                    store = st
                    break
            if el.tag.startswith('lineageos.preference.') and store is None:
                # lineageos.preference.SystemSettingMainSwitchPreference etc.
                for prefix, st in STORE_BY_CLASS:
                    if prefix in cls:
                        store = st
                        break
            kind = None
            if store is None:
                hit = find_store_in_src(root, key)
                if hit is None:
                    skipped.append('%s:%s (%s)' % (base, key, cls))
                    continue
                store, kind = hit
            if 'Switch' in cls:
                typ = 'BOOL'
            elif 'List' in cls:
                typ = 'ENUM'
            elif 'SeekBar' in cls or 'Seekbar' in cls:
                typ = 'INT'
            elif 'ColorPicker' in cls:
                typ = 'COLOR'
            elif kind == 'String':
                typ = 'STRING'
            else:
                skipped.append('%s:%s (%s: unknown type)' % (base, key, cls))
                continue
            title = resolve(el.get(A + 'title'), strings, scalars) or key
            default = resolve(el.get(A + 'defaultValue'), strings, scalars)
            leaf = leaf_name(key)
            path = ns + '.' + leaf
            if path in opts:
                path = ns + '.' + camel(key)
            if path in opts:
                continue
            o = {'path': path, 'type': typ, 'store': store, 'key': key, 'desc': title,
                 'default': default}
            if typ == 'INT':
                o['min'] = el.get(S + 'min') or el.get(A + 'min') or '0'
                o['max'] = el.get(A + 'max') or '100'
            if typ == 'ENUM':
                ev = el.get(A + 'entryValues')
                en = el.get(A + 'entries')
                values = arrays.get(ev[7:], []) if ev and ev.startswith('@array/') else []
                labels = arrays.get(en[7:], []) if en and en.startswith('@array/') else []
                if not values:
                    skipped.append('%s:%s (list without entryValues)' % (base, key))
                    continue
                names, used = [], set()
                for i, v in enumerate(values):
                    lab = resolve(labels[i], strings, scalars) if i < len(labels) else None
                    n = camel(lab) if lab else None
                    if not n or n in used or len(n) > 24:
                        n = camel('v ' + v) if re.match(r'^-?\d', v) else (camel(v) or 'v%d' % i)
                    if n in used:
                        n = n + str(i)
                    used.add(n)
                    names.append(n)
                o['enum'] = list(zip(names, values))
            opts[path] = o

    # A path can't be both an option and a namespace (statusBar.clock vs statusBar.clock.seconds):
    # such options become "<name>Enabled".
    for p in list(opts):
        if any(q.startswith(p + '.') for q in opts):
            o = opts.pop(p)
            o['path'] = p + 'Enabled'
            opts[o['path']] = o

    lines = []
    w = lines.append
    w('/*')
    w(' * Copyright (C) 2026 IchthysOS')
    w(' * SPDX-License-Identifier: Apache-2.0')
    w(' */')
    w('')
    w('package com.android.internal.ichthys.config;')
    w('')
    w('/**')
    w(' * crDroid settings as ichthys.nix options. GENERATED by')
    w(' * frameworks/base/tools/ichthys-config/gen_crdroid_options.py from crDroidSettings res/xml;')
    w(' * do not edit by hand, re-run the generator after changing a settings page.')
    w(' */')
    w('final class CrDroidOptions {')
    w('    private CrDroidOptions() {}')
    w('')
    w('    static void register(OptionSchema s) {')
    for p in sorted(opts):
        o = opts[p]
        b = 'Binding.setting(Binding.Store.%s, %s)' % (o['store'], java_str(o['key']))
        d = o['default']
        if o['type'] == 'BOOL':
            dv = 'Boolean.TRUE' if (d or '').lower() in ('true', '1') else 'Boolean.FALSE'
            w('        s.add(Option.bool(%s, %s, %s, %s));' % (java_str(p), dv, java_str(o['desc']), b))
        elif o['type'] == 'INT':
            try:
                dv = '%dL' % int(float(d)) if d not in (None, '') else 'null'
            except ValueError:
                dv = 'null'
            try:
                lo, hi = int(float(o['min'])), int(float(o['max']))
            except ValueError:
                lo, hi = 0, 0  # unresolved bound: no range check
            w('        s.add(Option.integer(%s, %s, %dL, %dL, %s, %s));' % (
                java_str(p), dv, lo, hi, java_str(o['desc']), b))
        elif o['type'] == 'COLOR':
            dc = None
            if d and d.startswith('#'):
                dc = d.lower()
            elif d and d.lower().startswith('0x'):
                dc = '#' + d[2:].lower()
            w('        s.add(Option.color(%s, %s, %s, %s).storedAsInt());' % (
                java_str(p), java_str(dc) if dc else 'null', java_str(o['desc']), b))
        elif o['type'] == 'STRING':
            w('        s.add(Option.string(%s, %s, %s, %s));' % (
                java_str(p), java_str(d) if d is not None else 'null', java_str(o['desc']), b))
        else:
            names = ', '.join(java_str(n) for n, _ in o['enum'])
            values = ', '.join(java_str(v) for _, v in o['enum'])
            dname = None
            for n, v in o['enum']:
                if v == d:
                    dname = n
            w('        s.add(Option.enumeration(%s, new String[] {%s}, new String[] {%s}, %s, %s, %s));'
              % (java_str(p), names, values, java_str(dname) if dname else 'null',
                 java_str(o['desc']), b))
    w('    }')
    w('}')
    w('')
    with open(out, 'w') as fh:
        fh.write('\n'.join(lines))
    sys.stderr.write('%d options, %d skipped\n' % (len(opts), len(skipped)))
    for s in skipped:
        sys.stderr.write('  skipped ' + s + '\n')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
