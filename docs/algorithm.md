# Car Ranking — Algorithm Spec (single source of truth)

This file defines HOW every number in the ranking is produced. The Kotlin code in `src/main/kotlin/ranker/` implements this exactly; the GitHub Pages table in `site/` only displays and sorts the numbers. No fetching or math happens in the page.

## Principles
- **Source-agnostic:** a listing may come from Autohero, AutoScout24, mobile.de, or be pasted manually. Each carries a `source` tag. The algorithm never depends on the source. **Ingestion status:** Autohero and AutoScout24 are both live (`data/listings.json` combines them). mobile.de is NOT fetched — it's protected by Akamai bot-management (a hard `403`), and getting past that reliably needs solving JS/fingerprint challenges, which this project deliberately does not automate. It stays a disabled "no data yet" chip in the filter panel.
- **Deterministic:** identical car attributes → identical column scores → identical Total, every run. Reference tables are fixed inputs (`data/reference.json`).
- **Per-column independence:** each column has its OWN algorithm and reads only the raw car factors it needs — it never reads another column's score.
- **Car-specific:** scores reflect THIS car (its real mileage), not the generic new-car rating. A car past a known failure km is penalised even if the model is generally reliable.

## Base filters (a listing must pass ALL to enter the table)
- `make ∈ {Audi, BMW, Mercedes-Benz, Porsche, Honda, Toyota, Hyundai}`
- `firstRegistrationYear ≥ 2018`
- `10000 ≤ priceEur ≤ 20000`
- `country == DE`
- `owners ≤ 3`
- `accidents == 0` (major accidents). **Minor pre-existing damage is NOT filtered** (see Minor damage column).
- **Fuel:** petrol, hybrid, or plug-in hybrid (petrol+electric) only → **diesel and pure-electric excluded.** Applied server-side via an OR-group on `fuelType ∈ {1039 petrol, 1041 hybrid, 1046 phev-or-hybrid}`. ⚠️ Autohero's `fuelType 1046` covers BOTH plug-in hybrids and plain hybrids — disambiguate at ingestion with `isPluginSystem` (`true` → `phev`, `false` → `hybrid`); getting this wrong also wrongly applies the Engine column's PHEV battery-risk penalty to plain hybrids.
- **Sale in progress:** listings already reserved/mid-sale are excluded. Autohero: `retailAdState == "reserved"`.
- **Doors:** ≥4 → excludes 2-door coupés/cabrios/roadsters and 3-door hatches; sedans, hatchbacks, SUVs, estates, MPVs pass. Applied **server-side at fetch** via Autohero's `doorCount >= 4` filter (confirmed working; the value is not returned in the list object, so it can't be displayed — only filtered). For manually-pasted listings, set `doors` on the Listing and the offline gate `isMultiDoor` enforces the same rule.
- **Trunk size ≥ 360L:** `resolveTrunkLitres(listing, ref)` = the listing's own `trunkLitres` **when the source reports it** (neither Autohero's nor AutoScout24's list-view API does — only a future detail-page scrape would), else falls back to `resolveBootLitres(make, model, ref)` (the per-model reference estimate). A car is excluded only when a value is **resolved and it's < 360L** — fully unknown (neither the listing nor the table has one) is **not** excluded, to avoid silently dropping every model/source we don't have boot data for.

## Columns
Identity (no score): `index`, `name` (Make · Model · Variant), `make`, `model` (exposed separately from `name` so the page can filter by brand/model reliably rather than parsing a combined string), `firstRegistration`, `fuelType`, `url` (clickable).

## Full pool shipped; the page owns the top-50 cut
`site/data.json` ships **every eligible scored car** (`buildTable(..., maxRows = listings.size)`), not just the top 50 — already sorted by Total with the standard tie-break (mileage asc → price asc → id asc). The page is responsible for capping to 50, both on first load and after a filter change, so brand/model/source selection can re-derive a full 50-car table instead of merely hiding rows from an already-capped set (see next section for why that distinction matters).

## Filter panel (hamburger drawer) — brand / source / model, staged behind "Update"
A hamburger icon (☰) opens a slide-in drawer with three staged filters, applied only when **Update** is clicked (checkbox/text changes inside the drawer do nothing to the table until then):
- **Brands:** a checkbox chip per distinct `ScoredCar.make` in the data, all checked by default, plus **Ford** and **Kia** shown as disabled "no data yet" placeholders (no fetch/reliability research exists for them — separate future task; they activate automatically once added, no page changes needed).
- **Listing source:** a checkbox chip per distinct `ScoredCar.source` — **Autohero** and **AutoScout24** are both live — plus **mobile.de** as a disabled "no data yet" placeholder (blocked by Akamai bot-management; see Principles above).

