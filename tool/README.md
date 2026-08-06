# 🚌 Pico Transit

Pico Transit is a friendly little companion for getting around on public transit. Real schedules, real-time arrivals, live connections at any stop, and a live map that shows exactly where your ride actually is — no ads, no clutter, no infinite scroll. Just "when's my bus," answered nicely. 🚏✨

Right now Pico Transit knows its way around **MBTA**, **RIPTA**, and **RTD Denver**, with more agencies hopefully hopping aboard down the road. It's built on the [Light SDK](../) for the Light Phone III, so it stays just as calm and un-distracting as the rest of your Light experience.

## 🗺️ What can it do?

- 🏠 **Pick your agency** — MBTA, RIPTA, or RTD Denver — and Pico Transit downloads their schedule right onto your phone.
- ⚙️ **Settings** — a default agency to skip the picker, light/dark map tiles, and on/off toggles (tap-and-hold a stop to jump to its arrivals — the same gesture also jumps from a Station map's own name to the main map centered on it; double-tap a station to zoom into its platforms; track tapped-open stops' own vehicles on the map; the home screen's trip progress bar; the home screen's daily message; and "See Everything," a map mode covered below).
- 📅 **Explore Schedules** — browse by Subway 🚇, Commuter Rail 🚆, or Bus 🚌, pick a route, a direction, and a stop, and see every departure today.
- 🔗 **Connections** — tap any stop along a trip to see what else comes through there next, across every platform of a station, not just the one your trip happened to use. Great for planning a transfer on the fly.
- 📍 **Leave Now** — type where you are (or let Pico Transit take a quick IP-based guess 🛰️) and get the closest stops, nearest first — it remembers your last search, so ducking into a stop's arrivals and back doesn't make you search all over again.
- ⏱️ **Live ETAs** — real-time predictions with On Time / Late / Early badges, whenever the agency's live feed is playing along nicely.
- 🗺️ **Map** — your stop, pinned on a live map, with nearby stops you can tap to reveal their names. Live vehicles show up right where they actually are, with a matching icon for their mode (subway/light rail, commuter rail, bus). Flip on "See Everything" (Settings) to drop the usual "just this stop's own vehicles" filter and plot every live vehicle in view instead, labeled with just its route until you tap it; narrow it back down by tapping a stop ("Filter by stop" — tags each vehicle TO/FROM/AT that stop) or by mode (Bus/Subway/Commuter Rail).
- 🚉 **Stations** — browse every real multi-platform station an agency has, and open a zoomed-in map of just that station's own real platforms and gates (elevators, entrances, and escalators are filtered out). For MBTA commuter rail, once a specific track is assigned — usually 10-15 minutes before departure — its vehicle shows up right on that track's own platform.
- ▶️ **Board a trip** — from any Trip Detail screen, tap Play to make it your current trip. Tap a stop along the way to mark where you're getting off — reach it, and Pico Transit throws a little "You've reached your stop! 🎉" celebration and jumps you to that stop's upcoming arrivals, whether you were looking at the trip or just sitting on the home screen.
- 🚦 **Home screen trip status** — while a trip is boarded, the home screen swaps its agency picker for your route, live ETA, and stops remaining, plus an optional progress bar with a little vehicle marker crawling from your boarding stop toward your alight stop.
- ↩️ **Jump back anytime** — a Play icon shows up in the corner of every screen while a trip's boarded, one tap from wherever you are back to its live tracking; a plain circle in the footer does the same for the home screen itself.
- ℹ️ **About** — a full legend of every icon and mode Pico Transit uses, reachable right from the home screen.

## 🧭 Modes

All three modes help you get to the same place: trip details, ready to board. Once you've found your trip, press play to board and tap the stop where you'll get off. You can return to the Home screen anytime for a clean, simple view of your progress and ETA.

Once boarded, tap the play button (top-right corner) anytime to jump back to your active trip's details — even while you're browsing for your next boarding. To stop tracking, return to trip details via the play indicator, then tap the stop button. If you open a different trip while already tracking one and tap play there, a small X appears next to the play indicator — that's your signal you're about to cancel your current trip and switch to following this new one instead.

