# Shared sports guide (app side)

Goal: a listing the operator publishes on the sports server shows up in **every** Nuvio
install's Live TV guide, with nobody opening a settings screen.

Server half: `esencial-sport/odd/tasks/shared-sports-epg.md`.

## What shipped

| Commit | What |
| --- | --- |
| `676d67916` | `NuvioGuideSources` seeds the built-in `EpgSource` once per `SEED_VERSION`, appended **after** the user's guides. `SPORTS_GUIDE_URL` buildConfigField, default `https://esencialsport.attendanceos.app/epg.xml`. Seeded at the top of `refreshLocked`. |
| `db9bc1ced` | Guide matching moved from playlist channels to the **merged** list, so native portal channels ("NBA TV 04") can carry a listing. Name-only matching is limited to built-in guides. Fingerprint follows the same list. |

Tests: `NuvioGuideSourcesTest` (seed idempotence, ordering after user guides, matching
scope), `MagisNativeLiveTest`, `SportsAddonChannelsTest`.

## Device evidence (emulator, build 14, `fullDebug`)

- Settings → Live TV → Playlists & Guides → TV Guides (EPG):
  `Nuvio Sports · 2 programs matched · Updated 10/23/26 7:10 PM · On` — the source was
  never added by hand.
- Start-Up Report showed the guide read and the Sports tab matching against
  `(guide 1 …)` / `(guide 2 …)`.
- The guide drew **Boca Juniors vs River Plate** on the `TVN Panama` row. That channel has
  no `tvg-id` in the playlist and the published listing carries only the name — the name
  match is what put it there.
- Publishing the same channel + title at a different clock **replaced** the old slot
  (`store.upsert`'s "correction of the start time" rule), so a moved kickoff leaves no
  ghost entry.

## Operator workflow

1. Open `https://esencialsport.attendanceos.app/epg-admin`, token from `ESS_EPG_TOKEN`.
2. Pick the channel name **exactly as the playlist shows it** (the admin page prefills
   from the sports catalog). Name matching is normalized but not fuzzy.
3. Publish. Users see it at the next guide refresh, or immediately after
   Settings → Live TV → Playlists & Guides → **Update Everything Now**.

## Follow-ups

- A wrong channel name silently shows nothing. Nothing in the app reports "published but
  matched nowhere"; the server knows its own names, the app knows its channels.
- `epgRefreshHours` default is 12 h, so a late kickoff edit can miss a game already in
  progress on a device that hasn't refreshed.
- No per-user local override yet; shared only.

## Files

- `app/src/main/java/com/nuvio/tv/livetv/data/NuvioGuideSources.kt`
- `app/src/main/java/com/nuvio/tv/livetv/data/LiveTvPreferences.kt` (`seedBuiltInGuides`)
- `app/src/main/java/com/nuvio/tv/livetv/data/LiveTvRepository.kt` (`buildProgramsInner`,
  `guideFingerprint`, `loadGuideFromDb`, `syncHidden`, `dropRemovedPlaylistsFromGuide`)
