# ServeTorr

Flixclusive provider that makes torrent links from **other** providers playable. Magnets, `.torrent` URLs and bare info
hashes are rewritten to a loopback URL; when the player opens it, a bundled-on-demand TorrServer engine streams the right
file over HTTP.

Flixclusive has no API for touching another provider's links and its player can't play torrents, so this provider works
by hooking host internals (reflection). Verified against **Flixclusive 3.0.1**.

## How it works

```
other provider getLinks()
        │  magnet:?xt=urn:btih:…
        ▼
InterceptingLinkApi ── TorrentLinkRewriter ──►  http://127.0.0.1:47390/torrent/<name>?link=…[&s=&e=]
                                                (instant, no I/O, Flag.Expires = now + 12h)
player opens the URL
        ▼
LinkProxy (127.0.0.1 only) ── LinkResolver
        │  1. NetworkPolicy: Wi-Fi only?          → 503
        │  2. engine.ensureRunning()               → 503 not installed / 502 failed
        │  3. engine.files() (30s, cached per hash)
        │  4. FileSelector picks the file          → 404 if the episode isn't in the torrent
        ▼
302 → http://127.0.0.1:<engine port>/stream/<name>?link=magnet:?xt=urn:btih:<hash>…&index=<id>&play
        ▼
TorrServer (in-process, gomobile .so) streams with Range support
```

Nothing touches the network while browsing. Torrents are only added when something is played.

## Source layout

| Package | Responsibility |
|---|---|
| `ServeTorr.kt` | Plugin entry. No `onLoad` exists, so everything is wired from `init` once the host injects `settings`. |
| `intercept/` | Finds the host's provider registry and link cache through Hilt, wraps every other provider, purges stale cached links. |
| `link/` | Torrent link parsing, file selection, proxy and engine URL building. Pure, unit-tested. |
| `proxy/` | Minimal HTTP/1.1 server on `127.0.0.1` that resolves lazily and redirects to the engine. |
| `engine/` | Engine download/verify/load, lifecycle (start, stop, idle auto-stop, restart detection), settings, torrent inventory, uninstall cleanup. |
| `net/` | OkHttp `Platform` wrapper that allows cleartext to loopback; Wi-Fi-only policy. |
| `settings/` | Compose settings screen, torrents sheet, theme, icon set. |
| `engine/` (module root) | Go engine build: bindings, upstream patches, `build.sh`. |

## Engine

