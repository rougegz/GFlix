#!/usr/bin/env python3
"""Slice 0 gate: :ext-core exists, is pure-JVM, contracts + tests present."""
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
EXT = ROOT / "ext-core" / "src" / "main" / "kotlin" / "com" / "streamflixreborn" / "extcore"
TESTS = ROOT / "ext-core" / "src" / "test" / "kotlin" / "com" / "streamflixreborn" / "extcore"

REQUIRED = ["CsModels.kt", "RepoManager.kt", "ExtensionInstaller.kt", "ExtApi.kt"]
REQUIRED_TESTS = ["RepoManagerTest.kt", "InstallerTest.kt"]

errors = []
for name in REQUIRED:
    if not (EXT / name).exists():
        errors.append(f"missing ext-core source: {name}")
for name in REQUIRED_TESTS:
    if not (TESTS / name).exists():
        errors.append(f"missing ext-core test: {name}")

if not (ROOT / "ext-core" / "build.gradle").exists():
    errors.append("missing ext-core/build.gradle")
settings = (ROOT / "settings.gradle").read_text()
if ":ext-core" not in settings:
    errors.append("settings.gradle does not include :ext-core")

# Pure-JVM: no android imports in ext-core main sources.
for path in EXT.glob("*.kt"):
    text = path.read_text()
    if re.search(r"import\s+android\.", text):
        errors.append(f"{path.name} imports android.* (must stay pure-JVM)")
    if "dalvik.system" in text:
        errors.append(f"{path.name} references dalvik (Android-only; move to :app)")

# Contract symbols that later slices depend on.
symbols = {
    "CsModels.kt": ["CsRepo", "CsExtensionMeta", "InstalledExtension", "ExtLink",
                    "verifySha256Hex", "sanitizeExtensionFileName"],
    "RepoManager.kt": ["normalizeRepoUrl", "parseRepository", "parsePluginList", "class RepoManager"],
    "ExtensionInstaller.kt": ["object ExtensionInstaller", "planInstall", "filesToDelete"],
    "ExtApi.kt": ["interface ExtContentApi", "sortLinksBestFirst", "class FakeExtApi"],
}
for fname, names in symbols.items():
    text = (EXT / fname).read_text() if (EXT / fname).exists() else ""
    for sym in names:
        if sym not in text:
            errors.append(f"{fname} missing symbol: {sym}")

if errors:
    print("FAIL check_ext_core")
    for e in errors:
        print(f" - {e}")
    sys.exit(1)
print("PASS check_ext_core: ext-core scaffold + contracts present, pure-JVM")
