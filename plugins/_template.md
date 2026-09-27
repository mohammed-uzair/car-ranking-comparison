# Plugin: <NAME>

- **name:** <ShortName>            # column key, e.g. "ROI", "carwow"
- **label:** <Header shown in the table>
- **scale:** 0–<max>              # e.g. 0–10 or 0–100
- **type:** local | external
- **source:** <where the score/logic comes from>
- **feeds Total:** NO             # independent scores never feed Total

## Input
Which `Listing` fields the plugin uses (see `src/main/kotlin/ranker/Models.kt`).

## Algorithm
For a **local** plugin: full deterministic pseudocode + any reference data it reads.
For an **external** plugin: the request (endpoint, auth, which listing fields are sent) and how the response maps to `{value, scale}`.

## Output
`{ value: Number (0..scale), scale: Int, available: Boolean, note: String }`.
`available:false` → renders `X`, skipped.

## Registration
Add an entry to `INDEPENDENT_PLUGINS` in `src/main/kotlin/ranker/BuildSite.kt` (name, label, scale, path to this file).
