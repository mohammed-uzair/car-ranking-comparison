# Car Ranking Comparison

A transparent, deterministic used-car ranking. **The repo is the single source of truth** for the data, the algorithm, and the display — and it's **one language, Kotlin, end to end** (ingestion, scoring, the live server). No Python, no other language anywhere in this repo.

## Architecture
```
Ingest.kt (Kotlin, live)             ── fetches Autohero + AutoScout24 on demand
        │
        ▼
Scoring.kt / BuildSite.kt            ── dedup → base-filter gate → per-column scores → rank
        │
        ├─ offline: ./gradlew run       → writes a static snapshot: site/data.json
        └─ live:    ./gradlew runServer → Server.kt, a local HTTP server (JDK built-in,
                                           no framework) exposing GET /api/pool
        ▼
site/index.html (static page)        ── loads the snapshot on open; the "Update" button
                                         calls the local server's /api/pool for a genuine
                                         live re-fetch + re-score, with a spinner while it runs
```
- **Source-agnostic:** every listing carries a `source`. The score never depends on where it came from.
- **Deterministic:** identical car attributes → identical scores → identical Total (fixed reference tables in `data/reference.json`).
- **Per-column independence:** each column has its own algorithm; `Total = sum of the active, available columns`.
- **A browser button can't run a local program on its own** — that's a browser sandboxing rule, not a design choice. `Server.kt` is the smallest thing that can listen locally so a click can trigger a real Kotlin fetch+rescore; see `docs/algorithm.md` for why it's needed and why it's the JDK's built-in `HttpServer` rather than a new framework dependency.

## Columns
Identity: index · name · first registration · fuel · price · km · URL · city (last).
Scored (0–100, toggle-able), **on by default**: Engine · Mileage · Value · Transmission · Consumption · ConsumptionUrban · TrunkSize.
Scored, **off by default** (placed last — situational / source-dependent, tick on to include): Owners · TireSeason · MinorDamage · Commercial.
Full rules & pseudocode: [`docs/algorithm.md`](docs/algorithm.md). Reliability (ROI) method: [`docs/reliability.md`](docs/reliability.md).

### Independent score plugins & Avg
ROI is not one of the total columns — it is the first **independent score plugin** (0–10, advisory, never summed into Total). Anyone can add another (e.g. a carwow-style rating) by dropping a `plugins/<name>.md` and registering it; a new advisory column appears. See [`plugins/README.md`](plugins/README.md). Use these to accept/skip a car regardless of its Total.
**Avg** sits right before ROI and is the default sort key: `mean(Total normalized to 0–10, every available independent plugin score)` — the one place Total and the plugins are combined into a single "best overall" figure, highest first.

### Filter panel — brand / source / model, live-refreshed on Update
A hamburger icon (☰) opens a drawer with **Brands**, **Listing source**, **Only show** (comma-separated terms, ALL must appear in the row's full name — make + model + trim, e.g. `Corolla, Touring Sports` for just the Corolla estate), and **Hide models** — all staged, applied only on **Update**. "Only show" matches against the full name rather than just the base model because a variant like the estate/combi trim (Autohero calls it "Touring Sports") lives in `subType`/`subTypeExtra`, not the `model` field itself. Clicking Update shows a spinner, calls the local server's `GET /api/pool` for a **genuine live re-fetch + re-score** (Autohero + AutoScout24, through the same scoring pipeline as the offline snapshot), then filters/sorts/caps the fresh pool to 50 under your selections — so excluding a brand backfills with the next-best contenders instead of shrinking the list. Ford/Kia show as disabled "no data yet" chips (separate future work; they activate automatically once added, no page changes needed). If the local server isn't running, Update falls back to re-ranking whatever's already loaded instead of breaking. See `docs/algorithm.md` for the full mechanism.

### Two live listing sources
**Autohero** and **AutoScout24**. AutoScout24's list-view API is missing several fields Autohero's has (owners, commercial flag, urban consumption, door count) — see `docs/algorithm.md` for the exact gaps and defaults. Notably, `ConsumptionUrban` is on-by-default and AS24 never reports it, so **AS24 cars don't reach the default top 50 (0/50) — untick `ConsumptionUrban` in the drawer to see them compete (verified: 17/50 once it's off)**.

### Cross-source de-duplication
`dedupeAcrossSources` keeps one listing per (name, price, color) when 2+ sources report the same car — see `docs/algorithm.md`. Two sources are live, but it's still a no-op: neither Autohero nor AutoScout24 exposes `color`, so nothing is ever considered a match.

