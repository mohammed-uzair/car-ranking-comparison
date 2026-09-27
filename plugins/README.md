# Independent Score Plugins

An **independent score** is an advisory rating produced by a self-contained plugin. Each plugin has its **own algorithm** (defined in its own `.md` file), takes a car **listing** as input, and returns a **single score on its own scale**.

**Key rule:** independent scores are shown as their **own columns** (before Total), are **sortable**, and are **NEVER summed into the Total**. They exist so a human can eyeball them and decide to take or skip a car — e.g. Total = 680 looks great, but a plugin score of 2/10 might make you walk away.

## Two plugin types
- **local** — the algorithm is fully described in the `.md` and implemented deterministically against fixed reference data in the repo (e.g. `ROI`). Same input → same score, offline.
- **external** — the plugin forwards the listing to a third-party service that runs *their* algorithm and returns a score (e.g. a `carwow`-style rating). Needs network + an endpoint/credentials; the `.md` documents the request/response mapping.

## Plugin contract
- **Input:** a `Listing` (see `src/main/kotlin/ranker/Models.kt`) — make, model, variant, year, mileage, price, fuel, power, etc.
- **Output:** `{ value: Number, scale: Int, available: Boolean, note: String }`. `available:false` renders as `X` and is skipped. `scale` is the max of the score range (10 → shown with 1 decimal; 100 → integer).
- **Determinism (local):** identical listing → identical score.

## Registering a plugin
1. Add `plugins/<name>.md` (copy `_template.md`).
2. For a **local** plugin, implement its scoring function and reference data.
3. Register it once:
   - Kotlin: add an entry to `INDEPENDENT_PLUGINS` in `src/main/kotlin/ranker/BuildSite.kt`.
   - Output: it appears in `site/data.json → independentColumns` and the page renders a new advisory column automatically.

## Current plugins
| Name | Scale | Type | Source | File |
|------|-------|------|--------|------|
| **ROI** | 0–10 | local | German TÜV + ADAC + DEKRA, per model+generation | [`roi.md`](roi.md) |
| _carwow_ (example, disabled) | 0–10 | external | carwow.com rating (illustrative) | [`carwow.md`](carwow.md) |
