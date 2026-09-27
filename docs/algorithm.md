# Car Ranking — Algorithm Spec (single source of truth)

This file defines HOW every number in the ranking is produced. The Kotlin code in `src/main/kotlin/ranker/` implements this exactly; the GitHub Pages table in `site/` only displays and sorts the numbers. No fetching or math happens in the page.

## Principles
- **Source-agnostic:** a listing may come from Autohero, AutoScout, mobile.de, or be pasted manually. Each carries a `source` tag. The algorithm never depends on the source.
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

## Columns
Identity (no score): `index`, `name` (Make · Model · Variant), `firstRegistration`, `fuelType`, `url` (clickable).

Scored (0–100 each, independent, **toggle-able**, summed into Total):

| # | Column | Higher score means |
|---|--------|--------------------|
| 1 | ROI | more reliable model (see below) |
| 2 | Engine | healthier engine at this car's mileage |
| 3 | Mileage | less wear for its age |
| 4 | Price (Value) | cheaper vs expected market price |
| 5 | Transmission | more desirable/robust gearbox |
| 6 | Owners | fewer previous owners |
| 7 | Consumption | lower L/100km |
| 8 | Trunk size | larger boot |
| 9 | Tire season | data present & favourable |
| 10 | Minor damage | no/fewer recorded minor damages |
| 11 | Commercial | private ownership (not ex-fleet/commercial) |

**Display order:** Engine, Mileage, Value, Transmission, Consumption, TrunkSize come first and are **ON by default**. **Owners, TireSeason, MinorDamage, Commercial are placed last and are OFF by default** — situational/source-dependent signals that can skew cross-source comparisons; tick them back on in the page when wanted. `DEFAULT_ACTIVE_COLUMNS` = the first six; this is also what the *stored* Total in `site/data.json` is computed from (the page recomputes live as columns are toggled).

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

**8. Trunk size (0–100):** `litres = reference.bootLitres["MAKE|MODEL"]`; `score = clamp(100 * (litres - 300) / (500 - 300), 0, 100)` (≤300 L→0, ≥500 L→100). Missing → `X`.

**9. Tire season (0–100):** `all-season/both → 100 ; single season → 70 ; missing → X`.

**10. Minor damage (0–100):** `numberOfDamages==0 OR damageList empty → 100 ; else clamp(100 - 8*count, 60, 100)`. Damage type text shown alongside.

**11. Commercial (0–100):** `private → 100 ; commercial/fleet (VAT-deductible) → 50` (downgrade, not a reject — negotiating leverage). Detected from Autohero `vatType==1054` (VAT-reclaimable = ex-business); margin scheme (`1053`) = private. For manual listings, set `commercial` on the Listing.

## Insertion / eviction (table stays ≤ 50)
Add a listing → base-filter gate → score all columns → compute Total → insert in sorted order → if size > 50, drop the lowest-Total row.
