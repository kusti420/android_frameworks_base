# IchthysOS privacy level "normal" (default): every shield on.
#
# A level only sets switches. The per-app lists (privacy.shields.<name>.allow / .protect) are the
# user's own decisions and are never changed by a level. Shields without a switch (usageHistory,
# bleAdvertise, wifiControl, uwb) are always on; scans, location and nfc apply to the apps listed
# in their "protect" list.
{
  privacy = {
    shields = {
      contacts.enable = true;
      contactsWrite.enable = true;
      calendar.enable = true;
      calendarWrite.enable = true;
      callLog.enable = true;
      callLogWrite.enable = true;
      smsRead.enable = true;
      smsSend.enable = true;
      mmsPush.enable = true;
      phoneNumber.enable = true;
      call.enable = true;
      answerCalls.enable = true;
      clipboard.enable = true;
      sensors.enable = true;
    };
    clipboardClearMinutes = 5;
    # Decoy mDNS services are a statement, not a protection: off at every level.
    networkDecoy = false;
  };
}
