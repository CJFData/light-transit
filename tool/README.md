# 🚌 Pico Transit

Pico Transit is a friendly little companion for getting around on public transit: real schedules, live arrivals, connections at any stop, and a live map showing where your ride actually is. No ads, no clutter, no infinite scroll. Just "when's my bus," answered nicely. 🚏✨

It covers **110 feeds**, with live tracking for MBTA, RIPTA, RTD Denver (with Bustang), LTC, STM Montréal, CTA, NYC Subway, LIRR, Metro-North, NYC buses, Nashville's WeGo, the SF Bay Area's 511.org agencies, and the 9 Puget Sound agencies. Colorado, Metra, Pace, and LA Metro have schedules only for now. It's built on the [Light SDK](../), so it stays as calm and un-distracting as the rest of your Light experience. Screenshots and recent updates are in the [main README](../README.md).

## 🗺️ What can it do?

- 🏠 **Pick your agency** and Pico Transit downloads its schedule to your phone.
- 🗂️ **Multiple schedules by region**: New York City, Denver, the SF Bay Area, and Puget Sound group their agencies, so you can browse several at once.
- 📅 **Schedules**: pick a route, direction, and stop to see today's departures (or tomorrow's).
- 🔗 **Connections**: see what else comes through any stop on your trip, across every platform.
- 📍 **Explore**: the closest stops to you (**Testing**), or to any address you search for.
- ⏱️ **Live ETAs** with On Time / Late / Early badges.
- 🗺️ **Map**: your stop, nearby stops, and live vehicles by mode, with a "See everything" view.
- 🚉 **Stations**: one entry per real station, with a map of its platforms and gates.
- ▶️ **Board a trip**, mark where you're getting off, and get a little celebration when you arrive. 🎉
- 🚋 **Select Run**: confirm or correct the "Closest match" for CTA 'L' trains and MBTA Green Line.
- 🚦 **Home screen trip status**: route, live ETA, stops remaining, and an optional progress bar.
- ℹ️ **About**: a legend of every icon.

## 🧭 Modes

All three modes lead to the same place: a trip's details, ready to board. Press Play to board and tap the stop where you'll get off. The home screen then shows your progress and ETA at a glance.

While you're on a trip, the Play button in the top-right corner jumps back to it from anywhere. To stop tracking, open the trip and tap Stop. If you press Play on a different trip, a small X next to the Play indicator warns that you're about to switch to it.

- 📅 **Schedule**: plan ahead by picking a route, direction, stop, and departure.
- 📍 **Explore**: start from the stops closest to you. Search for an address or landmark, or Recenter to come back to your location. Tap a stop for its arrivals, then an arrival for its trip.
- 🚉 **Station**: start from a transfer station. Tap a platform or gate on the map for its name, or tap and hold it for its arrivals. Tap and hold the station name to jump to the main map.

## 🛠️ Building & running it

Pico Transit lives inside a fork of the [light-sdk](../) monorepo. Follow the SDK's own setup first (GitHub token, Android Studio, and so on), then:

1. Open the project in Android Studio.
2. Run the `:tool` module on an emulator, or on [the LightOS emulator](../docs/system_app). [`lighttool.toml`](./lighttool.toml) targets a real phone (`com.lightos`) by default; switch `serverPackage` to the commented-out emulator line when running there.
3. Pick an agency, grab a coffee ☕ while the schedule downloads, and you're off.

## 📱 Getting it onto a *real* Light Phone III

Until Pico Transit is available through Light's Tool Library, install it with ADB, since LightOS can't install third-party APKs on the phone itself yet:

1. Build a debug APK with `./gradlew :tool:assembleDebug`, or download one from the repo's Releases.
2. Turn on Developer Options and USB debugging on your Light Phone III, plug it in, and run:
   ```bash
   adb install -r tool/build/outputs/apk/debug/tool-debug.apk
   ```
3. On the phone, allow "Any tools" in LightOS's tool settings. It'll warn you the tool isn't Light-vetted yet, which is expected for now. 🚧

That's it, happy transit-ing! 🚏🚌🚆

## 🧪 A few nerdy notes

- **Live data goes through `pico-transit-proxy`**, a Cloudflare Worker at `gtfs.picotransit.com` that keeps API keys server-side and caches responses for every rider. HTTP-only feeds (RIPTA, LTC) reach the app over HTTPS through it.
- **Explore's GPS** uses the Light SDK's location APIs and is marked **(Testing)** until LightOS trusts non-Light-signed builds for location. Address search uses Nominatim (OpenStreetMap), so please be kind to their free API! 🙏
- **Stations are grouped by GTFS `parent_station`**, leaving entrances, elevators, and escalators off the platform map.
- **Boarding is a saved reference, not a background tracker.** Live feeds are only polled while Trip Detail or the home screen is on screen.
- **Agency APIs fill gaps in GTFS-RT**: MBTA's V3 API provides commuter rail tracks and positions, and CTA's Bus Tracker is matched to scheduled trips by route and scheduled start time.

## 📄 License

This `tool/` directory (Pico Transit itself) is licensed separately from the rest of the monorepo: see [`LICENSE-TRANSIT`](../LICENSE-TRANSIT) (MIT, © Christian Ferreira / CJFData). The rest of `light-sdk` remains under its own [`LICENSE`](../LICENSE) (MIT, © The Light Phone).