### AutoScout24 ingestion — known gaps
AutoScout24's search-result page embeds real listing data as a Next.js `__NEXT_DATA__` JSON payload (`scripts/fetch_autoscout24.py`, source-agnostic output, same `Listing` shape as Autohero). It's a **sample** (5 pages / 100 listings per brand, ~600 total), not an exhaustive scrape of AS24's full national marketplace (tens of thousands of matching listings) — proportionate to the Autohero fetch, not an aggressive full-market crawl. Fields **not** available at the list-view level (would need per-listing detail-page scraping, same class of gap as Autohero's tire-season/trunk-size/color):
- **`owners`** defaults to `0` (unverified — not a real "zero owners" claim).
- **`commercial`** defaults to `false` (unverified).
- **`consumptionUrban`** stays `null` — AS24's list view gives only one combined L/100km figure. This means every AS24 car is missing a column that's **on by default**, so it starts every ranking at a real disadvantage regardless of the car's other merits. **Untick `ConsumptionUrban` (or `Consumption`) in the filter drawer to see AS24 cars compete fairly** — verified: 0 AS24 cars in the default top 50, 17 once that column is off.
- **`doors`** inferred from AS24's `variant` text (coupé/cabrio/roadster keywords → excluded; everything else assumed multi-door), not a real door count.
- Hybrid vs PHEV is a text heuristic (AS24's fuel taxonomy doesn't separate them, same ambiguity Autohero had before `isPluginSystem` fixed it there — no equivalent flag exists on AS24).
- **Hide models:** a free-text, comma-separated field matched case-insensitively as a substring against `ScoredCar.model` (e.g. typing `Yaris` hides every Yaris variant).

**Why "Update" re-ranks instead of just hiding rows:** the brand/model chips used to filter the already-capped top-50 live — so unchecking a brand that held many of those 50 slots (e.g. Toyota, which dominates via hybrid urban-consumption scores) just **shrank the visible list** instead of backfilling with the next-best BMW/Audi/etc. that would have made top-50 had that brand been excluded from the start. Clicking **Update** now:
1. Takes the **full** pool (`DATA.rows`, from the previous section).
2. Filters by the drawer's staged brand-exclusion, source-exclusion, and hide-models selections.
3. Sorts by `total` with the same tie-break as `buildTable` (mileage asc → price asc → id asc).
4. Takes the first 50, re-assigns `index` 1..N — this becomes `workingSet`, the pool the rest of the page (search box, sort clicks, column toggles) operates on.

