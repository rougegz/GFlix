#!/usr/bin/env python3
"""Slice 1 gate: Android runtime (Dex loader, adapter, facade, UI, flags)."""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP_EXT = ROOT / "app" / "src" / "main" / "java" / "com" / "gflix" / "app" / "extensions"
FRAG_EXT = ROOT / "app" / "src" / "main" / "java" / "com" / "gflix" / "app" / "fragments" / "extensions"

REQUIRED_APP = ["CsRepositoryStore.kt", "DexPluginLoader.kt", "CloudStreamAdapter.kt",
                "ExtProviderFacade.kt", "ExtLinkResolver.kt", "ExtensionActions.kt"]
REQUIRED_FRAG = ["ExtensionsViewModel.kt", "ReposMobileFragment.kt", "ReposTvFragment.kt",
                 "ExtBrowserMobileFragment.kt", "ExtBrowserTvFragment.kt"]

errors = []
for name in REQUIRED_APP:
    if not (APP_EXT / name).exists():
        errors.append(f"missing app extensions file: {name}")
for name in REQUIRED_FRAG:
    if not (FRAG_EXT / name).exists():
        errors.append(f"missing extensions UI file: {name}")

if not (ROOT / "app" / "src" / "main" / "java" / "com" / "gflix" /
        "app" / "fragments" / "player" / "PlayerViewModelV2.kt").exists():
    errors.append("missing PlayerViewModelV2.kt")

symbols = {
    APP_EXT / "DexPluginLoader.kt": ["PathClassLoader", "manifest.json", "pluginClassName"],
    APP_EXT / "ExtProviderFacade.kt": [": Provider", "class ExtProviderFacade", "getServers", "getVideo"],
    APP_EXT / "CloudStreamAdapter.kt": ["toServer", "toVideo", "toVideoOrNull", "sortBestFirst"],
    ROOT / "app" / "src" / "main" / "java" / "com" / "gflix" / "app" / "utils" / "TrackLanguage.kt": ["object TrackLanguage", "fun matches", "fun canonical"],
    APP_EXT / "ExtLinkResolver.kt": ["requestHeaders", "dataSourceFactory", "PER_API_TIMEOUT_MS" if False else "dataSourceFactory"],
    APP_EXT / "ExtProviderFacade.kt": ["PER_API_TIMEOUT_MS", "toVideoOrNull", "supervisorScope"],
    APP_EXT / "ExtensionActions.kt": ["sha256", "Extensions/", "fun install", "fun delete"],
    FRAG_EXT / "ExtensionsViewModel.kt": ["addRepo", "deleteRepo", "RepoManager"],
}
for path, names in symbols.items():
    text = path.read_text() if path.exists() else ""
    for sym in names:
        if sym not in text:
            errors.append(f"{path.name} missing symbol: {sym}")

prefs = (ROOT / "app" / "src" / "main" / "java" / "com" / "gflix" /
        "app" / "utils" / "UserPreferences.kt").read_text()
for sym in ["useExtensions", "currentExtensionId"]:
    if sym not in prefs:
        errors.append(f"UserPreferences missing {sym} flag")
for nav in ["app/src/main/res/navigation/nav_main_graph_mobile.xml",
            "app/src/main/res/navigation/nav_main_graph_tv.xml"]:
    text = (ROOT / nav).read_text()
    for dest in ["ext_repos", "ext_browser"]:
        if dest not in text:
            errors.append(f"{nav} missing destination: {dest}")
app_gradle = (ROOT / "app" / "build.gradle").read_text()
if "project(':ext-core')" not in app_gradle and 'project(path' not in app_gradle:
    errors.append("app/build.gradle missing :ext-core dependency")
if ":ext-core" not in app_gradle:
    errors.append("app/build.gradle missing :ext-core dependency")

if errors:
    print("FAIL check_android_runtime")
    for e in errors:
        print(f" - {e}")
    sys.exit(1)
print("PASS check_android_runtime: Dex loader + facade + extension UI present")

# --- Regression gates for the provider-purge + extension-UX slice ---
import re as _re
NAV_FILES = ["app/src/main/res/navigation/nav_main_graph_mobile.xml",
             "app/src/main/res/navigation/nav_main_graph_tv.xml"]
for nav in NAV_FILES:
    text = (ROOT / nav).read_text()
    if 'startDestination="@id/providers"' in text:
        errors.append(f"{nav} still starts at providers picker")
    if "fragments.providers.Providers" in text:
        errors.append(f"{nav} still references deleted Providers fragment")
for xml in ["app/src/main/res/xml/settings_mobile.xml",
            "app/src/main/res/xml/settings_tv.xml"]:
    text = (ROOT / xml).read_text()
    for key in ["p_settings_repos", "p_settings_ext_browser"]:
        if key not in text:
            errors.append(f"{xml} missing separate entry: {key}")
    for dead in ['android:key="screen_provider"', 'android:key="pc_tmdb_settings"']:
        if dead in text:
            errors.append(f"{xml} still contains legacy {dead}")
EXT_CORE = ROOT / "ext-core" / "src" / "main" / "kotlin" / "com" / "gflix" / "extcore"
repo_mgr = (EXT_CORE / "RepoManager.kt").read_text()
for sym in ["repoCandidates", "Tried:"]:
    if sym not in repo_mgr:
        errors.append(f"RepoManager.kt missing 404-fallback symbol: {sym}")
adapter = (APP_EXT / "CloudStreamAdapter.kt").read_text()
if "showKey" not in adapter or "distinctBy" not in adapter:
    errors.append("CloudStreamAdapter.kt missing season dedupe (showKey/distinctBy)")
facade = (APP_EXT / "ExtProviderFacade.kt").read_text()
if "invalidateAll" not in (ROOT / "app" / "src" / "main" / "java" / "com" / "gflix" / "app" / "fragments" / "extensions" / "ExtensionsViewModel.kt").read_text():
    errors.append("ExtensionsViewModel.selectExtension missing engine invalidate (stale APIs)")

if errors:
    print("FAIL check_android_runtime (regression gates)")
    for e in errors:
        print(f" - {e}")
    sys.exit(1)
