# site.md — the contract

This file is the acceptance checklist for this project. Before calling any change to `site/index.html`,
`Scoring.kt`, `Ingest.kt`, `Server.kt`, or `data/reference.json` "done", it must satisfy every rule below.
Read this file first when picking up work here. `README.md` is the getting-started guide; `docs/algorithm.md`
and `docs/reliability.md` are the implementation detail. This file is the "does it actually work" bar.

## What this site is

A personal, deterministic used-car ranking tool for one specific car search (Audi/BMW/Mercedes-Benz/Porsche/
Honda/Toyota/Hyundai/Mazda, 2018+, €10–20k, Germany, accident-free, ≤3 owners, ≥4 doors, petrol/hybrid/PHEV
only). It is not a general-purpose car-search product — every rule below exists to serve that one search, not
to be configurable/generic for its own sake.

## Primary use cases (what actually gets used)

1. **Open the page, see the default ranked table.** Default filter state: all brands unchecked except Toyota,
   all sources unchecked except Autohero. The user narrows from there, not the other way around.
2. **Narrow with the drawer (☰), then Update.** Brands / Listing source / Only show / Hide models / Min
   length are all *staged* — they take effect only when Update is clicked, never live-as-you-type.
3. **Update does a REAL live re-fetch + re-score.** GitHub only stores the code; the local Kotlin server
   (`./gradlew runServer`, `http://localhost:8081`) is what actually runs when Update is clicked. A client-side
   re-filter of already-loaded data is not an acceptable substitute and must never be silently swapped in for
   this without telling the user (the existing fallback-on-server-down path is the one sanctioned exception,
   and it says so in the status line).
4. **Body type (Autohero only) is a REAL server-side filter.** Checkboxes in the drawer (Combination/SUV/Van/
   Pickup/Convertible) are sent straight into the live Autohero query itself as `{"field":"bodyType","op":"eq",
   "value":[<codes>]}` — Autohero only returns matching cars, not a fetch-everything-then-filter. See
   `AUTOHERO_BODY_TYPE_CODES` in `Ingest.kt`.
5. **Sort, toggle columns, read it, click through to the real listing.** Table interactions are pure
   client-side re-derivations of `workingSet` / `DATA.rows` — no network calls.

## The contract (must always hold)

- **Every make and every source that appears in `DATA.rows` must have a corresponding filter chip.** No make
  or source may ever be visible in the table without a checkbox that can exclude it. A live Update can surface
  a make/source the static snapshot didn't have — `buildBrandFilters()`/`buildSourceFilters()` must be
  re-called every time `DATA` is replaced, not just once at page load. (This exact bug — Mazda added to
  ingestion but with no chip, silently bypassing brand filtering — was fixed 2026-10-01; don't reintroduce it.)
- **Default state, every time `DATA` changes:** brands → Toyota only checked; sources → Autohero only checked.
  A chip that already existed keeps the user's current checked/unchecked state across a rebuild (don't reset
  their choices mid-session); only a genuinely new chip gets the default.
