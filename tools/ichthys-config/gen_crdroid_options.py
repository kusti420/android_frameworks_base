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
IchthysOS options (privacy.*, background.*, theme.*, ...) are hand-written in IchthysOptions.java
and IchthysShields.java; a key bound there is never generated a second time.

Options survive Settings reorganisations: crdroid_options.lock (next to this script) remembers
every option ever generated, one JSON object per line: its path and its whole definition. So
  - a key that moves to another page keeps its path;
  - a key no crDroid page shows any more (the row was merged away, or stock Settings shows it)
    stays an option with the definition it last had. The setting still exists; only its row is
    gone. It is listed as KEPT on every run until someone decides: a key that is really dead goes
    into RETIRED, which turns it into a "removed" option (old configs and generations may still
    mention it; it is ignored with a note instead of failing the whole file);
  - only a key the lock has never seen derives its path from the page it is on (NAMESPACE, or a
    guess from the file name for a page that is not in the table yet).

Nothing is dropped silently: every key that does not become an option is listed on stderr with
the reason, and two keys that would get the same path, or a path that is both an option and a
namespace, abort the run without writing anything.

Usage: gen_crdroid_options.py <crDroidSettings dir> <output .java>
"""
import glob
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

A = '{http://schemas.android.com/apk/res/android}'
S = '{http://schemas.android.com/apk/res/com.android.settings}'
# IchthysOS: some pages declare the settings: prefix as res-auto; read both.
SA = '{http://schemas.android.com/apk/res-auto}'
HERE = os.path.dirname(os.path.abspath(__file__))
LOCK = os.path.join(HERE, 'crdroid_options.lock')

# Pages whose keys don't become options, with the reason (printed in the report). This only holds
# back keys the lock has never seen: an option that moves onto one of these pages is followed.
SKIP_FILES = {}
for _reason, _files in {
    'not a settings page': ['theme_engine_index.xml', 'ichthys_hub.xml'],
    'hand-written in IchthysOptions': [
        'ichthys_backstage.xml',         # background.backstageMaxHours
        'status_bar_icons.xml',          # statusBar.hiddenIcons (icon_blacklist)
        'theme_studio.xml',              # theme.*
        'slim_recents_settings.xml',     # recents.style
    ],
    'secrets, not configuration': [
        'play_integrity_fix.xml', 'tricky_store.xml', 'ichthys_duress_settings.xml',
    ],
}.items():
    for _f in _files:
        SKIP_FILES[_f] = _reason
# Keys that stop being options: nothing reads them any more, or it was decided that a setting
# without a row in Settings is not offered in ichthys.nix either. A key listed here becomes a
# "removed" option. key -> reason shown to someone whose config still sets it.
RETIRED = {
    'settings_icon_style': 'replaced by theme.iconBadges and theme.iconStyle',
    'settings_icon_corner_style': 'replaced by theme.iconBadges and theme.iconStyle',
    'settings_icon_random_colors': 'replaced by theme.iconBadges and theme.iconStyle',
    # SystemUI still reads it (default off); the row was dropped from Settings on purpose.
    'lockscreen_smartspace_enabled': 'the lock screen smartspace switch was dropped',
    # The fingerprint animation is centred on the sensor; SystemUI no longer reads an offset.
    'ichthys_udfps_anim_offset_x': 'the fingerprint animation follows the sensor position',
    'ichthys_udfps_anim_offset_y': 'the fingerprint animation follows the sensor position',
}
# Keys that never become a generated option, with the reason (printed in the report).
SKIP_KEYS = {}
for _reason, _keys in {
    'no such hardware on supported devices': [
        'hardware_keys_home_long_press', 'hardware_keys_home_double_tap',
        'hardware_keys_app_switch_long_press', 'hardware_keys_app_switch_double_tap',
    ],
    'needs the weather service; its position follows statusBar.weather.anchorToCutout': [
        'status_bar_weather',
    ],
    'covered by system.powerMenu.actions': [
        'screenshot', 'onthego', 'airplane', 'users', 'lockdown', 'emergency', 'devicecontrols',
    ],
    'covered by theme.style / theme.accent': ['theme_style', 'color_source'],
    # IchthysOS: moved from play_integrity_fix.xml (a SKIP_FILES page) to ichthys_apps.xml; it
    # stays no option, like the rest of the integrity spoofing.
    'secrets, not configuration': ['spoof_pif_photos'],
    # IchthysOS: OmniJaws' icon pack, picked in Settings but stored by OmniJaws (its settings
    # provider), so it is not a Settings key and not an ichthys.nix option.
    'stored by the weather app, not a Settings key': ['weather_icon_pack'],
}.items():
    for _k in _keys:
        SKIP_KEYS[_k] = _reason

# Namespace of the keys first seen on a page. Only used for keys the lock doesn't know yet: a
# key that moved here from another page keeps the path it had.
NAMESPACE = {
    'crdroid_settings_statusbar.xml': 'statusBar',
    'status_bar_battery.xml': 'statusBar',
    'status_bar_clock.xml': 'statusBar.clock',
    'network_traffic_settings.xml': 'statusBar.networkTraffic',
    'crdroid_settings_quicksettings.xml': 'quickSettings',
    'qs_layout_settings.xml': 'quickSettings.layout',
    'crdroid_settings_lockscreen.xml': 'lockscreen',
    'lockscreen_clock_preview_settings.xml': 'lockscreen.clock',
    'always_on_display_schedule.xml': 'lockscreen.ambient.schedule',
    'doze_settings.xml': 'lockscreen.ambient',
    'media_art_settings.xml': 'lockscreen.mediaArt',
    'pulse_settings.xml': 'lockscreen.pulse',
    'edge_light_settings.xml': 'lockscreen.edgeLight',
    'fingerprint_effects.xml': 'lockscreen.fingerprint',
    'crdroid_settings_button.xml': 'system.buttons',
    'power_menu.xml': 'system.powerMenu',
    'crdroid_settings_navigation.xml': 'system.navigation',
    'crdroid_settings_notifications.xml': 'system.notifications',
    'crdroid_settings_sound.xml': 'volume',
    'gradient_settings.xml': 'theme.gradient',
    'smart_pixels.xml': 'system.smartPixels',
    'monet_engine.xml': 'theme.monet',
    'ichthys_apps.xml': 'system.misc',
    'ichthys_background.xml': 'system.misc',
    'ichthys_install.xml': 'apps.compat',
    'ichthys_system.xml': 'system.ui',
    'charging_control_settings.xml': 'system.chargingControl',
}
# A page that is not in NAMESPACE (a new page of a Settings reorganisation) gets the namespace of
# the first of these words its file name contains, else system.<fileName>. Reported, so the page
# can be added to NAMESPACE; nothing on it is dropped.
NAMESPACE_HINTS = [
    ('status_bar', 'statusBar'), ('statusbar', 'statusBar'),
    ('quick_settings', 'quickSettings'), ('quicksettings', 'quickSettings'),
    ('lock_screen', 'lockscreen'), ('lockscreen', 'lockscreen'),
    ('doze', 'lockscreen.ambient'), ('ambient', 'lockscreen.ambient'),
    ('navigation', 'system.navigation'), ('notification', 'system.notifications'),
    ('power_menu', 'system.powerMenu'), ('button', 'system.buttons'),
    ('sound', 'volume'), ('volume', 'volume'), ('theme', 'theme'),
    ('background', 'background'), ('privacy', 'privacy'),
]
FILE_PREFIXES = ['crdroid_settings_', 'ichthys_', 'look_']

# Paths for keys whose derived path would mislead. Applied to keys the lock doesn't know and,
# through RENAMED, to keys it does.
PATHS = {
    'ichthys_nav_handle_hide': 'system.navigation.handle.hide',
    'ichthys_nav_handle_width': 'system.navigation.handle.width',
    'ichthys_nav_handle_thickness': 'system.navigation.handle.thickness',
    'ichthys_nav_handle_opacity': 'system.navigation.handle.opacity',
    'ichthys_aod_notif_icons': 'lockscreen.ambient.notificationIcons',
    'ichthys_lockscreen_max_notifs': 'lockscreen.maxNotifications',
    'ichthys_lockscreen_hide_statusbar': 'lockscreen.hideStatusBar',
    'ichthys_statusbar_notif_icons': 'statusBar.notificationIcons',
    'ichthys_screenshot_preview_timeout': 'system.ui.screenshotPreviewTimeout',
    'pm_low_target_sdk_allowed': 'apps.compat.allowLowTargetSdk',
    # IchthysOS: the charger-connected animation, on System > Battery and display (it was briefly
    # lockscreen.chargingAnimation on the lock screen page, never in a release).
    'ichthys_charging_animation': 'system.chargingAnimation.style',
    'ichthys_charging_animation_wake': 'system.chargingAnimation.wakeScreen',
    # IchthysOS: "Wake with cover magnet" (PhoneWindowManager lid switch), on System > Battery
    # and display.
    'ichthys_lid_wake_enabled': 'system.coverMagnetWake',
}
# Old path -> new path. The old path stays valid as an alias, so configs written before the
# rename still apply. (statusBar.clockEnabled was the clock *position*: the "option that is also
# a namespace gets Enabled appended" rule produced it.)
RENAMED = {
    'statusBar.clockEnabled': 'statusBar.clock.position',
    'statusBar.batteryBar.batteryBar': 'statusBar.batteryBar.enabled',
    'lockscreen.pulse.pulseEnabled': 'lockscreen.pulse.enabled',
    'lockscreen.pulse.ambientPulseEnabled': 'lockscreen.pulse.ambient',
    'lockscreen.mediaArt.lsMediaArtEnabled': 'lockscreen.mediaArt.enabled',
    'lockscreen.mediaArt.lsMediaArtBlurLevel': 'lockscreen.mediaArt.blurLevel',
    'lockscreen.mediaArt.lsMediaArtFadeLevel': 'lockscreen.mediaArt.fadeLevel',
    'lockscreen.mediaArt.lsMediaArtFilter': 'lockscreen.mediaArt.filter',
    'lockscreen.mediaArt.mediaArtEnabled': 'lockscreen.mediaArt.ambient',
    'system.smartPixels.smartPixelFilterEnabled': 'system.smartPixels.enabled',
    'system.smartPixels.smartPixelFilterPercent': 'system.smartPixels.percent',
    'statusBar.clock.clockSeconds': 'statusBar.clock.seconds',
    'statusBar.clock.clockBold': 'statusBar.clock.bold',
    'statusBar.clock.clockAutoHide': 'statusBar.clock.autoHide',
    'statusBar.clock.clockDateDisplay': 'statusBar.clock.dateDisplay',
    'statusBar.clock.clockDatePosition': 'statusBar.clock.datePosition',
    'statusBar.clock.clockDateStyle': 'statusBar.clock.dateStyle',
    'statusBar.clock.clockPeriodSeparator': 'statusBar.clock.periodSeparator',
}
# Defaults where the settings page disagrees with the code that reads the key. The reader wins:
# with the page's value as the option default, setting the option to "its default" would write
# nothing and leave the reader's (different) value in effect.
DEFAULT_OVERRIDES = {
    # SystemUI NetworkTraffic: parseIntegerSwitch(value, false).
    'network_traffic_autohide': 'false',
    # SystemUI NavigationBarView.NAV_KEY_ORDER_DEFAULT and Launcher3's AbstractNavButtonLayoutter
    # read it with 1 (back on the right).
    'navigationbar_key_order': 'true',
    # LineageSettingsProvider def_advanced_reboot = true; SystemUI GlobalActionsDialogLite and
    # PowerMenuActions read it with 1.
    'advanced_reboot': 'true',
}
# Enum names for values whose label is too long, numeric-only or missing: key -> {value: name}.
ENUM_NAMES = {
    'doze_always_on_auto_mode': {'0': 'off', '1': 'sunsetToSunrise', '2': 'customTime',
                                 '3': 'sunsetToTime', '4': 'timeToSunrise'},
    'qs_quick_pulldown': {'3': 'always'},
    'flashlight_on_call': {'3': 'whenSilent'},
    'pulse_bass_haptics': {'0': 'off', '1': 'whilePulseShows', '2': 'whilePlaying'},
    'network_traffic_units': {'0': 'bits', '1': 'bytes'},
    'ichthys_screen_reveal_style': {'0': 'default', '4': 'circle', '5': 'closeFromSides'},
}
# Enum names that were renamed: key -> {old name: new name}. The old name is still accepted.
# (The "v<value>" placeholders of earlier runs need no entry: Option accepts them for any enum.)
ENUM_OLD_NAMES = {
    'doze_always_on_auto_mode': {'disable': 'off'},
    'pulse_bass_haptics': {'onlyWhenPulseIsShowing': 'whilePulseShows'},
    'network_traffic_units': {'bitsPerSecondBS': 'bits', 'bytesPerSecondBS': 'bytes'},
}
# Descriptions for keys whose title says nothing on its own: key -> text.
DESCRIPTIONS = {
    'doze_always_on_auto_mode': 'Always-on display schedule.',
    'status_bar_clock': 'Status bar clock position.',
}
# Titles that need their page name in front to mean anything.
GENERIC_TITLES = {'enable', 'enabled', 'style', 'position', 'color', 'custom color', 'vibrate',
                  'haptic feedback', 'auto hide', 'alignment', 'thickness'}

# Leading key words dropped when they only repeat the namespace.
STRIP = ['status_bar_', 'statusbar_', 'qs_', 'lockscreen_', 'lock_screen_', 'network_traffic_',
         'battery_bar_', 'statusbar_battery_bar_', 'edge_light_', 'pulse_', 'doze_', 'ambient_',
         'charging_control_', 'smart_pixels_', 'adaptive_playback_', 'power_menu_', 'monet_',
         'udfps_']

STORE_BY_CLASS = [
    ('LineageSystemSetting', 'LINEAGE_SYSTEM'), ('LineageSecureSetting', 'LINEAGE_SECURE'),
    ('LineageGlobalSetting', 'LINEAGE_GLOBAL'), ('DangerGlobal', 'GLOBAL'),
    # IchthysOS: DangerSwitchPreference extends SecureSettingSwitchPreference (red card look).
    ('DangerSwitch', 'SECURE'),
    ('SystemSetting', 'SYSTEM'), ('SecureSetting', 'SECURE'), ('GlobalSetting', 'GLOBAL'),
]
# Preference classes that are navigation, decoration or custom pickers, never a plain setting.
NOT_SETTINGS = {
    'PreferenceCategory', 'Preference', 'PreferenceScreen', 'FooterPreference',
    'LayoutPreference', 'RemotePreference', 'IllustrationPreference', 'TopIntroPreference',
    'StartTimePreference', 'TargetTimePreference', 'FontsPickerPreference',
    'LockClockFontsPreference', 'QSTileIconShapePreference', 'ClockBackgroundChipPreference',
    'StatusBarLogoStylePreference', 'DeviceConfigSwitchPreference',
    'SystemPropertySwitchPreference', 'ButtonBacklightBrightness', 'UdfpsPackPreference',
    'ShoalProtectedPreference',
}


class Fatal(Exception):
    pass


def load_values(*roots):
    """strings/arrays/scalars of each root's res/values; earlier roots win."""
    strings, arrays, scalars = {}, {}, {}
    for root in reversed(roots):
        for f in sorted(glob.glob(os.path.join(root, 'res/values/*.xml'))):
            try:
                t = ET.parse(f)
            except ET.ParseError:
                continue
            for el in t.getroot():
                name = el.get('name')
                if not name:
                    continue
                if el.tag == 'string':
                    strings[name] = ''.join(el.itertext()).strip().strip('"').replace("\\'", "'")
                elif el.tag in ('string-array', 'integer-array', 'array'):
                    arrays[name] = [(''.join(i.itertext()) or '').strip()
                                    for i in el.findall('item')]
                elif el.tag in ('bool', 'integer', 'color', 'dimen'):
                    scalars[name] = (el.text or '').strip()
    return strings, arrays, scalars