This is filter+sort+cap over already-computed scores — no score is invented and nothing is fetched, consistent with "the page does no math." The top search box, column-score toggles, and Avg/Total stay **live/instant** as before (they narrow what's visible within the current `workingSet` or change its scoring inclusion, not which cars are in it — no re-rank needed).

Scored (0–100 each, independent, **toggle-able**, summed into Total):

| # | Column | Higher score means |
|---|--------|--------------------|
| 1 | ROI | more reliable model (see below) |
| 2 | Engine | healthier engine at this car's mileage |
| 3 | Mileage | less wear for its age |
| 4 | Price (Value) | cheaper vs expected market price |
| 5 | Transmission | more desirable/robust gearbox |
| 6 | Owners | fewer previous owners |
| 7 | Consumption | lower L/100km (combined) |
| 7b | ConsumptionUrban | lower L/100km (urban/city) — the more relevant figure for a Berlin-city use case |
| 8 | Trunk size | larger boot |
| 9 | Tire season | data present & favourable |
| 10 | Minor damage | no/fewer recorded minor damages |
| 11 | Commercial | private ownership (not ex-fleet/commercial) |

**Display order:** Engine, Mileage, Value, Transmission, Consumption, ConsumptionUrban, TrunkSize come first and are **ON by default**. **Owners, TireSeason, MinorDamage, Commercial are placed last and are OFF by default** — situational/source-dependent signals that can skew cross-source comparisons; tick them back on in the page when wanted. `DEFAULT_ACTIVE_COLUMNS` = the first six; this is also what the *stored* Total in `site/data.json` is computed from (the page recomputes live as columns are toggled).

## Total, Avg & sorting
- **Column order:** identity (index · name · reg · fuel · price · km) → **Avg** → **ROI (Independent score, 0–10)** → **Total** → Engine/Mileage/Value/Transmission/Consumption/TrunkSize (on by default) → Owners/TireSeason/MinorDamage/Commercial (off by default) → **City (last)**.
- **ROI is INDEPENDENT:** it is a 0–10 reliability score (same 0–10 scale as the Notion pages), shown in its own column, sortable, and **NOT included in the Total**. It doesn't influence ranking unless you sort by it.
- **Independent scores are plugins.** ROI is the first; any number of advisory scores (e.g. a `carwow`-style rating) can be added, each with its own algorithm and scale, each its own column, none feeding the Total. See [`plugins/README.md`](../plugins/README.md).
- `Total = Σ score(col) over the currently-ACTIVE total-columns that are not `X` for this row.` Default-active = Engine, Mileage, Value, Transmission, Consumption, TrunkSize; the other four start OFF (see above) and can be ticked on.
- **Avg (0–10, the default sort key):** the single best-overall figure — `mean(Total normalized to 0–10 across the active total-columns, every AVAILABLE independent plugin score normalized to 0–10)`. With just ROI registered: `Avg = mean(Total/(activeCols×100)×10, ROI)`. Recomputes live with the column toggles, same as Total. This is the one place Total and the independent plugins are combined — table sorts by Avg descending by default ("best of both, top to bottom").
- A column can be toggled **inactive** (excluded from every row's Total; shown faded) — used when a field isn't comparable across sources (e.g. tire season present on Autohero but not AutoScout).
- A single cell can be `X` (not available) → that cell contributes 0 and is skipped for that row only.
- The table sorts by any numeric column; default = Total descending. `index` = position after that sort.

## Per-column algorithms (pseudocode)
Helpers: `clamp(x,a,b)`, `age = max(1, CURRENT_YEAR - firstRegistrationYear)`, `kmPerYear = mileageKm / age`.

**ROI model-name resolution:** `resolveRoi(make, model, year)` first tries an exact `"MAKE|MODEL|GEN"` / `"MAKE|MODEL|*"` match. For wildcard-generation makes (Toyota/Honda/Hyundai) it then falls back to the longest known base model that is a **whole-word prefix** of `model` — e.g. Autohero's `model = "Auris Touring Sports"` resolves to the `"Auris"` reference entry, so the estate body-style suffix doesn't silently drop the car from the table. `resolveBootLitres` uses the same fallback, but a body variant with its own accurate entry (e.g. `"Auris Touring Sports": 530`) is matched directly first rather than reusing the hatchback's figure. BMW/Audi/Mercedes don't need the fallback — they enumerate body-variant model names explicitly (e.g. `"A3 Sportback"` vs `"A3 Limousine"`).

⚠️ **`generation()` check-order pitfall:** for Mercedes, `"a-klasse"` is a literal substring of `"gla-klasse"` and `"cla-klasse"`. If those are checked in the wrong order, GLA/CLA silently misclassify as A-Klasse's generation (which has no reference entry for them) and vanish from the table with no error. Always check the longer/more-specific model tokens (`gla`, `cla`) before shorter ones they contain (`a-klasse`). This class of bug — a listing being silently dropped by a name-matching mismatch rather than failing loudly — is exactly why `passesBaseFilter`/`resolveRoi` are unit-tested per brand+model, not just spot-checked.

**1. ROI (0–10, INDEPENDENT — not summed into Total)** — holistic **car-buying** score (full rules: [`plugins/roi.md`](../plugins/roi.md)). Starts from the reliability index `reference.roi["MAKE|MODEL|GEN"]` (TÜV+ADAC+DEKRA blend, same 0–10 scale as the Notion pages), then subtracts **hidden-flag** penalties — Toyota-not-hybrid, boot<360 L, high km/yr, overpriced, prior damage, previous owners, **commercial/fleet use**, no service history — and appends a **Verdict (Yes/No)** + the flag list to the cell note. Shown before Total; sortable; **excluded from Total**. Unknown model → car excluded.

**2. Engine (0–100):**
```
base       = reference.engineGrade[platform] * 10        # 0..100
threshold  = reference.engineThresholdKm[platform]       # known major-failure km, may be null
penalty    = if km >= threshold      -> clamp((km-threshold)/threshold * 40, 0, 40)
             else if km >= 0.9*thr   -> 8                 # approaching threshold
             else                    -> 0
powerAdj   = if kw < 70 -> -10 ; if kw >= 110 -> +5 ; else 0
fuelRisk   = (fuel==PHEV -> 10) + (fuel==DIESEL && kmPerYear < 8000 -> 8)   # city diesel DPF risk
score      = clamp(base - penalty + powerAdj - fuelRisk, 0, 100)
```

**3. Mileage (0–100):**
```
base    = clamp(100 * (25000 - kmPerYear) / 20000, 0, 100)   # 5k/yr→100, 25k/yr→0
absPen  = if km > 200000 -> clamp((km-200000)/50000 * 20, 0, 20) else 0
svcBonus= hasFilledServiceBook ? 5 : 0
score   = clamp(base - absPen + svcBonus, 0, 100)
```

**4. Price / Value (0–100):**
```
expected = expectedMarketPrice(model, year, mileage)   # per-model baseline (median + mileage/age adjustment) from the loaded pool
score    = clamp(50 + (expected - askingPrice) / expected * 100, 0, 100)   # cheaper than expected → >50
```

**5. Transmission (0–100):**
```
type = reference.gearTypeMap[gearType]        # automatic | manual | unknown
score = when(type) { automatic -> 85 ; manual -> 70 ; else -> 60 }
if (type==automatic && model ∈ reference.dctRiskModels) score = 60   # fragile dual-clutch
```

**6. Owners (0–100):** `owners ≤ 1 → 100 ; 2 → 80 ; 3 → 60 ; else 40`.

**7. Consumption (0–100):** `c = fuelConsumptionCombined`; `score = clamp(100 * (9 - c) / (9 - 4), 0, 100)` (≤4 L→100, ≥9 L→0). PHEV/hybrid scored on the same electric-adjusted figure when provided; missing → `X`.

**7b. ConsumptionUrban (0–100):** `c = fuelConsumptionUrban` (Autohero's `fuelConsumption.city`); `score = clamp(100 * (10 - c) / (10 - 3), 0, 100)` (≤3 L→100, ≥10 L→0). Wider/shifted band than combined since urban figures run higher for non-hybrids (stop-start traffic) but can be *lower* for hybrids (electric motor does more of the low-speed work) — reflects the user's actual Berlin-city use case more directly than the combined figure. Missing → `X`.

**8. Trunk size (0–100):** `litres = resolveTrunkLitres(listing, ref)` (listing's own value first, else the reference-table estimate — same resolution as the base filter above); `score = clamp(100 * (litres - 300) / (500 - 300), 0, 100)` (≤300 L→0, ≥500 L→100). Missing (neither source) → `X`. Note distinguishes `"(listing)"` vs `"(est.)"`.

**9. Tire season (0–100):** `all-season/both → 100 ; single season → 70 ; missing → X`.

**10. Minor damage (0–100):** `numberOfDamages==0 OR damageList empty → 100 ; else clamp(100 - 8*count, 60, 100)`. Damage type text shown alongside.

**11. Commercial (0–100):** `private → 100 ; commercial/fleet (VAT-deductible) → 50` (downgrade, not a reject — negotiating leverage). Detected from Autohero `vatType==1054` (VAT-reclaimable = ex-business); margin scheme (`1053`) = private. For manual listings, set `commercial` on the Listing.

## Cross-source de-duplication (`dedupeAcrossSources`)
When the SAME car is reported by more than one source, keep only the highest-priority source's listing before scoring/ranking. `SOURCE_PRIORITY = [autohero, autoscout24, mobile.de]` (earlier = kept).
```
if distinct(listings.source).count < 2: return listings unchanged   # nothing to dedupe with one source
key(l) = (l.color known?) ? (normalize(name), priceEur, normalize(color)) : NO_KEY   # color unknown -> never keyed
group listings with a key by that key; within each group, keep only the lowest-SOURCE_PRIORITY-index entry
listings without a key (color unknown) always stay distinct
```
Deliberately requires **both** ≥2 sources present **and** a known `color` before two listings are even considered a match — grouping purely by (name, price) with color unset would risk merging two genuinely different cars that just happen to share a name and price (plausible for same-spec batches). Runs inside `buildTable`, right after the base-filter gate and before scoring, so duplicates never skew the price-baseline stats either.

**Status today:** effectively a no-op. Two sources are now live (Autohero + AutoScout24, so the ≥2-sources condition IS met), but **neither exposes a `color` field**, so `color` stays listing-level `null` for every row — the second guard (known color required) means nothing is ever merged. Verified: 0 non-null `color` values in `data/listings.json`. Ready to activate once a source with real color data exists (a future detail-page scrape, or mobile.de).

## Insertion / eviction
Add a listing → base-filter gate → dedup → score all columns → compute Total → insert in sorted order. The **page's working set** (post filter-panel Update, or on first load) stays ≤ 50 — if a new/boosted car's Total lands it in the top 50, the previous lowest-Total row drops out of `workingSet`, same eviction behavior as before. (`site/data.json` itself now ships the *full* eligible pool, uncapped — see "Full pool shipped" above — so there's nothing to evict at that layer; the cap moved to the page.)
