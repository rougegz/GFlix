# Extensions & Repositories

GFlix plays content through **CloudStream-compatible extensions**. No
sources ship with the app — you add repository URLs, then install extensions.

## 1. Install a repo

1. Open **Settings → Extensions & Repositories → Repos**.
   Private/SSRF hosts (`10/8`, `172.16/12`, `192.168/16`, `169.254/16`,
   `metadata.google.internal`, `*.local`) are rejected; only `https` (+ `localhost` http for tests).
2. Paste a `repository.json` URL (example:
   `https://example.com/repository.json`).
3. Only `https` URLs are accepted (`http` works for `localhost` tests only).
4. The app fetches `repository.json`, then each URL in `pluginLists`, and lists
   every `SitePlugin` (name, version, language, tvTypes).

`repository.json` shape (CloudStream):

```json
{
  "name": "Demo repo",
  "manifestVersion": 1,
  "pluginLists": ["https://example.com/plugins.json"]
}
```

## 2. Install / update / enable / delete extensions

Open **Extensions**. Each row shows icon, version, language, types.

- **Install**: downloads the `.cs3` over `https` (10s connect / 15s read
  timeouts, no `https`→`http` downgrade redirects, 50 MB cap), requires
  `sha256-<hex>` (`fileHash`) except `localhost` test URLs, stores it at
  `files/Extensions/<repo>/<id>.cs3` (read-only, canonical-path checked).
- **Update**: offered when the repo `version` is newer (or `-1` always-update).
- **Disable**: keeps the file, skips it in search/playback.
- **Delete extension**: removes the `.cs3` (+ `oat` sidecar) and its DB row.
- **Delete repo**: removes the repo and uninstalls all of its extensions.
- Remotely disabled extensions (`status: 0`) are never loaded.

`.cs3` format: zip containing `manifest.json` (`pluginClassName`,
`requiresResources`, `version`) + `classes.dex`, loaded at runtime with an
isolated `PathClassLoader` (no app classes writable from plugin code).

## 3. Play

Details screens show **Watch with…** (extension picker). The player resolves
`loadLinks` → quality-sorted links with `Referer`/`Origin`/`Cookie` headers,
merges extension subtitles with OpenSubtitles/SubDL fallback, and auto-falls
back to the next link on failure. No server grid.

## 4. Troubleshooting

- `Extension hash mismatch`: repo rotated the file without updating `fileHash`;
  refresh the repo or reinstall.
- `No manifest.json`: the `.cs3` is corrupt or not a CloudStream build.
- `HTTP 404 for plugin list`: repo moved its `pluginLists` URL; delete + re-add.
- `No links from extensions`: all installed extensions are disabled or the repo
  disabled the plugin (`status: 0`).
