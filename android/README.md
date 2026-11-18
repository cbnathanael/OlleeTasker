# Ollee Watch for Tasker

A Tasker plugin that talks to an [Ollee watch](https://github.com/olleewatch/forge_emulator) over the
Forge Bluetooth LE service. Phone → watch writes arrive at the watch face's `ble_rx(data)` handler,
and watch → phone `Ollee.send(...)` messages become Tasker events.

## Setup

1. Install the app and open **Ollee Watch for Tasker**. A first-time setup wizard walks through:
   - granting Bluetooth and notification permissions,
   - turning off battery optimization (so the link can stay connected and reconnect in the background),
   - choosing the watch (paired devices plus a live BLE scan, with Ollee/Forge devices listed first),
   - a summary of how to use the plugin in Tasker.
   Any step can be skipped and finished later.
2. The main screen shows the link status with **Connect**, **Pause** and **Disconnect**, and flags anything
   that still needs fixing. The notification has Pause/Resume and Disconnect buttons too.
3. **Settings** (gear icon) lets you change the watch, fix permissions or battery optimization, and
   **Run setup again**.

The link restarts after a reboot or app update if it was left enabled. If the watch goes out of range,
the plugin retries after 1 s, 10 s and 30 s, then waits for Android to reconnect when the watch reappears.

To turn this off, uncheck **Auto-reconnect** under the Connect and Pause buttons on the main screen. The link then stops, as if you'd chosen **Disconnect**,
whenever the watch disconnects, a connection attempt fails, or the phone reboots. Nothing reconnects until you run
**Connect** (in the app or with the *Ollee: Connection* action), and sends fail while it's stopped.

### Low-power connection

**Settings → Request low power** works like the Gadgetbridge option of the same name. After each connection the plugin asks
Android for a low-power connection (`CONNECTION_PRIORITY_LOW_POWER`: roughly a 100–125 ms connection interval with
slave latency 2) instead of the balanced default. The watch's radio wakes less often, which saves battery, but messages
can take up to about half a second longer. It's a request: the watch can refuse it or choose its own parameters.
Changing it applies right away if connected. It's off by default; if connections become unreliable, turn it off.

## Sharing the watch with the Ollee app

Only one app can hold the watch connection at a time. Have Tasker hand it over to the Ollee app:

- **Profile:** Application → *Ollee*
- **Enter task:** Plugin → Ollee: Connection → *Pause link (Ollee app in use)*
- **Exit task:** Plugin → Ollee: Connection → *Resume link*

Pausing closes the connection fully. The paused state survives reboots, and **Resume** reconnects.
Actions that need the watch fail with a clear error while paused.

## Actions: Task → Plugin → Ollee Watch for Tasker

The plugin adds three actions:

| Action                            | Operations                                                                                                                                                                                                               | Outputs                          |
|-----------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------|
| **Ollee: Send message**           | Writes *prefix + notification number + repeat count + message* to the Forge RX characteristic in one write (connects first if needed). See [Message format](#message-format). To react to messages from the watch, use the event below. | `%ollee_state`                   |
| **Ollee: Send notification tone** | Sends a saved tone (by name or %variable).                                                                                                                                                                               | `%ollee_state`                   |
| **Ollee: Connection**             | *Connect and stay connected* (saves the watch if an address is given), *Disconnect and stop*, *Pause link* / *Resume link* (see above), *Get link status* (`stopped`, `paused`, `waiting`, `connecting` or `connected`). | `%ollee_state`, `%ollee_address` |

Actions created before the split keep working and open in the matching editor.

The watch address can be blank (use the saved watch), a MAC address, or a Tasker variable.

### Message format

- **Text**: one byte per character, which is how `ble_rx` reads data. Only characters with codes 0–255
  (Latin-1) can be sent.
- **UTF-8 text**: for watch code that decodes UTF-8 itself.
- **Hex**: raw bytes, e.g. `01 FF 2A`.

Each Send message action is **one write of at most 20 bytes**, arriving as a single `ble_rx` call:

```
<prefix><notification number><repeat count><message>      e.g.  OW23Hello
```

- **Prefix**: text or a %variable; blank means `OW`.
- **Notification number**: one character, `0`–`4` for the tone in that slot (see below), or `-` for no tone.
  It can also be a %variable that resolves to `0`–`4`, blank, or `-`.
- **Repeat count**: one digit, `0`–`9`, the number of times to repeat the notification chime; blank means `0`.
  It can also be a %variable that resolves to `0`–`9` or blank. Always sent, even with no tone.
- **Message**: the rest. Anything past 20 bytes is silently dropped (UTF-8 text is cut at a character boundary).

Tick **Send the message only** to skip the prefix, notification number and repeat count and send the message exactly as
entered (still one write, cut at 20 bytes).

The editor shows a live preview of exactly what will be sent.

## Notification tones

On the main screen, **Notification tones** lists the five beep patterns, numbered 0–4. Tap one to edit its
name and beeps. Each beep has a style (0 or 1) and a gap in ms before the next beep (0–1250 ms, rounded to 50 ms).
A tone can have up to 15 beeps. **Reset to default** (in the editor's menu) restores the factory tone for that
number. The defaults, named *Tone 0*–*Tone 4*, are (after rounding to 50 ms):

| # | Pattern                                        | Sent as       |
|---|------------------------------------------------|---------------|
| 0 | `[[0,300],[1,50],[1,0]]`                       | `OWT\|0gBA`   |
| 1 | `[[1,800],[0,50],[0,50],[0,50],[0,0]]`         | `OWT\|1Qbbba` |
| 2 | `[[1,100],[1,100],[1,100],[1,0]]`              | `OWT\|2CCCA`  |
| 3 | `[[0,50],[1,800],[0,150],[0,150],[0,0]]`       | `OWT\|3bQdda` |
| 4 | `[[0,50],[1,500],[1,50],[0,0]]`                | `OWT\|4bKBa`  |

**Save** stores the tone in the app and sends it to the watch (`OWT|…`), then shows whether the send worked. The
tone stays saved even if the watch can't be reached. The Tasker action **Send notification tone** (tone name or
%variable) also stores a tone on the watch. Tones are looked up by name when the action runs, so editing a tone
updates every task using it.

A tone is sent as a single message: `OWT|`, the tone's number (0–4), then one letter per beep. Lowercase means
style 0 and uppercase means style 1. The letter's position gives the gap: `a` = 0 ms, `b` = 50 ms, … `z` = 1250 ms.
For example, `[[0,300],[0,300],[1,0]]` saved as tone 2 is sent as `OWT|2ggA`. The watch can store each pattern by
its number, so a later **Send message** such as `OW23Hello` plays tone 2 and repeats it 3 times.

**Test on watch** in the editor sends `OWA|` plus the letters (e.g. `OWA|ggA`) for the pattern as currently edited.
The watch plays it once without storing it, and nothing is saved in the app.

Decoder for the watch face:

```js
var tones = [];                       // patterns by number, 0-4

function decodeTone(s) {              // s = "ggA"
  var p = [];
  for (var i = 0; i < s.length; i++) {
    var c = s.charCodeAt(i);
    if (c >= 97 && c <= 122) p.push([0, (c - 97) * 50]);      // a-z: style 0
    else if (c >= 65 && c <= 90) p.push([1, (c - 65) * 50]);  // A-Z: style 1
  }
  return p;
}

function ble_rx(data) {
  if (data.substr(0, 4) == "OWT|") {                  // "OWT|2ggA": store tone 2
    tones[+data[4]] = decodeTone(data.substr(5));
  } else if (data.substr(0, 4) == "OWA|") {           // "OWA|ggA": play once, don't store
    playPattern(decodeTone(data.substr(4)));
  } else if (data.substr(0, 2) == "OW") {             // "OW23Hello": tone 2, repeat 3 times, text "Hello"
    var n = data[2], repeats = +data[3];
    if (n != "-" && tones[+n]) {
      for (var i = 0; i <= repeats; i++) playPattern(tones[+n]); // your existing pattern player
    }
    showText(data.substr(4));
  }
}
```

## Event: Profile → Event → Plugin → Ollee Watch Event

Choose *Message received from watch* (optionally filtered by exact text, prefix, substring or regex),
*Watch connected*, or *Watch disconnected*. The link must be running to receive events.

Variables: `%ollee_event`, `%ollee_address`, `%ollee_message` (text), `%ollee_hex`, `%ollee_reason`
(for disconnects).

## Caveats

- A successful write means the watch's Bluetooth stack received the bytes. The watch face only gets
  them while it is on screen and subscribed to `E_BLE_RX`, or after it calls `Ollee.subscribe(E_BLE_RX)`.
- `Ollee.send` messages arrive as 20-byte notifications. The plugin treats a notification shorter than
  20 bytes, or a 500 ms pause, as the end of a message. Two messages sent back-to-back where the first
  is an exact multiple of 20 bytes may be merged.
- The RX/TX characteristic UUIDs (`c6fb0002-…`, `c6fb0003-…`) are inferred from the documented Forge
  service UUID `c6fb0001-a37d-40ac-89be-887d25e1f5b3`. The plugin also matches characteristics by the
  first 32 bits of the UUID.
- Tasker may throttle very rapid events.
- Not yet tested on real Ollee hardware.

## Building

```
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. Requires JDK 17+ and Android SDK 35.
