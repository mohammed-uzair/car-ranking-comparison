# ROI / Reliability method

The **ROI** column is a reliability index (0–10, ×10 for the 0–100 column), anchored to **published German data** and keyed by **model + generation** (deterministic; same model+gen → same ROI).

## Sources (equal blend)
- **TÜV Report** — Mängelquote: % of cars failing the HU main inspection for significant defects, per model × age band. Normalised: `tuvScore = clamp(10 * (1 - pct/25), 0, 10)`.
- **ADAC Pannenstatistik** — real breakdowns per 1,000 vehicles. Mapped from standing: very-reliable/lowest = 9, good/winner = 8, average = 5, poor = 2.
- **DEKRA Gebrauchtwagenreport** — severity-weighted fault index by mileage band. Mapped from standing: class-winner/top-3 = 9, best/very-good = 8.5, good = 7, average = 5, poor = 2; missing → skipped.

`roi = mean(available components)`, then a small engine-platform ± adjustment for known variant-specific risks (PHEV −1.0, Merc 7G-DCT city-diesel −0.3, GLA X156 −0.5, Audi 3.0 TDI −0.5, Audi 1.5 Evo1 −0.2, BMW B58 +0.3).

## Generation split (year → generation)
BMW: 1er F20 ≤2019 / F40 ≥2020 · 3er F30 ≤2019 / G20 ≥2020 · 2er F4x (Active/Gran Tourer) · 5er G30 · X1 F48 · X2 F39.
Audi: A1 GB · A3 8V ≤2020 / 8Y ≥2020 · A4 B9 · Q2 · Q3 F3.
Mercedes: A W177 · B W247 · GLA X156 ≤2020 / H247 ≥2020 · C W205 · CLA C117 ≤2019 / C118 ≥2019 · Citan.
Toyota / Honda / Hyundai / Mazda: filled per model, no generation split (all key on `|*`).

## Per-model ROI (current)
Stored in `data/reference.json` under `roi` as `"MAKE|MODEL|GEN": value`. Values are point-in-time (TÜV Report 2026, ADAC 2025, DEKRA). Full per-brand engine/model detail lives in the Notion Reliability Knowledge Base (linked in README) and is mirrored here.

### Mazda (added later, different confidence level than the rest)
Search coverage for Mazda didn't surface precise current-generation Mängelquote percentages the way it did for BMW/Audi/Mercedes — only brand/model-level standings: **3rd place manufacturer overall in ADAC Pannenstatistik 2025** (85.1% first-HU pass rate, TÜV rating 1.4, behind only Lexus and Toyota), **TÜV-Report 2026 names Mazda the overall winner, with the Mazda 2 winning the small-car category outright**. Mazda 3 and CX-5 both independently cited as "among the most reliable in their class"; CX-3/CX-5 share a recurring minor flag (above-average brake-disc wear + lighting faults after 2-3 and 4-5 years, not drivetrain-related); Mazda6's known weak point is body rust at door edges, also not drivetrain-related. Skyactiv-X (SPCCI) is Mazda's newer, more complex combustion tech — early units had ECU-mapping hesitation (fixed via software updates) and an ISG belt wear point (~€150–400 around 50,000km); "no major reliability pitfalls" reported, but kept as a distinct, slightly lower-scored engine platform (`MazdaSkyactivX` vs `MazdaSkyactivG`) rather than folded into the same number as the simpler NA/mild-hybrid engines. Mazda2 Hybrid is a rebadged Toyota Yaris Hybrid (built by Toyota, same powertrain) — scored at Toyota-hybrid parity (`MazdaHybrid`, matching `ToyotaHybrid`), distinct from Mazda's own 24V mild-hybrid Skyactiv-G ("Mild-Hybrid"/"MHEV" badging), which despite also decoding to Autohero's `fuelType 1046` is a completely different, much simpler system. `bootLitres` entries for Mazda are spec-sheet figures (representative trim), not independently re-verified per listing — same precision level as the rest of the table.

### Ford (added later, mixed-confidence like Mazda)
TÜV-Report 2026 coverage was uneven across the lineup. **Fiesta and Puma are genuine bright spots** — both
described as "performing surprisingly well, below class average" — Fiesta with concrete numbers (4.8% defect
rate at 2-3yr vs 6.5% class average, improving to 11.2% vs 13.6% at 6-7yr). **EcoSport** also "performs well,"
flagged only for cosmetic headlight/taillight faults across all model years. **Kuga is the weak point** —
"mixed picture, above-average defects" in TÜV, and ADAC separately flags frequent breakdowns for the 2021
model year. **Focus** had no specific current-generation (Mk4, 2018+) defect number in this search pass — only
the general-fleet observation that "lighting defects run through nearly the entire Ford range" — scored more
conservatively than Fiesta/Puma for that reason. **Engine platform (not brand-level) risk:** the 1.0 EcoBoost's
pre-~2018 "wet belt" (oil-lubricated timing belt) design is a well-documented, serious failure mode — an
NHTSA investigation into belt degradation contaminating the oil system and causing total engine seizure —
scored as a distinct, materially lower platform (`FordEcoBoost1.0`) than the 1.5 EcoBoost (`FordEcoBoost1.5`,
separate but less severe carbon-buildup/cooling/injector issues) or the mild-hybrid 48V-assisted variants
(`FordHybrid`, later-generation tech built after the wet-belt fix). `bootLitres` are spec-sheet figures except
Puma's 456L, which is a directly confirmed figure from this research pass.

### Kia (added later, generally strong and improving)
Picanto: 92% first-HU pass rate, flagged only for minor early oil loss. Stonic: compares favorably to Picanto
in a direct MOT pass-rate comparison. Ceed (current generation, 2018+ — matching our year filter, so
predecessor-generation weaknesses noted in sources don't apply here): "convinces with solid results at TÜV."
Sportage (current 2021+ generation): strong, ~4.0% defect rate at 2-3yr (tuvScore ≈ 8.4), a separate source
citing a 96.0% reliability rating for the same generation. Niro Hybrid: "100% in a What Car reliability
survey, zero faults reported... well-proven hybrid drivetrain" — scored at Toyota-hybrid-adjacent confidence
(`KiaHybrid`). **No reliable source was found for Rio, Soul, or XCeed** in this research pass — left out of
`roi` entirely (listed in `roiPending`) rather than guessed, same principle as Mazda's unmapped body-type
codes. Engine platforms: `KiaHybrid` (the dedicated Atkinson-cycle + DCT full-hybrid system, not the 48V
mild-hybrid T-GDI variants, which fall under `KiaTGDI` instead — same "mild" disambiguation Mazda's platform
detection already uses), `KiaTGDI` (turbo petrol), `KiaNA` (naturally aspirated small-car petrol).

> Note: TÜV (inspection defects, incl. wear items) and ADAC (breakdowns) can diverge — e.g. BMW X1/X2 have minor HU wear items but excellent breakdown records. Per user decision, we **blend the available components** (a missing TÜV number is not imputed).
