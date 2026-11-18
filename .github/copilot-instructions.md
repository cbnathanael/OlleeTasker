# Copilot instructions for OlleeTasker

Three loosely coupled parts that share one BLE wire protocol:

- `android/` — "Ollee Watch for Tasker": a Kotlin Android app (single `:app` module, package `com.naate.olleetasker`) that is a Locale/Tasker plugin and keeps a persistent BLE link to an Ollee watch.
- `watchfaces/` — Ollee Forge watch face JS (e.g. `SimpleNotifications.js`) that receives the plugin's messages in `ble_rx(data)`.
- `tasker/OWT.prj.xml` — example Tasker project exported from Tasker (edit in Tasker, not by hand).

## Build

Run from `android/` (JDK 17+, Android SDK 35; AGP 9 with built-in Kotlin, no separate Kotlin plugin):

```
./gradlew assembleDebug      # APK -> app/build/outputs/apk/debug/
./gradlew lint               # Android lint
```

There are no unit or instrumented tests. Dependencies are declared inline in `app/build.gradle.kts` (no version catalog); only AppCompat and Material are used — no Compose, coroutines, or DI.

## Android architecture

- **`OlleeLink`** (object) is the single process-wide BLE link: connect/reconnect (1 s/10 s/30 s backoff, then autoConnect), pause/resume, writes, and reassembly of 20-byte TX notifications into messages (a short chunk or 500 ms idle ends a message). GATT operations are blocking and serialized by `opLock`; callbacks from stale `BluetoothGatt` instances are ignored. Call `OlleeLink.init(context)` before use. With `LinkPrefs.autoReconnect` off, every lost or failed connection goes through `linkFailedLocked()`, which stops the link (`enabled = false`) instead of retrying, and `send` requires an existing connection; only `connect`/`start`/`resume` may connect. `setupLocked()` ends with `requestPriorityLocked()`, which requests LOW_POWER or BALANCED per `LinkPrefs.lowPower` (Settings); its failure is only logged.
- **`LinkService`** is the foreground service that keeps the process alive while the link is wanted. `LinkPrefs` (SharedPreferences `ollee_link`) persists address/enabled/paused so `BootReceiver` can restore the link.
- **Tasker protocol** is hand-implemented in `Tasker.kt` (no TaskerPlugin library). Action flow: Tasker → `FireReceiver` (sets `RESULT_CODE_PENDING`, starts the service, runs `ActionRunner.run` on a background thread) → `Tasker.signalFinish` with output vars (`%ollee_state`, `%ollee_address`, `%errmsg`). Throw `GattException` with a user-readable message to fail an action.
- **Actions**: Tasker lists three actions, all `activity-alias`es of `EditActivity` declared in `AndroidManifest.xml`. `ActionKind` maps alias component names to their allowed `Operation`s, so adding an action needs both a manifest alias and an `ActionKind` entry.
- **Events**: `TaskerEvents` calls `Tasker.requestQuery` with pass-through data; `QueryReceiver` matches it against the `EventConfig` edited in `EventEditActivity`.
- **Config bundles**: `PluginConfig`/`EventConfig` serialize to/from Tasker bundles with keys prefixed `com.naate.olleetasker.extra.` plus a `VERSION`. Saved Tasker actions must keep working: when changing fields, bump the version and migrate old bundles in `fromBundle` (see the v1 and `SEND_AND_WAIT` handling). Add any field that may hold `%variables` to `BUNDLE_KEY_VARIABLE_REPLACE_KEYS`.
- **Tones** (`Tones.kt`, `ToneStore`): exactly 5 named patterns in fixed slots 0–4 (unsaved slots fall back to `Tone.DEFAULTS`), max 15 beeps, gaps 0–1250 ms in 50 ms steps.

## Wire protocol (keep Android, watch face, and READMEs in sync)

Every phone → watch write is a single write of at most 20 bytes. "Text" format is Latin-1, one byte per char (`Ble.encodeWatchText`).

- Message: `<prefix><tone 0-4 or '-'><repeat 0-9><text>`, default prefix `OW`, e.g. `OW23Hello`. Built in `ActionRunner`.
- Tone definition: `OWT|<slot><letters>`; one letter per beep: lowercase = style 0, uppercase = style 1, letter index × 50 ms = gap after the beep (e.g. `OWT|2ggA`).
- Test play: `OWA|<letters>` plays the pattern once without storing it on the watch.
- UUIDs live in `Ble.kt`: Forge service `c6fb0001-…`, RX `c6fb0002-…`, TX `c6fb0003-…` (also matched by the first 32 bits).

If you change the protocol, update `ActionRunner`/`Tones.kt`, `watchfaces/SimpleNotifications.js`, the decoder example in `android/README.md`, and `watchfaces/README.md` together.

## Conventions

- Kotlin official code style; KDoc on non-obvious classes and fields. Stateful singletons are `object`s; threading uses plain `Executors`/`thread {}`.
- BLE code uses `@SuppressLint("MissingPermission")`; runtime permission checks live in `Perms.kt` and the setup wizard (`SetupActivity`/`SetupHelpers`).
- UI is View-based AppCompat Activities with XML layouts in `res/layout`.
- Update `android/README.md` when user-visible behavior, actions, or Tasker variables change.
