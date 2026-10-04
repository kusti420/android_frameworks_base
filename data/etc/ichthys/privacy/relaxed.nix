# IchthysOS privacy level "relaxed": the shields that cost nothing stay, the ones that ask or
# degrade go.
#
# Kept: everything where a shielded app simply sees an empty phone (contacts, calendar, call log,
# texts, phone number) and the clipboard guard. An app you trust is one tap in Ichthys Guard.
# Off: the shields you notice in daily use.
{
  privacy = {
    shields = {
      contacts.enable = true;
      calendar.enable = true;
      callLog.enable = true;
      smsRead.enable = true;
      phoneNumber.enable = true;
      clipboard.enable = true;

      # Writes that report success but are not saved confuse more than they protect once the
      # data itself is shielded.
      contactsWrite.enable = false;
      calendarWrite.enable = false;
      callLogWrite.enable = false;
      # No confirmation for every text and call an app starts: stock behaviour.
      smsSend.enable = false;
      call.enable = false;
      answerCalls.enable = false;
      mmsPush.enable = false;
      # Exact sensors for every app: compasses, step counters and games work without an
      # exemption. This is the one that makes the phone fingerprintable again.
      sensors.enable = false;
    };
    clipboardClearMinutes = 0;  # stock: the clipboard is cleared after an hour
    networkDecoy = false;
  };
}