def resolve(ref, strings, scalars):
    """The text a resource reference stands for, or None when it can't be resolved."""
    if ref is None:
        return None
    if ref.startswith('@string/'):
        return strings.get(ref[8:])
    for p in ('@bool/', '@integer/', '@color/'):
        if ref.startswith(p):
            return scalars.get(ref[len(p):])
    if ref.startswith('@'):
        return None
    return ref


def camel(words):
    parts = [w for w in re.split(r'[^A-Za-z0-9]+', words or '') if w]
    if not parts:
        return None
    return parts[0].lower() + ''.join(p[:1].upper() + p[1:].lower() for p in parts[1:])


def leaf_name(key, ns):
    k = key[len('ichthys_'):] if key.startswith('ichthys_') else key
    for p in sorted(STRIP, key=len, reverse=True):
        if k.startswith(p) and len(k) > len(p):
            k = k[len(p):]
            break
    # Drop a leading word that repeats the namespace (clock_dot_shape in lockscreen.clock).
    words = k.split('_')
    last = ns.split('.')[-1].lower()
    while len(words) > 1 and words[0].lower() == last:
        words = words[1:]
    return camel('_'.join(words))


def guess_namespace(base):
    stem = base[:-4]
    for word, ns in NAMESPACE_HINTS:
        if word in stem:
            return ns
    for p in FILE_PREFIXES:
        if stem.startswith(p):
            stem = stem[len(p):]
    return 'system.' + (camel(stem) or 'misc')


