# ServeTorr

makes torrent links from other providers playable. flixclusive can't play magnets and has no api for touching other providers' links, so this does it the hacky way.

## flow

1. plugin `init` (no onLoad exists, and we have no capability the host would call):
   - swap okhttp's `Platform` for one that allows cleartext to loopback. host targets sdk 36 with no network security config so the player refuses `http://127.0.0.1` otherwise. okhttp checks `Platform.get()` per connection so swapping it works. only literal loopback is allowed (`127.x.y.z`, `::1`, `localhost`), not names like `127.evil.com`
   - find the host's `ProviderRepositoryImpl.pluginsMap` through hilt (`HostProviderRegistry`) and wrap every other provider (`LinkInterceptor` / `InterceptingPlugin`)
   - start the link proxy on `127.0.0.1:<port setting>` (default 47390)
2. `getLinks` of a wrapped provider: torrent streams (magnet, `.torrent` url, bare infohash) get rewritten to `http://127.0.0.1:<port>/torrent/<name>?link=<source>[&s=&e=]`. no io, instant. `Flag.Expires` = now + 12h. if the engine isn't installed or the proxy isn't up, the original link goes through with a 1h expiry so the host asks again later
3. player opens the proxy url → `LinkProxy`/`LinkResolver`:
   - start the engine if needed (503 not installed, 502 broken)
   - fetch the file list (30s timeout, one fetch per torrent, cached per infohash)
   - `FileSelector` picks the file. movie = biggest non-sample video. episode = matching SxxEyy / 1x02 / season folder etc, 404 instead of guessing
   - 302 to the engine `http://127.0.0.1:<engine port>/stream/<name>?link=magnet:?xt=urn:btih:<hash>...&index=<id>&play`

nothing touches the network while browsing. torrents only get added when you hit play.

## engine

