# Plugin: Ownership Cost (running cost + comfort score)

- **name:** OwnershipCost
- **label:** Ownership Cost
- **scale:** 0–100
- **type:** local
- **feeds Total:** NO (independent, advisory — same as ROI)
- **output format:** `OwnershipCost: X/100` + a one-line breakdown of the five sub-scores in the cell note

User-defined (2026-10-03): "what will this exact car cost to run, and how comfortable is it to actually
live with" — a second independent axis alongside ROI's reliability judgment. Rules were dictated by the
user, not invented; see the session's plan file for the original request. Two of the five components
(resale value, comfort) are **segment-level estimates**, not per-model research — per-model research for
those two specifically produced unreliable/spam-adjacent sources (unlike ROI's systematic TÜV/ADAC/DEKRA
data), so the user explicitly chose segment bands over false per-model precision. See `data/reference.json`'s
`_segmentNote` for the sourcing detail on that call.

## Assumptions (user-confirmed)
- Annual driving: **5,000 km/year** (not a typo — user confirmed this low-mileage, mostly-city profile).
- Petrol price: **€1.70/L**.
- Usage split: **80% city, 20% Autobahn**.
- Round-trip reference routes: **Berlin↔Amsterdam** (656km one-way, 1,312km round trip) and
  **Berlin↔Hamburg** (289km one-way, 578km round trip) — both Autobahn, no tolls.

## Algorithm (deterministic)
```
# 1. Annual fuel cost (lower = better)
consFuel = consumptionUrban ?? consumptionCombined ?? null     # L/100km; null → component unavailable
annualFuelCost = consFuel * (5000/100) * 1.70                  # €/year
fuelCostScore = clamp(100 * (850 - annualFuelCost) / (850 - 255), 0, 100)
  # band: €255/yr (3 L/100km, best real-world case) .. €850/yr (10 L/100km, worst case in our pool)

# 2. Kfz-Steuer (German vehicle tax; real formula, not estimated -- petrol/hybrid/phev all taxed the same way,
#    all have a combustion engine + displacement in our data)
hubraumCost = ceil(ccm / 100) * 2.00
co2Surcharge = tiered(co2, bands=[(95,0.00),(115,2.00),(135,2.20),(155,2.50),(175,2.90),(195,3.40),(∞,4.00)])
  # cumulative: each bracket's grams-above-the-previous-threshold taxed at that bracket's rate
kfzSteuer = hubraumCost + co2Surcharge                          # €/year; null if ccm or co2 missing
taxScore = clamp(100 * (300 - kfzSteuer) / (300 - 50), 0, 100)

# 3. Resale value after 5 years / 30,000km (higher = better) -- SEGMENT-level, see data/reference.json
segment = reference.segments["MAKE|MODEL"]                      # null → component unavailable
residualPct = reference.segmentData[segment].residualPct5yr
  + (fuel in {hybrid, phev} ? reference.hybridResidualBonusPct : 0)
resaleScore = residualPct                                       # already a natural 0-100ish % -- used directly

# 4. Round-trip fuel cost, Berlin<->Amsterdam + Berlin<->Hamburg combined (lower = better)
consTrip = consumptionHighway ?? consumptionCombined ?? consumptionUrban ?? null
totalTripCost = consTrip * ((1312 + 578) / 100) * 1.70           # €, both round trips combined
tripCostScore = clamp(100 * (400 - totalTripCost) / (400 - 150), 0, 100)
  # band: €150 (efficient car, ~3.9 L/100km) .. €400 (thirsty car, ~10.6 L/100km) over 1,890km combined

# 5. Drive comfort, 80% city / 20% Autobahn (higher = better) -- SEGMENT-level, see data/reference.json
comfortScore = (0.8 * segmentData.cityComfort + 0.2 * segmentData.autobahnComfort) * 10   # 0-10 -> 0-100

# Final: equal-weight mean of whatever sub-scores are available (same "blend available components,
# don't impute a missing one" principle ROI already uses)
score = mean(available sub-scores among [fuelCostScore, taxScore, resaleScore, tripCostScore, comfortScore])
```

## Input / Output
Input: `consumptionUrban`/`consumptionCombined`/`consumptionHighway`, `ccm`, `co2`, `fuel`, `make`, `model`
from `Listing`. Output: `{ value: score (0–100), scale: 100, note: "<breakdown of the 5 sub-scores>" }`.
Unavailable only when **none** of the five sub-scores can be computed (e.g. a listing missing both
consumption figures AND an unmapped make/model) — same "available=false only when truly nothing to show"
principle as every other scored column.

## Reference data
`data/reference.json → segments` (make|model → segment name), `segmentData` (segment → residualPct5yr /
cityComfort / autobahnComfort), `hybridResidualBonusPct`. Sourcing and confidence level documented in
`_segmentNote` in the same file. Kfz-Steuer bands are the real, current (2026) German formula — not
estimated — see `docs/ownership-cost.md` if one exists, or the commit that added this plugin.