def find_store_in_src(sources, key):
    """Best effort: find the Settings table a fragment uses for this key."""
    hits = []
    quoted = '"%s"' % key
    for text in sources:
        if quoted not in text:
            continue
        m = re.search(r'(\w+)\s*=\s*"%s"' % re.escape(key), text)
        names = [quoted] + ([m.group(1)] if m else [])
        for line in text.splitlines():
            if any(n in line for n in names):
                mm = re.search(
                    r'(LineageSettings\.(System|Secure|Global)|Settings\.(System|Secure|Global))'
                    r'\.(put|get)(Int|String|Long|Float)', line)
                if mm:
                    table = mm.group(2) or mm.group(3)
                    lineage = mm.group(0).startswith('Lineage')
                    hits.append((('LINEAGE_' if lineage else '') + table.upper(), mm.group(5)))
    return hits[0] if hits else None


def java_str(s):
    return '"' + (s or '').replace('\\', '\\\\').replace('"', '\\"').replace('\n', ' ') + '"'


def color_default(d):
    """The XML default as Option prints a stored colour: "#rrggbb" when opaque, else
    "#aarrggbb". A colour picker stores the default as an int, so 0xFFFFFF (no alpha byte) is
    fully transparent white, "#00ffffff", not "#ffffff"."""
    if d is None or d == '':
        return None
    d = d.strip()
    try:
        if d.startswith('#'):
            h = d[1:]
            if len(h) in (3, 4):
                h = ''.join(c * 2 for c in h)
            if len(h) == 6:
                h = 'ff' + h
            argb = int(h, 16)
        elif d.lower().startswith('0x'):
            argb = int(d, 16)
        else:
            argb = int(d)
    except ValueError:
        return None
    argb &= 0xFFFFFFFF
    return '#%06x' % (argb & 0xFFFFFF) if argb >> 24 == 0xFF else '#%08x' % argb


