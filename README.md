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

### Hide / exclude a column (fairness across sources)
In the page, untick a column's header checkbox → it fades and is removed from every Total (which re-sums and re-sorts). A per-cell `X` marks a value as N/A (never counted). Use this when a field (e.g. tire season) exists on Autohero but not on AutoScout, so the comparison stays fair.

## Base filters
Brands Audi/BMW/Mercedes-Benz/Porsche/Honda/Toyota/Hyundai · first reg ≥ 2018 · €10k–20k · Germany · ≤3 owners · accident-free · ≥4 doors (no 2-door coupés) · **petrol / hybrid / plug-in hybrid only** (no diesel, no pure-electric) · **excludes reserved / sale-in-progress listings**.

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
- **Phase 2 (done):** JUnit suite — **41 tests, 0 failures** (`src/test/kotlin`): base-filter gate (incl. sale-in-progress), determinism, ROI independence, mileage-threshold penalty, column scores, X/toggle exclusion, default-active columns, sort, eviction, edge cases. The real Kotlin run (`gradlew run`) generates `site/data.json` and was **cross-checked cell-for-cell against a Python oracle (0 mismatches)**.
- **Data:** all 7 brands researched (Honda & Hyundai ROI added). Fresh fetch: 474 listings → 420 eligible (after excluding 35 reserved/sale-in-progress) → top 50 (`data/listings.json` → `site/data.json`).
- **Pending data:** tire-season & trunk-size ingestion from listing detail pages (currently `X` / model-lookup).

## References
- Notion — [Reliability Knowledge Base](https://app.notion.com/p/3e76f8b68bea81ea9cf0ec6e7f3d809e) · [Car Evaluation framework](https://app.notion.com/p/3e76f8b68bea8150a0a7c121d078504a) · [Final Candidate](https://app.notion.com/p/3e76f8b68bea8153b125eeb95032475c) · [Autohero AI Search (fetch recipe)](https://app.notion.com/p/3e76f8b68bea819fb075dd9506db194c)
- [Google Sheet "Car candidates"](https://docs.google.com/spreadsheets/d/1VxyCg5jhEEhZj-nIlLhTL7miNjSBUeo13BhUVbypQQ8/edit) (legacy / optional export)
- GitHub Pages table: _enable Pages on this repo (Settings → Pages → deploy from `main` / `site` folder) — URL will appear here._
