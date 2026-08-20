// pico-transit-proxy — fixed allowlist proxy for GTFS/GTFS-RT feeds.
// Only exact paths below are ever fetched; the target is never taken from the request.

const PROXIED_ROUTES = {
  '/mbta/tripupdates': 'https://cdn.mbta.com/realtime/TripUpdates.pb',
  '/mbta/vehiclepositions': 'https://cdn.mbta.com/realtime/VehiclePositions.pb',
  '/mbta/v3/vehicles': 'https://api-v3.mbta.com/vehicles',
  '/ripta/tripupdates': 'http://realtime.ripta.com:81/api/tripupdates?format=gtfs.proto',
  '/ripta/vehiclepositions': 'http://realtime.ripta.com:81/api/vehiclepositions?format=gtfs.proto',
  '/rtd/tripupdates': 'https://open-data.rtd-denver.com/files/gtfs-rt/rtd/TripUpdate.pb',
  '/rtd/vehiclepositions': 'https://open-data.rtd-denver.com/files/gtfs-rt/rtd/VehiclePosition.pb',
  '/bustang/tripupdates': 'https://open-data.rtd-denver.com/files/gtfs-rt/cdot/Bustang_TripUpdate.pb',
  '/bustang/vehiclepositions': 'https://open-data.rtd-denver.com/files/gtfs-rt/cdot/Bustang_VehiclePosition.pb',
  '/ltc/tripupdates': 'http://gtfs.ltconline.ca/TripUpdate/TripUpdates.pb',
  '/ltc/vehiclepositions': 'http://gtfs.ltconline.ca/Vehicle/VehiclePositions.pb',
  '/ltc/static': 'https://www.londontransit.ca/gtfsfeed/google_transit.zip',
  '/stm/tripupdates': 'https://api.stm.info/pub/od/gtfs-rt/ic/v2/tripUpdates',
  '/stm/vehiclepositions': 'https://api.stm.info/pub/od/gtfs-rt/ic/v2/vehiclePositions',
  '/cta/bus/vehicles': 'https://www.ctabustracker.com/bustime/api/v3/getvehicles',
};

// 511 SF Bay agencies whose static GTFS is sourced through 511's datafeed API instead of a direct
// agency-domain download -- either because no independent download exists at all, or because the
// direct one that does exist is one MobilityData's own catalog marks deprecated (e.g. SamTrans'
// unstable CMS media-asset link; ACE/Petaluma/SMART's Trillium/CDN URLs, still live as of
// 2026-08-20 but deprecated in MobilityData's favor of 511 -- audited against
// github.com/MobilityData/mobility-database-catalogs that same day). A /511SF<CODE>/static route
// is generated for each one below (target is still a fixed, hardcoded URL per code, never taken
// from the incoming request). Every other 511-integrated agency (BART, Muni, and everything else
// wired for realtime) sources its OWN static feed directly from its own domain -- this list has
// nothing to do with realtime at all, see REGIONAL_FEEDS below for that.
const STATIC_VIA_511_AGENCIES = [
  '3D', 'AF', 'CE', 'DE', 'EE', 'GF', 'PE', 'PG', 'SA', 'SI', 'SM', 'SS', 'TF', 'VC',
];

for (const agencyId of STATIC_VIA_511_AGENCIES) {
  PROXIED_ROUTES[`/511SF${agencyId}/static`] =
    `http://api.511.org/transit/datafeeds?operator_id=${agencyId}`;
}

// Regional realtime aggregators -- one shared upstream feed per (region, kind) covering every
// integrated agency at once, with each agency's own entities distinguished by an
// "<agencyCode><idSeparator>" prefix on trip_id/route_id (confirmed live for 511's SF Bay Area
// feed: trip_id "BA:1965572", route_id "BA:19"). A request for one agency's realtime
// (/<routePrefix><CODE>/tripupdates|vehiclepositions) is served by fetching+caching the ONE
// shared regional payload (routePrefix + '/regional/' + kind, also directly fetchable for
// debugging/inspection -- see PROXIED_ROUTES below) and filtering it down to just that agency's
// entities, stripping the prefix back off so the result is byte-for-byte indistinguishable from a
// real dedicated per-agency feed to the app (see filterRegionalFeed). This is what actually
// bounds the worker's own upstream request rate to the aggregator: however many agencies get
// realtime wired, the worker still only ever makes ONE tripupdates + ONE vehiclepositions fetch
// to 511 per cache window, not one per agency.
//
// To onboard a second regional aggregator later (a different region's own "511"), add another
// entry here with its own routePrefix/regionalPathPrefix/idSeparator -- no changes needed to
// serveRegionalAgencyRoute or the protobuf filtering logic itself, both are generic.
const REGIONAL_FEEDS = [
  {
    routePrefix: '/511SF',
    regionalPathPrefix: '/511SF/regional',
    idSeparator: ':',
  },
];
for (const feed of REGIONAL_FEEDS) {
  PROXIED_ROUTES[`${feed.regionalPathPrefix}/tripupdates`] = 'http://api.511.org/Transit/TripUpdates?agency=RG';
  PROXIED_ROUTES[`${feed.regionalPathPrefix}/vehiclepositions`] = 'http://api.511.org/Transit/VehiclePositions?agency=RG';
}