def enum_names(key, values, labels, notes):
    """One readable, unique name per value; a "v<value>" placeholder only as a reported last
    resort."""
    forced = ENUM_NAMES.get(key, {})
    short = []
    for i, v in enumerate(values):
        lab = labels[i] if i < len(labels) else None
        short.append(camel(re.sub(r'\([^)]*\)', ' ', lab)) if lab else None)
    names, used = [], set()
    for i, v in enumerate(values):
        lab = labels[i] if i < len(labels) else None
        # The whole label first (the name earlier runs gave), then without its parenthesis.
        candidates = [forced.get(v), camel(lab) if lab else None]
        if short[i] and short.count(short[i]) == 1:
            candidates.append(short[i])
        if not re.match(r'^-?\d+$', v):
            candidates.append(camel(v))
        name = next((c for c in candidates if c and c not in used and len(c) <= 28), None)
        if name is None:
            name = 'v' + v.replace('-', 'Minus')
            while name in used:
                name += '_'
        used.add(name)
        names.append(name)
    return list(zip(names, values))


FIELDS = ('path', 'store', 'key', 'type', 'default', 'min', 'max', 'enum', 'desc', 'page')


def read_lock():
    """{(store, key): definition}; a definition has FIELDS."""
    lock = {}
    if os.path.exists(LOCK):
        with open(LOCK, encoding='utf-8') as fh:
            for line in fh:
                line = line.strip()
                if not line or line.startswith('#'):
                    continue
                o = json.loads(line)
                lock[(o['store'], o['key'])] = o
    return lock


