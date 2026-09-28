#!/usr/bin/env python3
"""Real AutoScout24 ingestion: fetches listings for our 7 brands, maps into the source-agnostic
Listing schema (source="autoscout24"), and writes them to data/autoscout24_listings.json.

Access: AS24 embeds listing data as JSON in a Next.js __NEXT_DATA__ script tag on the normal search
HTML page -- no separate API call needed, no bot-wall encountered (unlike mobile.de, which returns a
hard Akamai 403 and is NOT attempted here -- see docs/algorithm.md).

Scope: a proportionate SAMPLE (5 pages = 100 listings per brand, ~600 total), not an exhaustive scrape
of AS24's full national marketplace (which would be 700+ requests across ~14,000 matching listings) --
matches the order of magnitude of the existing Autohero fetch. Raise PAGES_PER_BRAND to widen the sample.

Known data gaps at the list-view level (same class of gap as Autohero's tire-season/trunk-size/color,
would need per-listing detail-page scraping): doors (inferred from `variant` text instead), owners count
(defaults 0 -- undocumented/unverified for AS24 rows), commercial-use flag (defaults false), color,
trunkLitres. Hybrid vs PHEV is disambiguated with a text heuristic (AS24's fuel taxonomy doesn't split
them, same ambiguity Autohero had before `isPluginSystem` fixed it there -- no equivalent flag here).
`consumptionUrban` always stays null (AS24's list view gives one combined L/100km figure only) -- since
that column is ON by default, AS24 cars are structurally disadvantaged in the default ranking; untick
ConsumptionUrban in the page's filter drawer to see them compete fairly.

Usage:
    python3 scripts/fetch_autoscout24.py
    # then merge into data/listings.json, e.g.:
    python3 -c "
import json
a = json.load(open('data/listings.json'))
b = json.load(open('data/autoscout24_listings.json'))
existing_ids = {l['id'] for l in a}
json.dump(a + [l for l in b if l['id'] not in existing_ids], open('data/listings.json','w'), indent=1)
"
"""
import json, re, time, subprocess, os

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
PAGES_PER_BRAND = 5   # 100 listings/brand cap -- a sample, not a full-market scrape (see module docstring)

MAKE_SLUGS = {
    "Audi": "audi", "BMW": "bmw", "Mercedes-Benz": "mercedes-benz", "Porsche": "porsche",
    "Honda": "honda", "Toyota": "toyota", "Hyundai": "hyundai",
}
COUPE_WORDS = {"coupe", "coupé", "cabrio", "cabriolet", "roadster"}

def fetch(url):
    r = subprocess.run(["curl", "-s", "-A", UA, "-H", "Accept-Language: de-DE,de;q=0.9",
                         "-w", "\n__STATUS__%{http_code}", url], capture_output=True, text=True, timeout=30)
    out = r.stdout
    idx = out.rfind("\n__STATUS__")
    body, status = (out[:idx], int(out[idx+len("\n__STATUS__"):])) if idx >= 0 else (out, 0)
    return body, status

def parse_next_data(html):
    m = re.search(r'<script id="__NEXT_DATA__" type="application/json">(.*?)</script>', html, re.S)
    if not m: return None
    return json.loads(m.group(1))

def parse_km(s):
    if not s: return 0
    return int(re.sub(r"[^\d]", "", s) or 0)

def parse_year(vehicle_details):
    for d in vehicle_details or []:
        if d.get("iconName") == "calendar":
            m = re.search(r"(\d{4})", d.get("data", ""))
            if m: return int(m.group(1))
    return None

def parse_kw(vehicle_details):
    for d in vehicle_details or []:
        if d.get("iconName") == "speedometer":
            m = re.search(r"(\d+)\s*kW", d.get("data", ""))
            if m: return int(m.group(1))
    return 0

def parse_consumption(vehicle_details):
    combined = None
    for d in vehicle_details or []:
        if d.get("name") == "fuelConsumptionExtended":
            m = re.search(r"([\d,.]+)\s*l/100", d.get("data", ""))
            if m: combined = float(m.group(1).replace(",", "."))
    return combined   # AS24 list view gives one combined figure only, no separate urban -> consumptionUrban stays None

