#!/usr/bin/env python3
"""Slice 1 gate: Android runtime (Dex loader, adapter, facade, UI, flags)."""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP_EXT = ROOT / "app" / "src" / "main" / "java" / "com" / "streamflixreborn" / "streamflix" / "extensions"
FRAG_EXT = ROOT / "app" / "src" / "main" / "java" / "com" / "streamflixreborn" / "streamflix" / "fragments" / "extensions"

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

if not (ROOT / "app" / "src" / "main" / "java" / "com" / "streamflixreborn" /
        "streamflix" / "fragments" / "player" / "PlayerViewModelV2.kt").exists():
    errors.append("missing PlayerViewModelV2.kt")

symbols = {
    APP_EXT / "DexPluginLoader.kt": ["PathClassLoader", "manifest.json", "pluginClassName"],
    APP_EXT / "ExtProviderFacade.kt": [": Provider", "class ExtProviderFacade", "getServers", "getVideo"],
    APP_EXT / "CloudStreamAdapter.kt": ["toServer", "toVideo", "sortBestFirst"],
    APP_EXT / "ExtensionActions.kt": ["sha256", "Extensions/", "fun install", "fun delete"],
    FRAG_EXT / "ExtensionsViewModel.kt": ["addRepo", "deleteRepo", "RepoManager"],
}
for path, names in symbols.items():
    text = path.read_text() if path.exists() else ""
    for sym in names:
        if sym not in text:
            errors.append(f"{path.name} missing symbol: {sym}")

prefs = (ROOT / "app" / "src" / "main" / "java" / "com" / "streamflixreborn" /
        "streamflix" / "utils" / "UserPreferences.kt").read_text()
if "useExtensions" not in prefs:
    errors.append("UserPreferences missing useExtensions flag")
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
