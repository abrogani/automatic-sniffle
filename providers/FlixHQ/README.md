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

**Byse.** The embed is a React app that gates playback behind a "click play to verify you're a human" screen
(`captcha-gate__play`), then a proof-of-work and an AES-GCM encrypted playback payload bound to a browser fingerprint.
None of that is reimplemented. The embed URL is loaded in a visible `WebViewDriver` registered with
`WebViewDriverManager`; the user taps play, the page decrypts its own payload, and the stream is captured from:

1. `jwplayer().getPlaylist()[0]` polled every 500 ms (`sources` and caption `tracks`), then
2. any `.m3u8` / `.mp4` request seen in `shouldInterceptRequest`, preferring a `master` playlist.

Capture times out after 90 s. Streams carry `Flag.RequiresAuth` with `Referer`, `Origin` and `User-Agent` of the embed
host. The WebView is destroyed after each `getLinks` and again in `onUnload`.

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
