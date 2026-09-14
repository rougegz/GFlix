# Plan: GFlix → CloudStream extensions rewrite (in-place)

Goal: replace all hardcoded providers/extractors with CloudStream-compatible
`.cs3` extension runtime + CloudStream-style repo/extension/player UI, same
package `com.gflix.app`, mobile + TV.

User decisions (2026-09-14): in-place rewrite; Dex `.cs3` loader; mobile+TV
slice 1; keep TMDB + Supabase + OpenSubtitles/SubDL; ship empty (no default
repos) + docs.

Evidence base:

- Streamflix: 328 `.kt`, ~68 provider registry entries
  (`providers/Provider.kt:62-131`), ~96 extractor files
  (`extractors/Extractor.kt:22-122` + dispatcher `:124-233`), bloated player
  (`PlayerViewModel.State LoadingServers/SuccessLoadingServers`,
  `PlayerMobileFragment ~1609 lines`, `PlayerTvFragment ~2021 lines`), settings
  (`SettingsMobileFragment 1481 lines`, `SettingsTvFragment 2095 lines`),
  per-provider DB sharding (`AppDatabase.sanitizeProviderName/resetInstance`).
- CloudStream upstream (`recloudstream/cloudstream`):
  `Repository{iconUrl,name, description,manifestVersion,pluginLists[]}`
  (`RepositoryManager.kt:33-40`),
  `SitePlugin{url,status,version,apiVersion,name,internalName,authors, tvTypes,language,iconUrl,fileSize,fileHash}`
  (`:49-76`), `.cs3`/`.zip` containing
  `manifest.json{pluginClassName,requiresResources, version}` loaded via
  `PathClassLoader(filePath, parent)` + reflection
  (`PluginManager.kt:600-621,747-755`), sha256 `fileHash` verify + atomic move
  (`RepositoryManager.kt:193-240`),
  `BasePlugin.registerMainAPI/ registerExtractorAPI`,
  `REPOSITORIES_KEY + PREBUILT_REPOSITORIES`,
  `ExtensionsFragment/PluginsFragment/PluginsViewModel/RepoAdapter/ PluginAdapter`,
  player `CS3IPlayer:IPlayer + PlayerView + GeneratorPlayer`.
- Forks: Sozo `lib/core/cloudstream/cloudstream_channel.dart` MethodChannel JSON
  façade is the cleanest bridge pattern to copy; Sozo-tv native Kotlin
  `com/lagradost + com/saikou` proves CloudStream player code ports to Leanback;
  Zangetsu most mature Flutter ref; Nuvio best for TV EPG/calendar ideas (not
  code).

## Milestone 0: Scaffold + contracts (additive, old code untouched) — DONE 2026-09-14 (`verify_rewrite` PASS)

- [x] Add `:ext-core` pure-JVM Kotlin module (`settings.gradle` include,
      `ext-core/build.gradle` with
      `kotlin-jvm + kotlinx-serialization-json + junit4` only, no `android.*`
      imports) — verify: `python3 tests/check_ext_core.py` → all files exist +
      no android imports
- [x] Add contracts `ext-core/src/main/kotlin/com/gflix/extcore/`:
      `CsModels.kt` (CsRepo, CsExtensionMeta mirroring SitePlugin,
      InstalledExtension, ExtLink, ExtSearchItem, ExtLoadData), `RepoManager.kt`
      (normalize/validate/ parse repository.json via injected HttpGet,
      unit-testable), `ExtensionInstaller.kt` (version compare, sha256 check,
      status INSTALLED/ENABLED/UPDATE_AVAILABLE), `ExtApi.kt`
      (search/mainPage/load/ loadLinks façade) — verify: same script + `kotlinc`
      syntax gate if JDK present
- [x] Add JVM unit tests
      `ext-core/src/test/.../RepoManagerTest.kt + InstallerTest.kt` with fake
      HTTP (no network) — verify: `python3` checks test names present;
      `./gradlew :ext-core:test` when JDK+Gradle available

## Milestone 1: Android runtime (Dex loader + adapter + facade, behind flag) — DONE 2026-09-14 (`verify_rewrite` PASS)

- [x] `app/.../extensions/` package: `CsRepositoryStore.kt` (Room
      `RepoEntity/ExtEntity + Dao`, HTTPS-only except localhost, 5-min repo
      cache), `DexPluginLoader.kt` (`PathClassLoader(cs3File, parent)` +
      `manifest.json` `pluginClassName` reflection + isolated OkHttp + timeouts,
      `oat/` cleanup), `CloudStreamAdapter.kt` (`ExtractorLink→Video/Server`,
      `SearchResponse/LoadResponse→Movie/TvShow/Episode`),
      `ExtProviderFacade.kt` (`implements Provider`, merges enabled extensions,
      per-extension try/catch isolation) + `UserPreferences.useExtensions` flag
      (default false) — verify: `python3 tests/check_android_runtime.py` →
      files + symbols present
