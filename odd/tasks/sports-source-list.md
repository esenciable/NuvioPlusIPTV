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
