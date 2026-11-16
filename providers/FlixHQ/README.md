# FlixHQ

Flixclusive provider for [flixhqz.net](https://flixhqz.net): catalogs, search, metadata, IMDB cross-match and streams.
Rewritten from scratch for core-stubs **1.4.0**. Provider id `abrogani-flixhq` is unchanged, so existing installs update in place.

## Capabilities

| Capability | Class | Source |
|---|---|---|
| Catalog | `FlixHQCatalogApi` | Movies, TV Shows, Top IMDB, plus one catalog per genre scraped from the home sidebar. Paged via `/<path>/page/<n>/`. |
| Search | `FlixHQSearchApi` | `/search?keyword=<q>&page=<n>`. `Type` filter (All / Movies / TV Shows) is applied client-side. |
| Metadata | `FlixHQMetadataApi` | Detail page: JSON-LD plus the `.elements` rows. Shows are eager: `getSeason` returns `null`. |
| Cross-match | `FlixHQCrossMatchApi` | `getById` searches the IMDB id; `getByFuzzy` needs an exact normalized title and a matching year. |
| Media links | `FlixHQMediaLinkApi` | Server list from the player token, then one extractor per server. |

## Ids

| Kind | Id | Example |
|---|---|---|
| Movie | URL path | `watch-movie/other-mommy-2026-watch-online` |
| Show | URL path | `watch-series/the-mob-2026-watch-online` |
| Episode | URL path | `episode/the-mob-2026-watch-online/s01-e02` |

If the host hands back an episode whose id is not ours, the path is rebuilt from the show id, season and episode number.

## Link flow

```
getLinks(media, episode)
   │  GET page            movie: /watch-movie/<slug>/   episode: /episode/<slug>/sNN-eMM/
   │  data-token          movie: #main-wrapper          episode: #series-player
   ▼
POST /ajax/ajax.php       multipart  players=<token>  (movie)  |  players_show=<token>  (episode)
   ▼
[{ name, link, en_sub }]  ── per server ──►  VidmolyExtractor | ByseExtractor
   │
   └─ sub.info / subget query param ──► SubtitleResolver (JSON track list, captions only)
```

Servers run concurrently and each is isolated: a failing server is logged and the rest keep going. `getLinks` throws only
when no stream was found at all. Streams and subtitles are emitted as soon as they resolve and de-duplicated by URL.

## Extractors

| Server | Extractor | Method |
|---|---|---|
| Vidmoly (`kaembed.net`, `vidmoly.*`) | `VidmolyExtractor` | Plain HTTP. Reads `sources: [{ file: '…master.m3u8…' }]` from the JW Player setup. Falls back to any `.m3u8` URL in the page. |
| FlixHQ (Byse, `/e/<code>`) | `ByseExtractor` + `ByseWebView` | WebView, see below. |
| Videasy | not implemented | Unverified. |

**Vidmoly.** The master playlist needs no `Referer`. The signed URL carries `s` (issued at, epoch seconds) and `e`
(lifetime, seconds); the stream gets `Flag.Expires((s + e) * 1000)`.

**Byse.** The embed is a React app that gates playback behind a proof-of-work, an optional "click play to verify you're
human" screen (`captcha-gate__play`) and an AES-GCM encrypted playback payload bound to a browser fingerprint. None of
that is reimplemented. The embed URL is loaded in a visible `WebViewDriver`; the page decrypts its own payload and the
stream is captured from:

1. `jwplayer().getPlaylist()[0]` polled every 500 ms (`sources` and caption `tracks`), then
2. any `.m3u8` / `.mp4` request seen in `shouldInterceptRequest`, preferring a `master` playlist.

Capture times out after 90 s. Streams carry `Flag.RequiresAuth` (`Referer`, `Origin`, `User-Agent` of the embed host)
and `Flag.Expires` from the signed `s` / `e` query parameters. The WebView is destroyed after each `getLinks` and again
in `onUnload`. Page load, console and load errors are logged with the `ByseWebView` prefix.

Do not call `WebViewDriverManager.register` yourself: `WebViewDriver.loadUrl` registers a non-headless driver, and
`register` destroys whatever is registered first, so an extra call destroys the driver that is about to load.

## Device verification

Verified on the Flixclusive 3.0.1 release APK, Android 13 AVD (`Medium_Phone_33`, WebView 109).

| Area | Result |
|---|---|
| Provider load | `FlixHQ-debug v2.0.0` listed with Links, Catalogs, Search, Metadata, Cross-match |
| Catalogs | Home rows and hero populated from the genre catalogs |
| Search, paging | Results and the Type filter render; broad queries return a paged grid |
| Metadata | Movie (rating, runtime, language, year, certification, genre, overview) and series (seasons, episodes) |
| Vidmoly | Stream plays (HLS master playlist, no headers needed) |
| Byse | Page renders on WebView 109; `jwplayer` sources captured as `Byse · 1080p`; master playlist, variant playlist and first segment fetched by ExoPlayer |

Observed host behaviour, not caused by the provider: on 3.0.1 the first playback of a session with sideloaded subtitles
failed twice with `ExoPlaybackException` (`IllegalStateException` in `ProgressiveMediaPeriod.startLoading`), once with
12 subtitle tracks and once with 2, while the same stream with no subtitles, and a later start in the same session, played.
Subtitle labels are tidied (`English - [Eng] 2` becomes `English`, duplicates get an index) because the host feeds the
label to `Locale.Builder().setLanguage`, which logs an `IllformedLocaleException` for free-form labels.

## Source layout

| Path | Responsibility |
|---|---|
| `FlixHQ.kt` | Plugin entry; lazy capability instances (`manifest` is injected after construction). |
| `api/` | The five capability implementations and constants. |
| `parser/FlixHQParser.kt` | Every selector for the site, as pure functions over a Jsoup `Document`. |
| `extractors/` | `EmbedExtractor`, Vidmoly, Byse (`ByseWebView`, pure `ByseCaptures` helpers), `SubtitleResolver`. |
| `dto/` | Gson DTOs. The SDK `fromJson` is kotlinx-serialization only. |
| `extensions/` | Url / id helpers, HTTP helpers, Gson helpers. |

## Tests

```bash
./gradlew :FlixHQ:testDebugUnitTest --offline
```

Offline only, against trimmed copies of the live markup in `src/test/resources/fixtures/`. The Byse WebView path cannot
run on the JVM and is verified on a device only.
