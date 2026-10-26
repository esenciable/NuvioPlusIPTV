# Sports metadata inside the app (native Kotlin rblive client)

**Status:** N1 landed (protocol golden vectors). N2 next.
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

1. **Playback keeps using the lazy descriptor, never a raw URL.** The native provider builds
   `{base}/rblive/playlist.m3u8?s=<b64url({matchId,sportType,streamId,siteType})>` — the same shape
   `stream.js:232-238` emits, and `decodeLazySource` accepts four positive integers and nothing else.
   The client therefore never supplies a target URL, which is what keeps the server's SSRF perimeter
   closed by construction rather than by trusting a query string. Building `u=` instead would make
   the device depend on the server's host allowlist and would reopen that hole.
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
- **N5 — Provider seam.** `SportsSourceProvider` with `events()` / `sources(id)`;
  `RemoteSportsProvider` (today's HTTP, unchanged behaviour) and `NativeSportsProvider`; selection
  behind the switch; playback rows built as `s=` descriptors against the existing proxy.
- **N6 — Measure and decide rollout.** Compare per-device upstream traffic and listing latency
  against the server's counters on a real match day, then flip the default or keep the fallback.

## Risks and non-goals

- **Risk: the port silently drifts from the JS.** Mitigated by N1 existing before N2 — the fixtures
  are the contract, and the JS tests that generated them stay in the server repo as the source.
- **Risk: per-device browsing multiplies upstream load** (130 events × N devices instead of × 1
  server). The polling cadence must move to the device with the same discipline the server learned:
  listing is not watching, so a listing pass costs one `match/detail` and no probes.
- **Non-goal:** removing the server. Segments still need it, and this plan does not pretend
  otherwise.
- **Non-goal:** the generic Stremio addon surfaces (catalogs in the catalog UI, streams in the
  Streams screen). That is a different shape — a virtual `esencial-sport://` addon following the
  `nuvio-server://` precedent — and it is not what was asked for.
