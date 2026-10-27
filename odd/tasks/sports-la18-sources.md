# Feature: la18hd ("Partidos de Hoy") as a Sports-tab source

Status: **REMOVED** (2026-10-26, by operator decision — "elimina todo lo que tenga que ver con la18hd, no funciona")

## Why it was removed

The channel streams behind the agenda live on a CDN (`fubo18.com`, Flussonic) whose
"Streamer protection system" blocks by requester-IP reputation:

- The operator's home IP (181.78.55.108, shared by the deploy server) is flagged —
  predating this work, most plausibly from weeks of rblive proxy traffic through the
  same house; both CDNs run the same protection product.
- Direct playback from the operator's network: 403 on the playlist (the token mint
  itself works; the token is NOT IP-bound — proven by a home-minted token serving a
  playlist from another egress).
- A Cloudflare Worker relay was built and deployed (mint worked from CF egress), but
  fubo18 also answers 403 to Cloudflare's datacenter ranges, with or without headers.
- The only egress measured clean was a residential VPN path — no host we control runs
  there, and the operator declined further relay hunting.

## What was removed (app commits a02976cd9..6a7d9775c; server 667906f)

- `SportsLa18Feed` (agenda123.json parser) + fixtures + tests
- `La18StreamResolver` (la18:<id> placeholder minting) + fixtures + tests
- la18 feed wiring in `SportsRepository`, the LA18 Feed profile, la18 DI entry
- `NativeLiveEntry.contributesCatalog` (reverted: it existed only for the la18 entry)
- `LA18_WORKER_BASE/KEY` BuildConfig fields
- `esencial-sport/worker/la18` (the Cloudflare relay) + its tests

Kept (generic, tested, la18-independent): the `SportsAddonChannels.Feed` profile and
the stable score-ordering in `decorate()`.

## Evidence preserved

Measurements from 2026-10-26 live in Engram (records 5989 and later under topic
`sports-la18-sources`): gate = `sec-fetch-dest: iframe`; token 5 h; 22/25 channels
alive; CDN 403s home IP and Cloudflare egress; token not IP-bound.