### Car length (Autohero only, opt-in)
Autohero's bulk search API never reports body style or dimensions — `model`/`subType`/`subTypeExtra` are only engine size + trim badge ("Corolla" / "2.0 Hybrid" / "Team D"), identical for a hatchback and its Touring Sports estate. The individual listing's detail page does carry it, so a **Fetch lengths** button (above the table) does one extra page-fetch per row for the currently shown results and adds a **Length** column (e.g. `465cm (Kombi)`), plus a **Min length (cm)** input that hides rows below the threshold. Gated to ≤15 shown rows (narrow with "Only show" / brand filters first) since it's a per-row HTTP request, not part of the bulk pool fetch.

### Hide / exclude a column (fairness across sources)
In the page, untick a column's header checkbox → it fades and is removed from every Total (which re-sums and re-sorts). A per-cell `X` marks a value as N/A (never counted). Use this when a field (e.g. tire season) exists on Autohero but not on AutoScout, so the comparison stays fair.

## Base filters
Brands Audi/BMW/Mercedes-Benz/Porsche/Honda/Toyota/Hyundai · first reg ≥ 2018 · €10k–20k · Germany · ≤3 owners · accident-free · ≥4 doors (no 2-door coupés) · **petrol / hybrid / plug-in hybrid only** (no diesel, no pure-electric) · **excludes reserved / sale-in-progress listings** · **trunk ≥ 360L when known** (listing's own value, else the per-model reference estimate; fully unknown is not excluded).

## Run
```bash
./gradlew test          # run the test suite
./gradlew run           # regenerate the static snapshot: site/data.json (offline batch)
./gradlew runServer     # start the local live server (what the page's Update button calls)
```
With the server running, open **http://localhost:8081** — not an IDE's built-in file-preview URL (e.g. IntelliJ's `localhost:6334x`); that only serves the static HTML and has no `/api/pool` behind it. `http://localhost:8081` serves the page itself, same origin as the API — no separate static-file server needed.

**If `./gradlew` fails with a cryptic error like `What went wrong: 25.0.3`:** Gradle 8.10.2 doesn't support very new JDKs, and your shell's default `java` may not be compatible. Create a local `gradle.properties` (gitignored — this is machine-specific, don't commit it) with `org.gradle.java.home=/path/to/a/compatible/JDK` (Java 21 LTS is known to work), or export `JAVA_HOME` to that path before running `./gradlew`.

## Status
- **Phase 1 (done):** algorithm (`docs/` + Kotlin) + page (`site/`) + independent-score plugins (`plugins/`).
- **Phase 2 (done):** JUnit suite — **60+ tests, 0 failures** (`src/test/kotlin`): base-filter gate (incl. sale-in-progress, trunk<360L), determinism, ROI independence, mileage-threshold penalty, column scores, X/toggle exclusion, default-active columns, cross-source dedup, full-pool sizing, sort, eviction, edge cases, plus ingestion-parsing fixtures.
- **Phase 3 (done):** live server (`Server.kt`) — the Update button does a real fetch+rescore, not just a client-side re-rank of a static snapshot.
- **Data:** all 7 brands researched (Honda & Hyundai ROI added). Two live sources: Autohero + AutoScout24 (`Ingest.kt`), both fetchable offline (`./gradlew run`) or live (`./gradlew runServer`).
- **Pending work (separate, larger tasks — see `docs/algorithm.md`):** Ford & Kia reliability research + fetch; tire-season, trunk-size, color, owners, and commercial-flag ingestion from listing detail pages (currently `X`/model-lookup/default/`null`).
- **mobile.de was evaluated and ruled out** — a hard `403` from Akamai bot-management that applies to every client type equally (verified with `curl` directly), not something this project will attempt to evade. It has no representation anywhere in the codebase.
- **Fixed model-name matching bugs:** body-style suffixes baked into Autohero's `model` field (e.g. "Auris Touring Sports") and a Mercedes-Benz `generation()` substring collision (GLA/CLA-Klasse silently misclassifying as A-Klasse) were both silently dropping cars from the table. See `docs/algorithm.md` for the fix and the regression tests in `ScoringTest.kt`.

## References
- Notion — [Reliability Knowledge Base](https://app.notion.com/p/3e76f8b68bea81ea9cf0ec6e7f3d809e) · [Car Evaluation framework](https://app.notion.com/p/3e76f8b68bea8150a0a7c121d078504a) · [Final Candidate](https://app.notion.com/p/3e76f8b68bea8153b125eeb95032475c) · [Autohero AI Search (fetch recipe)](https://app.notion.com/p/3e76f8b68bea819fb075dd9506db194c)
- [Google Sheet "Car candidates"](https://docs.google.com/spreadsheets/d/1VxyCg5jhEEhZj-nIlLhTL7miNjSBUeo13BhUVbypQQ8/edit) (legacy / optional export)
- GitHub Pages table: static snapshot only (no live server on GitHub Pages — that only runs on your own machine via `./gradlew runServer`). _Enable Pages on this repo (Settings → Pages → deploy from `main` / `site` folder) — URL will appear here._
