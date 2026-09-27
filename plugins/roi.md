# Plugin: ROI (Car-Buying Assistant score)

- **name:** ROI
- **label:** ROI (Independent score)
- **scale:** 0–10
- **type:** local
- **feeds Total:** NO (independent, advisory)
- **output format:** `ROI: X/10` + a one-line **Verdict** (blunt) + **Yes/No** (shown in the cell tooltip/note)

A holistic "should I buy this specific car" judgment. Prestige does **not** earn points — **reliability + low running cost** do. Extra spend must earn its ROI (lower km, newer, non-commercial, accident-free); more money is not automatically better.

## Budget & needs anchor
- Budget ≤ ~€20,000.
- Use: Berlin city + occasional long trips (Warsaw/Vienna/Zurich, ~2×/yr).
- **Minimum boot: 360 L** — enforced as a **hard base filter** (`passesBaseFilter`/`resolveTrunkLitres`, see `docs/algorithm.md`), not a scoring flag here. A car reaching this plugin never has a *known* boot below 360L.
- Drivetrain: petrol or hybrid. **Hybrid required only for Toyota**; for premium brands petrol (and manual) are fine.
- SUV is a bonus, not a requirement — judge on practicality, stability, long-trip + city comfort, parking.

## Algorithm (deterministic)
```
base = reference.roi["MAKE|MODEL|GEN"]          # reliability index 0-10 (TÜV+ADAC+DEKRA); unknown → car excluded
adj  = 0 ; flags = []
kmPerYear = mileageKm / max(1, YEAR - firstRegistrationYear)

# hidden flags — every one lowers the score AND is listed unprompted:
Toyota not hybrid   : make==Toyota && fuel∉{hybrid,phev}      → adj -= 1.0 ; flag
high km for age     : kmPerYear > 18000                       → adj -= min(1.5,(kmPerYear-18000)/8000) ; flag
overpriced          : askingPrice > 1.05 * expectedPrice      → adj -= min(1.0,(asking-expected)/expected*3) ; flag
listed damage       : damageList≠∅ (minor cosmetic COUNT alone is NOT penalised) → adj -= 0.6 ; flag
previous owners     : owners==2 → adj-=0.2 ; owners≥3 → adj-=0.6 ; flag
service history      : !hasFilledServiceBook                   → adj -= 0.3 ; flag
commercial use       : (Gewerbliche Nutzung, when known)       → adj -= 0.5 ; flag (downgrade, NOT auto-reject; negotiating leverage)

roi = clamp(base + adj, 0, 10)
verdict = roi >= 7 ? "Yes" : "No"
note = "{verdict}. " + (flags ? "Flags: " + join(flags) : "no red flags")
```
> **Removed:** the old "boot < 360L" soft penalty here — superseded by the hard filter above, which now removes those cars entirely rather than merely downgrading them.

## Non-negotiables (checked; flag if failing / unknown)
TÜV/HU validity · accident/damage history (accident-free enforced upstream) · previous owners · commercial-use flag · **Deutsche Ausführung (German spec)** preferred · service-history completeness.
Fields not in the listing feed (HU date, import plates, German spec, commercial flag) are checked manually per car; when a data source provides them, wire them in here.

## Input / Output
Input: full `Listing` + the per-model price baseline (for the overpriced check). Output: `{ value: roi (0–10), scale: 10, note: "<Verdict>. Flags: …" }`.

## Reference data
`data/reference.json → roi` (reliability base per MAKE|MODEL|GEN) and `bootLitres`. Reliability method: `docs/reliability.md`.
