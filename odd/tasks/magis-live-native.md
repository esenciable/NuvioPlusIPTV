# Magis Live native in the NuvioPlusIPTV base

Branch: `feat/magis-live-native` (from `box-verify`).

Goal: show and play Magis live TV inside the base app's own Live TV tab (its screens, its
ExoPlayer, its favorites/recents), with **zero server dependency**: the device mints its own
anonymous Magis session, signs every origin request locally and talks directly to the CDN.

Source of truth for the protocol: `nuvio-es` fork (`ext/livetv/magis/*`, verified on the Mi Box)
and the TS reference (`esencial-play/src/magis/live.ts`).

## Decisions

- **Re-sign at resolve/play time, not per segment.** The base player freezes headers when the
  MediaSource is built (DefaultHttpDataSource.setDefaultRequestProperties). The fork ships the
  exact same behaviour and it plays minutes of live TV on the same box, so we copy the contract
  instead of inventing a custom DataSource. Expiry surfaces as an error and the retry re-resolves.
- **Channels carry a `magis-live://<code>` marker URL.** The playback controller is the single
  choke point that turns the marker into a real URL + fresh signed headers, so every entry point
  (guide, search, zap, home row, sports, reminders, reconnect) gets resolution for free.
- **Native channels live beside playlist channels, never inside them.** The playlist cache,
  fingerprint and EPG pipeline stay playlist-only; the merge happens in the public
  `channels` flow so the UI derives Magis categories as ordinary groups.
- **No EPG for Magis in this pass.** Magis channels have no tvg-id, so they stay out of guide
  matching (guide-less rows, same as the fork's first pass).

## Tasks

- [x] WU1 — Port `MagisLiveClient` + narrow ports (`MagisLiveCatalogApi`, `MagisLivePlaybackApi`)
      into the base; reuse the base's already-ported `slbRequestParams`/`ttlOf`. Tests with a fake
      portal (categories, paging, resolve, signing).
- [x] WU2 — Base-shaped native live plumbing: `NativeLiveSource`/loader/resolver interfaces,
      `MagisLiveSource` mapping (key `magis:<code>`, groupId `magis:<categoryId>`),
      `MagisChannelLoader`, `MagisStreamResolver`, `NativeLiveRepository` (refresh + cache), DI in
      `LiveTvMagisModule`.
- [x] WU3 — Merge native channels into `LiveTvRepository.channels`; resolve the `magis-live://`
      marker in `LiveTvPlaybackController` (play + reconnect re-sign).
- [x] WU4 — Build, install, verify on a device: Magis groups appear, a channel plays,
      reconnect works, no server involved. Verified on the Android TV emulator AND on the Mi Box 4.

## Evidence

- **WU1** — commit `09723f86d`. `MagisLiveClientTest`: 11 tests, green
  (`:app:testFullDebugUnitTest --tests com.nuvio.tv.ext.livetv.magis.MagisLiveClientTest`).
  Covers the call order (mint → `v4/startPlayLive` → `v14/getSlbInfo` with the CHANNEL code, not
  the playCode), the license/playCode pair never crossed between entries, `sign_type=cfl` exact
  match (cflx rejected), the typed errors, the bare-host playlist URL and the signed header shape.
- **WU2** — commit `17ced7a18`. `MagisNativeLiveTest` (10) + `NativeLiveSourcesTest` (5): green.
  Mapping, paging stop on a short page, one row per channel across categories, a dead category
  leaving the rest, ownership routing, resolve/re-marker round trip, unconfigured = absent.
- **WU3** — commit `2cde65572`. Full unit suite: 2357 tests, 3 failed, 1 skipped — the same three
  (`SettingsCatalogTest`, `TraktAuthServiceTest`, `TraktProgressServiceRefreshTest`) fail on a
  clean `HEAD` worktree of this branch's parent, so they are pre-existing and unrelated.
- **WU4** — Android TV emulator (`emulator-5554`), `app-full-arm64-v8a-debug.apk`, fork build 12:
  - `I/NuvioNativeLive: loaded=1033 in 10147 ms` — the portal walk is real, no proxy.
  - Live TV's group list ends with the portal's own categories (`Panamá`, `Puerto Rico`,
    `Uruguay`, `España` …) beside the playlist's, with the portal's logos.
  - `TELEONCE PR HD` played in fullscreen and was still advancing 3+ minutes later (different
    programme, `/tmp/magis-after3min.png`); zapping to `TELEMETRO PAN HD` resolved a fresh signal
    (`/tmp/magis-pan.png`). Zero `PlaybackException`, zero 401/403 in logcat.
  - The Mi Box 4 (`192.168.18.6:5555`, `armeabi-v7a`, fork build 12 installed over the box's own
    playlists): `I/NuvioNativeLive: loaded=1033 in 25968 ms` — the same 1033 channels, walked in
    26 s on the box's slower CPU instead of the emulator's 10 s. The owner confirmed it installs
    and plays correctly there.

## Follow-ups (deliberately not in this change)

- **EPG for Magis channels**: they carry no tvg-id, so they show no listings. The portal has guide
  data; wiring it is its own change.
- **CDN fallback in the UI**: `MagisStreamResolver.resolveCdn(channel, index)` already serves the
  provider's other CDNs; the base's [LiveChannel] carries one URL, so a source picker is where the
  rest land.
- **Recording a native channel**: refused with a message. Recording would need the signature
  re-minted for the recording's whole duration — a different piece of work.
- **Per-request re-signing inside the player**: not needed for what was measured (minutes of live
  on a resolve-time signature), and the fork's live path makes the same cut.

## Environment notes

- **Installing over adb on the Mi Box**: `adb install` fails with `device offline` mid-stream on
  this box's Wi-Fi. What works: `adb push` the APK to `/data/local/tmp/` (verify the byte count),
  then `adb shell pm install -r <path>`. The box also drops adb when it sleeps, so
  `settings put global stay_on_while_plugged_in 7` helps during a long push.
- **Fork versionCode**: the box had build 10 installed, so a plain debug build (…00) is refused as
  a downgrade. Pass `-PlivetvBuildNumber=12` (or higher) to `assembleFullDebug`.