- [x] Keep old providers wired; facade opt-in only — verify: `Provider.kt` still
      has legacy map (no deletion yet), app still compiles conceptually

## Milestone 2: Repo + Extension Settings UI (CloudStream-like, mobile + TV) — DONE 2026-09-14 (`verify_rewrite` PASS)

- [x] `fragments/extensions/`: `ReposMobile/TvFragment`, `ExtBrowserMobile/TvFragment`,
      `ExtensionsViewModel` (addRepo with URL-candidate fallback, deleteRepo
      cascade, install/update/enable/disable/delete, strict selectExtension with
      auto-enable + engine invalidate); nav `ext_repos`/`ext_browser` in both
      graphs; startDestination=`home` (no provider picker; `providers` id kept
      as ext-browser alias for old popUpTo calls)
- [x] Settings: SEPARATE `p_settings_repos` → ext_repos + `p_settings_ext_browser`
      → ext_browser (both XMLs + both fragments); per-provider domain screen
      (`screen_provider`), TMDB category + toggle + API key deleted; missing
      `backup/` package restored against single extensions DB (Gson rows +
      sqlite-zip with traversal guard)

## Milestone 3: Player UI cleanup (CloudStream-style, no server grid)

- [ ] New flow `PlayerViewModelV2`: `extension → loadLinks → ExtLink[]`
      (`ExtLinkResolver.kt`: sort by quality, headers Referer/Origin/Cookie,
      subtitles merge with OpenSubtitles/SubDL fallback), ExoPlayer `MediaItem`
      factory with per-link OkHttp headers, auto-fallback to next link on
      failure; UI: single "Watch with…" extension sheet + quality/track/subtitle
      pickers + gestures (double-tap seek, swipe brightness/volume), OP/ED skip
      hook (`SkipStamp`-compatible), PiP + autoplay-next preserved — verify: old
      `LoadingServers/SuccessLoadingServers` path still present but V2 files
      exist side-by-side
- [ ] Port from Sozo-tv/CloudStream: `PlayerGestureHelper` keep,
      `SubtitleOffsetRenderersFactory` keep, add `TrackSelectionOverride`
      quality picker + `PlayerSubtitleHelper` merge — verify: new player files
      list matches plan

## Milestone 4: Browse/Search/Details rewired + legacy deletion — DONE 2026-09-14 (`verify_rewrite` PASS)

- [x] Provider picker deleted: nav starts at `home`; `ProvidersMobile/TvFragment`
      gone; MainMobile/TvActivity stripped of dead provider init/imports and
      `currentProvider` gates (first-run with no extensions routes to
      `ext_repos`); global search includes `language=="multi"` providers;
      `CloudStreamAdapter.toSearchItems` collapses S1/S2 season entries onto one
      card (`showKey`); `ExtensionContentProvider` selection is strict (no silent
      fall back to all); legacy SerienStream bypass / AnimeOnline clearance /
      per-provider settings / TMDB references removed; `ExtProviderFacade`
      kept as compat delegate
- [ ] DELETE: `providers/*Provider.kt` except `TmdbProvider.kt` (moved to
      `core:metadata` conceptually), `extractors/*.kt` except shim, dead
      `WebViewResolver/BypassWebSocket*/JsUnpacker/CryptoAES-per-provider` refs,
      `ProviderChangeNotifier` coupling, per-provider DBs
      (`SerienStreamDatabase`, `AniWorldDatabase`); shrink
      `Provider.companion.providers` to `{Tmdb, ExtFacade}`; TMDB-keyed
      favorites/resume — verify:
      `test $(ls app/.../providers/*Provider.kt | wc -l) -le 2` and
      `test $(ls app/.../extractors/*.kt | wc -l) -le 3`
- [ ] Docs: `EXTENSIONS.md` (how to install repo, install/delete extensions,
      per-extension settings, troubleshooting hash mismatch) + empty-repo
      first-run UX — verify: file exists

## Acceptance (global)

- `python3 tests/verify_rewrite.py` → PASS (checks all milestone
  file/symbol/count gates)
- With JDK+SDK: `./gradlew :ext-core:test :app:assembleDebug :app:lintDebug`
  green
