# TVDB runtime regression fixtures

Recorded from the public TVDB v4 episode endpoints on 28 October 2026:
Office (US), series 73244, official and alternate; Firefly, series 78874,
official and DVD. These minimal fixtures retain only episode IDs, display
coordinates, names and air dates. No authentication response, key, token,
user data, overview or artwork is included.

They exercise complete real-world order sizes and stable identity differences;
provider metadata in tests is constructed separately and does not reproduce
a private addon response. Live opt-in verification is in TvdbLiveIntegrationTest.

`office-provider-episodes.json` records the configured provider's public episode
IDs, titles, coordinates, air dates and availability on 28 October 2026. All URLs,
account configuration, artwork and stream references are excluded. It reproduces
the combined-episode structure involved in Trakt mapping-cache contamination.

`office-all-orders.json` contains complete public identity/title/date/runtime facts
for all seven discovered Office orders (including the season-endpoint fallbacks
for Blu-ray and Extended Cut). It contains no account, authentication or stream data.

`office-trakt-episodes.json` contains the 292 public Trakt episode catalogue entries
retrieved from the public catalogue on 28 October 2026.
Only public titles, native coordinates, external IDs and first-air dates are retained.
No account history, access token, client credential or other account data is included.


Kitchen Nightmares public regression fixtures (29 October 2026):
- `kitchen-orders.json`: 125 factual episode records per order, extracted from
  unauthenticated TheTVDB `/series/kitchen-nightmares-us/allseasons/official` and
  `/allseasons/alternate` catalogue pages; no TVDB key used.
- `kitchen-trakt-catalogues.json`: separate public 2007 and 2023 Trakt catalogues,
  with 92 and 33 episode records; only title/year, public IDs, numbers and air dates.
- `kitchen-trakt-episode-links.json`: public TVDB episode-ID lookups for the
  original premiere and three revival season premieres. The 2026 premiere has
  no direct TVDB-ID hit; its verified-parent catalogue supplies title/date proof.

The 2023 Trakt parent lacks a series TVDB ID. Tests preserve its own IMDb/TMDB/Trakt
IDs and withhold the unresolved Firenze Pizzeria/Pizzaria typo. None of these
fixtures contains credentials, private account history, stream URLs or image URLs.
Simkl parent 222 in the test is a synthetic snapshot identifier, not a claimed live
Simkl catalogue ID. Direct TMDB responses are mocked using public native IDs; no
live TMDB API response is claimed where the local key was unavailable.

- `kitchen-trakt-minimal-episode-link.json` records the default public episode-ID
  lookup response, which omits first_aired. The HTTP regression returns this
  response unless the real Retrofit request asks for extended=full, preventing
  mocks from silently supplying fields the actual request did not fetch.
