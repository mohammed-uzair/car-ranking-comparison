# Car Ranking Comparison

**[`site.md`](site.md) is the contract** — the invariants that must always hold and the verification checklist
for any change here. Read it before touching `site/index.html`, the Kotlin scoring/ingestion, or reference data.

A transparent, deterministic used-car ranking. **The repo is the single source of truth** for the data, the algorithm, and the display — and it's **one language, Kotlin, end to end** (ingestion, scoring, the live server). No Python, no other language anywhere in this repo.

New here (including an AI agent setting this up for the first time)? **Read this Setup section first**, then [`site.md`](site.md) before changing anything — it's the contract this project runs on. Just want to know how to use the website itself, no technical background needed? See **[`HOW_TO_USE.md`](HOW_TO_USE.md)**.

## Setup

**Prerequisites:** a JDK (21 LTS known-good — see the troubleshooting note under [Run](#run) if your default `java` is newer and `./gradlew` fails), and `git`. No other tools, no Node, no Python — this is a single-language Kotlin/Gradle project.

```bash
git clone https://github.com/mohammed-uzair/car-ranking-comparison.git
cd car-ranking-comparison
./gradlew test          # confirms the toolchain works — should finish green, 100+ tests
./gradlew runServer      # starts the local live server
```

Then open **http://localhost:8081** in a browser. That's the whole setup — no API keys, no accounts, no config files to fill in. The page loads a static snapshot (`site/data.json`) instantly; clicking **Update** does a real live fetch from Autohero + AutoScout24 through the local server (takes 15–40s, see [Architecture](#architecture) below for why that's a local server and not, say, a cloud function).

If you only want to read/modify the scoring logic without running a live fetch, `./gradlew test` and `./gradlew run` (regenerates the static snapshot from a fixture file, no network) are enough — `runServer` is only needed for the live "Update" button.

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
Identity: index · name · first registration · fuel · price · km · URL.
Scored (0–100, toggle-able), **on by default**: Engine · Mileage · Value · Transmission · ConsumptionUrban · TrunkSize.
Consumption (combined), Owners, TireSeason, MinorDamage, Commercial, and City are **hidden from the table display** (2026-10-03, user request) but still exist in the data — Consumption still silently contributes to Total exactly as before (it just has no visible column/toggle anymore); the rest were already off-by-default and now have no UI at all.
Full rules & pseudocode: [`docs/algorithm.md`](docs/algorithm.md). Reliability (ROI) method: [`docs/reliability.md`](docs/reliability.md).

### Independent score plugins & Avg
ROI and **OwnershipCost** are independent score plugins (never summed into Total). **ROI** (0–10) is the reliability judgment, TÜV/ADAC/DEKRA-backed. **OwnershipCost** (0–100, added 2026-10-03, user-defined rules) blends five sub-scores — annual fuel cost (5,000km/yr assumption, €1.70/L), German Kfz-Steuer (real 2026 CO2+displacement formula), 5-year/30,000km resale value, Berlin↔Amsterdam + Berlin↔Hamburg round-trip fuel cost, and drive comfort (80% city/20% Autobahn weighting). Resale value and comfort are **segment-level estimates** (Kleinwagen/Kompaktklasse/Kompakt-SUV/Mittelklasse-SUV/Mittelklasse-Sedan/Van), not per-model research — per-model research for those two specifically turned up unreliable/spam-adjacent sources, unlike ROI's systematic data; see `data/reference.json`'s `_segmentNote` and `plugins/ownership-cost.md`. Anyone can add another plugin by dropping a `plugins/<name>.md` and registering it; a new advisory column appears automatically. See [`plugins/README.md`](plugins/README.md).
**Avg** sits right before ROI and is the default sort key: `mean(Total normalized to 0–10, every available independent plugin score)` — now blends Total, ROI, and OwnershipCost together, highest first.

### Filter panel — brand / source / model, live-refreshed on Update
A hamburger icon (☰) opens a drawer with **Brands**, **Listing source**, **Only show** (comma-separated terms, ALL must appear in the row's full name — make + model + trim, e.g. `Corolla, Touring Sports` for just the Corolla estate), and **Hide models** — all staged, applied only on **Update**. "Only show" matches against the full name rather than just the base model because a variant like the estate/combi trim (Autohero calls it "Touring Sports") lives in `subType`/`subTypeExtra`, not the `model` field itself. Clicking Update shows a spinner, calls the local server's `GET /api/pool` for a **genuine live re-fetch + re-score** (Autohero + AutoScout24, through the same scoring pipeline as the offline snapshot), then filters/sorts/caps the fresh pool to 50 under your selections — so excluding a brand backfills with the next-best contenders instead of shrinking the list. All 10 brands are live (no placeholder chips currently). If the local server isn't running, Update falls back to re-ranking whatever's already loaded instead of breaking. See `docs/algorithm.md` for the full mechanism.

### Two live listing sources
**Autohero** and **AutoScout24**. AutoScout24's list-view API is missing several fields Autohero's has (owners, commercial flag, urban consumption, door count) — see `docs/algorithm.md` for the exact gaps and defaults. Notably, `ConsumptionUrban` is on-by-default and AS24 never reports it, so **AS24 cars don't reach the default top 50 (0/50) — untick `ConsumptionUrban` in the drawer to see them compete (verified: 17/50 once it's off)**.

### Cross-source de-duplication
`dedupeAcrossSources` keeps one listing per (name, price, color) when 2+ sources report the same car — see `docs/algorithm.md`. Two sources are live, but it's still a no-op: neither Autohero nor AutoScout24 exposes `color`, so nothing is ever considered a match.

### Body type (Autohero only, real server-side filter)
Autohero's bulk search API never reports body style via its own field names — `model`/`subType`/`subTypeExtra` are only engine size + trim badge ("Corolla" / "2.0 Hybrid" / "Team D"), identical for a hatchback and its Touring Sports estate — and guessing plausible filter field names/values (`bodyType: "StationWagon"`, `"Kombi"`, etc.) was uniformly rejected. The actual mechanism, found by capturing Autohero's **own** site's network request (a HAR export) rather than guessing further: the field really is `bodyType`, but the value is an **array of numeric codes**, not a string — e.g. `{"field":"bodyType","op":"eq","value":[1023]}` for "Combination" (wagon/estate). Confirmed live and wired into `Ingest.kt`'s `autoheroFilter()` as a real, server-side-narrowed query (not fetch-then-filter) — the drawer's **Body type** checkboxes (Combination, SUV, Van/Minibus, Pickup, Convertible — the 5 codes verified against real matching models) are sent straight into the Autohero search request itself on Update. AutoScout24 has no equivalent; use "Only show" (text matching) for it there.

### Hide / exclude a column (fairness across sources)
In the page, untick a column's header checkbox → it fades and is removed from every Total (which re-sums and re-sorts). A per-cell `X` marks a value as N/A (never counted). Use this when a field (e.g. tire season) exists on Autohero but not on AutoScout, so the comparison stays fair.

## Base filters
Brands Audi/BMW/Mercedes-Benz/Porsche/Honda/Toyota/Hyundai/Mazda · first reg ≥ 2018 · €10k–22k · Germany · ≤3 owners · accident-free · ≥4 doors (no 2-door coupés) · **petrol / hybrid / plug-in hybrid only** (no diesel, no pure-electric) · **excludes reserved / sale-in-progress listings** · **trunk ≥ 360L when known** (listing's own value, else the per-model reference estimate; fully unknown is not excluded).

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
- **Data:** all 10 brands researched (Audi/BMW/Mercedes/Porsche/Honda/Toyota/Hyundai/Mazda/Ford/Kia — Mazda/Ford/Kia ROI is standing/ranking-based: TÜV/ADAC/DEKRA coverage didn't surface precise current-generation Mängelquote % for every model, see `docs/reliability.md`). Two live sources: Autohero + AutoScout24 (`Ingest.kt`), both fetchable offline (`./gradlew run`) or live (`./gradlew runServer`).
- **Pending work (separate, larger tasks — see `docs/algorithm.md`):** Kia Rio/Soul/XCeed reliability research (no source found yet, left out rather than guessed — see `roiPending` in `data/reference.json`); tire-season, trunk-size, color, owners, and commercial-flag ingestion from listing detail pages (currently `X`/model-lookup/default/`null`); per-model (not segment-level) resale-value and drive-comfort research for the `OwnershipCost` plugin, if ever revisited — see `site.md`'s "Known current gaps".
- **mobile.de was evaluated and ruled out** — a hard `403` from Akamai bot-management that applies to every client type equally (verified with `curl` directly), not something this project will attempt to evade. It has no representation anywhere in the codebase.
- **Fixed model-name matching bugs:** body-style suffixes baked into Autohero's `model` field (e.g. "Auris Touring Sports") and a Mercedes-Benz `generation()` substring collision (GLA/CLA-Klasse silently misclassifying as A-Klasse) were both silently dropping cars from the table. See `docs/algorithm.md` for the fix and the regression tests in `ScoringTest.kt`.

## Contributing

This repo is open for contributions. `main` is protected — pushes go through a pull request (except for the
repo owner). To contribute:

1. Fork the repo, make your change on a branch, open a PR against `main`.
2. Before opening it, run `./gradlew test` locally — it must pass (100+ tests).
3. If your change touches `site/index.html`, the Kotlin scoring/ingestion, or `data/reference.json`, **read
   [`site.md`](site.md) first** — it's the enforced contract (invariants + a verification checklist), not just
   a suggestion. A PR that violates it (e.g. a new brand with no researched reliability data, or a live fetch
   that can silently truncate without telling the user) will likely be asked to fix that before merging.
4. Reference data changes (brand reliability, segment estimates, etc.) should cite a real source in the PR
   description — see `docs/reliability.md` and `data/reference.json`'s `_note`/`_segmentNote` for the standard
   this project holds itself to (and a cautionary tale about a content-farm domain that nearly got cited as a
   source — don't let that happen to your PR either).

## References
- Notion — [Reliability Knowledge Base](https://app.notion.com/p/3e76f8b68bea81ea9cf0ec6e7f3d809e) · [Car Evaluation framework](https://app.notion.com/p/3e76f8b68bea8150a0a7c121d078504a) · [Final Candidate](https://app.notion.com/p/3e76f8b68bea8153b125eeb95032475c) · [Autohero AI Search (fetch recipe)](https://app.notion.com/p/3e76f8b68bea819fb075dd9506db194c)
- [Google Sheet "Car candidates"](https://docs.google.com/spreadsheets/d/1VxyCg5jhEEhZj-nIlLhTL7miNjSBUeo13BhUVbypQQ8/edit) (legacy / optional export)
- GitHub Pages table: static snapshot only (no live server on GitHub Pages — that only runs on your own machine via `./gradlew runServer`). _Enable Pages on this repo (Settings → Pages → deploy from `main` / `site` folder) — URL will appear here._

## License
[MIT](LICENSE)