def decode_fuel(vehicle, subtitle_blob):
    raw = vehicle.get("fuel", "")
    if raw == "Benzin": return "petrol"
    if raw == "Diesel": return "diesel"
    if raw == "Elektro": return "electric"
    if "Elektro/Benzin" in raw or "Hybrid" in raw:
        low = subtitle_blob.lower()
        return "phev" if ("plug-in" in low or "plugin" in low) else "hybrid"
    return ""

def decode_body(variant):
    low = (variant or "").lower()
    for w in COUPE_WORDS:
        if w in low: return "coupe"
    return None

def decode_transmission(vehicle):
    t = (vehicle.get("transmission") or "").lower()
    return "manual" if "schalt" in t else "automatic"

def to_listing(l):
    v = l["vehicle"]
    if not v.get("make") or not v.get("model"): return None   # occasionally missing/incomplete on AS24's side
    vd = l.get("vehicleDetails", [])
    year = parse_year(vd)
    if year is None: return None
    subtitle_blob = " ".join([v.get("modelVersionInput") or "", v.get("subtitle") or ""])
    price = l.get("price", {}).get("priceRaw")
    if price is None: return None
    return dict(
        source="autoscout24", id=l["id"], url="https://www.autoscout24.de" + l.get("url", ""),
        make=v["make"], model=v["model"], subType=v.get("motorTypeName") or "", subTypeExtra=v.get("variant") or "",
        firstRegistrationYear=year, mileageKm=parse_km(v.get("mileageInKm")), priceEur=int(price),
        fuel=decode_fuel(v, subtitle_blob), gearRaw=decode_transmission(v),
        kw=parse_kw(vd), ccm=parse_km(v.get("engineDisplacementInCCM")),
        owners=0, accidents=1 if v.get("isCurrentlyDamaged") else 0,
        numberOfDamages=0, damageList=[], hasFilledServiceBook=False,
        commercial=False, saleInProgress=False,
        consumptionCombined=parse_consumption(vd), consumptionUrban=None,
        tireSeason=None, doors=None, body=decode_body(v.get("variant")),
        country=l.get("location", {}).get("countryCode", "DE"), city=l.get("location", {}).get("city", ""),
        color=None, trunkLitres=None,
    )

def fetch_brand(make):
    slug = MAKE_SLUGS[make]
    out = []
    for page in range(1, PAGES_PER_BRAND + 1):
        url = (f"https://www.autoscout24.de/lst/{slug}?atype=C&cy=D&damaged_listing=exclude"
               f"&fregfrom=2018&pricefrom=10000&priceto=20000&fuel=B,2&ustate=N,U&page={page}")
        html, status = fetch(url)
        if status != 200:
            print(f"  {make} page {page}: HTTP {status}, stopping"); break
        data = parse_next_data(html)
        if not data:
            print(f"  {make} page {page}: no __NEXT_DATA__, stopping"); break
        pp = data["props"]["pageProps"]
        listings = pp.get("listings", [])
        if not listings:
            print(f"  {make} page {page}: 0 listings, stopping"); break
        for l in listings:
            listing = to_listing(l)
            if listing: out.append(listing)
        total_pages = pp.get("numberOfPages", 1)
        print(f"  {make} page {page}/{min(PAGES_PER_BRAND,total_pages)}: +{len(listings)} (total so far {len(out)})")
        if page >= total_pages: break
        time.sleep(0.7)   # be a polite client
    return out

if __name__ == "__main__":
    all_listings = []
    for make in MAKE_SLUGS:
        print(f"Fetching {make}...")
        all_listings.extend(fetch_brand(make))
        time.sleep(0.7)
    print(f"\nTotal AutoScout24 listings fetched: {len(all_listings)}")
    out_path = os.path.join(REPO_ROOT, "data", "autoscout24_listings.json")
    json.dump(all_listings, open(out_path, "w"), indent=1)
    print(f"Saved to {out_path}")
