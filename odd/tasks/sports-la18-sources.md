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

- [ ] **L1 — la18 feed parser**: `SportsLa18Feed.kt` + fixture JSON from the real response
      + unit tests (title parse, Lima -> ms, live status, source mapping, skip foreign links).
- [ ] **L2 — stream resolver**: `la18:<id>` -> signed m3u8, golden HTML fixture from the real
      response, cache + failure semantics (null, never cached).
- [ ] **L3 — tab merge**: repository refreshes both feeds; decorate generalized; unmatched
      la18 events appended as rows; dedupe by teams+time against the addon feed.
- [ ] **L4 — playback wiring**: resolver registered in DI; `la18:` scheme handled by the
      controller; recording refused for la18 channels.
- [ ] **L5 — verify + build**: full unit suite vs baseline (3 known failures), build 19,
      emulator smoke test, commits per work unit, docs.

## Evidence

- 2026-10-26 measurements in Engram + this doc's Why section.
- Gate bisect: `sec-fetch-dest: iframe` alone opens canales.php (h2 and h1.1, curl and node).
- Cross-IP test: laptop-minted token works from laptop, host is CDN-blocked regardless of
  whose token — the block is by IP, not token binding (untestable beyond that from here).

## Non-goals

- No server-side proxy (CDN blocks the host IP).
- No tarjetarojita embed support (browser-only, measured 0/24).
- No la18 channels in the Live TV channel list (only the Sports tab).