our own gomobile build of [YouROK/TorrServer](https://github.com/YouROK/TorrServer) (gpl-3), pinned commit in `engine/build.sh`. hosted as a release asset, downloaded at runtime, never bundled in the flx.

- `engine/bindings` is our `torrServer` gomobile package: `startTorrentServer(path, port)` (returns the real port or -1, never `os.Exit`), `stopTorrentServer`, `addTrackers`, `waitTorrentServer`. it only links the http api, no web ui / telegram bot / mcp / dlna / bonjour
- binds `127.0.0.1` only (upstream's own bind option) and answers 403 to anything with `Origin` or `Sec-Fetch-Site`, so other devices and web pages can't use it
- `engine/patches`: dlna/bonjour stubs under the `servetorr` build tag, reopen the settings db after stop (upstream panicked on stop → start in one process), and a patch to gomobile's generated java that adds a lazy `go.Seq.load()` (stock gomobile loads the lib in a static init)
- build: `ANDROID_HOME=… ANDROID_NDK_HOME=… engine/build.sh [out]`. needs go 1.27.4, a jdk and ndk r28. output is reproducible (builds in `/tmp/servetorr-engine` so no local paths end up in the `.so`). then:
  1. upload `torrserver.aar` as a release asset under tag `servetorr-engine-<VERSION>`
  2. copy the printed per-abi `libgojni.so` sha256s and the `bindings.dex` sha256 into `EngineSource`
  3. copy `bindings.dex` to `src/main/assets/torrserver/`
  4. bump `const tag` in `bindings/torrserver.go` and `TAG` in `build.sh` together (echo is `ServeTorr torrserver <tag>`, also in `TorrServerClient`)
- 64-bit `.so`s are 16 KB aligned (android 15+). ~26 MB each unpacked, ~9 MB download
- installer range-requests the aar's zip tail, reads the central directory and fetches just `jni/<abi>/libgojni.so`. falls back to streaming the whole aar if the server ignores `Range`. abi picked by process bitness
- the bindings dex can't live in the flx dex: `System.loadLibrary("gojni")` resolves through the defining classloader, so it's loaded with a `DexClassLoader` whose library path is the engine dir
- engine runs on a random port, only started on first play. "stop engine" really stops it (http, bt client, db) and the next play starts it again in the same process
- the `.so` / go runtime can't be unloaded. the loaded engine version is kept in a system property, so installing a different version while one is loaded (or a reloaded provider copy with a new version) puts it in "restart required" and the settings show a popup. a failed start, a dead engine or a lib loaded through a classloader this copy can't reach end up there too. stop/start and remove + reinstall of the same version don't need a restart
- port / loaded / failed / version flags live in system properties so a reloaded copy of the provider (new classloader) finds the running engine. the bindings class is stashed there too
- `/echo` must return exactly `ServeTorr torrserver <tag>`. we only reuse a port this process started
- file ids are 1-based in info-dict order. entries without an id are skipped. single-file torrents ignore `index`
- `add` returns right away, metadata is polled with `get` (20s)
- `/stream` re-parses `link` every request (a `.torrent` url would get re-downloaded on every seek) so the engine is always addressed by an infohash magnet. it unescapes `link` twice, so `%` and `+` get pre-escaped
- on every bt connect upstream also fetches a public trackers list (ngosang, via github/jsdelivr). only happens once the engine runs, i.e. at play time

## settings

- link proxy port + on/off for interception
- download / remove engine, stop engine, stop all torrents. remove stops it first
- torrserver's `BTSets` that make sense on a phone: cache size (MB, per torrent), read-ahead, preload, disk cache (always `cacheDir/torrent/cache`), remove cache on drop, idle timeout, connections, up/down limits (KiB/s), peer port, encryption, ipv6, tcp/utp/upnp/dht/pex/upload toggles, local peer discovery (upstream defaults it on, we default it off), retracker mode + tracker list, debug. the rest (ssl, tmdb, search, dlna, …) is kept as the engine reports it
- stored in the plugin datastore, not just the engine's `config.db` (that lives in cacheDir and can get wiped). re-applied on every engine start if they differ
- `set` replaces the whole struct, so it's always get → merge → set. it also drops every torrent and reconnects, so applying while something plays interrupts it
- the screen uses its own green material3 theme (`settings/theme/ServeTorrTheme.kt`). picks dark or light from the host's background, not the system setting, since the host is dark-only. less common engine options are under "advanced"

## host weirdness this handles

- same-id provider update doesn't call `onUnload` on the old copy, and profile switch calls `clearAll()` without it either. the interceptor only acts once it has seen itself in the map, and retires + restores its wrappers once it's gone. wrappers from other copies are recognised by class name
- uninstall racing the init-time install: uninstall wins
- host decides "has settings" with `getDeclaredComposableMethod("SettingsScreen")` on the exact class, so there's a wrapper with and without `SettingsScreen` (`InterceptingPlugin.create`). otherwise every provider gets an empty settings page
- `providerRepositoryImplProvider` in the hilt component is an unscoped factory. calling `get()` on it builds a second empty repo. only memoizing `DoubleCheck`/`SingleCheck` get unwrapped
- host caches links by url and replays them without calling `getLinks` while any is unexpired. `Flag.Expires` is epoch millis. episode is in the proxy url so packs don't collide in that cache
- after a host update the old copy's proxy may still own the port. `GET /torrent-proxy/ping` tells us it's ours and we reuse it

## known issues

- cached raw magnets from before the engine was installed stick around until they expire (only the ones we pass through get the 1h expiry)
- the engine release asset has to be uploaded by hand (see engine section) before installs work
- if a `/settings` set is still in its reconnect loop when the engine stops, upstream keeps retrying and could bring the bt client back. not patched
- interception is all reflection on host internals. checked against flixclusive 3.0.1. a host update can break it, the settings screen says so when it does
- removing the provider doesn't delete the engine files, the host gives no uninstall signal (onUnload is the same for disable). use "remove engine" first
- saw the engine file go missing twice on the emulator after deploying new builds, couldn't reproduce after. nothing in here or the host deletes it without logging. if it happens, it just shows "not installed" again

## icon

`icon/icon.svg` (source), `icon.png` 512, `icon-256.png`. not uploaded anywhere yet, `iconUrl` isn't set

## testing

- `./gradlew :ServeTorr:testDebugUnitTest --offline`
- `engine/build.sh` for the engine
- on device: android 14+ refuses the debug `.flx` from shared storage (writable dex), so use an android 13 avd with the release apk. `adb shell appops set com.flixclusive MANAGE_EXTERNAL_STORAGE allow` first or the app crashes reading `updater.json`. then `./gradlew :ServeTorr:deployWithAdb`. to test an engine build before it's uploaded, serve the aar from your machine with `adb reverse` and point `AAR_URL` at it temporarily
- need some provider emitting a magnet. sintel works: `magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=Sintel&ws=https%3A%2F%2Fwebtorrent.io%2Ftorrents%2F`