// Matches e.g. "/511SFBA/tripupdates" -> { feed, code: "BA", kind: "tripupdates" }. Deliberately
// does NOT match a feed's own regionalPathPrefix routes (".../regional/tripupdates") or its
// per-agency static routes (".../BA/static") -- both fall through to the generic PROXIED_ROUTES
// handling below unchanged.
function matchRegionalAgencyRoute(pathname) {
  for (const feed of REGIONAL_FEEDS) {
    if (!pathname.startsWith(feed.routePrefix)) continue;
    const rest = pathname.slice(feed.routePrefix.length);
    const m = rest.match(/^([A-Z0-9]+)\/(tripupdates|vehiclepositions)$/);
    if (!m) continue;
    return { feed, code: m[1], kind: m[2] };
  }
  return null;
}

const REDIRECT_ROUTES = {
  '/mbta/static': 'https://cdn.mbta.com/MBTA_GTFS.zip',
  '/ripta/static': 'https://ripta.com/RIPTA-GTFS.zip',
  '/rtd/static': 'https://www.rtd-denver.com/files/gtfs/google_transit.zip',
  '/bustang/static': 'https://www.rtd-denver.com/files/gtfs/bustang-co-us.zip',
  '/stm/static': 'https://www.stm.info/sites/default/files/gtfs/gtfs_stm.zip',
};

// Unified table for injecting per-origin API keys into the outgoing request.
// Each entry: which routes it applies to (by path prefix), which env var
// holds the key, and whether the key goes in the query string or a header.
// Add a new keyed origin by adding one row here — no new branching logic needed.
const API_KEY_INJECTIONS = [
  { prefix: '/mbta/v3/vehicles', envVar: 'MBTA_API_KEY', type: 'query', param: 'api_key' },
  { prefix: '/stm/', envVar: 'STM_API_KEY', type: 'header', header: 'apiKey' },
  { prefix: '/511SF', envVar: 'SF511_API_KEY', type: 'query', param: 'api_key' },
  { prefix: '/cta/bus/', envVar: 'CTA_BUS_API_KEY', type: 'query', param: 'key' },
];

// 511's TripUpdates/VehiclePositions endpoints require either a `format` query
// param or an Accept header naming the protobuf mime type -- undocumented
// behavior for the omitted case ranges from erroring to silently returning an
// empty-but-valid FeedMessage, so send it explicitly rather than relying on
// whatever the server defaults to. Scoped to /511SF only: the MBTA v3 API on
// this same worker needs a JSON Accept, so this can't be set globally.
const ACCEPT_HEADER_OVERRIDES = [
  { prefix: '/511SF', header: 'application/x-google-protobuf' },
];

// Some origins 403 this worker's own default 'pico-transit-proxy' User-Agent and need something
// that reads as a real browser instead (confirmed live for CTA's realtime domain, before that
// integration moved to a different API entirely -- kept here, empty, since the next origin that
// needs this won't be the last). Add a { prefix, userAgent } row per origin that needs it; no
// other origin on this worker needs its default UA overridden today, so this isn't global.
const USER_AGENT_OVERRIDES = [];

// Matches the app's own realtime poll interval (~10s) -- some origins (MBTA's v3 API, STM's API)
// need requests spaced at least that far apart, so this can't go shorter across the board. A poll
// landing right at a cache entry's edge can still occasionally get one repeat value before the
// next entry refreshes, but this is as tight as it can go without risking those origins' own
// limits.
const DEFAULT_CACHE_SECONDS = 10;
// Static GTFS feeds change rarely and are large, so they get a much longer
// TTL instead of the 10s RT default -- still cached, just far less often refetched.
const STATIC_CACHE_SECONDS = 21600; // 6 hours
const CACHE_SECONDS_OVERRIDE = {
  '/ltc/static': STATIC_CACHE_SECONDS,
  ...Object.fromEntries(
    Object.keys(PROXIED_ROUTES)
      .filter((path) => path.endsWith('/static'))
      .map((path) => [path, STATIC_CACHE_SECONDS])
  ),
};
const ALLOWED_METHODS = new Set(['GET', 'HEAD']);

