# Torrent

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

cloudstream's gomobile build of torrserver, `com.github.recloudstream:torrentserver:7861970` on jitpack. no license on that fork (upstream torrserver is gpl-3), so the `.so` is downloaded at runtime and never bundled.

- installer range-requests the aar's zip tail, reads the central directory and fetches just `jni/<abi>/libgojni.so` (~4.5MB). falls back to streaming the whole aar if the server ignores `Range`. sha256 pinned per abi in `EngineSource`. abi picked by process bitness
- java bindings (`go.Seq`, `torrServer.TorrServer`) are in `src/main/assets/torrserver/bindings.dex`. they can't live in the flx dex: `go.Seq.load()` does `System.loadLibrary("gojni")` which resolves through the defining classloader, so they're loaded with a `DexClassLoader` whose library path is the engine dir. regen:
  ```
  d8 --release --min-api 23 --lib android-36/android.jar --output out classes.jar
  ```
  (`classes.jar` from the aar, sha256 `792029709516fde134cd8c2eead8da2aa6c9eca27185107172917d088e3c3b22`, build-tools 36.1.0)
- this fork needs an explicit `go.Seq.load()`, static init doesn't load the lib
- engine runs on a random port, only started on first play
- the `.so` / go runtime loads once per process and never unloads. the server itself restarts fine though: `stopTorrentServer` closes http, the bt client and the db, and the next `startTorrentServer` reopens them (checked on device). that's what "stop engine" does
- port / loaded / failed flags live in system properties so a reloaded copy of the provider (new classloader) finds the running engine. the bindings class is stashed there too so a reloaded copy can still stop it and call `addTrackers`
- `startTorrentServer` opens its bbolt db before the port check and leaves it open on failure, so after one `-1` every retry hangs on the lock. we give up and tell the user to restart the app
- `/echo` must return exactly `CloudStream torrserver`. we only reuse a port this process started
- file ids are 0-based, info-dict order, and `id` is omitted for file 0 (`omitempty`). missing id = 0
- the fork's `add` blocks until metadata and hangs forever if the torrent is dropped meanwhile, so adds race against a drop counter and re-add once
- `/stream` re-parses `link` every request (a `.torrent` url would get re-downloaded on every seek) so the engine is always addressed by an infohash magnet. it also unescapes `link` twice, so `%` and `+` get pre-escaped

## settings

- link proxy port + on/off for interception
- download / remove engine, stop engine, stop all torrents. remove stops it first
- all of torrserver's `BTSets` that do something in this build: cache size (MB, per torrent), read-ahead, preload, disk cache (always `cacheDir/torrent/cache`), remove cache on drop, idle timeout, connections, up/down limits (KiB/s), peer port, encryption, ipv6, tcp/utp/upnp/dht/pex/upload toggles, retracker mode + tracker list, debug. `EnableDLNA` and `FriendlyName` exist but do nothing here so they're hidden
- stored in the plugin datastore, not just the engine's `config.db` (that lives in cacheDir and can get wiped). re-applied on every engine start if they differ
- `set` replaces the whole struct, so it's always get → merge → set. it also drops every torrent and reconnects, so applying while something plays interrupts it

## host weirdness this handles

- same-id provider update doesn't call `onUnload` on the old copy, and profile switch calls `clearAll()` without it either. the interceptor only acts once it has seen itself in the map, and retires + restores its wrappers once it's gone. wrappers from other copies are recognised by class name
- uninstall racing the init-time install: uninstall wins
- host decides "has settings" with `getDeclaredComposableMethod("SettingsScreen")` on the exact class, so there's a wrapper with and without `SettingsScreen` (`InterceptingPlugin.create`). otherwise every provider gets an empty settings page
- `providerRepositoryImplProvider` in the hilt component is an unscoped factory. calling `get()` on it builds a second empty repo. only memoizing `DoubleCheck`/`SingleCheck` get unwrapped
- host caches links by url and replays them without calling `getLinks` while any is unexpired. `Flag.Expires` is epoch millis. episode is in the proxy url so packs don't collide in that cache
- after a host update the old copy's proxy may still own the port. `GET /torrent-proxy/ping` tells us it's ours and we reuse it

## known issues

- torrserver listens on all interfaces with no auth and cors `*`. while it runs, anything on the same wifi (or a web page on the phone) can hit it. can't fix without our own engine build (one line in `server.go`/`web/server.go` to bind 127.0.0.1)
- cached raw magnets from before the engine was installed stick around until they expire (only the ones we pass through get the 1h expiry)
- interception is all reflection on host internals. checked against flixclusive 3.0.1. a host update can break it, the settings screen says so when it does
- removing the provider doesn't delete the engine files, the host gives no uninstall signal (onUnload is the same for disable). use "remove engine" first
- saw the engine file go missing twice on the emulator after deploying new builds, couldn't reproduce after. nothing in here or the host deletes it without logging. if it happens, it just shows "not installed" again

## testing

- `./gradlew :Torrent:testDebugUnitTest --offline`
- on device: android 14+ refuses the debug `.flx` from shared storage (writable dex), so use an android 13 avd with the release apk. `adb shell appops set com.flixclusive MANAGE_EXTERNAL_STORAGE allow` first or the app crashes reading `updater.json`. then `./gradlew :Torrent:deployWithAdb`
- need some provider emitting a magnet. sintel works: `magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=Sintel&ws=https%3A%2F%2Fwebtorrent.io%2Ftorrents%2F`
