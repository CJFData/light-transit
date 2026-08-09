# Pico Transit — Release Notes

*Everything unique to Pico Transit vs. upstream `light-sdk`/`main`*

## 🚌 What Pico Transit is

A from-scratch companion tool for the Light Phone III: real GTFS schedules, live arrivals, live vehicle tracking, and trip boarding — no ads, no clutter. Built on top of the base `light-sdk` scaffold, renamed from "Transit" early on.

## 🏙️ Agency support

- **MBTA** and **RIPTA** — the original two agencies, with full realtime support.
- **RTD Denver** — added via a community contribution, including GTFS-RT decode fixes and secondary-feed support.
- **Bustang** (Colorado's statewide intercity coach) — merged directly into RTD Denver: its routes, stops, schedules, and live vehicles all show up alongside RTD's own.
- **LTC Ontario** (London, Ontario) — newly added, along with fixes for its zero-trip routes, an HTTP static-feed URL, and a missing trust anchor (see below).

## 🗺️ Core features

- **Agency picker home screen** — pick MBTA, RIPTA, RTD Denver, or LTC, and Pico Transit downloads that agency's schedule to the phone.
- **Explore Schedules** — browse by Subway, Commuter Rail, or Bus, pick a route/direction/stop, and see every departure for the day.
- **Connections** — tap any stop along a trip to see everything else that comes through there next, across every platform of a station.
- **Explore / nearby stops** — type a location or let the app guess via IP geolocation, and get the closest stops ranked by distance; remembers your last search.
- **Live ETAs** — real-time predictions with On Time / Late / Early badges wherever an agency's live feed supports it.
- **Live map** — your stop pinned with nearby stops and live vehicles shown with mode-specific icons; a "See Everything" mode drops the per-stop filter to show every live vehicle in view, filterable by stop or mode.
- **Stations** — every real multi-platform station, deduplicated via GTFS `parent_station` so a hub shows as one entry, with a zoomed-in map of just its real platforms (entrances/elevators/escalators filtered out). MBTA commuter rail track assignments come from MBTA's V3 API once a track is assigned, ~10–15 minutes before departure.
- **Board a trip** — tap Play from any Trip Detail screen to make it your current trip, track your position via the vehicle marker, mark an alight stop, and get a "You've reached your stop! 🎉" celebration when you arrive.
- **Home screen trip status** — while boarded, the home screen swaps the agency picker for your route, live ETA, stops remaining, and an optional progress bar with a vehicle marker crawling from boarding stop to alight stop.
- **Jump back anytime** — a persistent Play icon returns you to live trip tracking from anywhere while a trip is boarded.
- **About screen** — a full legend of every icon and mode Pico Transit uses.
- **Settings** — default agency (skip the picker), light/dark map tiles, and toggles for tap-and-hold-to-arrivals, double-tap-to-zoom-platforms, tracking a tapped stop's vehicles, the home screen progress bar, the home screen daily message, and "See Everything."

## 🐛 Notable fixes

- **Fixed routes showing more "directions" than they actually have** — `getDirections` treated every distinct headsign as its own direction, so a route with short-turn trips (e.g. MBTA's Franklin/Foxboro Line) showed 4 "directions" instead of 2. Now grouped by the real GTFS `direction_id` (never more than 2), with an Inbound/Outbound header wherever an agency publishes the optional `directions.txt` extension (MBTA does); every real headsign stays individually selectable, none get hidden or merged away.
- **Fixed "phantom stops" on short-turn trips** — picking a short-turn destination (e.g. "Toward Readville" on the Franklin/Foxboro Line) used to still list every stop the full-length trip reaches, since direction alone couldn't tell the two apart. Stop lists are now scoped to the exact destination picked, and additionally filtered to stops with a departure still remaining today, so tapping in never dead-ends on an empty screen — that screen now reads "Nothing found in today's schedule." instead of the more ambiguous "No stops found."
- **Departures now match the direction picked** — a stop shared by a short-turn trip and a longer one shows both by default, since either one gets you at least as far as the shorter trip promises — but never the other way around, so picking the longer destination never shows a trip that stops short of it. A new Settings toggle ("Include longer trips in departures") switches to an exact match only, for anyone who'd rather not see the extra trips at all.
- **Fixed a subway departures-loading slowdown** — the match logic above initially checked every candidate trip against every reference trip regardless of headsign, which was fine for routes with a handful of trips but took several seconds on high-frequency subway lines where hundreds of trips share one headsign (confirmed up to ~35s on MBTA's Orange Line). Same-headsign trips now skip that check entirely (they trivially qualify on their own), cutting the worst measured case by roughly three orders of magnitude.
- **Home screen progress bar stuck** — agencies that never populate `current_stop_sequence` (e.g. RIPTA) left the home screen's progress bar frozen even though Trip Detail moved correctly; home screen now shares Trip Detail's GPS-proximity/TripUpdate fallback, and only credits a stop as "completed" once actually passed, not merely approached.
- **RTD Denver GTFS-RT decode** — fixed a decode failure and restored HomeScreen features that had regressed during RTD integration; also made the agency list scrollable.
- **LTC navigation bug** — zero-trip routes (which are real in LTC's data) caused an infinite back-button bounce between screens; fixed with explicit trip-existence checks and a dedicated "no trips" state.
- **LTC missing trust anchor** — LTC's static-feed host serves a valid cert chain rooted at a 2021 Sectigo root that older Android system trust stores don't have yet, so HTTPS requests failed with `SSLHandshakeException: Trust anchor for certification path not found` even though the certificate itself is valid. Fixed with a code-only, LTC-scoped `BundledRootTrustAnchor` (`GtfsTrustAnchors.kt`) that bundles the missing root and is wired in only via LTC's own agency component — every other agency's HTTPS client is untouched.
- **Timezone-aware schedules** — GTFS time math previously used the device's timezone instead of the agency's, causing wrong-day/wrong-time results for out-of-timezone agencies; fixed by threading the agency's own timezone through all schedule calculations.
- **Stations search keyboard** — the search keyboard silently stopped accepting input on reopen due to a stale ViewModel callback; fixed by hoisting the text field state.

## 🔧 Under the hood

- **`:netconfig` cleartext exception, scoped to RIPTA and LTC** — both agencies' realtime `TripUpdates`/`VehiclePositions` feeds are plain-HTTP-only with no HTTPS equivalent (confirmed via direct TLS handshake attempts), so a narrowly-scoped Network Security Config exception permits cleartext only to `realtime.ripta.com` and `gtfs.ltconline.ca` — no blanket cleartext allowance elsewhere. **This is unlikely to survive Light's actual build/signing pipeline as-is**: `builder/lightbuilder/extract.py` only extracts `tool/` and discards every other module (including `netconfig`), and the plugin's `ManifestGenerator` never emits a `networkSecurityConfig` manifest attribute at all — a real workaround needs to come from Light's side before this can be trusted in production.
- Local **Light Keyboard UI** dependency (previously pulled from JitPack) folded in as a proper subtree.
- **BouncyCastle** (`bcprov-jdk18on`) added to the dependency allow-list to support agency-specific crypto needs.
- No device GPS is used anywhere — nearby-stop and location search run entirely on Nominatim/OpenStreetMap and IP-based geolocation, since the SDK doesn't expose GPS to tools.
- Boarding a trip is a saved reference, not a background tracker — no live-feed polling happens unless Trip Detail or the home screen is actually open and visible.

## 🙌 Credits

Thanks to [Jose Briones](https://github.com/jbriones95) for his continued work integrating RTD Denver and Colorado transit into Pico Transit.