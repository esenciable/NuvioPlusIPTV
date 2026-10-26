# la18 fixture provenance

`agenda-v1.json` is the real agenda document served by
`https://la18hd.su/eventos/json/agenda123.json` on **2026-10-26** (36 rows, 14 distinct
fixtures, 25 channel ids, 10 tarjetarojita embed links).

It is the golden input for `SportsLa18FeedTest`. The source of truth is the live site; to
refresh, fetch the agenda and replace this file, then re-read the golden expectations in the
test (group counts, kick-off epochs, source labels). Do not hand-edit the fixture to make a
test pass — a changed feed is a changed contract and must be measured again.
