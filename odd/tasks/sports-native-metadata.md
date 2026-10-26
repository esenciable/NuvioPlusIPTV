# Sports metadata inside the app (native Kotlin rblive client)

**Status:** N1 landed (protocol golden vectors). Target moved from C to **D** on measurement — see
"D is possible" below. N2 next.

## D is possible: the segment gate is headers, and they are static

Measured from this laptop (a non-server IP) with the protocol client run locally, no help from
`esencialsport`:

| what was fetched | result |
| --- | --- |
| `match/live`, `match/detail`, `stream/detail` straight from upstream | 94 fixtures, 9 live, resolved a real playlist URL + 2 backup CDN hosts |
| the media playlist, **no headers at all** | `200`, 4907 bytes, a real `#EXTM3U` |
| one segment, no headers | `403` — `Forbidden (NR)` |
| one segment, `user-agent` only | `403` — `Forbidden (NR)` |
| one segment, `origin` = the site origin | `403` — `Forbidden (NAR)` |
| one segment, `user-agent` + `referer` + `origin` (the app's triple) | `200` — **1,005,612 bytes, first byte `0x47`, real MPEG-TS** |

Then the question the first run could not answer — this laptop's IPv6 egress is in France, so
residential geo-blocking was still untested. Forced over the **Argentine residential IPv4**
(`200.12.215.154`), same fresh segment URL:

```
IPv4 residencial AR  + 3 headers   -> 200  bytes=1020276  MPEG-TS
IPv4 residencial AR  + sin headers -> 403  bytes=14       no-TS
```

Three consequences, in order of how much they change the plan:

1. **The server is not needed for playback.** The gate is a request header set, and a device can
   send it. Nothing about the response depended on which machine asked.
2. **The headers are static.** Unlike Magis, where the signature is minted per viewing and the fork
   froze headers at `MediaSource` creation, these three values never change. ExoPlayer's ordinary
   default request properties on the HLS data source cover every segment of the item — no
   resolve-time signing, no custom `DataSource`.
3. **The playlist rewrite must move to the device.** Segment hosts rotate per fetch (`george.cml206vooc.xyz`,
   then `donahue.cxl204oump.xyz` for the same match minutes later) and `buildCslWorkerUrl` maps the
   playlist's own host onto that worker family. That is pure logic — it ports, and it is golden-testable
   like the decoders.

Not proven by this: what happens when the upstream rotates the required triple, or starts checking
the IP too. That is why the server path stays as a fallback rather than being deleted.

**One consequence to decide on, not to discover later:** the shared operator EPG is hosted on the
same box (`/epg.xml`, seeded into every device by `NuvioGuideSources`). "No depend del server" for
browsing and playback does not have to mean the guide disappears — but if the box ever goes away, it
takes the guide with it. Either the guide moves somewhere static, or it stays a deliberate, small
dependency.
**Decision behind it:** the operator chose "the app builds the fixtures and the feed list itself,
without asking our server", with playback still going through the segment proxy.

## Goal

Replace the Sports tab's dependency on `esencialsport.attendanceos.app` for **metadata** with a
Kotlin client that speaks to the upstream sports API directly, the way the Magis Live port does for
channel lists. The server stays exactly one job: fetching segments, which a plain HTTP client cannot
do.

## Why this is worth the port

Every device today shares one server IP against the upstream's rate limit. That shared budget is the
root cause of the whole cooldown saga: one viewer re-fetching a live playlist was the loudest
metadata caller on the box, and the box's cooldown made **every** viewer's listings arrive late
(`sports-multi-sources.md` in the server repo, measured: `stream/detail` 88 calls in 260 s, cooldown
duty cycle 59%). Moving metadata per device removes the shared bottleneck by construction: each
device pays for its own browsing, and the server's `/health` counters stop being a global throttle.

## What the code says (evidence, not intention)

- The tab is already independent of the addon registry: `SportsRepository.kt:1124` hardcodes
  `addonBaseUrl`, and nothing in `livetv/sports/` reads `AddonPreferences`. So this is about removing
  the **server**, not the addon install step.
- The seam to swap is small: `SportsAddonFeed` takes `httpGet`, `dir`, `ttlMs`, `now` in its
  constructor (`SportsAddonFeed.kt:66-71`) and answers two shapes — `GET {base}/events.json` (`:152`)
  and `GET {base}/stream/tv/{id}.json` (`:156`) — into `AddonEvent` / `AddonSource` (`:16-58`).
- Upstream needs **no credentials**: hosts, `User-Agent`, `Origin`, `Referer` (`rbClient.js:39-56`)
  and a body signature that is *fetched* from `/api/common/bs`, not computed
  (`proto.js:decodeBodySignature`). Same shape as Magis's session mint.
- The protocol is obfuscated text + hand-rolled protobuf: `rot47` / `rot13` / `bruDecode`
  (`proto.js:30-80`), a tolerant varint walker (`:92-192`), and per-endpoint decoders
  (`decodeUserInfo:315`, `decodeBodySignature:349`, …). 720 + 608 lines of JS.
- The JS tests **build** the protobuf bytes instead of recording them
  (`tests/rbLiveProtocol.test.js:28-52` — `lenField`, `varintField`, `fixed32Field`, `success()`).
  That is what makes a golden-vector port possible: the same byte vectors can be dumped as fixtures
  and the Kotlin decoders held to the same expected output.
- The segment worker gates on **headers, not IP**: "any other origin answers 200 with a 5-byte
  'Hello' decoy" (`rbClient.js:505-512`), and the server itself fetches from a datacenter IP with
  nothing but those headers. Recorded as the open question for a later pass; not needed for this one.

## Design decisions

1. **Playback goes direct, and the server keeps only the fallback role.** The device resolves the
   stream, rewrites the playlist with `buildCslWorkerUrl`, and plays it with the app's static
   `user-agent` / `referer` / `origin`. The old lazy `s=<descriptor>` form (what `stream.js:232-238`
   emits, validated by `decodeLazySource`) stays implemented as the fallback path when native
   playback fails — it is the shape that keeps the server's SSRF perimeter closed by construction, so
   a device on that path still never supplies a target URL.
2. **Native is a provider behind an interface, with the remote feed kept as fallback.** A protocol
   rotation upstream (the hosts already rotate per generation — `pollitt.peb7105afhp4.xyz` today)
   must degrade to "the listings come from the server like yesterday", not to an empty Sports tab.
3. **Port the decoders before the transport.** The decoders are pure and golden-testable; the
   transport is network behaviour. Nothing about the first four units needs a device or a server.
4. **Ship it behind a switch, off by default.** Per-device rollout, and a measured comparison
   against the server's `/health` counters before it becomes the default path.

## Work units

**N1 is done.** `app/src/test/resources/rblive/rblive-protocol-v1.json` carries the vectors, with
`README.md` beside it naming the source of truth (`esencial-sport`, `tools/protocolVectors.cjs`, and
the test that keeps the fixture honest against the real decoders). Server commit `769c2a3`, 202 tests
green. What the fixture caught while being written: a first draft of the generator built team
records without their `f30` wrapper and decoded to `teams: []` — silently a weaker contract than the
JS test it was copied from. The re-run test is what made that visible.

Each one lands with tests on a feature branch; none of them is useful without the next, and none
breaks the current path on its own.

- **N1 — Golden protocol fixtures.** Dump byte vectors (obfuscations, walker, and one of each
  endpoint envelope: `match/count`, `match/live`, `match/detail`, `stream/detail`, `common/bs`,
  `user/info`) from the JS builders into a JSON fixture file the app's tests can read. Pure test
  asset; no production code.
- **N2 — Kotlin decode core.** `rot47`/`rot13`/`bruDecode`, varint reader, tolerant walker, field
  extractors. Tests: the N1 vectors, byte for byte.
- **N3 — Kotlin transport.** Request builder (host, `sfver{md5}` path prefix, UA/Origin/Referer),
  `common/bs` signature fetch and cache, 429 cooldown, soft-reject detection (`<PB…`, short alnum
  decoys, empty bodies — `rbClient.js:96-104`), retries. Tests with a fake HTTP layer, no network.
- **N4 — Endpoint decoders → domain models.** The four calls the tab needs, producing the same
  `AddonEvent` / `AddonSource` shapes the remote feed produces today. Golden-vector tests.
- **N4b — Playlist rewrite and the CSL worker mapping.** Port `buildCslWorkerUrl`, `isCslSegment`
  and `rewriteCslPlaylist` (plus the `EXT-X-START` the live edge needs), tested against the same
  fixture vectors the server tests use. This is what turns a resolved playlist into one a player can
  actually follow without a proxy.
- **N5 — Provider seam.** `SportsSourceProvider` with `events()` / `sources(id)`;
  `RemoteSportsProvider` (today's HTTP, unchanged behaviour) and `NativeSportsProvider`; selection
  behind the switch; playback rows carrying the rewritten direct URLs plus the header triple.
- **N5b — Player headers.** Set the static `user-agent` / `referer` / `origin` on the Live TV data
  source for these rows only, and prove a segment actually plays on a device. This is the unit where
  D either lands or dies, so it comes before anything is made default.
- **N6 — Measure and decide rollout.** Compare per-device upstream traffic and listing latency
  against the server's counters on a real match day, then flip the default or keep the fallback.
- **N7 — The guide question.** Decide where `/epg.xml` lives if the sports box stops being load-
  bearing. Not a code task yet; it is a dependency the operator should choose knowingly.

## Risks and non-goals

- **Risk: the port silently drifts from the JS.** Mitigated by N1 existing before N2 — the fixtures
  are the contract, and the JS tests that generated them stay in the server repo as the source.
- **Risk: per-device browsing multiplies upstream load** (130 events × N devices instead of × 1
  server). The polling cadence must move to the device with the same discipline the server learned:
  listing is not watching, so a listing pass costs one `match/detail` and no probes.
- **Risk: the header triple is someone else's decision.** It works today and it is not a contract we
  hold. Mitigated by the fallback path, which is the current behaviour and costs nothing to keep.
- **Non-goal (revised):** the server is no longer a goal to remove — it is a fallback to preserve.
  Segments proved reachable from a device; the box stays up so an upstream rotation degrades
  listings instead of killing them.
- **Non-goal:** the generic Stremio addon surfaces (catalogs in the catalog UI, streams in the
  Streams screen). That is a different shape — a virtual `esencial-sport://` addon following the
  `nuvio-server://` precedent — and it is not what was asked for.
