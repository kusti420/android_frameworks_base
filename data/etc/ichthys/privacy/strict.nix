# IchthysOS privacy level "strict": every shield on, and what "normal" trades for convenience.
#
# The shields are already all on at "normal"; strict tightens what has a dial. It does not empty
# the per-app lists (a level never touches them): review them in Ichthys Guard.
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
    # A copied password is gone after a minute instead of five.
    clipboardClearMinutes = 1;
    networkDecoy = false;
  };
}
