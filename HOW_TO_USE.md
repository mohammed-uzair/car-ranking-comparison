# How to Use This Website

A simple guide, written in plain English — no technical knowledge needed.

## What is this?

This website helps you find a good used car to buy in Germany. It looks at real car listings from two
websites (Autohero and AutoScout24) and scores each car based on things that matter when buying a used car:
how reliable it is, how good the engine is, how much fuel it uses, how big the trunk is, and more. It then
shows you the best cars first, so you don't have to dig through hundreds of listings yourself.

## Opening the page

If someone has already started the local server for you, just open this address in your browser:

**http://localhost:8081**

(If nothing loads, ask whoever set this up to run `./gradlew runServer` first — see the main `README.md` for
that part.)

## What you'll see

A table, one row per car. Each row shows:
- The car's name, with a link you can click to open the real listing
- Year, fuel type, price, and mileage (km)
- An **Avg** score — the single "how good is this car overall" number, highest first
- Other scores like **ROI** (is this a smart buy?) and **Ownership Cost** (what will it cost to run?)
- A **TOTAL** score and several smaller scores that add up to it (Engine, Mileage, Value, etc.)

The table starts sorted by **Avg**, best car at the top. You don't need to do anything to see good
recommendations — just open the page.

## Sorting

Click on any column title (like "Price €" or "ROI") to sort the table by that column. Click it again to
flip the order (highest-to-lowest or lowest-to-highest). A little arrow (▲ or ▼) shows you which way it's
currently sorted.

## Searching within the list

There's a search box near the top. Type a word (like a city name, "hybrid", or part of a car's name) and
the table will only show rows that match — useful for quickly finding something within what's already shown.

## Narrowing down what you see (the Filters menu)

Click the **☰** icon (top-left, looks like three lines) to open the Filters panel. Here's what each part does:

- **Brands** — tick only the car brands you're interested in (e.g. just Toyota and Mazda).
- **Listing source** — choose whether to include Autohero, AutoScout24, or both.
- **Only show** — type a word or two (like "Corolla" or "Touring Sports") and only cars whose name
  contains all of those words will show up.
- **Hide models** — the opposite: type a word and cars matching it get hidden.
- **Body type** — tick a body style (like "Combination" for a wagon/estate car, or "SUV") and only cars of
  that shape will be fetched. This one only works for Autohero listings.

None of these take effect immediately — after picking what you want, click the blue **Update** button at
the bottom of the panel.

## The Update button

This is the important one. Clicking **Update** does a **real, live search** — it goes out to Autohero and
AutoScout24 right now, grabs their current listings, and re-scores everything fresh. This takes about
15–40 seconds (you'll see a spinner while it works), because it's genuinely fetching live data, not just
re-sorting something already on the page.

If the local server isn't running, Update will fail gracefully and just re-sort whatever was already loaded,
rather than breaking the page.

## Turning columns on or off

Each score column (like "Engine" or "Transmission") has a small checkbox in its header. Unticking it does
two things: that column fades out, and it stops counting toward the **TOTAL** score. This is useful if you
don't care about a particular factor and want the ranking to reflect that.

## What the scores mean, briefly

- **ROI** (0–10): is this specific car, at this specific price and mileage, actually a smart buy? Takes into
  account reliability, how many owners it's had, whether it looks overpriced, and more.
- **Ownership Cost** (0–100): roughly, how expensive is this car to actually own and run day-to-day —
  fuel, German road tax, how much value it's expected to lose over time, and how comfortable it is to drive.
- **TOTAL** and **Avg**: TOTAL adds up the individual scored columns (Engine, Mileage, etc.). Avg blends
  TOTAL together with ROI and Ownership Cost into one overall number — this is the default sort order, and
  usually the quickest way to see "what's the best car here."

## A note on trust

Every number on this page is calculated the same way for every car — there's no hidden weighting or paid
placement. If a number looks wrong or a car seems scored unfairly, it's worth checking the small note text
next to that score (hover over a cell) — it usually explains exactly why that number is what it is.
