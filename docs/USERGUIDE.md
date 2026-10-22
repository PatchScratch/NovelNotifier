# NovelNotifier — User Guide

A plain-language walkthrough of everything the app does, one task at a time. The [README](../README.md) describes the project; this guide shows you the taps.

## Contents
1. [First start](#first-start)
2. [Following novels on Novel Updates](#following-novels-on-novel-updates)
3. [The Japanese sites (Syosetu / Kakuyomu / AlphaPolis)](#the-japanese-sites)
4. [Reading chapters](#reading-chapters)
5. [Offline reading & re-downloading](#offline-reading--re-downloading)
6. [Your own ePub library](#your-own-epub-library)
7. [Translating (AI, your own key)](#translating)
8. [Read aloud (TTS)](#read-aloud-tts)
9. [Organizing: shelves, series, multi-select](#organizing)
10. [Covers](#covers)
11. [Backups & moving devices](#backups--moving-devices)
12. [Troubleshooting](#troubleshooting)

---

## First start

1. Install the APK (allow "unknown sources" if Android asks).
2. **Settings → Log in on Novel Updates** — a guided onboarding sizes your library and offers to download your lists.
3. Optional: **Settings → Translation** — paste an OpenAI-compatible API key to unlock AI features (translation, covers).
4. That's it. The app checks for its own updates once a day and offers them with a changelog.

No account is needed for the Japanese sites; their features work immediately.

## Following novels on Novel Updates

- Your **reading lists** appear in the drawer (☰). Tap a list to see its series; the number on a tile is chapters left to read.
- Tap a series → the series page: description, series info, and the full **RELEASES** table. **READ CHAPTER** continues where you stopped; **MARK AS READ** ticks the chapter on the site itself.
- Checking a chapter row moves your NU reading-list marker (the site keeps one tick per series).
- New-chapter **notifications** arrive through your personal NU feed — nothing to configure. Mute per list (Lists ⚙ in the drawer) or per series (the bell on the series page).
- Read on one device, see it read on the others: read positions sync through NU's read-activity feed automatically.

## The Japanese sites

Open the drawer and tap the site chip (📖 Syosetu / Kakuyomu / AlphaPolis). Each site is its own workspace with the same layout you know from NU: grid, badges, series page, Next Chapter.

- **Discover (★)** — rankings and advanced search; the R18 sister sites sit behind an 18+ gate in settings.
- **Login** (drawer) syncs your bookmarks/follows from the site. Long-press any tile to move it between the site's own categories.
- Notifications for followed works run in the same background check as NU.

## Reading chapters

- ‹ Prev / Next › moves through the book. The ⋮ menu holds the display settings: **text or web view**, page theme (sepia/dark/light), serif/sans, font size.
- **Text view** shows the extracted chapter — clean, themed, ad-free. If a site defeats extraction, the reader falls back to the full page.
- Japanese readers: **long-press any word** for the offline dictionary (reading, part of speech, meanings).
- Your position saves itself per series. "Next Chapter" on the series page always knows where you were — even across volume numbering like v3c1 after c20.

## Offline reading & re-downloading

Chapters save themselves the moment you read them — that's the default, nothing to enable.

- **Offline-first**: opening a chapter you've read before renders the stored copy instantly, with **zero network**. Sites are only contacted for chapters you've never opened.
- **⋮ → Offline chapters** — the switch that controls this. On (default) = stored copies first; off = always fetch online.
- **⋮ → Re-download chapter** — the escape hatch for translator updates: refetches the chapter from the site and overwrites the stored copy. Your translation of that chapter is kept.
- Want a whole series offline before a trip? Series page → ⋮ → the download options (missing-only or force-all). Downloads show a ✓ on the chapter list when stored.

## Your own ePub library

The Local Library lives in the same drawer, as its own "sites": create as many libraries as you like (Fiction, Light Novels…) — ☰ → the site chip → **＋ New library**.

**Adding books — three ways** (drawer):
- **Import epubs (copy)** — the file is copied into the library's own storage.
- **Link epubs on device (no copy)** — the book stays where it is on your device; the app reads it in place. Your original file is never modified or deleted.
- **Link a whole folder** — pick a folder once; every ePub inside (subfolders included) joins the library through a single permission. Re-run it anytime — new files are added, existing ones skipped.

**How things are organized**:
- Multi-volume ePubs group into a **series** automatically (by title). The series page uses the same INFO/RELEASES layout as the sites; the chapter list follows **the book's own table of contents**.
- **Shelves** are your categories: create them under Shelves ⚙, assign from the series page (⚑), filter by them in the drawer, hide a shelf behind your fingerprint (18+ style).
- The ✕ in the search box clears it; chips under the search box are your recent searches (tap to reuse, long-press to forget).

**When the automatic grouping is wrong** (a volume landed in its own series, a comma sneaked into a title):
- Open the stray series → ⋮ → **Merge into another series…** → pick the right one. All volumes move.
- Or long-press tiles on the grid to **multi-select** → Shelf… / Merge… / Delete.

**Keeping it healthy**: the app watches for duplicates (the same file linked twice, re-saved copies under slightly different names) and for dead links (files you moved or deleted — the drawer grows a "🧹 N missing files — clean up…" row). Cleanup is always one explicit tap.

## Translating

Bring your own key (Settings → Translation); everything below uses it.

- **While reading** — ⋮ → Translate: the chapter is translated into English (or your configured target) and shown paragraph-under-paragraph. Style rules keep 「」, inner voice, and onomatopoeia intact for Japanese novels. Toggle the translation display and layout (JP only / EN only / both) in the Translation submenu.
- **Series pages** — ⋮ → **Translate title & description**: a dimmed English line appears under the originals. The ⋮ toggle shows/hides it.
- **A whole local-library series** — series page → ⋮ → **Translate all chapters…**: every chapter of every volume, with live progress ("37/439"). Already-translated chapters are skipped, so you can stop anytime and resume later by running it again. Keep the page open while it runs.

Translations are stored inside the app as sidecars — your ePub files are never rewritten, and deleting a book cleans up after itself.

## Read aloud (TTS)

In the reader, ▶ starts read-aloud. Three engines (Settings): the **system TTS** (zero download, reads Japanese), **Kokoro** (offline neural English), or a cloud voice through your key. The control bar does play/pause, paragraph skip, tap-a-paragraph to jump there, a live speed slider, and a sleep timer. Headset buttons control it too.

## Organizing

- **Grid**: long-press = multi-select → Shelf… / Merge… / Delete. ✓ marks selected tiles.
- **Drawer**: shelves with counts (tap = filter, tap again = clear), the 🧹 cleanup row when files went missing, library management (rename, storage location).
- **Series page**: ⚑ shelf, ⋮ for rename / description / cover / merge / translate / delete. Breadcrumb labels (top-left) always show which library or series you're in.
- **Hidden shelves**: flip a shelf's switch in Shelves ⚙ — its books vanish from every screen until you unlock with your fingerprint via "Reveal hidden (18+)" in the drawer.

## Covers

Covers extract automatically from each ePub (and from the sites' series pages). If a cover is genuinely absent you'll see the "No cover" placeholder. Fixes: volume page → ⋮ → **Extract cover from epub**; series page → ⋮ → **Choose cover…** to use any volume's art. Site series without covers can get AI art (Generate cover).

## Backups & moving devices

Settings → Back up library → pick a file. A backup carries your lists, categories/shelves, preferences, the **local-library index** (series, shelves, grouping) — but not ePub files themselves or your logins. Restore on the new device, then re-link your folders; everything snaps back into place. Log in to each site again.

## Troubleshooting

- **A chapter sits on a loading page** — it's fetching from the site (first time only). If it truly stalls, check you're online, then ⋮ → re-open the chapter. Stored chapters always open instantly.
- **"Site parse returns nothing"** on NU — the site sent a challenge page. Pull-to-refresh once; if a Cloudflare check appears, complete it and the app retries automatically.
- **Covers missing after an import** — they fill in over the first minute in the background; ⋮ → Extract cover from epub forces one.
- **A volume that shouldn't be there** — likely the same file linked twice; the cleanup pass flags it, or delete the extra copy manually.
- **Translation failed** — check the key in Settings; the status line names the problem.

Stuck on something else? [Open an issue](https://github.com/PatchScratch/NovelNotifier/issues).
