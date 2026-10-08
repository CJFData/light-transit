# 🚌 Pico Transit: Public Transit for the Light Phone III

Pico Transit is a friendly little companion for getting around on public transit: real schedules, live arrivals, connections at any stop, and a live map showing where your ride actually is. No ads, no clutter, no infinite scroll. Just "where's my bus," answered nicely. 🚏✨

It covers **134 feeds**. Live tracking works for **MBTA**, **RIPTA**, **RTD Denver** (with Bustang), **LTC** (London, Ontario), **STM Montréal**, **CTA**, **NYC Subway**, **LIRR**, **Metro-North**, NYC buses (all 5 boroughs plus MTA Bus Company), **Nashville's WeGo**, **Cleveland's GCRTA**, **SEPTA**'s buses, Metro, and trolleys, **COLTS** in Scranton, the San Francisco Bay Area's 511.org agencies (BART, Muni, AC Transit, Caltrain, VTA, and dozens more), and all 9 **Puget Sound** agencies (King County Metro, Sound Transit, and more). The rest have schedules only for now: about 40 Colorado agencies, SEPTA Regional Rail, Southern New England's regional transit authorities and ferries, Metra, Pace, and LA Metro.

Use it on its own or alongside the Light Phone's Directions tool. It's built on the [Light SDK](https://github.com/lightphone/light-sdk), so it stays as calm and un-distracting as the rest of your Light experience.

📦 **Download it** from the [Releases page](https://github.com/CJFData/light-transit/releases) (the APK is under each release's Assets), then see [Getting it onto a real Light Phone III](#-getting-it-onto-a-real-light-phone-iii) below.

## 🔄 Recent updates

**v0.5.1**
- 🚋 **Cleveland, live**: GCRTA's buses, Red Line, and light rail, with live arrivals, vehicles, and service alerts.
- 🔔 **SEPTA, live**: SEPTA's buses, Metro, and trolleys now show live arrivals, vehicles, and service alerts. Regional Rail stays schedules-only for now.
- 🚌 **Northeastern Pennsylvania, live**: COLT (County of Lackawanna Transit System) in Scranton, with live arrivals, vehicles, and service alerts.
- ⚠️ **More service alerts**: Nashville's WeGo now has service alerts too.

**v0.5.0**
- ⚠️ **Service alerts** (optional, turn on in Settings): detours, closures, and other service changes for MBTA, RTD, LTC, NYC Subway, NYC buses, LIRR, Metro-North, the San Francisco Bay Area, and Puget Sound. See them on the home screen, as an alert icon on routes, stops, and trips, and as pop-ups when new ones arrive.
- 🚇 **Closest match for every MBTA subway line**: trains Pico Transit can't match to a scheduled trip now get a closest match on all subway lines, not just the Green Line.
- 🗺️ **More places**: SEPTA in Philadelphia, and a Southern New England region joining MBTA and RIPTA with 12 Massachusetts transit authorities and 8 ferries. 132 feeds in all.
- 🙏 **Clearer data credits**: agencies whose data comes through 511.org or Sound Transit are now credited by their own names.
- ✨ **Simpler Settings and About screens**, with short, plain descriptions.

**v0.4.1**
- 🌲 **Puget Sound, live**: King County Metro, Sound Transit, Pierce Transit, Community Transit, Kitsap Transit, Intercity Transit, Everett Transit, Washington State Ferries, and the Seattle Center Monorail now show live arrivals and vehicles, through Sound Transit's OneBusAway API.
- 🙏 **Clearer data credits**: King County Metro shows the credit King County asks for, and agencies whose data comes through Sound Transit or 511.org credit them alongside the agency.
- ⚙️ **Tidier Settings**: Clear schedule cache and Only download over Wi-Fi now sit right after the agency picker.
- ⌨️ **Keyboard update**: after typing a symbol or number, the keyboard switches back to letters, like the LightOS keyboard.

**Earlier**
- 🗺️ **Easier-to-read maps**: street names are drawn larger, and each map loads with about a quarter as many tile requests.
- 🚌 **CTA bus trips load much faster**: matching live buses to their scheduled trips no longer scans the whole schedule for every bus.
- 🔍 **Explore search stays put**: typing an address is no longer interrupted when the background location lookup finishes.
- 🔒 **A new home for live data**: the proxy moved to `gtfs.picotransit.com` and now only accepts the kinds of requests the app actually makes.
- 🐛 **Steadier live tracking** on feeds with infrequent GPS updates. RIPTA also follows each vehicle along its real route path, so it catches up even when several stops pass between updates.
- 🎶 **Nashville's WeGo, live**: added in honor of Dolly Parton, with full live tracking from day one.

The full history is in [RELEASE_NOTES.md](RELEASE_NOTES.md).

## 🔭 Coming up

- 📍 **Real GPS for Explore**: built, but it needs a LightOS update before it can work, so it's marked **(Testing)** for now. Address search works in the meantime.
- 📡 **Expanding current agency feeds**: bringing live arrivals, vehicles, and service alerts to more of the agencies Pico Transit has schedules for today.
- 🚏 **Adding more agencies**, with help from the community.

## 🚏 Agencies and live data

Everything Pico Transit covers, and which live data it uses for each. A dash means Pico Transit doesn't use that data for the agency yet.

**Southern New England**

| Agency | Schedule | Trip updates | Vehicle positions | Service alerts⁶ |
|---|:-:|:-:|:-:|:-:|
| MBTA¹ | ✓ | ✓ | ✓ | ✓ |
| RIPTA | ✓ | ✓ | ✓ | — |
| SRTA | ✓ | — | — | — |
| Merrimack Valley Transit | ✓ | — | — | — |
| Berkshire RTA | ✓ | — | — | — |
| Brockton Area Transit | ✓ | — | — | — |
| Cape Ann Transportation | ✓ | — | — | — |
| Cape Cod RTA | ✓ | — | — | — |
| Franklin RTA | ✓ | — | — | — |
| Lowell RTA | ✓ | — | — | — |
| MetroWest RTA | ✓ | — | — | — |
| Montachusett RTA | ✓ | — | — | — |
| Nantucket - The WAVE | ✓ | — | — | — |
| Pioneer Valley Transit | ✓ | — | — | — |
| Bay State Cruise Company | ✓ | — | — | — |
| Cuttyhunk Ferry | ✓ | — | — | — |
| Freedom Cruise Line | ✓ | — | — | — |
| Hy-Line Cruises | ✓ | — | — | — |
| Patriot Party Boats | ✓ | — | — | — |
| Seastreak | ✓ | — | — | — |
| Vineyard Fast Ferry | ✓ | — | — | — |
| Block Island Ferry | ✓ | — | — | — |

**Canada**

| Agency | Schedule | Trip updates | Vehicle positions | Service alerts⁶ |
|---|:-:|:-:|:-:|:-:|
| LTC Ontario | ✓ | ✓ | ✓ | ✓ |
| STM Montréal | ✓ | ✓ | ✓ | — |

**New York City**

| Agency | Schedule | Trip updates | Vehicle positions | Service alerts⁶ |
|---|:-:|:-:|:-:|:-:|
| NYC Subway | ✓ | ✓ | ✓⁵ | ✓ |
| LIRR | ✓ | ✓ | ✓ | ✓ |
| Metro-North | ✓ | ✓ | ✓ | ✓ |
| NYC Bus - Bronx | ✓ | ✓ | ✓ | ✓ |
| NYC Bus - Brooklyn | ✓ | ✓ | ✓ | ✓ |
| NYC Bus - Manhattan | ✓ | ✓ | ✓ | ✓ |
| NYC Bus - Queens | ✓ | ✓ | ✓ | ✓ |
| NYC Bus - Staten Island | ✓ | ✓ | ✓ | ✓ |
| NYC Bus - MTA Bus Company | ✓ | ✓ | ✓ | ✓ |

**Chicago**

| Agency | Schedule | Trip updates | Vehicle positions | Service alerts⁶ |
|---|:-:|:-:|:-:|:-:|
| CTA | ✓ | ✓³ | ✓³ | — |
| Metra | ✓ | — | — | — |
| Pace | ✓ | — | — | — |

**Denver & Colorado**

| Agency | Schedule | Trip updates | Vehicle positions | Service alerts⁶ |
|---|:-:|:-:|:-:|:-:|
| RTD Denver² | ✓ | ✓ | ✓ | ✓ |
| Bustang | ✓ | ✓ | ✓ | — |
| All Points Transit | ✓ | — | — | — |
| Avon Transit | ✓ | — | — | — |
| Baca Area Transportation | ✓ | — | — | — |
| Bent County Transportation | ✓ | — | — | — |
| Blackhawk and Central City Tramway | ✓ | — | — | — |
| Boulder County | ✓ | — | — | — |
| Breckenridge Free Ride | ✓ | — | — | — |
| Bustang Outrider | ✓ | — | — | — |
| City of Fountain Transit | ✓ | — | — | — |
| Clear Creek County Transit | ✓ | — | — | — |
| Core Transit | ✓ | — | — | — |
| Dolores County | ✓ | — | — | — |
| Durango Transit | ✓ | — | — | — |
| Easy Ride Transportation | ✓ | — | — | — |
| El Paso Fountain Valley Senior Citizens Program Inc. | ✓ | — | — | — |
| Envida | ✓ | — | — | — |
| Epic Mountain Express | ✓ | — | — | — |
| Estes Transit | ✓ | — | — | — |
| Garden of the Gods | ✓ | — | — | — |
| Greeley-Evans Transit | ✓ | — | — | — |
| Gunnison Valley RTA | ✓ | — | — | — |
| Home James Transportation | ✓ | — | — | — |
| Mountain Metropolitan Transit | ✓ | — | — | — |
| Parachute Area Transit System | ✓ | — | — | — |
| Prairie Express Transit | ✓ | — | — | — |
| Pueblo Transit | ✓ | — | — | — |
| RFTA | ✓ | — | — | — |
| Road Runner Transit | ✓ | — | — | — |
| Rocky Mountain National Park Shuttles | ✓ | — | — | — |
| San Miguel Authority for Regional Transportation | ✓ | — | — | — |
| Snowmass Village Transportation | ✓ | — | — | — |
| Steamboat Springs Transit | ✓ | — | — | — |
| Summit Stage | ✓ | — | — | — |
| Town of Mountain Village | ✓ | — | — | — |
| Town of Telluride | ✓ | — | — | — |
| Transfort | ✓ | — | — | — |
| TSC Transit | ✓ | — | — | — |
| University of Colorado Boulder | ✓ | — | — | — |
| Vail Transit | ✓ | — | — | — |
| Via Mobility | ✓ | — | — | — |
| Winter Park Transit | ✓ | — | — | — |

**San Francisco Bay Area (via 511.org)**

| Agency | Schedule | Trip updates | Vehicle positions | Service alerts⁶ |
|---|:-:|:-:|:-:|:-:|
| BART | ✓ | ✓ | —⁴ | ✓ |
| SFMTA Muni | ✓ | ✓ | ✓ | ✓ |
| AC Transit | ✓ | ✓ | ✓ | ✓ |
| Caltrain | ✓ | ✓ | ✓ | ✓ |
| VTA | ✓ | ✓ | ✓ | ✓ |
| County Connection | ✓ | ✓ | ✓ | ✓ |
| ACE | ✓ | ✓ | ✓ | ✓ |
| Santa Cruz METRO | ✓ | ✓ | ✓ | ✓ |
| Capitol Corridor | ✓ | ✓ | ✓ | ✓ |
| Emery Go-Round | ✓ | ✓ | ✓ | ✓ |
| Golden Gate Transit | ✓ | ✓ | ✓ | ✓ |
| Marin Transit | ✓ | ✓ | ✓ | ✓ |
| Mission Bay TMA | ✓ | ✓ | ✓ | ✓ |
| Mountain View Community Shuttle | ✓ | ✓ | ✓ | ✓ |
| MVgo | ✓ | ✓ | ✓ | ✓ |
| Petaluma Transit | ✓ | ✓ | ✓ | ✓ |
| Rio Vista Delta Breeze | ✓ | ✓ | ✓ | ✓ |
| SMART | ✓ | ✓ | ✓ | ✓ |
| SF Bay Ferry | ✓ | ✓ | ✓ | ✓ |
| San Leandro LINKS | ✓ | ✓ | ✓ | ✓ |
| SamTrans | ✓ | ✓ | ✓ | ✓ |
| Sonoma County Transit | ✓ | ✓ | ✓ | ✓ |
| Santa Rosa CityBus | ✓ | ✓ | ✓ | ✓ |
| SolTrans | ✓ | ✓ | ✓ | ✓ |
| WestCat | ✓ | ✓ | ✓ | ✓ |
| LAVTA Wheels | ✓ | ✓ | ✓ | ✓ |
| Tri Delta Transit | ✓ | ✓ | ✓ | ✓ |
| Angel Island Tiburon Ferry | ✓ | ✓ | ✓ | ✓ |
| Commute.org Shuttles | ✓ | ✓ | ✓ | ✓ |
| Dumbarton Express | ✓ | ✓ | ✓ | ✓ |
| Emery Express | ✓ | ✓ | ✓ | ✓ |
| FAST | ✓ | ✓ | ✓ | ✓ |
| Golden Gate Ferry | ✓ | ✓ | ✓ | ✓ |
| Presidio Go | ✓ | ✓ | ✓ | ✓ |
| SFO Airport | ✓ | ✓ | ✓ | ✓ |
| South San Francisco Shuttle | ✓ | ✓ | ✓ | ✓ |
| Treasure Island Ferry | ✓ | ✓ | ✓ | ✓ |
| Union City Transit | ✓ | ✓ | ✓ | ✓ |
| Vacaville City Coach | ✓ | ✓ | ✓ | ✓ |
| VINE Transit | ✓ | ✓ | ✓ | ✓ |

**Philadelphia**

| Agency | Schedule | Trip updates | Vehicle positions | Service alerts⁶ |
|---|:-:|:-:|:-:|:-:|
| SEPTA Bus & Metro | ✓ | ✓ | ✓ | ✓ |
| SEPTA Regional Rail | ✓ | — | — | — |

**Northeastern Pennsylvania**

| Agency                                     | Schedule | Trip updates | Vehicle positions | Service alerts⁶ |
|--------------------------------------------|:-:|:-:|:-:|:-:|
| County of Lackawanna Transit System (COLT) | ✓ | ✓ | ✓ | ✓ |

**Nashville**

| Agency | Schedule | Trip updates | Vehicle positions | Service alerts⁶ |
|---|:-:|:-:|:-:|:-:|
| Nashville - WeGo Public Transit | ✓ | ✓ | ✓ | ✓ |

**Cleveland**

| Agency                      | Schedule | Trip updates | Vehicle positions | Service alerts⁶ |
|-----------------------------|:-:|:-:|:-:|:-:|
| GCRTA | ✓ | ✓ | ✓ | ✓ |

**Los Angeles**

| Agency | Schedule | Trip updates | Vehicle positions | Service alerts⁶ |
|---|:-:|:-:|:-:|:-:|
| LA Metro | ✓ | — | — | — |

**Puget Sound**

| Agency | Schedule | Trip updates | Vehicle positions | Service alerts⁶ |
|---|:-:|:-:|:-:|:-:|
| King County Metro | ✓ | ✓ | ✓ | ✓ |
| Sound Transit | ✓ | ✓ | ✓ | ✓ |
| Pierce Transit | ✓ | ✓ | ✓ | ✓ |
| Community Transit | ✓ | ✓ | ✓ | ✓ |
| Kitsap Transit | ✓ | ✓ | ✓ | ✓ |
| Intercity Transit | ✓ | ✓ | ✓ | ✓ |
| Everett Transit | ✓ | ✓ | ✓ | ✓ |
| Washington State Ferries | ✓ | ✓ | ✓ | ✓ |
| Seattle Center Monorail | ✓ | ✓ | ✓ | ✓ |

¹ MBTA subway trains that Pico Transit can't match to a scheduled trip are shown as a "closest match". Commuter rail positions and track numbers come from MBTA's V3 API.  
² RTD Denver includes Bustang's routes and live vehicles.  
³ For CTA, Pico Transit uses CTA Bus Tracker for buses (predicted arrivals and positions, matched to scheduled trips) and Train Tracker for 'L' trains, shown as a "closest match" for live arrivals and boarded trips. 'L' trains aren't drawn on the map yet.  
⁴ Pico Transit doesn't show live BART vehicle positions; BART arrivals come from trip updates.  
⁵ Pico Transit shows NYC Subway trains at their current stop rather than at a GPS position.  
⁶ Service alerts are optional. Turn them on in Settings → Service alerts.

## 🗺️ What can it do?

- 🏠 **Pick your agency** from the welcome screen and Pico Transit downloads its schedule to your phone. The home screen then shows a clock in the agency's timezone and its name.

  <img src="docs/screenshots/Screenshot_20260810_171500.png" alt="Pico Transit screenshot" width="220"> <img src="docs/screenshots/Screenshot_20260810_171800.png" alt="Pico Transit screenshot" width="220">

- 🗂️ **Multiple schedules by region**: New York City, Southern New England, Philadelphia, Denver, the San Francisco Bay Area, and Puget Sound group their agencies together. Turn on more schedules from the same region in Settings → "Additional Schedules", and tap and hold one there to make it your primary.

  <img src="docs/screenshots/NYCtransit.png" alt="Pico Transit screenshot" width="220">

- 📅 **Schedules**: browse by Subway 🚇, Commuter Rail 🚆, Bus 🚌, or Ferry ⛴️, then pick a route, direction, and stop to see today's departures. Tap the Departures header to see tomorrow's instead.

  <img src="docs/screenshots/Screenshot_20260801_204325.png" alt="Pico Transit screenshot" width="220">

  Routes for each supported agency:

  <img src="docs/screenshots/Screenshot_20260817_013000.png" alt="Pico Transit screenshot" width="220"> <img src="docs/screenshots/Screenshot_20260817_012800.png" alt="Pico Transit screenshot" width="220"> <img src="docs/screenshots/Screenshot_20260817_013500.png" alt="Pico Transit screenshot" width="220"> <img src="docs/screenshots/Screenshot_20260817_013600.png" alt="Pico Transit screenshot" width="220"> <img src="docs/screenshots/Screenshot_20260817_013800.png" alt="Pico Transit screenshot" width="220">

- 🔗 **Connections**: tap any stop along a trip to see what comes through there next, across every platform of a station. Handy for planning a transfer on the fly.

  <img src="docs/screenshots/Screenshot_20260801_211241.png" alt="Pico Transit screenshot" width="220">

- 📍 **Explore**: the closest stops to an address or landmark, nearest first. Finding stops near you with GPS is marked **Testing**, since it needs a LightOS update to work; once it does, Recenter brings you back to your own location.

  <img src="docs/screenshots/explore_nearby_stops.png" alt="Pico Transit screenshot" width="220"> <img src="docs/screenshots/Screenshot_20260801_214943.png" alt="Pico Transit screenshot" width="220">

- ⏱️ **Live ETAs** with On Time / Late / Early badges, whenever the agency's live feed is playing along nicely.

  <img src="docs/screenshots/Screenshot_20260810_172200.png" alt="Pico Transit screenshot" width="220">

- 🗺️ **Map**: your stop and the stops around it, with live vehicles shown by mode (subway/light rail, commuter rail, bus, ferry). "See everything" shows every live vehicle in view; filter it by stop or by mode.

  <img src="docs/screenshots/Screenshot_20260801_200838.png" alt="Pico Transit screenshot" width="220"> <img src="docs/screenshots/Screenshot_20260801_204556.png" alt="Pico Transit screenshot" width="220">

- 👆 **Gestures**: tap and hold a stop or station name to jump to its live arrivals. On the map, double-tap a station to zoom into its platforms, and double-tap its name to zoom back out. On Trip Detail, a tap opens a stop's connections and tap-and-hold opens its arrivals; once you've boarded, a tap sets where you're getting off.

- 🚉 **Stations**: one entry per real station, with a map of just its platforms and gates. MBTA commuter rail trains show up on their track once one is assigned, usually 10-15 minutes before departure.

  <img src="docs/screenshots/Screenshot_20260801_204530.png" alt="Pico Transit screenshot" width="220">

- ▶️ **Board a trip**: tap Play on Trip Detail, then tap the stop where you're getting off. When you arrive, Pico Transit celebrates with "You've reached your stop! 🎉" and shows that stop's upcoming arrivals.

  <img src="docs/screenshots/Screenshot_20260810_172300.png" alt="Pico Transit screenshot" width="220"> <img src="docs/screenshots/Screenshot_20260801_212442.png" alt="Pico Transit screenshot" width="220">

- 👀 **Show earlier stops** (Settings): a boarded trip also lists the stops before yours, greyed out, so you can watch your vehicle approach.

  <img src="docs/screenshots/pre-arrival.png" alt="Pico Transit screenshot" width="220">

- 🚋 **Select Run**, for CTA 'L' trains and MBTA subway lines: when Pico Transit can't match a live train to a scheduled trip for certain, it shows a "Closest match". Select Run lets you confirm or correct it by tapping the vehicle you're actually on.

  <img src="docs/screenshots/fuzzy_runs.png" alt="Pico Transit screenshot" width="220">

- 🚦 **Home screen trip status**: while you're on a trip, the home screen shows your route, live ETA, stops remaining, and an optional progress bar.

  <img src="docs/screenshots/Screenshot_20260810_172500.png" alt="Pico Transit screenshot" width="220"> <img src="docs/screenshots/Screenshot_20260817_233823.png" alt="Pico Transit screenshot" width="220"> <img src="docs/screenshots/Screenshot_20260820_003442.png" alt="Pico Transit screenshot" width="220">

- ⚠️ **Service alerts** (optional, turn on in Settings): detours, closures, and other service changes, for agencies with an alerts feed. The home screen shows your trip's alerts while you're on one. An alert icon marks affected routes, stops, and trips, and tapping it opens the full alert: what it affects, how long it lasts, and the details. New alerts can also pop up as they come in.

  <img src="docs/screenshots/alerts_home.png" alt="Home screen showing an alert under the trip progress bar" width="220"> <img src="docs/screenshots/alerts_routes.png" alt="Route list with alert icons" width="220"> <img src="docs/screenshots/alerts_trip.png" alt="Trip Detail with alert icons at the top and beside a stop" width="220"> <img src="docs/screenshots/alerts_modal.png" alt="An open service alert" width="220">

- ↩️ **Jump back anytime**: a Play icon in the corner takes you back to your trip from any screen, and the footer circle takes you home.

  <img src="docs/screenshots/Screenshot_20260801_212013.png" alt="Pico Transit screenshot" width="220">

- ⚙️ **Settings**: switch agencies, turn on service alerts (off by default) and choose where they show, pick a light or dark map, choose gestures, turn Select Run options on or off, manage location, download only over Wi-Fi (on by default), or clear the schedule cache.

  <img src="docs/screenshots/Screenshot_20260810_172700.png" alt="Pico Transit screenshot" width="220"> <img src="docs/screenshots/Screenshot_20260801_215602.png" alt="Pico Transit screenshot" width="220">

- ℹ️ **About**: a legend of every icon Pico Transit uses.

  <img src="docs/screenshots/Screenshot_20260810_171900.png" alt="Pico Transit screenshot" width="220">

## 🛠️ Building & running it

Pico Transit lives in `tool/` inside this fork of the [light-sdk](https://github.com/lightphone/light-sdk) monorepo. Follow the SDK's own setup first (GitHub token, Android Studio, and so on), then:

1. Open the project in Android Studio.
2. Run the `:tool` module on an emulator, or on [the LightOS emulator](docs/system_app). [`tool/lighttool.toml`](tool/lighttool.toml) targets a real phone (`com.lightos`) by default; switch `serverPackage` to the commented-out emulator line when running there.
3. Pick an agency, grab a coffee ☕ while the schedule downloads, and you're off.

## 📱 Getting it onto a *real* Light Phone III

Until Pico Transit is available through Light's Tool Library, install it with ADB, since LightOS can't install third-party APKs on the phone itself yet:

1. Download the latest APK from the [Releases page](https://github.com/CJFData/light-transit/releases): open the newest release and grab `pico-transit-v<version>.apk` under **Assets**. Or build one yourself with `./gradlew :tool:assembleRelease`.
2. Turn on Developer Options and USB debugging on your Light Phone III, plug it in, and run:
   ```bash
   adb install -r pico-transit-v<version>.apk
   ```
3. On the phone, allow "Any tools" in LightOS's tool settings.

That's it, happy transit-ing! 🚏🚌🚆

## 🧪 A few nerdy notes

- **Live data goes through `pico-transit-proxy`**, a small Cloudflare Worker at `gtfs.picotransit.com` (in its own repo). It only fetches a fixed list of upstream URLs, keeps every API key server-side, caches responses so riders share upstream requests, and accepts only the kinds of requests the app makes.
- **Feeds fetched over plain HTTP** reach the app over HTTPS through the proxy, so the app never uses cleartext.
- **Explore's GPS** uses the Light SDK's location APIs and needs a LightOS update to work. Address search uses Nominatim (OpenStreetMap), so please be kind to their free API! 🙏
- **Stations are grouped by GTFS `parent_station`**, so a big hub shows up once. Entrances, elevators, and escalators are left out of its platform map.
- **Boarding is a saved reference, not a background tracker.** Live feeds are only polled while Trip Detail or the home screen is on screen.
- **Agency APIs alongside GTFS-RT**: Pico Transit uses MBTA's V3 API for commuter rail tracks and positions, and matches CTA Bus Tracker data to scheduled trips by route and scheduled start time.
- **"Closest match"** pairs live CTA 'L' runs and MBTA subway trains that Pico Transit can't match exactly with the nearest scheduled trip, in order. These aren't plotted on the map yet.
- **NYC Subway's 8 line-group feeds** are merged by the proxy into one, and their trip IDs are decoded back to real scheduled trips.
- **One upstream feed for the San Francisco Bay Area**: the proxy fetches 511.org's regional feed once and slices it per agency.
- **Map tiles** come from CARTO through the proxy. Each 512px tile is cut into four, which keeps requests down and makes street names easier to read.

## 🙌 Credits

Thanks to [Jose Briones](https://github.com/jbriones95) for his continued support integrating RTD Denver and Colorado transit into Pico Transit.

Thanks to [Guy Dupont](https://github.com/dupontgu) on the Light team for adding `LightConnectivity` to `light-sdk`, the network-state API that made the "Only download over Wi-Fi" setting possible, and to the whole Light Phone team for making this SDK a genuine pleasure to build on and the LightOS developer community such a positive, supportive place to be.

Thanks to Claude (Anthropic) for extensive collaboration throughout Pico Transit's development, including learning Kotlin from scratch, implementing the "Only download over Wi-Fi" setting, and working through the codebase's comments and documentation together.

## 📄 License

The [`tool/`](tool/) directory (Pico Transit itself) is licensed separately from the rest of the monorepo: see [`LICENSE-TRANSIT`](LICENSE-TRANSIT) (MIT, © Christian Ferreira / CJFData). The rest of `light-sdk` remains under its own [`LICENSE`](LICENSE) (MIT, © The Light Phone).