// ---- Generic protobuf field-level read/write (schema-agnostic: preserves every field byte-for-
// byte except the specific ones filterRegionalFeed below chooses to touch, so nothing this app's
// own decoder does or doesn't know about gets silently dropped). ----

function readVarint(buf, pos) {
  let result = 0n;
  let shift = 0n;
  while (true) {
    const b = buf[pos];
    pos += 1;
    result |= BigInt(b & 0x7f) << shift;
    if (!(b & 0x80)) break;
    shift += 7n;
  }
  return [result, pos];
}

function writeVarint(value) {
  const bytes = [];
  let v = BigInt(value);
  while (true) {
    const b = Number(v & 0x7fn);
    v >>= 7n;
    if (v !== 0n) {
      bytes.push(b | 0x80);
    } else {
      bytes.push(b);
      break;
    }
  }
  return new Uint8Array(bytes);
}

function concatBytes(arrays) {
  const total = arrays.reduce((sum, a) => sum + a.length, 0);
  const out = new Uint8Array(total);
  let offset = 0;
  for (const a of arrays) {
    out.set(a, offset);
    offset += a.length;
  }
  return out;
}

// Parses [start, end) into a flat list of { fieldNo, wireType, value } -- value is a BigInt for
// wireType 0 (varint), a raw Uint8Array slice for wireType 1/2/5 (fixed64/length-delimited/
// fixed32). Doesn't recurse into submessages; callers do that themselves only where needed.
function parseFields(buf, start, end) {
  const fields = [];
  let pos = start;
  while (pos < end) {
    const [tag, afterTag] = readVarint(buf, pos);
    pos = afterTag;
    const fieldNo = Number(tag >> 3n);
    const wireType = Number(tag & 7n);
    if (wireType === 0) {
      const [value, afterValue] = readVarint(buf, pos);
      pos = afterValue;
      fields.push({ fieldNo, wireType, value });
    } else if (wireType === 1) {
      fields.push({ fieldNo, wireType, value: buf.slice(pos, pos + 8) });
      pos += 8;
    } else if (wireType === 2) {
      const [len, afterLen] = readVarint(buf, pos);
      pos = afterLen;
      const l = Number(len);
      fields.push({ fieldNo, wireType, value: buf.slice(pos, pos + l) });
      pos += l;
    } else if (wireType === 5) {
      fields.push({ fieldNo, wireType, value: buf.slice(pos, pos + 4) });
      pos += 4;
    } else {
      throw new Error(`bad wiretype ${wireType} at byte ${pos}`);
    }
  }
  return fields;
}

function encodeField(field) {
  const tag = writeVarint(BigInt(field.fieldNo) << 3n | BigInt(field.wireType));
  if (field.wireType === 0) {
    return concatBytes([tag, writeVarint(field.value)]);
  } else if (field.wireType === 1 || field.wireType === 5) {
    return concatBytes([tag, field.value]);
  } else if (field.wireType === 2) {
    return concatBytes([tag, writeVarint(BigInt(field.value.length)), field.value]);
  }
  throw new Error(`cannot encode wiretype ${field.wireType}`);
}

function encodeFields(fields) {
  return concatBytes(fields.map(encodeField));
}

// ---- Regional-feed-specific filtering (GTFS-RT field numbers are part of the public spec, not
// specific to any one region, so these are safe to hardcode rather than configure per-feed). ----

const utf8Decoder = new TextDecoder();
const utf8Encoder = new TextEncoder();
// FeedEntity field numbers for the two submessage kinds a regional feed's entities can carry.
const CONTAINER_FIELD_NO = { tripupdates: 3, vehiclepositions: 4 };
// Within either TripUpdate or VehiclePosition, field 1 is the nested TripDescriptor; within
// TripDescriptor, field 1 is trip_id and field 5 is route_id -- both get the agency prefix.
const TRIP_DESCRIPTOR_FIELD_NO = 1;
const TRIP_ID_FIELD_NO = 1;
const ROUTE_ID_FIELD_NO = 5;

