# 🚌 Pico Transit (Public Transit for the Light Phone III)

Pico Transit is a friendly little companion for getting around on public transit. Real schedules, real-time arrivals, live connections at any stop, and a live map that shows exactly where your ride actually is — no ads, no clutter, no infinite scroll. Just "where's my bus," answered nicely. 🚏✨

Right now Pico Transit knows its way around **MBTA** and **RIPTA**, with more agencies hopefully hopping aboard down the road. It's built on the [Light SDK](../) for the Light Phone III, so it stays just as calm and un-distracting as the rest of your Light experience.

Pico Transit can be used alongside the light phone's directions tool for more context on your commutes, or standalone, covering buses, commuter rail.subway systems, and transit stations.

## 🗺️ What can it do?

- 🏠 **Pick your agency** — MBTA or RIPTA — and Pico Transit downloads their schedule right onto your phone.
  
  ![alt text](docs/screenshots/Screenshot_20260801_202930.png)
  
- ⚙️ **Settings** — a default agency to skip the picker, light/dark map tiles, and on/off toggles (tap-and-hold a stop to jump to its arrivals, double-tap a station to zoom into its platforms, track tapped-open stops' own vehicles on the map, and the home screen's trip progress bar).
  
  ![alt text](docs/screenshots/Screenshot_20260801_215544.png)
  ![alt text](docs/screenshots/Screenshot_20260801_215602.png)

- 📅 **Explore Schedules** — browse by Subway 🚇, Commuter Rail 🚆, or Bus 🚌, pick a route, a direction, and a stop, and see every departure today.
  ![alt text](docs/screenshots/Screenshot_20260801_204325.png)

- 🔗 **Connections** — tap any stop along a trip to see what else comes through there next, across every platform of a station, not just the one your trip happened to use. Great for planning a transfer on the fly.
  
  ![alt text](docs/screenshots/Screenshot_20260801_211241.png)
  
- 📍 **Explore** — type where you are (or let Pico Transit take a quick IP-based guess 🛰️) and get the closest stops, nearest first — it remembers your last search, so ducking into a stop's arrivals and back doesn't make you search all over again. Need something more precise, search for an address or landmark and find the closest stops in feet to it, then find how soon the next trip will arrive at your stop.
  
   ![alt text](docs/screenshots/Screenshot_20260801_214943.png)
   ![alt text](image.png)
  
- ⏱️ **Live ETAs** — real-time predictions with On Time / Late / Early badges, whenever the agency's live feed is playing along nicely.
  
   ![alt text](docs/screenshots/Screenshot_20260801_204530.png)

- 🗺️ **Map** — your stop, pinned on a live map, with nearby stops you can tap to reveal their names. Live vehicles show up right where they actually are, with a matching icon for their mode (subway/light rail, commuter rail, bus).
  
  ![alt text](docs/screenshots/Screenshot_20260801_200838.png)
  ![alt text](docs/screenshots/Screenshot_20260801_204556.png)

- 🚉 **Stations** — browse every real multi-platform station an agency has, and open a zoomed-in map of just that station's own platforms.
  
  ![alt text](docs/screenshots/Screenshot_20260801_204530.png)

- ▶️ **Board a trip** — from any Trip Detail screen, tap Play to make it your current trip. Keep track of which stop you're closest to from the vehicle icon.Tap a stop along the way to mark where you're getting off — reach it, and Pico Transit throws a little "You've reached your stop! 🎉" celebration and jumps you to that stop's upcoming arrivals, whether you were looking at the trip or just sitting on the home screen.
  

   ![alt text](docs/screenshots/Screenshot_20260801_214024.png)
   ![alt text](docs/screenshots/Screenshot_20260801_212442.png)
  
- 🚦 **Home screen trip status** — while a trip is boarded, the home screen swaps its agency picker for your route, live ETA, and stops remaining, plus an optional progress bar with a little vehicle marker crawling from your boarding stop toward your alight stop.

  ![alt text](docs/screenshots/Screenshot_20260801_212055.png)

- ↩️ **Jump back anytime** — a Play icon shows up in the corner of every screen while a trip's boarded, one tap from wherever you are back to its live tracking; a plain circle in the footer does the same for the home screen itself.
  
   ![alt text](docs/screenshots/Screenshot_20260801_212013.png)

- ℹ️ **About** — a full legend of every icon and mode Pico Transit uses, reachable right from the home screen.
  
   ![alt text](docs/screenshots/Screenshot_20260801_212041.png)

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
- **Stations are deduplicated using GTFS's `parent_station`** — a big station with several platforms (subway entrances, commuter rail tracks, etc.) shows up as one marker/entry, not one per platform, while still resolving to the right platform's `stop_id` under the hood for schedule lookups.
- **Boarding a trip is a saved reference, not a background tracker** — Pico Transit never polls a live feed while the app itself isn't open. "You've reached your stop" detection only runs while Trip Detail or the home screen is actually visible and polling, the same way every other bit of live tracking in the app works.

## 📄 License

The [`tool/`](tool/) directory (Pico Transit itself) is licensed separately from the rest of the monorepo — see [`LICENSE-TRANSIT`](LICENSE-TRANSIT) (MIT, © Christian Ferreira / CJFData). The rest of `light-sdk` remains under its own [`LICENSE`](LICENSE) (MIT, © The Light Phone).
