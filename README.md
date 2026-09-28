# Car Ranking Comparison

A transparent, deterministic used-car ranking. **The repo is the single source of truth** for the data, the algorithms, and the display. A static GitHub Pages table shows the ranking and sorts it — it does **no fetching and no math**.

## Architecture
```
listings (Autohero / AutoScout / manual, source-tagged)
        │  ingest → data/listings.json
        ▼
Kotlin scorer (src/main/kotlin/ranker)  ── implements docs/algorithm.md exactly
        │  → site/data.json  (per-column 0–100 scores + Total)
        ▼
site/index.html (GitHub Pages)  ── displays, sorts, toggles columns. No logic.
```
- **Source-agnostic:** every listing carries a `source`. The score never depends on where it came from.
- **Deterministic:** identical car attributes → identical scores → identical Total (fixed reference tables in `data/reference.json`).
- **Per-column independence:** each column has its own algorithm; `Total = sum of the active, available columns`.

## Columns
Identity: index · name · first registration · fuel · price · km · URL · city (last).
Scored (0–100, toggle-able), **on by default**: Engine · Mileage · Value · Transmission · Consumption · ConsumptionUrban · TrunkSize.
Scored, **off by default** (placed last — situational / source-dependent, tick on to include): Owners · TireSeason · MinorDamage · Commercial.
Full rules & pseudocode: [`docs/algorithm.md`](docs/algorithm.md). Reliability (ROI) method: [`docs/reliability.md`](docs/reliability.md).

### Independent score plugins & Avg
ROI is not one of the total columns — it is the first **independent score plugin** (0–10, advisory, never summed into Total). Anyone can add another (e.g. a carwow-style rating) by dropping a `plugins/<name>.md` and registering it; a new advisory column appears. See [`plugins/README.md`](plugins/README.md). Use these to accept/skip a car regardless of its Total.
**Avg** sits right before ROI and is the default sort key: `mean(Total normalized to 0–10, every available independent plugin score)` — the one place Total and the plugins are combined into a single "best overall" figure, highest first.

### Filter panel — brand / source / model, re-ranked on Update
`site/data.json` ships the **entire eligible pool**, not just top 50 — the page owns the top-50 cut so it can re-derive it after a filter change. A hamburger icon (☰) opens a drawer with **Brands**, **Listing source**, and **Hide models** — all staged, applied only on **Update**. Update filters the full pool, re-sorts (same rule as `buildTable`: total desc, mileage → price → id), and re-caps to 50 — so excluding a brand backfills with the next-best contenders instead of shrinking the list. Ford/Kia and mobile.de show as disabled "no data yet" chips (separate future work — mobile.de specifically is blocked by Akamai bot-management, see below); real brands/sources appear automatically once added, no page changes needed.