- **A listing may never appear in the table without a real ROI (reliability) reference entry**
  (`passesBaseFilter()`'s last line). Don't relax this gate to "show it anyway with ROI as X" — that's a
  deliberate quality bar, not an oversight. If a brand is added to ingestion, it is not done until
  `data/reference.json` has real researched ROI data for it, and `docs/reliability.md` documents the sourcing.
  If precise data isn't available (e.g. Mazda — standings/rankings only, no clean Mängelquote %), say so
  explicitly in both the reference file and the docs; don't present a lower-confidence number as equally rigorous.
- **One business-logic language: Kotlin, end to end.** No Python (or any other language) anywhere in the
  committed codebase, ever — this was an explicit, hard correction once already.
- **No new Gradle dependencies without the user's explicit sign-off.** Outbound HTTP, the local server, and
  JSON all use JDK-builtin APIs (`java.net.http.HttpClient`, `com.sun.net.httpserver.HttpServer`) plus the one
  existing `kotlinx-serialization-json` dependency — not Ktor, not a new HTTP/JSON library.
- **mobile.de stays removed.** Hard Akamai `403` for every client type, confirmed with curl directly — this is
  not a CORS problem a server-side fetch fixes, and evading bot detection is out of scope. Don't re-add it.
- **A length/size-type filter (or any filter needing a per-row extra fetch) must never silently blank the
  table** when the extra data hasn't been fetched yet. Fail visibly (a status message), not by returning zero
  rows that look indistinguishable from "nothing matched."
- **A paginated live fetch must never silently return a partial result as if it were complete.** Confirmed,
  reproducible root cause of a real reported bug (2026-10-01): `fetchAutohero()`'s pagination loop `break`d on
  any single transient non-200/parse failure anywhere in its ~15+ page sequence, with zero retry and zero
  signal to the caller — a "145 eligible" result looked identical to a healthy "442 eligible" one, just
  smaller, so a user had no way to know real listings (e.g. rarer trims) were simply never fetched that
  Update. Fixed with `withRetry()` (linear-backoff retry per page/request, unit-tested in `WithRetryTest`) plus
  `FetchResult(listings, complete, note)` threaded through `fetchAutohero()`/`fetchAutoScout24Brand()`/
  `fetchAllListingsLive()` → `SiteData.fetchWarning` → a visible `⚠` status message in `applyUpdate()`. Any
  future multi-request fetch (a new source, a new per-row enrichment) must follow this same pattern — retry
  transient failures, and surface incompleteness rather than hiding it.
- **There is exactly one filter surface: the ☰ drawer, applied on Update.** No standalone buttons for a single
  filter field (a "Fetch lengths now" button was removed 2026-10-01). Every staged filter runs automatically
  as part of a single Update click. Don't reintroduce a second manual trigger for something the drawer stages.
- **Autohero's `bodyType` search field IS real and does work server-side — this was wrongly reported as
  impossible for a while, and the correction matters methodologically, not just as a fact.** A min-length
  filter was originally built as a client-side-only workaround (fetch the pool, then fetch each Autohero
  row's own detail page, then filter) because every guessed `{"field":"bodyType","op":"eq","value":"..."}`
  request — tried with string values like `"StationWagon"`, `"Kombi"`, `"station_wagon"`, 17 variants total —
  was rejected with the same generic error, which was (wrongly) taken as proof the field wasn't filterable at
  all. The real cause was a **type mismatch, not a missing feature**: the field expects an ARRAY OF NUMERIC
  CODES (`{"field":"bodyType","op":"eq","value":[1023]}` for "Combination"/wagon), discovered only by
  capturing Autohero's own site's real network request (a user-provided HAR export) rather than continuing to
  guess string values. **Lesson: a GraphQL field rejecting every guessed VALUE is not evidence the field
  itself is unusable — a generic/uninformative error from an app-level (not GraphQL-schema-level) validation
  can just as easily mean the TYPE is wrong, not that the FIELD is wrong.** When a field name is confirmed to
  exist (e.g. found literally in the target site's own source, as `bodyType` was) but every value is rejected,
  capturing a real request beats continuing to guess. This is now implemented for real — see
  `AUTOHERO_BODY_TYPE_CODES` in `Ingest.kt` — and the old per-row-detail-page length-fetch machinery
  (`/api/lengths`, `fetchAutoheroDetail`, the Length column) was removed as superseded, per the user's
  explicit direction once the real filter was confirmed working.
- **A `hidden` attribute must be respected in CSS, not just assumed to work.** `.spinner{display:inline-block}`
  silently defeated the `hidden` attribute (an author-origin class rule beats the UA's `[hidden]{display:none}`
  at equal specificity, regardless of source order) — the Update spinner was animating non-stop from page load
  for an unknown period before this was caught. Any element toggled via `hidden` needs an explicit
  `.class[hidden]{display:none}` rule, not just the bare class rule plus the attribute.
- **The full Kotlin test suite must stay green** (currently 90 tests, `./gradlew test`) after every change,
  including page-JS-only changes that don't touch Kotlin at all — run it anyway, it's cheap and it's the
  regression net for the scoring/ingestion logic the page depends on.
- **Pushes go to `mohammed-uzair/car-ranking-comparison`, as a private repo, via the personal GitHub account**
  (`gh auth switch --user mohammed-uzair` → push → `gh auth switch --user mohammeduzair-oviva` back), never
  the default Oviva account.

## Verification checklist before calling a change "done"

A change is not done when it compiles. It is done when it has been checked against the thing a user will
actually hit:

1. `./gradlew test` — 0 failures.
2. If `site/index.html` changed: validate JS syntax (e.g. load the inline `<script>` body through `new
   Function(...)`), then **restart the actual running local server** (`pkill -f ranker.ServerKt` /
   `pkill -f "gradlew runServer"`, then `./gradlew runServer` again) — the JVM process does not pick up new
   Kotlin automatically, and a stale server will silently serve old behavior while looking like it works.
3. If a brand/source was added or changed: hit `/api/pool` for real (`curl` is fine, no browser required) and
   confirm the new make/source actually appears in `rows`, not just that the request succeeds.
4. If the drawer/filter UI changed: confirm the new make/source gets a chip (grep the served HTML, or trace
   `buildBrandFilters()`/`buildSourceFilters()`'s output against the live `DATA.rows`) — don't assume.
5. Confirm defaults: fresh load *and* post-Update should both show Toyota-only / Autohero-only checked unless
   the user already changed it in this session.
6. For anything claiming to fetch external data (Autohero detail pages, AS24, etc.), verify with a direct
   `curl` against the real endpoint before trusting the Kotlin code's handling of it — API behavior (redirects,
   field whitelists, rejected filters) has been wrong-by-assumption more than once in this project's history.

## Known current gaps (don't rediscover these from scratch)

- Ford/Kia are real, live brands now (added 2026-10-03, same research-backed pattern as Mazda). `BRAND_PLACEHOLDERS`
  is currently empty — no brand is left as a disabled "no data yet" chip. Kia Rio/Soul/XCeed have no researched
  ROI yet (`roiPending` in `data/reference.json`) and won't appear in the table until they do.
- Several columns (Consumption-combined, Owners, TireSeason, MinorDamage, Commercial, City) are hidden from
  the table display (2026-10-03, user request) but still exist in the underlying data — Consumption still
  silently contributes to Total exactly as before, it just has no visible column or toggle anymore.
- **OwnershipCost** (0-100, added 2026-10-03) is a second independent plugin alongside ROI — user-defined
  rules (fuel cost, Kfz-Steuer, resale, trip cost, comfort), see `plugins/ownership-cost.md`. Two of its five
  sub-components (resale value, drive comfort) are deliberately **segment-level estimates**
  (`data/reference.json`'s `segments`/`segmentData`), not per-model research — per-model research for those
  two specifically produced unreliable/spam-adjacent sources (two content-farm domains were caught and
  excluded mid-research: `wp.redrockla.edesigninteractive.com` appeared for both an Audi A1 AND an SUV
  comfort query, a reused spam template, not a coincidence). If asked to make resale/comfort per-model later,
  that's a real scope increase (~45 models × real research), not a quick tweak — size it before starting.
- AutoScout24's list API is missing several fields Autohero's has (owners, commercial flag, urban consumption,
  door count) — see `docs/algorithm.md` for exact defaults/gaps.
- Cross-source dedup (`dedupeAcrossSources`) is currently a no-op: neither live source exposes `color`, and the
  dedup guard deliberately requires a known color before merging two listings.
- Body type filtering is Autohero-only (real server-side filter, 5 codes confirmed: Combination/SUV/Van/
  Pickup/Convertible — "limousine"/"Small cars"/"Coupé-Sport" codes weren't found). AutoScout24 has no
  equivalent; exact car length (cm) isn't exposed anywhere anymore (the old per-row detail-page length fetch
  was removed as superseded once body type was confirmed working).
- Mazda's ROI is standing/ranking-based, not a precise current-generation defect-percentage calc like
  BMW/Audi/Mercedes — see `docs/reliability.md`.
