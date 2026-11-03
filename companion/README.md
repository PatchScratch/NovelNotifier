# NovelNotifier Companion (pre-release)

Big-screen management app for the NovelNotifier library on your phone —
Linux first, Windows from the same build. The phone stays the **source of
truth**: every change the companion makes goes over your home network to a
small authenticated API on the phone (app ≥ this batch, Settings →
**Desktop companion**), and that API only calls the app's own locked,
gate-tested operations. The companion has no library logic and no local
index of its own.

## What it does (batch 1)

- **Shelves** — create, rename, delete, and see counts; deleting a shelf
  just unshelves its series (nothing is deleted).
- **Series** — searchable, sortable, multi-select list; bulk move
  series to a shelf / unshelve in one action.
- **Series detail** — rename, edit description, move volumes between
  series, merge into another series, remove-from-library or move-to-trash
  (the phone's v3.3 semantics: nothing is permanently destroyed except by
  **Empty Trash in the phone's Settings**, which the companion cannot do).
- **Merge suggestions** — same-key collisions and near-identical names
  (the auto-grouping misses). Suggestions only; **every merge needs your
  confirm** — the companion never merges or deletes on its own.

## Install / run (Linux)

**AppImage is the preferred package** (Wayne, 2026-11-02). Build it with one
command — it runs `packageAppImage` (jlink runtime) and wraps the result
into a portable single-file AppImage with appimagetool:

```bash
cd companion
dev/build-appimage.sh
# → dist/NovelNotifierCompanion-<version>-x86_64.AppImage
```

`chmod +x` and run it, or register a menu entry pointing `Exec=` at it (the
script's repo has the icon at `companion/icon-512.png`). The script needs
`appimagetool` (downloads to ~/.cache if not on PATH) and libfuse2 at
runtime — standard on most desktops.

For development iteration, `./gradlew run` starts the unbundled app faster
than repackaging.

Windows: `dev/build-windows-zip.sh` cross-builds a **windows-x64 zip from
Linux** (jpackage can't produce Windows packages on a Linux host, and CMP
1.9's cross-platform property is ignored): the OS-agnostic app jars with the
Windows skiko native swapped in, a bundled Temurin 21 Windows runtime, and a
`NovelNotifierCompanion.bat` launcher — unzip and run, no Java install
needed. Artifacts cache under `~/.cache/novelnotifier-companion/`. A proper
`packageMsi` still wants a Windows machine.

## Pairing with the phone

On the phone: Settings → **Desktop companion** → enable, note the
`http://<ip>:8790` address and the pairing token, and type both into the
companion's connect screen. The pairing config is saved locally under
`~/.novelnotifier-companion.json` (host + token only; never synced).

## Security model

- Bearer-token auth on every request; tokens are compared constant-time;
  failed attempts are logged on the phone (`LANAPI` tag).
- The server serves only the `/api/v1/…` JSON endpoints and cached cover
  images. It never serves files, never opens an epub for a remote request,
  and cannot empty the trash or delete a library.
- Traffic is plain HTTP scoped to your LAN. If you want it off, toggle the
  switch — the foreground service stops with it.

## Layout

Standalone Gradle build (the Android app's build never sees it):

- `src/main/kotlin/…/Model.kt` — mirror of the phone's `/api/v1` JSON.
- `ApiClient.kt` — the whole wire protocol in one file.
- `MergeSuggestions.kt` — pure title-similarity logic (unit-tested).
- `Main.kt` — Compose Desktop UI.
- `src/test/` — contract tests against a fake phone server.
