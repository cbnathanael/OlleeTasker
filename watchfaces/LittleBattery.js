//A very basic battery level display. Rounds the read value to 1 decimal place and displays in the Day slot, and a rough percentage in the main time body, with 2.7V being critical/10%
function init() {
}

function enter() {
  Ollee.clear();
  var level = Ollee.battery();
  var lRound = Math.round(level * 10);
  Ollee.drawDay(lRound);
  Ollee.drawMain(batteryPercent(level)+" \\");
}
function batteryPercent(v) {
  var pts = [[2.6, 0], [2.7, 10], [2.8, 45], [2.9, 75], [3.0, 100]];
  if (v <= pts[0][0]) return 0;
  if (v >= pts[pts.length - 1][0]) return 100;
  for (var i = 1; i < pts.length; i++) {
    if (v <= pts[i][0]) {
      var a = pts[i - 1], b = pts[i];
      return Math.round(a[1] + (v - a[0]) / (b[0] - a[0]) * (b[1] - a[1]));
    }
  }
}
function exit() {
}
