// Simple Notification
// Displays the last recieved message, along with a notification tone and led flash.
// L button toggles the LED flash when a tone plays
// A button toggles notification plays
// Listens for two BLE messages:
// 1) Change/Set beep pattern: "OWT|"<pattern number><encoded pattern string> example: "OWT|2ggA"
//    Prefix "OWT|" indicates a tone update
//    "2" is the pattern number, 0-4
//    The remaining bytes are the encoded tone pattern:
//      A lowercase letter is Ollee.beep(), an uppercase is Ollee.beepShort()
//      The letter itself is the gap, in milliseconds to pause after the beep in 50ms increments, where a = 0ms and z = 1250ms
//  So "OWT|2ggA" would set tones[2] = [[0,300],[0,300],[1,0]]
// 2) Notification pattern: "OW"<pattern number><pattern play times><message string> example: "OW00Hello"
//    Prefix "OW" indicates a new message
//    The first digit is the tone pattern to play
//    The second digit is how many times to play the tone, up to 9 times. 0 and 1 both play the tone once.
//    The remainder of the message is accepted as-is and displayed on the watchface. The last recieved message will always display.
var tones = [
  // patterns by number, 0-4
  [
    [0, 300],
    [1, 70],
    [1, 0],
  ],
  [
    [1, 800],
    [0, 40],
    [0, 40],
    [0, 40],
    [0, 0],
  ],
  [
    [1, 100],
    [1, 100],
    [1, 100],
    [1, 0],
  ],
  [
    [0, 50],
    [1, 800],
    [0, 125],
    [0, 125],
    [0, 0],
  ],
  [
    [0, 50],
    [1, 500],
    [1, 70],
    [0, 0],
  ],
];

// Runtime subscription bitmask.
var subscribe = E_BUTTONS + E_BLE_RX + E_BLE_CONNECT + E_BLE_DISCONNECT;

var SCROLL_MS = 350; // per character, as on the stock databank face
var WIDTH = 6; // clock digits
var off = 0; // scroll position in the entry's text
var scrollId = -1;
var IDLE_MS = 10000;
var NOTE_ON_MS = 80;
var PHASE_IDLE = 0;
var PHASE_PLAY = 2;
var PHASE_GAP = 3;

var locked = true;
var timer = -1;
var idleAt = 0;

var ledOn = false;
var noteLightEndAt = 0;

var pattern = null;
var phase = PHASE_IDLE;
var notePos = 0;
var repeatPos = 0;
var soundAt = 0;

var playTone = true;
var useLed = true;
var lastMsg = "";

function now() {
  return new Date().getTime();
}

function clearTimer() {
  if (timer !== -1) Ollee.clearTimeout(timer);
  timer = -1;
}

function stopScroll() {
  if (scrollId !== -1) Ollee.clearInterval(scrollId);
  scrollId = -1;
}

// 6 characters of text + ' ' from `off`, wrapping, so the end and the start
// are one space apart as they go by
function windowAt(t, o) {
  var loop = t + " ";
  var w = "";
  for (var k = 0; k < WIDTH; k++) w += loop.charAt((o + k) % loop.length);
  return w;
}
function scrollStep() {
  var t = lastMsg;
  // The entry was cleared or shortened by the phone mid-scroll
  if (t.length <= WIDTH) {
    off = 0;
    stopScroll();
    render();
    return;
  }
  off = (off + 1) % ((t.length + 1)*5);
  render();
  // One full pass done: stop at the start, so the timer only runs while
  // there's something moving
  if (off === 0) stopScroll();
}

function startScroll() {
  stopScroll();
  if (lastMsg.length > WIDTH)
    scrollId = Ollee.setInterval(scrollStep, SCROLL_MS);
}
function updateLamp() {
  if (!useLed) {
    Ollee.led_off();
    return;
  }
  var t = now();
  var wanted = noteLightEndAt > t;

  if (wanted === ledOn) return;
  ledOn = wanted;

  if (wanted) Ollee.led_on();
  else Ollee.led_off();
}

function render() {
  if (!Ollee.active()) return;
  Ollee.drawLap(Ollee.connected());
  Ollee.drawPM(playTone);
  Ollee.drawHR(useLed);
  if (lastMsg === '') Ollee.drawMain('--');
  // Padded on the right so short text starts at the left digit
  else Ollee.drawMain(lastMsg.length <= WIDTH ? (lastMsg + '      ').slice(0, WIDTH) : windowAt(lastMsg, off));
}