- 📅 **Schedule** — Start here to plan ahead. Pick a route, direction, stop, and departure time to lock in your trip.
- 📍 **Explore** — Start by entering a known location — search by address, landmark, or commercial place. Nominatim translates that into a location and finds the closest stops nearby. Tap a stop to see upcoming arrivals and ETAs. Tap any arrival to view trip details.
- 🚉 **Station** — For agencies with transfer stations, start by selecting your station. Tap any platform or gate on the map to see its name. Tap and hold a platform or gate to see all upcoming arrivals there, or tap and hold the station name above the map to jump to the main map centered on that station. Select a trip to view its details.

## 🛠️ Building & running it

Pico Transit lives inside the [light-sdk](../) monorepo — check the [root README](../README.md) first for one-time setup (GitHub token, Android Studio, etc). Once that's done:

1. Open the whole `light-sdk` project in Android Studio.
2. Run the `:tool` module on an emulator, or better yet, [the LightOS emulator](../docs/system_app) — that's this app! 🎉
3. Tap an agency, grab a coffee ☕ while it downloads the schedule, and you're off.

## 📱 Getting it onto a *real* Light Phone III

Light's official "build it, sign it, share it" pipeline for community tools isn't quite ready yet — vetting is expected around August/September 2026, with the full sharing platform following in October. So for now, sideloading via ADB is the way, and Light's own docs say that's totally fine for the adventurous! 🤠

1. In [`lighttool.toml`](./lighttool.toml), point `serverPackage` at the real LightOS package instead of the emulator:
   ```toml
   serverPackage = "com.lightos"
   ```
2. Build a debug APK:
   ```bash
   ./gradlew :tool:assembleDebug
   ```
3. Turn on Developer Options + USB debugging on your Light Phone III (same as any Android device), plug it in, then:
   ```bash
   adb install -r tool/build/outputs/apk/debug/tool-debug.apk
   ```
4. On the phone, allow "Any tools" in LightOS's tool settings — it'll warn you this one isn't Light-vetted yet, which is expected for a homemade build like this. 🚧

That's it — happy transit-ing! 🚏🚌🚆

## 🧪 A couple of nerdy notes

- **RIPTA's live feeds are HTTP-only** (no HTTPS), which Android blocks by default. There's a small, clearly-labeled `:netconfig` module that grants just that one narrow exception — see its own `build.gradle.kts` for exactly what it does and how to remove it if you'd rather stay HTTPS-only everywhere.
- **No device GPS is used anywhere** — the SDK doesn't expose it to tools yet. Nearby-stop and location search are powered by Nominatim (OpenStreetMap) and IP-based geolocation instead. Be kind to their free APIs! 🙏
- **Stations are deduplicated using GTFS's `parent_station`** — a big station with several platforms (subway entrances, commuter rail tracks, etc.) shows up as one marker/entry, not one per platform, while still resolving to the right platform's `stop_id` under the hood for schedule lookups. Only real platforms and boarding areas count as "member platforms" for this — GTFS also links entrances, elevators, and escalator nodes to the same parent station, and those are filtered out so a big hub's map isn't cluttered with dozens of non-boardable points.
- **Boarding a trip is a saved reference, not a background tracker** — Pico Transit never polls a live feed while the app itself isn't open. "You've reached your stop" detection only runs while Trip Detail or the home screen is actually visible and polling, the same way every other bit of live tracking in the app works.
- **Commuter rail track assignments come from MBTA's V3 API, not GTFS-RT** — GTFS-RT never publishes which specific track a commuter rail trip will use, and MBTA's own dispatch system usually doesn't decide until 10-15 minutes before departure. Pico Transit polls the V3 API (`api-v3.mbta.com`, no key required) for this and for commuter rail's own live vehicle positions, falling back to the standard GTFS-RT feed if a trip has no V3 match yet.

## 📄 License

This `tool/` directory (Pico Transit itself) is licensed separately from the rest of the monorepo — see [`LICENSE-TRANSIT`](../LICENSE-TRANSIT) (MIT, © Christian Ferreira / CJFData). The rest of `light-sdk` remains under its own [`LICENSE`](../LICENSE) (MIT, © The Light Phone).