def write_lock(lock):
    with open(LOCK, 'w', encoding='utf-8') as fh:
        fh.write('# Every crDroidSettings option ever generated, one JSON object per line:\n'
                 '# its path and the definition it had when a settings page last showed it.\n'
                 '# Maintained by gen_crdroid_options.py; see its docstring. To rename an\n'
                 '# option, add it to RENAMED in the generator; to drop a dead key, to RETIRED.\n')
        for o in sorted(lock.values(), key=lambda o: (o['path'], o['store'], o['key'])):
            fh.write(json.dumps({f: o.get(f) for f in FIELDS}, ensure_ascii=False,
                                sort_keys=False) + '\n')


def reserved_bindings(out):
    """(store, key) bound by the hand-written options, and every key they mention."""
    bound, keys = set(), set()
    d = os.path.dirname(os.path.abspath(out))
    # IchthysOS: without IchthysOptions.java beside the output, every option it binds came out a
    # second time (generating into a scratch dir did that) and IchthysConfigService refused the
    # schema on every boot. So it must be there.
    if not os.path.exists(os.path.join(d, 'IchthysOptions.java')):
        sys.exit('gen_crdroid_options: no IchthysOptions.java next to %s; write the output into '
                 'core/java/com/android/internal/ichthys/config/ (or a copy of that directory)'
                 % out)
    for name in ('IchthysOptions.java', 'IchthysShields.java'):
        f = os.path.join(d, name)
        if not os.path.exists(f):
            continue
        text = open(f, encoding='utf-8').read()
        for store, key in re.findall(r'setting\(\s*(?:Binding\.Store\.)?(\w+),\s*"([^"]+)"\)',
                                     text):
            bound.add((store, key))
            keys.add(key)
        if name == 'IchthysShields.java':
            # The shield registry: literal keys, and the two keys of every feature(...) shield.
            keys.update(re.findall(r'"(ichthys_[a-z0-9_]+)"', text))
            for feature in re.findall(r'feature\("\w+",\s*"(\w+)"', text):
                keys.add('ichthys_%s_shield' % feature)
                keys.add('ichthys_%s_allowed_pkgs' % feature)
    return bound, keys