### Two live listing sources
**Autohero** and **AutoScout24** are both fetched today (`data/listings.json` = 1,071 combined listings). **mobile.de is not** — it returns a hard `403` from Akamai bot-management even with realistic browser headers; reliably getting past that needs solving JS/fingerprint challenges, which this project won't automate. AutoScout24's list-view API is missing several fields Autohero's has (owners, commercial flag, urban consumption, door count) — see `docs/algorithm.md` for the exact gaps and defaults. Notably, `ConsumptionUrban` is on-by-default and AS24 never reports it, so **AS24 cars don't reach the default top 50 (0/50) — untick `ConsumptionUrban` in the drawer to see them compete (verified: 17/50 once it's off)**.

### Cross-source de-duplication
`dedupeAcrossSources` keeps one listing per (name, price, color) when 2+ sources report the same car — see `docs/algorithm.md`. Two sources are live now, but it's still a no-op: neither Autohero nor AutoScout24 exposes `color`, so nothing is ever considered a match (verified: 0 non-null `color` values across all 1,071 listings).

### Hide / exclude a column (fairness across sources)
In the page, untick a column's header checkbox → it fades and is removed from every Total (which re-sums and re-sorts). A per-cell `X` marks a value as N/A (never counted). Use this when a field (e.g. tire season) exists on Autohero but not on AutoScout, so the comparison stays fair.

## Base filters
Brands Audi/BMW/Mercedes-Benz/Porsche/Honda/Toyota/Hyundai · first reg ≥ 2018 · €10k–20k · Germany · ≤3 owners · accident-free · ≥4 doors (no 2-door coupés) · **petrol / hybrid / plug-in hybrid only** (no diesel, no pure-electric) · **excludes reserved / sale-in-progress listings** · **trunk ≥ 360L when known** (listing's own value, else the per-model reference estimate; fully unknown is not excluded).

## Run (Phase 2 — needs Kotlin/Gradle)
```bash
./gradlew run --args="data/listings.json data/reference.json site/data.json"   # regenerate the table
./gradlew test                                                                  # run the test suite
```
Preview the page locally:
```bash
cd site && python3 -m http.server 8080   # then open http://localhost:8080
```

## Status
- **Phase 1 (done):** algorithm (`docs/` + Kotlin) + page (`site/`) + independent-score plugins (`plugins/`).
- **Phase 2 (done):** JUnit suite — **60 tests, 0 failures** (`src/test/kotlin`): base-filter gate (incl. sale-in-progress, trunk<360L), determinism, ROI independence, mileage-threshold penalty, column scores, X/toggle exclusion, default-active columns, cross-source dedup, full-pool sizing, sort, eviction, edge cases. The real Kotlin run (`gradlew run`) generates `site/data.json` and was **cross-checked cell-for-cell against a Python oracle (0 mismatches)**.
- **Data:** all 7 brands researched (Honda & Hyundai ROI added). Two sources combined: Autohero (474 fetched) + AutoScout24 (597, `scripts/fetch_autoscout24.py`) = 1,071 listings → 562 eligible → **entire pool shipped** to `site/data.json`; the page caps to top 50 (see the filter-panel section above).
- **Pending work (separate, larger tasks — see `docs/algorithm.md`):** Ford & Kia reliability research + fetch; mobile.de ingestion (blocked by Akamai, not attempted); tire-season, trunk-size, color, owners, and commercial-flag ingestion from listing detail pages (currently `X`/model-lookup/default/`null`).
- **Fixed model-name matching bugs:** body-style suffixes baked into Autohero's `model` field (e.g. "Auris Touring Sports") and a Mercedes-Benz `generation()` substring collision (GLA/CLA-Klasse silently misclassifying as A-Klasse) were both silently dropping cars from the table. See `docs/algorithm.md` for the fix and the regression tests in `ScoringTest.kt`.
- **Fixed a cross-language rounding bug:** the Python oracle rounded each individual score to 2dp before summing into `total`, while Kotlin summed full-precision scores and rounded once at the end — a systematic (not just floating-point-noise) discrepancy that only started flipping sort order once the full pool (hundreds of rows, densely-packed totals) shipped instead of just 50. Both now match exactly (Kotlin: `Math.round`-based half-up rounding once, at the end; oracle: mirrors it via `round2_half_up`, plus unrounded individual cells).

## References
- Notion — [Reliability Knowledge Base](https://app.notion.com/p/3e76f8b68bea81ea9cf0ec6e7f3d809e) · [Car Evaluation framework](https://app.notion.com/p/3e76f8b68bea8150a0a7c121d078504a) · [Final Candidate](https://app.notion.com/p/3e76f8b68bea8153b125eeb95032475c) · [Autohero AI Search (fetch recipe)](https://app.notion.com/p/3e76f8b68bea819fb075dd9506db194c)
- [Google Sheet "Car candidates"](https://docs.google.com/spreadsheets/d/1VxyCg5jhEEhZj-nIlLhTL7miNjSBUeo13BhUVbypQQ8/edit) (legacy / optional export)
- GitHub Pages table: _enable Pages on this repo (Settings → Pages → deploy from `main` / `site` folder) — URL will appear here._