function stripPrefixFromTripDescriptor(bytes, prefixWithSeparator) {
  const fields = parseFields(bytes, 0, bytes.length);
  const out = fields.map((f) => {
    if ((f.fieldNo === TRIP_ID_FIELD_NO || f.fieldNo === ROUTE_ID_FIELD_NO) && f.wireType === 2) {
      const str = utf8Decoder.decode(f.value);
      if (str.startsWith(prefixWithSeparator)) {
        return { ...f, value: utf8Encoder.encode(str.slice(prefixWithSeparator.length)) };
      }
    }
    return f;
  });
  return encodeFields(out);
}

function stripPrefixFromContainer(containerBytes, prefixWithSeparator) {
  const fields = parseFields(containerBytes, 0, containerBytes.length);
  const out = fields.map((f) => {
    if (f.fieldNo === TRIP_DESCRIPTOR_FIELD_NO && f.wireType === 2) {
      return { ...f, value: stripPrefixFromTripDescriptor(f.value, prefixWithSeparator) };
    }
    return f;
  });
  return encodeFields(out);
}

function extractTripId(containerBytes) {
  const fields = parseFields(containerBytes, 0, containerBytes.length);
  const tripDescriptor = fields.find((f) => f.fieldNo === TRIP_DESCRIPTOR_FIELD_NO && f.wireType === 2);
  if (!tripDescriptor) return null;
  const tdFields = parseFields(tripDescriptor.value, 0, tripDescriptor.value.length);
  const tripIdField = tdFields.find((f) => f.fieldNo === TRIP_ID_FIELD_NO && f.wireType === 2);
  return tripIdField ? utf8Decoder.decode(tripIdField.value) : null;
}

// Filters a raw regional FeedMessage down to one agency's entities (matched by trip_id prefix),
// stripping that prefix from trip_id/route_id so the output is indistinguishable from a real
// dedicated per-agency feed. The FeedHeader (field 1) and every field on every KEPT entity other
// than the two touched above pass through byte-identical -- this never needs to understand a
// field it isn't specifically modifying, declared or not.
function filterRegionalFeed(rawBytes, agencyCode, kind, idSeparator) {
  const prefixWithSeparator = `${agencyCode}${idSeparator}`;
  const containerFieldNo = CONTAINER_FIELD_NO[kind];
  const top = parseFields(rawBytes, 0, rawBytes.length);
  const out = [];
  for (const f of top) {
    if (f.fieldNo !== 2 || f.wireType !== 2) {
      out.push(f); // FeedHeader or anything else at the top level -- untouched
      continue;
    }
    const entityFields = parseFields(f.value, 0, f.value.length);
    const container = entityFields.find((ef) => ef.fieldNo === containerFieldNo && ef.wireType === 2);
    if (!container) continue;
    const tripId = extractTripId(container.value);
    if (!tripId || !tripId.startsWith(prefixWithSeparator)) continue;

    const newEntityFields = entityFields.map((ef) =>
      ef.fieldNo === containerFieldNo && ef.wireType === 2
        ? { ...ef, value: stripPrefixFromContainer(ef.value, prefixWithSeparator) }
        : ef
    );
    out.push({ fieldNo: 2, wireType: 2, value: encodeFields(newEntityFields) });
  }
  return encodeFields(out);
}

// Serves one agency's slice of a shared regional feed: checks this agency's own cached filtered
// result first (cheapest -- no fetch, no filtering), then the shared raw regional payload's own
// cache entry (one fetch/filter still needed, but no trip to 511), and only reaches 511 itself,
// rate-limited, when neither is cached. Every request for every agency sharing this feed can only
// ever cause AT MOST one real 511 fetch per (region, kind) per cache window, however many
// distinct agencies or users are asking.
async function serveRegionalAgencyRoute(match, request, env, ctx) {
  const { feed, code, kind } = match;
  const cache = caches.default;

  const agencyCacheKey = new Request(new URL(`${feed.routePrefix}${code}/${kind}`, request.url).toString(), request);
  const cachedAgency = await cache.match(agencyCacheKey);
  if (cachedAgency) return cachedAgency;

  const regionalPath = `${feed.regionalPathPrefix}/${kind}`;
  const regionalCacheKey = new Request(new URL(regionalPath, request.url).toString(), request);
  let rawBytes;
  const cachedRegional = await cache.match(regionalCacheKey);
  if (cachedRegional) {
    rawBytes = new Uint8Array(await cachedRegional.arrayBuffer());
  } else {
    if (env.RATE_LIMITER) {
      const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
      const { success } = await env.RATE_LIMITER.limit({ key: ip });
      if (!success) {
        return new Response('Too many requests', { status: 429 });
      }
    }

    const target = new URL(PROXIED_ROUTES[regionalPath]);
    const key = env.SF511_API_KEY;
    if (key) target.searchParams.set('api_key', key);

    let upstream;
    try {
      upstream = await fetch(target.toString(), {
        headers: { 'User-Agent': 'pico-transit-proxy', Accept: 'application/x-google-protobuf' },
      });
    } catch (err) {
      return new Response('Origin fetch failed', { status: 502 });
    }
    if (upstream.status < 200 || upstream.status >= 300) {
      return new Response('Origin error', { status: upstream.status });
    }

    rawBytes = new Uint8Array(await upstream.arrayBuffer());
    const rawResponse = new Response(rawBytes, {
      status: 200,
      headers: {
        'Content-Type': 'application/x-google-protobuf; charset=utf-8',
        'Cache-Control': `public, max-age=${DEFAULT_CACHE_SECONDS}`,
      },
    });
    ctx.waitUntil(cache.put(regionalCacheKey, rawResponse.clone()));
  }

  const filtered = filterRegionalFeed(rawBytes, code, kind, feed.idSeparator);
  const filteredResponse = new Response(filtered, {
    status: 200,
    headers: {
      'Content-Type': 'application/x-google-protobuf; charset=utf-8',
      'Cache-Control': `public, max-age=${DEFAULT_CACHE_SECONDS}`,
    },
  });
  ctx.waitUntil(cache.put(agencyCacheKey, filteredResponse.clone()));
  return filteredResponse;
}

