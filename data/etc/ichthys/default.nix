# IchthysOS baseline: what a new user starts with.
#
# Applied as generation 1 of every new user at first boot (generation 0 is the untouched state
# before it), with only the options below written, each one explicitly. It is also what the
# option defaults are: "reset to default" and "never set" give this look, and IchthysConfigTest
# fails if this file, its preset and level, and the defaults in IchthysOptions.java drift apart.
#
# To change what a fresh install looks like, change it here (or in presets/koi.nix and
# privacy/normal.nix) together with the option default.
{
  # The koi look: ink ground, hairline surfaces, koi-orange accent, Syne / Instrument Sans /
  # JetBrains Mono. All theme.* values come from presets/koi.nix, so the default theme and the
  # "koi" entry of Theme Studio can't differ.
  theme.preset = "koi";

  statusBar = {
    # Two-row lattice of circles instead of the stock single row: the Shoal status bar.
    layout = "zigzag";

    # Seconds in the status bar clock. Costs a redraw per second while the screen is on, which
    # is the price of a clock that shows the phone is alive.
    clock.seconds = true;

    notificationDot = {
      enabled = true;
      aod = true;             # also on the always-on display, dimmed
      color = "accent";
      # The dot says "something is new"; the icons still say what. Hiding them is a choice.
      replaceIcons = false;
    };

    privacyIndicator = {
      # A ring around the front camera cutout while camera, microphone or location is in use.
      # Displays without a cutout fall back to the stock corner dot on their own.
      style = "cutoutRing";
      colorByType = false;    # one colour: the ring is a warning light, not a legend
      # ringWidth is left unset on purpose: the ring is then sized for the display's cutout.
    };
  };

  # Honeycomb of circles instead of the tile grid: the Shoal quick settings.
  quickSettings.style = "shoal";

  # Outlined circles with an accent home key (three-button navigation). Gesture navigation is
  # unaffected.
  navBar.style = "shoal";

  # Every privacy shield on: privacy/normal.nix. The per-app lists start empty and are never part
  # of a level; apps get real data only when the user says so.
  privacy.level = "normal";

  # Don't kill cached apps every time the screen turns on: the app you return to would start
  # cold. The switch stays in Settings for devices that are short of memory.
  system.misc.screenOnMemoryReclaim = false;
}
