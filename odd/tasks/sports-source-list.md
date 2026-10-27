# Sports: every feed of a match, not just one

## The complaint

Napoli vs AS Roma has **ten** feeds upstream and the Sports tab offers **one**.

## Diagnosis (measured, not guessed)

`GET /events.json` publishes `sources: []` for almost every live match:

```
poll1: 58 live events, sources only in positions 0..6   (the first 7)
poll2: 61 live events, sources in positions 0,2,3,4    (those were still in resolveCache)
live con sources null (no se pudo preguntar): 0
live con sources []   (preguntó y dijo "cero"):        51
```

`GET /stream/tv/rb_4441324_1.json` for the same match answers **10 named feeds**
(`8X VN`, `TyR`, `TUV TH`, `DZ IT`, `Sport PT`, `DZ FR`, `ESPN MX`, `Fubo US`, `1X EN`, `TV EN`)
with `streamId`, `resolution` and `verified` — the exact shape `AddonSource` already parses.

So the data exists and the app already renders one row per source
(`SportsAddonChannels.channelsFor`). What is broken is how the sources travel.

Two defects, in the server:

1. **A cooldown is published as "this match has no feeds".** `fetchDetailWithRetry` returns
   `{ match: null, streams: [] }` while cooling, `resolveFor` turns that into
   `streams: [], failed: false`, and `sourcesFor` maps it to `[]` — the value the code's own
   comment reserves for "we asked and it truly has none". A rate limit is drawn as an empty shelf.
2. **The listing pass burns the budget watching needs.** `/events.json` resolves *every* live
   match on every poll (58 × userinfo + detail + up to 4 probes at concurrency 3). The upstream
   answers 429, `_cooldownUntil` is **global** for 90 s (`RBLIVE_COOLDOWN_MS`), and everything
   after the first handful comes back empty — including real playback attempts from viewers.

## Plan

| Unit | Where | What |
| --- | --- | --- |
| WU-H | `esencial-sport` | Cooldown/throw ⇒ `failed` ⇒ feed publishes `null`, never `[]`. Bound the listing fan-out to a small per-poll budget and skip it entirely while cooling, so polling the feed cannot starve playback. |
| WU-I | `nuvio-plus-sports` | Ask for a match's feeds where the match is needed: `GET {base}/stream/tv/{id}.json`, mapped to `AddonSource`, and applied to the candidates before `decorate`. Final shape: asked when the viewer **opens** the fixture, not in the polling pass — see "Design change found on device". |

Backward compatible both ways: an older app keeps whatever the feed sends; a newer app against an
older feed still plays the single `streamUrl` it always had.

## Acceptance

- Napoli (and any live match) offers ~10 named, distinguishable sources in the tab.
- `sources: []` in the feed means only "asked and there are none" — provable by a test that forces
  a cooldown and asserts `null`.
- Polling `/events.json` no longer trips the global cooldown: while cooling, the feed answers from
  cache without asking upstream.
- No regression on the addon's playback routes (`/rblive/playlist`, `/rblive/segment`).

## Out of scope

- The shared EPG work (separate feature, `shared-sports-guide.md`).
- Automatic quality selection between the ten feeds.

## Evidence log

- 2026-10-24: diagnosis above, from production `events.json` × 2 and the stream route.

## Design change found on device (build 15 → 16)

The first version asked inside the polling pass, for a budget of the first six live events. On the
emulator that shape failed exactly as written: `/events.json` sorts live matches by the **earliest**
kick-off, so the budget spent itself on the games that started longest ago, and the match the viewer
had just opened — the one that matters — was never asked. Napoli vs AS Roma still showed one row.

The ask moved to `SportsRepository.loadSources(eventId)`, called from the detail screen's
`LaunchedEffect(ev.id)` in `SportsScreen.kt`: one request for the fixture somebody opened, no budget
to tune, no load on matches nobody is looking at, and nothing added to the ten-second poll.
`askedAt` (5 min) keeps a re-open off the network, and the answer goes through `runMatch()` so
`decorate` **replaces** the rows for that fixture instead of leaving the single link behind as an
eleventh row.

## What the operator's own device test found (build 16 → 17)

The viewer checked three live matches on the TiVo. That report separated two defects the emulator
could not show, because the emulator's one fixture happened to be a big one:

| Match | feed published | route answered | what the tab showed | defect |
| --- | --- | --- | --- | --- |
| Chelsea vs Tottenham | 10 sources | — | several, **after a few minutes** | the ask was cached as empty on the first `failed` answer |
| Valencia vs Villarreal | `null` (outside the poll budget) | `failed: true`, 0 streams | one row | `failed` read as "this match has no feeds" and stored 5 min |
| Dortmund vs Frankfurt | **6 sources** | 6 streams | **no row at all** | the join never found the event |

Dortmund was the interesting one: the server had the feeds and named them, and the tab still showed
nothing. The feed writes that club as `Eintracht Fráncfort`; the guide says `Frankfurt`. Folding
accents gives `francfort`, which is not the word `Frankfurt`, so both-teams matching failed and the
match got no Esencial row — a missing row, not a shorter list. Four aliases went into
`SportsCatalog.ALIASES` (Frankfurt, Nuremberg, Club Brugge, Porto — the Spanish forms the feed uses
today, taken from its 940 distinct names), and `Bayern de Múnich` was folded into the existing
`bayern munich` entry instead of added as a second key: `mapOf` lets a duplicate key silently drop
the first entry's values, which would have deleted `"bayern"` and `"fc bayern"`.

The other fix is in `SportsAddonFeed.sources()`: it returned an empty list for every failure, so the
tab stored a rate limit as a fact about the fixture. `null` now means "no answer" and caches
nothing; an empty list means the addon asked and the match has no feeds.

