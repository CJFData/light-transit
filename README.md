# 🚌 Pico Transit

Transit is a friendly little companion for getting around on public transit. Real schedules, real-time arrivals, live connections at any stop, and a tiny radar screen that shows you exactly which direction your ride is coming from — no ads, no clutter, no infinite scroll. Just "when's my bus," answered nicely. 🚏✨

Right now Transit knows its way around **MBTA** and **RIPTA**, with more agencies hopefully hopping aboard down the road. It's built on the [Light SDK](../) for the Light Phone III, so it stays just as calm and un-distracting as the rest of your Light experience.

## 🗺️ What can it do?

- 🏠 **Pick your agency** — MBTA or RIPTA — and Transit downloads their schedule right onto your phone.
- 📅 **Explore Schedules** — browse by Subway 🚇, Commuter Rail 🚆, or Bus 🚌, pick a route, a direction, and a stop, and see every departure today.
- 🔗 **Connections** — tap any stop along a trip to see what else comes through there next. Great for planning a transfer on the fly.
- 📍 **Leave Now** — type where you are (or let Transit take a quick IP-based guess 🛰️) and get the 20 closest stops, nearest first.
- ⏱️ **Live ETAs** — real-time predictions with On Time / Late / Early badges, whenever the agency's live feed is playing along nicely.
- 🧭 **ETA Radar** — a little live radar screen showing exactly which direction your bus (and nearby stops!) are coming from, right now.

## 🛠️ Building & running it

Transit lives inside the [light-sdk](../) monorepo — check the [root README](../README.md) first for one-time setup (GitHub token, Android Studio, etc). Once that's done:

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
