# Ollee Watch For Tasker & Forge Watchfaces

- [`android/`](android/) — Ollee Watch for Tasker, the Tasker plugin and control app ([README](android/README.md))
- [`watchfaces/`](watchfaces/) — Watchfaces for Ollee Forge that utilize the plugin ([README](watchfaces/README.md))

## What this does
Ollee Watch For Tasker is a Tasker plugin app that manages the bluetooth connection to the Ollee Watch as a background service and provides Plugin actions and events to Tasker. As much as I don't want to run multiple apps, I think this is the best compromise that still allows people to manage the bluetooth connection and tx/rx with a known and respected system. I tried a few different methods, and this ultimately "felt right". 

## Setup
- Download the Simple Notification watchface and upload it to your watch via Ollee Forge and the Ollee app: [SimpleNotifications.js](watchfaces/SimpleNotifications.js)
- Install the OWT apk and follow the setup instructions (detailed in the [README](android/README.md))
- Download the example Tasker project and install it in tasker ([OWT.prj.xml](tasker/OWT.prj.xml))
- ???
- Profit

## AI use and safety disclaimer
I am ashamed to admit that the Android app is absolutely vibe-coded. I have done a code review on it and don't see anything alarming, but I can't guarantee it won't break something, somewhere. I'm still running it on my own device though. Since this is not being distributed through an appstore or anything that could provide a modicum of protection against malicious intent, the code is published here and you can inspect and compile it yourself. I certainly wouldn't trust some rando on the internet handing me an APK saying "yeah it's safe because claude wrote it" without being able to inspect it myself.

Will it delete all of the data on your phone? Probably not.