Our own gomobile build of [YouROK/TorrServer](https://github.com/YouROK/TorrServer) (GPL-3.0). The upstream commit is
pinned in `engine/build.sh`.

**What we change** (`engine/bindings`, `engine/patches`):

- `bindings/`: the `torrServer` gomobile package. `startTorrentServer(path, port)` returns the real port or `-1` and never
  exits the process. Also `stopTorrentServer`, `addTrackers`, `waitTorrentServer`. Only the HTTP API is linked: no web
  UI, Telegram bot, MCP, DLNA or Bonjour.
- Binds `127.0.0.1` only (upstream's bind option). Requests carrying `Origin` or `Sec-Fetch-Site` get `403`, so web
  pages can't drive it.
- `upstream-0001`: DLNA/Bonjour stubs under the `servetorr` build tag.
- `upstream-0002`: reopen the settings DB after stop, so stop → start works in one process.
- `upstream-0003`: stop cancels any in-flight `/settings` reconnect loop (epoch counter), and the trackers-list refresh
  pauses while stopped.
- `gobind-java-lazy-load.patch`: adds a lazy `go.Seq.load()`. Stock gomobile loads the library in a static initializer,
  which we can't allow (see Loading).

**Versioning.** `VERSION` is a plain integer. These move together:

| Where | Value |
|---|---|
| `EngineSource.VERSION` | `1` |
| `const tag` in `bindings/torrserver.go` | `1` |
| `TAG` in `build.sh` | `1` |
| Release tag | `servetorr-engine-1` |
| `/echo` | `ServeTorr torrserver 1` |

**Building.**

```sh
ANDROID_HOME=… ANDROID_NDK_HOME=… engine/build.sh [out-dir]
```

- Requires Go 1.27.4, a JDK (Temurin 25.0.2 in CI), NDK 28.2.13676358 and build-tools 36.1.0. Runs on macOS or Linux.
- Output is reproducible: the build runs in a fixed `/tmp/servetorr-engine` with `-trimpath` and no build ID.
- Writes `torrserver.aar` and `sha256.txt`. The AAR contains `jni/<abi>/libgojni.so` (16 KB aligned on 64-bit) and a
  top-level `classes.dex` (the d8'd bindings).

**Releasing.** Push a `servetorr-engine-<VERSION>` tag, or run the workflow manually.
`.github/workflows/servetorr-engine.yml` builds, checks the hashes against the pins in `EngineSource.kt`, and publishes
the release asset. If the hashes differ, the job fails and its summary contains the `EngineSource` block to paste: commit
it, move the tag, run again. **The CI build is the source of truth**; the current pins come from a macOS build.

**Install.** `EngineInstaller` range-requests the AAR's ZIP tail, reads the central directory, and fetches only
`jni/<abi>/libgojni.so` and `classes.dex` (one range each, roughly 9 MB). If the server ignores `Range`, it streams the
whole AAR once instead. Each file is checked against its pinned sha256 and size cap, written to a temp file, then
renamed and made read-only once both pass. The ABI comes from the process bitness. "Installed" means both files are
present and verified.

**Loading.** `System.loadLibrary("gojni")` resolves through the *defining* class loader, so the bindings dex is loaded
with a `DexClassLoader` whose library search path is the engine dir. It can't live in the `.flx` dex.

**Runtime rules.**

- The engine starts on first play, on a random port. "Stop engine" stops HTTP, the BT client and the DB; the next play
  starts it again in the same process.
- Idle auto-stop: a watcher (one per process) polls `/torrents list` every 30 s. After N minutes with no active torrent
  and no resolution in flight, it stops the engine. Default 5 minutes; 0 disables.
- The `.so` can't be unloaded. The loaded version, started port and failure flags live in system properties so a
  reloaded provider copy (new class loader) can reuse the engine. If a *different* version is installed while one is
  loaded, or a start fails, or the engine dies, the state becomes `RestartRequired` and the settings screen shows a
  one-time dialog. Remove + reinstall of the same version needs no restart.
- `/echo` must match `ServeTorr torrserver <VERSION>` exactly. We only reuse a port this process started.

**Upstream behaviour the client relies on.**

- File ids are 1-based, in info-dict order. Entries without an id are skipped. Single-file torrents ignore `index`.
- `add` returns immediately; metadata is polled with `get` (20 s timeout).
- `/stream` re-parses `link` on every request and unescapes it twice. We always address the engine by info-hash magnet
  (a `.torrent` URL would be re-downloaded on every seek) and pre-escape `%` and `+`.
- `/settings set` replaces the whole struct, drops every torrent and reconnects. We always do get → merge → set.
- On every BT connect upstream fetches a public trackers list (ngosang via GitHub/jsdelivr), which happens only at play
  time.

**Paths.**

| Path | Contents |
|---|---|
| `filesDir/torrent/engine/<VERSION>/` | `libgojni.so`, `bindings.dex`, `oat/` (ART) |
| `cacheDir/torrent/server/` | engine DB (`config.db`, `settings.json`, `viewed.json`) |
| `cacheDir/torrent/cache/<hash>/` | disk cache, when enabled |

Every deletion of engine files is logged with path and size. Missing or failed-verification files are logged at start.

## Settings

| Setting | Stored in | Notes |
|---|---|---|
| Intercept links | plugin DataStore | Master switch for rewriting. |
| Link port | plugin DataStore | Default 47390. Changing it rebinds the proxy live. Links cached on the old port are purged. |
| Wi-Fi only | plugin DataStore | Proxy answers 503 off Wi-Fi/Ethernet. A running engine stops when the default network leaves Wi-Fi or the setting is turned on while off Wi-Fi. VPNs count as Wi-Fi when their underlying transport is Wi-Fi. |
| Stop when idle | plugin DataStore | Minutes, 0 to 240, 0 = never. |
| Engine settings | plugin DataStore, applied to the engine | Cache size (MB, per torrent), read-ahead, preload, disk cache, idle torrent timeout, connections, speed limits (KiB/s), peer port, encryption, IPv6, TCP/uTP/UPnP/DHT/PEX/upload toggles, local peer discovery (upstream defaults on; we default off), retracker mode, tracker list. Re-applied on every engine start, because the engine's own DB lives in `cacheDir` and can be wiped. |

Default trackers are the union of CloudStream's list and ours (`EngineSource.DEFAULT_TRACKERS`).

The screen uses its own green Material 3 theme (`settings/theme`) and picks dark or light from the host's background,
since the host is dark-only. Icons are Material Icons path data embedded in `settings/icons`, because the host doesn't
ship `material-icons`. The header icon is loaded with Coil from `ICON_URL`.

**Torrents sheet.** Lists the engine's torrents plus disk-cache leftovers, refreshing every 2 s. Per-torrent removal
calls `rem`, clears its viewed state and deletes its cache dir. **Clear cache** wipes all torrents, the disk cache, the
engine DB (kept while running) and leftovers from older engine versions, including the CloudStream build. The installed
engine and the user's settings are kept.

## Host integration

All of this is reflection against Flixclusive internals. Each lookup degrades to a logged warning and pass-through
behaviour; none of them throw.

| What | How | Why |
|---|---|---|
| Cleartext to loopback | Swap OkHttp's `Platform` (checked per connection) | Host targets SDK 36 with no network security config. Only literal loopback is allowed (`127.x.y.z`, `::1`, `localhost`). |
| Provider registry | Hilt `SingletonC` → `ProviderRepositoryImpl.pluginsMap` | `GetMediaLinksUseCaseImpl` calls `getMediaLinkApi()` fresh per request, so swapping map entries takes effect immediately. |
| Link cache | Hilt → `AppDatabase` → `cachedMediaLinkDao()` / `userDao()` | The host replays cached links per (user, media, episode) while any is unexpired, and old raw magnets never expire. Any group holding a raw torrent link (once the engine is installed) or a proxy URL on an old port is deleted whole, after the proxy starts, on install, and on port change. |
| Settings detection | Two wrapper classes, with and without `SettingsScreen` | The host checks `getDeclaredComposableMethod("SettingsScreen")` on the exact class. |

Lifecycle quirks handled:

- **Profile switch and same-id updates.** A same-id provider update and a profile switch (`clearAll()`) don't call
  `onUnload`. The interceptor acts only after it has seen itself in the map, and retires and restores its wrappers once
  it's gone. Wrappers from other copies are recognised by class name.
- **Factory bindings.** `providerRepositoryImplProvider` is an unscoped factory, and calling `get()` would build a
  second, empty repository. Only memoizing `DoubleCheck`/`SingleCheck` instances are unwrapped.
- **Stale proxy after a host update.** The old copy's proxy may still own the port. `GET /torrent-proxy/ping`
  identifies it so we can reuse it.
- **Uninstall cleanup.** `onUnload` fires for uninstall, id-changing updates and backup restores. Cleanup runs 5 s and
  25 s later and deletes engine files only if no ServeTorr class remains in `pluginsMap` *and* our `.flx` (path read
  from the class loader) is gone.

## Development

```sh
./gradlew :ServeTorr:testDebugUnitTest --offline
./gradlew :ServeTorr:make :ServeTorr:deployWithAdb
```

On-device testing:

- **Use an Android 13 AVD with the release APK.** Android 14+ refuses a debug `.flx` from shared storage (writable dex).
- **Grant storage access first.** Run `adb shell appops set com.flixclusive MANAGE_EXTERNAL_STORAGE allow`, or the host
  crashes reading `updater.json`.
- **Testing an engine build before it's released.** Serve the AAR from your machine (`python3 -m http.server`),
  `adb reverse tcp:<port> tcp:<port>`, and temporarily point `AAR_URL` at `http://127.0.0.1:<port>/torrserver.aar`.
  Loopback cleartext is allowed.
- **A public-domain magnet for testing (Sintel):**
  `magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=Sintel&ws=https%3A%2F%2Fwebtorrent.io%2Ftorrents%2F`.
  Requesting `/torrent/x?link=<encoded magnet>` from `adb shell` (`nc`) exercises the whole path without a player.

## Known limitations

- **Host internals.** Interception, the link cache purge and uninstall detection depend on host internals. A host update
  can break them; the settings screen reports when interception is unavailable.
- **Hash pins.** The pinned engine hashes come from a macOS build until the release workflow has run once. If the Linux
  output differs, update the pins from the workflow summary.
- **Backup restore.** If a restore takes longer than about 25 s to bring ServeTorr back, the uninstall cleanup deletes
  the engine and it has to be downloaded again.
- **Unexplained missing engine files.** Engine files were seen disappearing twice on an emulator and couldn't be
  reproduced. A likely cause is an older provider copy installing a different engine version and clearing the other
  version dirs. All such deletions are now logged.