function stopSound() {
  pattern = null;
  phase = PHASE_IDLE;
  soundAt = 0;
  noteLightEndAt = 0;
  updateLamp();
}

function patternDuration(notes) {
  var total = 0;
  for (var i = 0; i < notes.length; i++) {
    total += NOTE_ON_MS + (i < notes.length - 1 ? notes[i][1] : 0);
  }
  return total;
}

function notify(index, repeat) {
  stopSound();
  if (!playTone) return;

  var p = tones[index];
  if (!p) return;

  pattern = { notes: p, repeats: repeat, repeatGap: 1500 };
  notePos = 0;
  repeatPos = 0;
  phase = PHASE_PLAY;

  var endAt =
    (soundAt = now()) +
    patternDuration(pattern.notes) * pattern.repeats +
    pattern.repeatGap * (pattern.repeats - 1);

  return endAt;
}

function playDue() {
  if (!pattern || soundAt > now()) return;

  var late = now() - soundAt;
  soundAt += late;

  if (noteLightEndAt) noteLightEndAt += late;

  var p = pattern;

  if (phase === PHASE_GAP) {
    noteLightEndAt = 0;
    updateLamp();

    repeatPos += 1;
    if (repeatPos >= p.repeats) {
      stopSound();
      return;
    }

    phase = PHASE_PLAY;
    notePos = 0;
    soundAt += p.repeatGap;
    if (soundAt > now()) return;
  }

  var notes = p.notes;

  if (notePos === 0) {
    noteLightEndAt = soundAt + patternDuration(notes);
    updateLamp();
  }

  var note = notes[notePos];
  if (note[0] === 0) Ollee.beep();
  else Ollee.beepShort();

  soundAt += NOTE_ON_MS;
  notePos += 1;

  if (notePos < notes.length) soundAt += note[1];
  else phase = PHASE_GAP;
}

function pump() {
  clearTimer();
  playDue();
  updateLamp();
  render();
  wake();
}

function wake() {
  clearTimer();

  var t = now();
  var next = 0;

  function due(at) {
    if (at > 0 && (!next || at < next)) next = at;
  }

  if (pattern) due(soundAt);
  if (noteLightEndAt > t) due(noteLightEndAt);

  if (!next) return;

  timer = Ollee.setTimeout(pump, Math.max(1, next - t));

  if (timer === -1) {
    Ollee.led_off();
    ledOn = false;
  }
}

function reset() {
  clearTimer();
  stopSound();

  Ollee.led_off();
  ledOn = false;
}

function init() {
  Ollee.subscribe(E_BLE_RX + E_BLE_CONNECT + E_BLE_DISCONNECT);
}

function enter() {
  reset();
  locked = false;
  idleAt = now() + IDLE_MS;
  Ollee.clear();
  wake();
  render();
  startScroll();
}

function exit() {
  reset();
  locked = true;
}

function button(btn, event, _millis) {
  if (event !== E_DOWN) return;

  if (btn === B_A) {
    playTone = !playTone;
    render();
    return;
  }

  if (btn === B_L) {
    useLed = !useLed;
    render();
    return;
  }
}

function ble_rx(data) {
  if (data.substr(0, 4) == "OWT|") {
    // "OWT|2ggA": store tone 2
    tones[+data[4]] = decodeTone(data.substr(5));
  } else if (data.substr(0, 2) == "OW") {
    // "OW23Hello": tone 2, repeat 3 times, text "Hello"
    var n = data[2],
      repeats = +data[3];
    if (n != "-" && tones[+n]) {
      console.log(n, repeats);
      notify(+n, repeats);
    }
    lastMsg = data.substr(4);
    if(Ollee.active()) startScroll();
    pump();
    return;
  }
  Ollee.beep();
  pump();
}

function ble_connect() {
  render();
  wake();
}

function ble_disconnect() {
  render();
  wake();
}

function decodeTone(s) {
  // s = "ggA"
  var p = [];
  for (var i = 0; i < s.length; i++) {
    var c = s.charCodeAt(i);
    if (c >= 97 && c <= 122)
      p.push([0, (c - 97) * 50]); // a-z: style 0
    else if (c >= 65 && c <= 90) p.push([1, (c - 65) * 50]); // A-Z: style 1
  }
  return p;
}
