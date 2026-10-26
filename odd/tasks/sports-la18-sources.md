# Feature: la18hd ("Partidos de Hoy") as a Sports-tab source

Status: in progress
Branch: `feat/magis-live-native`
Created: 2026-10-26

## Why

The user pointed at `https://la18hd.su/eventos/` as the source behind La Cancha's
football listings. Measured on 2026-10-26 (see Engram session notes):

- `agenda123.json` is a public static JSON: 36 events, fields `title` (League: Home vs Away),
  `time` (America/Lima), `date`, `status`, `language`, `link`. No API, no signing.
- Each event lists 2-4 channels, not per-match streams: 36 events share 25 channel ids
  (espn, espn2, espn2mx, espn3mx, espn4, espn5, espn7, espndeportes, espnpremium, disney6/7/8,
  dsports, dsports_op2, dsportsar, premiere1/2/3, tntsportschile, winplus, beinsportes, hypermotion1).
- `https://la18hd.su/vivo/canales.php?stream=<id>` gate: requires `sec-fetch-dest: iframe`
  (or an Android/ExoPlayer UA). Answer is an HTML page embedding a signed JSON config:
  `"src":"https://<sub>.fubo18.com/<channel>/mono.m3u8?token=<40hex>-<nonce>-<exp>-<start>"`.
- The signed HLS is plain: playlist and MPEG-TS segments answer 200 with no special headers.
  22/25 channels alive (disney2/5/9 dead: 404/500). Token validity 5 h, reusable.
- The fubo18 CDN blocks the deploy host IP outright ("Streamer protection system", even for a
  self-minted token), so a server-side proxy is impossible. Client-side native resolution is
  the only path — same shape as Magis.
- La Cancha APK inspected: it is a WebView shell around lacancha.tv (no hidden API, no la18hd
  in the dex); its native part is only iptv-org / Free-TV playlists. Nothing to port from it.
- tarjetarojita links inside the agenda are the embeds already measured unplayable (0/24).

Decision (user): integrate as a **Sports-tab source** (events -> channels), client-side only.

## Architecture

Reuse the addon-source pattern end to end:

1. `SportsLa18Feed` — fetches `agenda123.json`, parses events into `AddonEvent`s whose
   sources are `url = "la18:<channelId>"` placeholders (one per la18hd channel link;
   tarjetarojita links skipped: measured dead).
2. Resolution — a `NativeLiveResolver` implementation mints at play time:
   `GET canales.php?stream=<id>` with `sec-fetch-dest: iframe`, extracts the signed `src`
   from the config JSON, caches per channel id (token lives 5 h).
3. `SportsAddonChannels.decorate` generalized to run over both candidate feeds
   (esencial-sport + la18); events la18 carries that no existing row covers are appended
   so a match ESPN misses still shows.
4. Playback — the existing native-placeholder choke point in `LiveTvPlaybackController`
   handles `la18:` URLs; recording refused (token expires).

## Work units

- [x] **L1 — la18 feed parser**: `SportsLa18Feed.kt` + fixture `la18/agenda-v1.json` (real
      response) + 10 unit tests. Commit `a02976cd9`.
- [x] **L2 — stream resolver**: `La18StreamResolver.kt`, `la18:<id>` -> signed m3u8 behind
      `sec-fetch-dest: iframe`, 1h cache of the 5h token, mutex-shared mints, failures never
      cached; fixture `la18/canales-v1.html`. Commit `98d7aa25d`.
- [x] **L3 — tab merge**: `SportsAddonChannels.Feed` profile; both feeds decorate; URL joining
      leaves the `la18:` placeholder alone; `SportsLa18Rows` appends matches nothing else
      carries (never duplicating a covered one); repository refreshes both feeds. Commit
      `fd8dee6b6`.
- [x] **L4 — playback wiring**: `NativeLiveEntry.contributesCatalog` (a resolve-only source
      must not claim "build has native sources"); la18 entry registered in DI, always
      configured, zero channels; recording refused by the generic native check. Commit
      `3bdbaacca`.
- [x] **L5 — verify + build**: full suite 2401 tests with only the 3 known pre-existing
      failures; build 19 (`versionCode 107019`) assembled; booted clean on emulator. Device
      verification of the tab handed to the operator.

## Evidence

- 2026-10-26 measurements in Engram + this doc's Why section.
- Gate bisect: `sec-fetch-dest: iframe` alone opens canales.php (h2 and h1.1, curl and node).
- Cross-IP test: laptop-minted token works from laptop, host is CDN-blocked regardless of
  whose token — the block is by IP, not token binding (untestable beyond that from here).

## Non-goals

- No server-side proxy (CDN blocks the host IP).
- No tarjetarojita embed support (browser-only, measured 0/24).
- No la18 channels in the Live TV channel list (only the Sports tab).