def scan(root, lock, hand_bound, hand_keys, report, notes):
    """The definitions the settings pages give today: {(store, key): definition}."""
    # The crDroid pages also use strings and arrays of the Settings app they are built into.
    strings, arrays, scalars = load_values(root, os.path.dirname(os.path.abspath(root)))
    sources = []
    for f in glob.glob(os.path.join(root, 'src/**/*.java'), recursive=True) + \
            glob.glob(os.path.join(root, 'src/**/*.kt'), recursive=True):
        try:
            sources.append(open(f, encoding='utf-8', errors='ignore').read())
        except OSError:
            pass
    known_keys = set(key for _, key in lock)
    found = {}
    for f in sorted(glob.glob(os.path.join(root, 'res/xml/*.xml'))):
        base = os.path.basename(f)
        try:
            tree = ET.parse(f)
        except ET.ParseError as e:
            # A page being edited right now: say so; its options keep their last definition.
            notes.append('%s does not parse (%s)' % (base, e))
            continue
        if base in SKIP_FILES:
            report.append('%s page skipped: %s' % (base, SKIP_FILES[base]))
        page = resolve(tree.getroot().get(A + 'title'), strings, scalars)
        for el in tree.iter():
            key = el.get(A + 'key')
            if not key:
                continue
            cls = el.tag.split('.')[-1]
            if cls in NOT_SETTINGS:
                continue
            if base in SKIP_FILES and key not in known_keys:
                continue
            if key in SKIP_KEYS:
                report.append('%s:%s skipped: %s' % (base, key, SKIP_KEYS[key]))
                continue
            if key in RETIRED:
                notes.append('%s:%s is in RETIRED but %s still shows it' % (base, key, base))
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
                if key in hand_keys:
                    report.append('%s:%s hand-written in IchthysOptions' % (base, key))
                    continue
                hit = find_store_in_src(sources, key)
                if hit is None:
                    report.append('%s:%s (%s) NOT AN OPTION: no settings table found for it'
                                  % (base, key, cls))
                    continue
                store, kind = hit
            if (store, key) in hand_bound or key in hand_keys:
                report.append('%s:%s hand-written in IchthysOptions' % (base, key))
                continue
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
                report.append('%s:%s (%s) NOT AN OPTION: unknown preference type'
                              % (base, key, cls))
                continue

            title_ref = el.get(A + 'title')
            title = resolve(title_ref, strings, scalars)
            if title:
                desc = title
                if title.strip().lower() in GENERIC_TITLES and page:
                    desc = '%s: %s' % (page, title[:1].lower() + title[1:])
            else:
                desc = None
            o = {'type': typ, 'store': store, 'key': key, 'desc': desc, 'page': base,
                 'default': resolve(el.get(A + 'defaultValue'), strings, scalars),
                 'min': None, 'max': None, 'enum': None}
            if typ == 'INT':
                o['min'] = el.get(S + 'min') or el.get(SA + 'min') or el.get(A + 'min') or '0'
                o['max'] = el.get(A + 'max') or '100'
            if typ == 'ENUM':
                ev = el.get(A + 'entryValues')
                en = el.get(A + 'entries')
                values = arrays.get(ev[7:], []) if ev and ev.startswith('@array/') else []
                labels = arrays.get(en[7:], []) if en and en.startswith('@array/') else []
                if not values:
                    report.append('%s:%s NOT AN OPTION: list without entryValues' % (base, key))
                    continue
                labels = [resolve(lab, strings, scalars) for lab in labels]
                if 0 < len(labels) < len(values):
                    # A value without an entry can't be picked in Settings either.
                    notes.append('%s: values %s have no entry and are left out of the enum'
                                 % (key, ', '.join(values[len(labels):])))
                    values = values[:len(labels)]
                o['enum'] = [list(e) for e in enum_names(key, values, labels, notes)]

            prev = found.get((store, key))
            if prev is not None:
                # The same key on two pages is one option. The pages must agree about it.
                if prev['default'] is None and prev['type'] == typ:
                    prev['default'] = o['default']  # only one of the pages states the default
                elif o['default'] is not None and \
                        (prev['type'], prev['default']) != (typ, o['default']):
                    notes.append('%s is %s default %r on %s but %s default %r on %s: using the '
                                 'first' % (key, prev['type'], prev['default'], prev['page'],
                                            typ, o['default'], base))
                continue
            found[(store, key)] = o
    return found


