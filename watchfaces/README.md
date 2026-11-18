# Watchfaces for Ollee Forge

## Simple Notifications
Displays the last recieved message, along with a notification tone and led flash. The BLE connection runs in the background and will beep at you from the main watchface, too.

### Functionality
- The message text is cleared on enter() if the recieve time is an hour or more.
- L button toggles the LED flash when a tone plays
- A button toggles notification plays
- Listens for three BLE messages:
  1) Change/Set beep pattern 
    - `"OWT|"<pattern number><encoded pattern string>` example: `"OWT|2ggA"`
    - Prefix `"OWT|"` indicates a tone update
    - `"2"` is the pattern number, 0-4
    - The remaining bytes are the encoded tone pattern
      - A lowercase letter is `Ollee.beep()`, an uppercase is `Ollee.beepShort()`
      - The letter itself is the gap, in milliseconds to pause after the beep in 50ms increments, where a = 0ms and z = 1250ms
    - So `"OWT|2ggA"` would set `tones[2] = [[0,300],[0,300],[1,0]]`
2) Test a beep pattern
    - `"OWA|"<encoded pattern string>` example: `"OWA|ggA"`
    - Plays the pattern once without storing it (sent by **Test on watch** in the app's tone editor)
3) Notification pattern 
  - `"OW"<pattern number><pattern play times><message string>` example: `"OW00Hello"`
  - Prefix `"OW"` indicates a new message
  - The first digit is the tone pattern to play, 0-4
  - The second digit is how many times to play the tone, up to 9 times. 0 and 1 both play the tone once.
  - The remainder of the message is accepted as-is and displayed on the watchface. The last recieved message will always display.

  ## Little Battery
  Just a small face to display the current reported voltage, rounded to the tenth, in the Day display, and a rough percentage in the Main display. It considers 2.7V "critical" and reports 10%.