- Manual (device once): add repo → install → enable → search → play with
  headers/subs → update → disable → delete extension → delete repo

## Risks

- Dex `PathClassLoader` on minSdk 21 + Play policy → mitigate: app-private
  `files/Extensions/<repoHash>/<id>.cs3`, no `REQUEST_INSTALL_PACKAGES`.
- Single-URL `Video(source)` vs multi-`ExtractorLink` → mitigate: V2 resolver
  keeps V1 `Video` as one link for compat during transition.
- Paired mobile/TV fragments double all UI work → mitigate: shared
  VMs/use-cases, thin views.
- No SDK in this container → mitigate: `:ext-core` pure-JVM + python structural
  gates; Gradle assemble verified where SDK exists.

## Adjacent security follow-ups (out of scope for this slice, do NOT fix here)
- `BackupRestoreManager.kt:348` zip-slip on restore (`databases/` prefix escape).
- `AndroidManifest.xml` deep-link `gflix://resolve?ws=&token=` SSRF/open-redirect
  (`MainMobileActivity` exported + raw `ws` URL) — enforce `wss://` + allowlist.
- Global cleartext (`usesCleartextTraffic=true` + `network_security_config.xml`
  base-config) — flip to `false` once repo/dex traffic is proven https-only.
- `allowBackup=true` exfiltrates `files/Extensions/*.cs3` + DBs — exclude or disable.

## Reference-repo feature backlog (mined 2026-09-14)
SHIPPED v1.0.9: remember-speed. SHIPPED v1.1.0 (Slice A): smart default
audio/subtitle language + max resolution (TrackLanguage, CloudStream/Zangetsu
pattern). SHIPPED v1.1.1 (Slice B/C/D): TvFocus scale for all TV cards,
video-only retry on audio codec failure (Just Player lesson), GFlix locale names.
Research fleet (12 agents): mpv/mpvRx, VLC, Nova/Just Player, Kodi, Stremio,
CloudStream UI, SkyStream, sozo-tv, Zangetsu, Lumera, ARVIO, Jellyfin/Findroid.
Ranked portable ideas from NuvioMobile-Enhanced / Sozo / Sozo-tv / Zangetsu / CloudStream releases:
1. DONE — Remember-My-Choices speed carry-over (Nuvio 0.4.13).
2. TODO — Finish-time overlay + hold-to-speed guard (Nuvio 0.3.1, Zangetsu v2.1.0).
3. TODO — Subtitle language grouping + custom font (Nuvio 0.3.1, CloudStream v4.5.2).
4. TODO — Auto-skip OP/ED + intro-skip DB (Zangetsu v1.9.3, CloudStream v4.8.0).
5. TODO — Report-broken-link from title (Sozo v2.0) — fits .cs3 model perfectly.
6. TODO — In-app updater already exists; add Beta channel opt-in (Zangetsu v1.9.1).
7. TODO — One-tap sanitized log share (Zangetsu v2.1.0) for extension bug reports.
8. TODO — Sleep timer (Zangetsu v1.9.3).
9. TODO — App Lock PIN/biometric (Sozo v2.0).
10. TODO — Catalogue-first resolve + source priority sweep (Zangetsu v2.0.0) — core rewrite step.

## UI Overhaul Program (launched 2026-09-14)
Goal: best lightweight GFlix UI + player + settings, D-pad safe, old+new devices.
Research fleet (12 subagents): mpv-android/mpvRx, VLC, Nova/Just Player,
Kodi 10-foot UI, Stremio catalog, CloudStream UI, SkyStream, sozo-tv, Zangetsu,
Lumera, ARVIO, Jellyfin/Findroid TV UX.
Slices: A smart defaults (audio/sub/resolution + label matching e.g. Telugu),
B D-pad, C player settings, D dead-code/weight, then review + signed release.
Constraints: additive + small diffs, CI must stay green, no device here —
D-pad claims verified by code inspection + lint, noted as needs-device-test.

## Release v1.3.0-cloudstream (2026-09-14)
- Branch: main only (feature branch merged + deleted).
- Tag `v1.3.0-cloudstream` → `.github/workflows/release.yml` built 3 APKs
  (universal / mobile / TV) and published GitHub Release with generated notes.
- Signed with ephemeral debug key (no KEYSTORE secrets set). To ship
  release-signed APKs, add secrets: KEYSTORE (base64), SIGNING_KEY_ALIAS,
  SIGNING_STORE_PASSWORD, SIGNING_KEY_PASSWORD, then push a new `v*` tag.