def finish(o, notes):
    """Applies the tables above to a definition (fresh or from the lock) for the output."""
    key = o['key']
    o = dict(o)
    if key in DEFAULT_OVERRIDES:
        o['default'] = DEFAULT_OVERRIDES[key]
    if key in DESCRIPTIONS:
        o['desc'] = DESCRIPTIONS[key]
    elif not o.get('desc'):
        o['desc'] = key.replace('_', ' ').capitalize()
        notes.append('no title text for %s: described by its key; add it to DESCRIPTIONS' % key)
    if o['type'] == 'ENUM':
        forced = ENUM_NAMES.get(key, {})
        names = [forced.get(v, n) for n, v in o['enum']]
        if len(set(names)) != len(names):
            raise Fatal('%s: enum names %s are not unique' % (key, names))
        o['enum'] = [[n, v] for n, (_, v) in zip(names, o['enum'])]
    return o


def main(root, out):
    lock = read_lock()
    hand_bound, hand_keys = reserved_bindings(out)
    report = []     # keys that did not become an option, with the reason
    notes = []      # quality problems worth fixing
    found = scan(root, lock, hand_bound, hand_keys, report, notes)

    # Paths: the lock's for a key it knows, else PATHS, else the page's namespace.
    guessed = {}
    for ident, o in sorted(found.items()):
        if ident in lock:
            o['path'] = lock[ident]['path']
        elif o['key'] in PATHS:
            o['path'] = PATHS[o['key']]
        else:
            ns = NAMESPACE.get(o['page'])
            if ns is None:
                ns = guessed.setdefault(o['page'], guess_namespace(o['page']))
            o['path'] = ns + '.' + leaf_name(o['key'], ns)
    for base, ns in sorted(guessed.items()):
        notes.append('%s is not in NAMESPACE: its new keys went to %s.*' % (base, ns))

    # Options no page shows any more keep their last definition, unless they became
    # hand-written (then they leave the lock) or are RETIRED (then they become "removed").
    kept = []
    for ident, o in sorted(lock.items()):
        if ident in found:
            continue
        if ident in hand_bound or o['key'] in hand_keys or o['key'] in SKIP_KEYS:
            report.append('%s (%s) left the lock: hand-written or skipped now'
                          % (o['path'], o['key']))
            continue
        found[ident] = dict(o)
        if o['key'] not in RETIRED:
            kept.append(o)

    opts, removed = {}, {}
    for ident, o in sorted(found.items()):
        o['path'] = RENAMED.get(o['path'], o['path'])
        target = removed if o['key'] in RETIRED else opts
        other = opts.get(o['path']) or removed.get(o['path'])
        if other is not None:
            raise Fatal('path collision: %s is both %s (%s) and %s (%s); give one of them a '
                        'path in PATHS' % (o['path'], other['key'], other['page'], o['key'],
                                           o['page']))
        target[o['path']] = o

    # A path can't be both an option and a namespace (statusBar.clock vs statusBar.clock.seconds).
    hand_paths = set()
    hand = os.path.join(os.path.dirname(os.path.abspath(out)), 'IchthysOptions.java')
    if os.path.exists(hand):
        hand_paths = set(re.findall(r'(?:Option\.\w+|alias|removed)\(\s*"([A-Za-z0-9_.]+)"',
                                    open(hand, encoding='utf-8').read()))
    every = sorted(set(opts) | set(removed) | hand_paths | set(RENAMED))
    for p in list(opts) + list(removed):
        if p in hand_paths:
            raise Fatal('path collision: %s is generated and hand-written in IchthysOptions' % p)
        clash = [q for q in every if q.startswith(p + '.') or p.startswith(q + '.')]
        if clash:
            raise Fatal('%s is an option and %s is under it or above it; give one of them '
                        'another path in PATHS' % (p, clash[0]))

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
        o = finish(opts[p], notes)
        b = 'Binding.setting(Binding.Store.%s, %s)' % (o['store'], java_str(o['key']))
        d = o['default']
        if o['type'] == 'BOOL':
            # No default on the page: unknown, the reader decides (null), not "false".
            dv = 'null' if d is None else (
                'Boolean.TRUE' if d.lower() in ('true', '1') else 'Boolean.FALSE')
            w('        s.add(Option.bool(%s, %s, %s, %s));' % (java_str(p), dv, java_str(o['desc']), b))
        elif o['type'] == 'INT':
            try:
                dv = '%dL' % int(float(d)) if d not in (None, '') else 'null'
            except ValueError:
                dv = 'null'
            try:
                lo, hi = int(float(o['min'])), int(float(o['max']))
            except (TypeError, ValueError):
                lo, hi = 0, 0  # unresolved bound: no range check
            w('        s.add(Option.integer(%s, %s, %dL, %dL, %s, %s));' % (
                java_str(p), dv, lo, hi, java_str(o['desc']), b))
        elif o['type'] == 'COLOR':
            dc = color_default(d)
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
            current = set(n for n, _ in o['enum'])
            old = ''.join('.enumAlias(%s, %s)' % (java_str(a), java_str(n))
                          for a, n in sorted(ENUM_OLD_NAMES.get(o['key'], {}).items())
                          if n in current and a not in current)
            for n, v in o['enum']:
                if re.match(r'^v(Minus)?\d+_*$', n):
                    notes.append('placeholder enum name %s for %s=%s: add it to ENUM_NAMES'
                                 % (n, o['key'], v))
            w('        s.add(Option.enumeration(%s, new String[] {%s}, new String[] {%s}, %s, %s, %s)%s);'
              % (java_str(p), names, values, java_str(dname) if dname else 'null',
                 java_str(o['desc']), b, old))
    aliases = [(old, new) for old, new in sorted(RENAMED.items()) if new in opts or new in removed]
    if aliases:
        w('')
        w('        // Renamed options: the old path still works.')
        for old, new in aliases:
            if new in opts:
                w('        s.alias(%s, %s);' % (java_str(old), java_str(new)))
            else:
                w('        s.removed(%s, %s);' % (java_str(old),
                                                  java_str(RETIRED[removed[new]['key']])))
    if removed:
        w('')
        w('        // Retired: no code reads these keys any more. Old configs and generations may')
        w('        // still mention them; that is a note, never an error.')
        for p in sorted(removed):
            w('        s.removed(%s, %s);' % (java_str(p), java_str(RETIRED[removed[p]['key']])))
    w('    }')
    w('}')
    w('')
    with open(out, 'w') as fh:
        fh.write('\n'.join(lines))
    write_lock(dict(((o['store'], o['key']), o) for o in found.values()))

    err = sys.stderr.write
    err('%d options (%d of them on no page any more), %d retired, %d not generated, %d notes\n'
        % (len(opts), len(kept), len(removed), len(report), len(notes)))
    for o in sorted(kept, key=lambda o: o['path']):
        err('  KEPT %s (%s, last on %s): no page shows it any more; add the key to RETIRED if '
            'nothing reads it\n' % (RENAMED.get(o['path'], o['path']), o['key'], o['page']))
    for s in report:
        err('  ' + s + '\n')
    for s in sorted(set(notes)):
        err('  NOTE ' + s + '\n')


if __name__ == '__main__':
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    try:
        main(sys.argv[1], sys.argv[2])
    except Fatal as e:
        sys.exit('gen_crdroid_options.py: ' + str(e) + '\nnothing written')