export default {
  async fetch(request, env, ctx) {
    if (!ALLOWED_METHODS.has(request.method)) {
      return new Response('Method not allowed', { status: 405 });
    }

    const url = new URL(request.url);

    if (url.pathname in REDIRECT_ROUTES) {
      return Response.redirect(REDIRECT_ROUTES[url.pathname], 302);
    }

    const regionalMatch = matchRegionalAgencyRoute(url.pathname);
    if (regionalMatch) {
      return serveRegionalAgencyRoute(regionalMatch, request, env, ctx);
    }

    const target = PROXIED_ROUTES[url.pathname];
    if (!target) {
      return new Response('Not found', { status: 404 });
    }

    // Cache is checked BEFORE the rate limiter -- a request this Worker can answer for free from
    // its own cache shouldn't cost the caller anything from their budget. Only a request that
    // actually needs to reach the real origin gets rate-limited below.
    const cache = caches.default;
    const cacheKey = new Request(url.toString(), request);
    const cached = await cache.match(cacheKey);
    if (cached) return cached;

    if (env.RATE_LIMITER) {
      const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
      const { success } = await env.RATE_LIMITER.limit({ key: ip });
      if (!success) {
        return new Response('Too many requests', { status: 429 });
      }
    }

    const originUrl = new URL(target);
    for (const [key, value] of url.searchParams) {
      originUrl.searchParams.append(key, value);
    }

    const fetchHeaders = { 'User-Agent': 'pico-transit-proxy' };

    for (const injection of API_KEY_INJECTIONS) {
      if (!url.pathname.startsWith(injection.prefix)) continue;
      const key = env[injection.envVar];
      if (!key) continue;

      if (injection.type === 'query') {
        originUrl.searchParams.set(injection.param, key);
      } else if (injection.type === 'header') {
        fetchHeaders[injection.header] = key;
      }
    }

    for (const override of ACCEPT_HEADER_OVERRIDES) {
      if (url.pathname.startsWith(override.prefix)) {
        fetchHeaders['Accept'] = override.header;
      }
    }

    for (const override of USER_AGENT_OVERRIDES) {
      if (url.pathname.startsWith(override.prefix)) {
        fetchHeaders['User-Agent'] = override.userAgent;
      }
    }

    let upstream;
    try {
      upstream = await fetch(originUrl.toString(), {
        method: request.method,
        headers: fetchHeaders,
      });
    } catch (err) {
      return new Response('Origin fetch failed', { status: 502 });
    }

    const headers = new Headers(upstream.headers);
    const cacheSeconds = CACHE_SECONDS_OVERRIDE[url.pathname] ?? DEFAULT_CACHE_SECONDS;
    headers.set('Cache-Control', `public, max-age=${cacheSeconds}`);
    headers.append('Vary', 'Accept-Encoding');
    headers.delete('set-cookie');

    const response = new Response(upstream.body, {
      status: upstream.status,
      statusText: upstream.statusText,
      headers,
    });

    if (upstream.status >= 200 && upstream.status < 300) {
      ctx.waitUntil(cache.put(cacheKey, response.clone()));
    }
    return response;
  },
};