Both came from one observation the emulator could not make on its own: the fixture the viewer chose
is the one that tests the join, not the one the poll happened to resolve.

Full suite: 2378 tests, 3 failed — `SettingsCatalogTest`, `TraktAuthServiceTest`,
`TraktProgressServiceRefreshTest`, the same three that fail on clean `HEAD` with these changes
stashed (verified by re-running them without the working tree), so not caused by this work.

## Device evidence (build 16, emulator `emulator-5554`)

Napoli vs AS Roma, Serie A, LIVE at 66'. `WATCH ON YOUR CHANNELS` before this change: one row,
`Esencial Sport`. After: `Esencial Sport · Sport PT`, `· DZ FR`, `· ESPN MX`, `· Fubo US`, `· 1X EN`,
`· TV EN` … each its own row with the `Esencial Sport` badge, alongside the Magis `PARAMOUNT+ HD`
row. In the same minute the feed published 8 sources for that match (`8X VN`, `TyR` verified; the
rest lazy), and the upstream's list moves between probes — 10 in one, 8 in the next — which is the
fixture's own variability, not the app dropping rows.

## The retry the operator's second report asked for (build 17 → 18)

Next day, different fixtures: **the match's other feeds appeared only after playing the one link
that was there and coming back.** That is not the row list being wrong — it is `loadSources` asking
once per open and treating "the addon could not ask" (a cold route, its own rate limit) as the end
of the conversation. The viewer who stayed on the screen had already been left with one row.

`loadSources` now returns which of three things happened — `Nothing` (nothing to do), `Answered`
(a fact about the fixture, empty included), `CouldNotAsk` — and the detail screen retries once after
six seconds on the last one only. An answer is never re-asked: polling for a fact the server already
gave is traffic every other viewer pays for at the upstream.

`CouldNotAsk` is the same distinction the feed layer made in `b54087dd1`, now carried all the way to
the screen that can act on it. The coroutine belongs to `LaunchedEffect(ev.id)`, so closing the match
cancels the wait.

Server side, the cause of those cold answers was found the same day and is recorded in
`esencial-sport/odd/tasks/sports-multi-sources.md`: live HLS re-fetching its playlist resolved the
lazy source against the upstream every time — 88 metadata calls in 260 s from one viewer — which put
the shared client in a 429 cooldown 59% of the time. Cached (`ESS_LAZY_TTL_MS`, 60 s), the duty cycle
went to 0% with playback running. The retry stays because a route can still be cold for other
reasons, and the viewer sitting on the screen is the one who benefits.

---

## "Sometimes all the links, sometimes none" — three separate defects

Reported as one symptom; it was three, each producing a similar picture. Fixed in that order, each
one measured before being touched.

### 1. A rate-limit window is not a verdict about a match (server `15b16b4`)

Production counters over 47 h: `match/detail` 5048 calls, `match/live` 4516, `common/bs` 6947 — and
rate limited: `common/bs` 194, `match/live` 121, `match/detail` 27, `cooldownSkips` 276. Any single
429 puts the client in a **global** 90 s cooldown, and the viewer's own open then answered
`failed: true`, so the tab drew the one link the feed already carried.

- The last answer a viewer paid for is now kept 30 min (`ESS_STALE_TTL_MS`) and re-served, marked
  `stale`, whenever the upstream cannot be asked. Only a complete resolution replaces it.
- A failed answer carries `retryAfterMs` (the window the upstream named).
- `fetchBodySignature` was cached under the caller's query, which for match detail contains the
  `matchId` — so the cache never hit on that path and every open paid two upstream calls instead of
  one, to the most limited endpoint. Measured: codes 100/101 answer byte-identical versions for two
  different live matches and with no `matchId` at all. The cache key is the codes now; `common/bs`
  fell to 2 calls for 5 detail + 11 listing calls.

### 2. The ten-second poll erased what the viewer had just asked for (app `d78beb819`)

`/events.json` carries `sources: []` for live matches (listing does not probe), and
`refreshAddonFeed` **replaced** the candidate list with every poll. So:

1. open the match → the app asks → the full shelf appears;
2. within ≤60 s the poll replaces the list → the learned feeds are gone;
3. reopen inside the five-minute ask gate → `loadSources` says "we asked recently" and does not ask
   again → one row;
4. five minutes later it asks again → the full shelf is back.

`SportsAddonChannels.mergeLearned` keeps what was learned across polls, bounded by
`LEARNED_TTL_MS` (30 min: pauses and reconnects re-mint URLs), and a real answer from the feed still
replaces it.

### 3. A connector word hid the match entirely (app `40916ec54`)

A live Copa del Rey match (`Recreativo Huelva` vs `Rayo Vallecano`) showed **no rows at all** — "Not
found in your channels yet" — while the feed carried it as `rb_4520476_1`. The feed writes
"Recreativo **de** Huelva"; the join required one normalized name to be a contiguous whole-word run
inside the other, so the connector broke it.

`SportsAddonMatcher.nameMatches` now also compares the words that carry a club's name, dropping only
connectors and club-suffix filler. The filler list is deliberately small and pinned by tests on both
sides: it must join `Rayo` ↔ `Rayo Vallecano` and must never merge `Real Madrid` ↔ `Real Sociedad`,
`Sporting Gijon` ↔ `Sporting Lisbon`, `Manchester United` ↔ `Manchester City`, `Inter` ↔
`Internazionale`.

### Evidence on the devices

- Box, before: the Copa del Rey match with zero rows.
- Box, build 25 (`107025`, `40916ec54`): the same match shows its two links.
- Builds 22–25 installed on the emulator, the Mi Box (`192.168.18.6`) and the TiVo Stream 4K
  (`192.168.18.59`); full suite 2385 tests with only the three long-standing failures.
