# NovelNotifier

An Android app for following your [Novel Updates](https://www.novelupdates.com) reading lists: browse your library with covers and unread counts, read series details, mark chapters as read, and get notified when new chapters release.

This is a clean-room, from-scratch replacement for the abandoned *"Notifications for Novel Updates"* app (last released 2019, no longer functional). Novel Updates has no public API, so NovelNotifier reads the site the way a logged-in browser does — through embedded WebViews with your own session — and writes changes back (mark-as-read, change list) exactly as the website does.

## Features

- **Reading lists** — drawer of all your NU lists, grid or list layout, search, four sort modes, unread badges from last-read position.
- **First-run onboarding** — after login the app reads your lists, then shows how large your library is and how long a full download takes (conservative estimates), and lets you mark 1–3 *priority lists* so their chapters arrive first. A live counter tracks the list download.
- **Background library download** — covers first (visual, offline), then chapter lists, one paced request at a time with a progress notification (`Lists 3/15 · Covers 120/1419 · …`). The queue survives reboots and battery death and resumes where it stopped.
- **Chapter notifications** — new chapters detected via your personal NU RSS feed (captured automatically from the reading-list page — no URL copying) or, without any feed, by paced background checks of your lists. Per-list and per-series mute switches.
- **Cross-device read sync** — chapters you mark as read on one device appear read on your others via NU's read-activity feed.
- **Series pages** — INFO + full RELEASES table (cached page by page), next-chapter card, mark-as-read and list changes written back to the site.
- **In-app reader** — chapter rows and READ CHAPTER open inside the app; ‹ Prev / Next › walk the book. Two views: **Reader view** (chapter text extracted and rendered natively — sepia/dark/light page themes, serif/sans, adjustable font size) and **Web view** (the full page). Extraction filters hidden decoy content by computed styles and falls back to the web view per page when a host defeats it. Per-novel JavaScript toggle; ad/tracker hosts blocked; pop-ups disabled; open-in-browser escape hatch.
- **Backups & device transfer** — one zip with your lists, chapter caches, cover images, and settings; export/import through the system file picker (Google Drive, OneDrive, USB — anywhere it reaches). Backups never contain your password, login session, or feed tokens.
- **Themes** — light/dark/AMOLED-black/Material You, plus **custom themes from a JSON file** (see below).
- **Debug log** — a rotating on-device log of every Cloudflare encounter, scrape outcome, and image failure, pullable via adb (see below).

## How it works

There is no API and no Cloudflare bypass. The app embeds WebViews that share a real logged-in browser session with the Novel Updates site, runs small JavaScript snippets (`ScrapeJs.kt`) to read each page, and parses the results in Kotlin. Writes (mark-as-read, change list) use the same endpoints the website itself uses.

**Pacing rules** keep the app a good citizen: at most one site hit every 10 seconds (20 s after a Cloudflare page), cover downloads spaced 1.5 s apart, and a duty cycle that rests 10 minutes after every 120 jobs so a large library trickles in over hours instead of running flat-out. Repeated Cloudflare challenges back off escalatingly (1 → 5 → 30 minutes) — they mean the session is being rate-limited, and hammering makes it worse. When a real challenge appears, the WebView gets 12 seconds to clear it by itself (modern managed challenges usually do); only a stuck challenge is shown to the user to complete — there is no bypass and no headless trickery.

**Change detection** is conservative everywhere: an incoming chapter number must be *higher* than everything stored (list row, series cache, last-read) before anything updates, notifies, or re-fetches. Site placeholders and empty scrapes can never wipe cached data.

### File map

| File | Role |
|---|---|
| `Models.kt` | Data shapes: `Novel`, `ReadingList`, `Release`, `SeriesPage`. **Start here.** |
| `ScrapeJs.kt` | Every JavaScript payload the WebViews run — the only file to edit when NU changes its HTML. |
| `Parsers.kt` | String → data: `chapterNum` (the "is this new?" rule), RSS/series/list-page parsers, feed-link picking, Cloudflare detectors. |
| `Prefs.kt` / `Cache.kt` | SharedPreferences helpers and on-disk JSON caches (lists, series pages), merge helpers, cover queue math. |
| `MainActivity.kt` | Navigation + shared state + the RSS/check pipeline. |
| `NovelListScreen.kt` | Home: drawer, grid/list, pull-to-refresh, hidden list WebView. |
| `NovelDetailScreen.kt` | Series INFO/RELEASES, CHANGE LIST, MARK AS READ. |
| `OnboardingScreen.kt` | Fresh-login flow: hydration with live counter → size warning + estimates → priority-list picker. |
| `SyncQueue.kt` | Bulk-download job model, priority ordering, persistent queue file, conservative time estimates. |
| `SyncEngineService.kt` | The bulk downloader: paced WebView walking the queue, counter notification, Cloudflare policy. |
| `CoverStore.kt` / `CoverUi.kt` | Cover image files on disk; thumbnails prefer local files, then the network. |
| `BackupManager.kt` | One-zip export/import via the system file picker. Never contains login or feed tokens. |
| `UpdateCheck.kt` | Background checks (RSS when configured, engine list-checks otherwise) via WorkManager. |
| `ChapterAlerts.kt` | Grouped chapter notifications, mute rules, tap deep links, and the persisted alert store that survives reboots. |
| `AlertReceivers.kt` | Dismissal bookkeeping + BOOT_COMPLETED / app-update re-posting of unseen alerts. |
| `CustomTheme.kt` | Custom JSON themes: parser, strict validation, import, and the editable template. |
| `SettingsScreen.kt`, `ListOptionsScreen.kt`, `LoginWebScreen.kt`, `EditListsScreen.kt` | Screens. |
| `ReaderScreen.kt` | In-app chapter reader (browser mode): WebView, chapter nav, JS toggle, ad-block. |
| `SeriesSync.kt` | Per-series merge after a new chapter is detected. |
| `DebugLog.kt` | Rotating file log for on-device diagnosis. |

### Data flow

1. Lists and series pages paint instantly from `filesDir` caches (`list_cache.json`, `series_cache/`).
2. Fresh login → **onboarding** reads your lists, sizes the library, and queues the full download (covers → chapter pages, priority lists first).
3. New chapters are detected by the releases feed (auto-captured) or paced list checks; changed series get one page re-scraped and merged into the cache.
4. The read-activity feed merges marks from your other devices; local progress can never be rewound.
5. Everything the engine does is checkpointed in `filesDir/sync_queue.json` and resumable.

## Getting started

1. Open the project in Android Studio and let Gradle sync (JDK 25 comes with Studio's bundled JBR; CLI builds use `JAVA_HOME=<studio>/jbr`).
2. Run on a device. First launch: Settings → **Log in on Novel Updates** → onboarding takes over.
3. Unit tests: `./gradlew :app:testDebugUnitTest`.

## Debug log

The app records Cloudflare encounters (which detector, which URL), sync/check outcomes, cover saves, Coil image failures, and RSS errors to `filesDir/debug.log` (rotating, ~512 KB):

```
adb shell run-as com.patchscratch.novelnotifier cat files/debug.log > debug.log
```

## Themes

Settings → **Display** offers the built-in looks: **Follow system** (light/dark, the default), Light, Dark, **AMOLED black** (true-black surfaces for OLED screens), and **Wallpaper colors** (Material You, Android 12+). Your choice — plus the layout and notification preferences — travels inside backups.

### Custom themes

You can also theme the app yourself with a JSON file:

1. **Save a theme template** in Settings → Display — it writes a complete, working starter file (`novelnotifier-theme.json`, pre-filled with the built-in green palette) through the file picker.
2. Edit the colors in any text editor. Delete any line you don't care about — anything omitted falls back to the built-in green.
3. **Import theme file (JSON)…** picks it back up. The theme applies immediately and shows in the picker as **Custom: *name***.

The format:

```json
{
  "name": "Ocean",
  "light": {
    "primary": "#0B57D0",
    "background": "#F8F9FF",
    "surface": "#F8F9FF"
  },
  "dark": {
    "primary": "#A8C7FA",
    "background": "#0F1420",
    "surface": "#0F1420"
  }
}
```

Rules:

- `"light"` and `"dark"` are each optional, but **at least one is required**. With both, the theme follows the system light/dark switch; with only one, that scheme is always used.
- Colors are `"#RRGGBB"` or `"#AARRGGBB"` (alpha first).
- Settable roles: `primary`, `onPrimary`, `primaryContainer`, `onPrimaryContainer`, `secondary`, `onSecondary`, `secondaryContainer`, `onSecondaryContainer`, `tertiary`, `onTertiary`, `tertiaryContainer`, `onTertiaryContainer`, `background`, `onBackground`, `surface`, `onSurface`, `surfaceVariant`, `onSurfaceVariant`, `outline`.
- Pair each color with its `on…` partner (e.g. `background` + `onBackground`) or text may become unreadable — contrast is your responsibility, the app doesn't check it.
- Typos fail loudly: an unknown role or malformed color aborts the import with a message naming the problem. Nothing half-applies.
- The imported file is stored inside the app and copied into backups, so it moves with your library to a new device.

The on-screen contrast tip: the status bar icon color follows whichever scheme is active, so light text on a light status bar in your theme usually means `background`/`onBackground` got swapped.

## Backups & moving devices

Settings → **Back up library** writes one zip via the file picker — save it to Google Drive, OneDrive, a USB drive, anywhere. On the new device: install, **Restore from backup**, log in. Your library and covers appear instantly; the sync engine tops up anything stale. Backups deliberately exclude credentials and feed tokens — a restored device does a fresh login and re-captures its own feed links automatically.

## Roadmap

Ideas in rough order: reader mode (extracted, themed chapter text — browser mode shipped), and — only after the reader exists — **offline reading**, since storing chapters for offline use has nothing to attach to until the app can display them. Parsing hazard to remember: NU hides decoy chapter links with CSS — randomized class names, zero-opacity text, and JS-injected traps — so reader extraction must filter by *computed* styles (`getComputedStyle` in the WebView: visible, non-hidden, opacity > 0), never by element presence alone.

## Acknowledgements

Design informed by reverse-engineering the original *Notifications for Novel Updates* v1.9.3–v1.13 (Apache-licensed era, abandoned) — thank you to its author. This codebase shares no code with it.

The reader's font-substitution decoder (for hosts that render text through custom substitution fonts) is also published standalone as [font-decipher](https://github.com/PatchScratch/font-decipher).

## License

[MIT](LICENSE) — © 2026 Wayne Yow (PatchScratch)
