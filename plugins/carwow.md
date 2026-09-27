# Plugin: carwow (example · disabled)

- **name:** carwow
- **label:** carwow score
- **scale:** 0–10
- **type:** external
- **source:** carwow.com editorial/expert rating (illustrative — not an official public scoring API)
- **feeds Total:** NO
- **status:** DISABLED (no endpoint wired). This file documents how an *external* plugin would work.

## Input
Sent to the external scorer: `make`, `model`, `subType`, `firstRegistrationYear`, `mileageKm`, `fuel`, `kw`, `priceEur`.

## Algorithm (external)
1. POST the listing fields to the provider's scoring endpoint (endpoint + auth configured out-of-band).
2. Provider runs *their* algorithm and returns a rating.
3. Map their rating onto `{value: 0–10, scale: 10}`. If the call fails or the model isn't rated → `available:false` (`X`).

## Output
`{ value, scale: 10, available, note: "carwow expert rating" }`.

## To enable
Wire an adapter that performs the request, then add `carwow` to `INDEPENDENT_PLUGINS`. Until then it stays out of `independentColumns` and no column is shown.

> Purpose: demonstrates that independent scores can come from **any** third party — each is just another advisory column, never part of the Total.
