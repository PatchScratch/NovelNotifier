# NovelNotifier

[English](README.md) · [日本語](README.ja.md)

An Android app for reading web novels: follow your [Novel Updates](https://www.novelupdates.com) reading lists (covers, unread counts, chapter alerts, mark-as-read), and discover/read/download from the Japanese webnovel sites [Syosetu (なろう)](https://syosetu.com) and [Kakuyomu (カクヨム)](https://kakuyomu.jp) — rankings, search, in-app reading, and offline ePub building across all three.

This is a clean-room, from-scratch replacement for the abandoned *"Notifications for Novel Updates"* app (last released 2019, no longer functional). Novel Updates has no public API, so its side reads the site the way a logged-in browser does — through embedded WebViews with your own session — and writes changes back (mark-as-read, change list) exactly as the website does. The Japanese sites instead use their official/open APIs (Syosetu's documented JSON API; Kakuyomu's open GraphQL endpoint), so no scraping is involved there.

## Features

- **Reading lists** — drawer of all your NU lists, grid or list layout, search, four sort modes, unread badges from last-read position.
- **First-run onboarding** — after login the app reads your lists, then shows how large your library is and how long a full download takes (conservative estimates), and lets you mark 1–3 *priority lists* so their chapters arrive first. A live counter tracks the list download.
- **Background library download** — covers first (visual, offline), then chapter lists, one paced request at a time with a progress notification (`Lists 3/15 · Covers 120/1419 · …`). The queue survives reboots and battery death and resumes where it stopped.
- **Chapter notifications** — new chapters detected via your personal NU RSS feed (captured automatically from the reading-list page — no URL copying) or, without any feed, by paced background checks of your lists. Per-list and per-series mute switches.
- **Cross-device read sync** — chapters you mark as read on one device appear read on your others via NU's read-activity feed.
- **Series pages** — INFO + full RELEASES table (cached page by page), next-chapter card, mark-as-read and list changes written back to the site.
- **In-app reader** — chapter rows and READ CHAPTER open inside the app; ‹ Prev / Next › walk the book.
- **Offline library as ePub** — opt in per series from the reader's ⋮ menu, and chapters save into a standard ePub book for that series (cover embedded, real table of contents) as you read them — or bulk-download everything the chapter list has loaded. Saved chapters render when you're offline, and the book exports/shares to any other e-reader app. Nothing saves without your say-so; storage stays yours. Two views: **Reader view** (chapter text extracted and rendered natively — sepia/dark/light page themes, serif/sans, adjustable font size) and **Web view** (the full page). Extraction filters hidden decoy content by computed styles and falls back to the web view per page when a host defeats it. Per-novel JavaScript toggle; ad/tracker hosts blocked; pop-ups disabled; open-in-browser escape hatch.
- **Backups & device transfer** — one zip with your lists, chapter caches, cover images, and settings; export/import through the system file picker (Google Drive, OneDrive, USB — anywhere it reaches). Backups never contain your password, login session, or feed tokens.
- **Themes** — light/dark/AMOLED-black/Material You, plus **custom themes from a JSON file** (see below).
- **Japanese sites: Syosetu (なろう) & Kakuyomu (カクヨム)** — each a segregated workspace with its own drawer (hamburger → site chips). **Syosetu**: official JSON API discovery (rankings, advanced search parity with yomou), in-app WebView login, bookmark library sync, and the R18 sister sites (Nocturne / Moonlight / Ladies) behind an 18+ gate with per-site toggles. **Kakuyomu**: discovery through the site's own open GraphQL endpoint (daily/weekly/monthly/all-time rankings × 12 genres × serial/short, word search with paging), work detail + full ordered table of contents from the work page's embedded data, no account needed; R18 works are hidden behind an 18+ gate with an R18-only browse chip; device-local bookmarks (⚑ on a work, carried in backups) with the site's own follow (フォロー) noted for a future login. Both read through the same in-app reader and save offline ePubs through the same pipeline as NU. Full EN/JP app-language toggle; anything stored or sent stays in the site's native terms.
- **Debug log** — a rotating on-device log of every Cloudflare encounter, scrape outcome, and image failure, pullable via adb (see below).

## How it works

**Novel Updates** has no API and no Cloudflare bypass. The app embeds WebViews that share a real logged-in browser session with the site, runs small JavaScript snippets (`ScrapeJs.kt`) to read each page, and parses the results in Kotlin. Writes (mark-as-read, change list) use the same endpoints the website itself uses.

**Syosetu** is served by its official, documented JSON API (`api.syosetu.com` — search, rankings, metadata; guideline ~1 request/second, which we pace well beyond); login features (bookmarks) read your own pages with the WebView session's cookies. **Kakuyomu** is served by the same open GraphQL endpoint its own website uses (`kakuyomu.jp/graphql`, no auth) for discovery, and one work-page GET returns the full metadata + ordered table of contents. Episode text on both flows through the same in-app reader WebView as NU.

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

## AI translation & cover costs (bring your own key)

Chapter translation and AI cover art run through any OpenAI-compatible
endpoint (OpenRouter, OpenAI, a local server) — you bring your own key,
set once in Settings → Translation. Translations are stored in the
offline ePub library alongside the original text (English renders under
each Japanese paragraph in the reader), so they carry between devices in
backups and exports like any other book. The API key never leaves the
device and is excluded from backups.

Approximate per-chapter cost for a typical web-novel chapter (~3,000 Japanese
characters in ≈ ~2,500–3,000 input tokens, ~2,000–2,500 output tokens for a
quality English translation, plus ~500 tokens of prompt overhead). Prices are
per **million tokens**, input/output, from [OpenRouter](https://openrouter.ai/models)
and [AICostNest](https://www.aicostnest.com)'s 2026 pricing guide — check live
prices before relying on these:

| Tier | Model examples (in/out $/M) | Per chapter | 300-chapter novel |
|---|---|---|---|
| Budget | Gemini 2.5 Flash-Lite ($0.10/$0.40), GPT-4o-mini ($0.15/$0.60), DeepSeek V3 (~$0.28/$0.42) | ~$0.001–0.003 | ~$0.30–0.90 |
| **Quality (recommended)** | **GPT-4.1-mini ($0.40/$1.60)**, Gemini 2.5 Flash ($0.30/$2.50), Claude Haiku 4.5 ($1.00/$5.00) | ~$0.004–0.015 | ~$1.20–4.50 |
| Premium | Claude Sonnet 4.5 ($3/$15), GPT-4.1 ($2/$8) | ~$0.03–0.05 | ~$9–15 |

Rules of thumb: a **quality-tier** model translates a chapter for about half a
cent — a full 600-chapter epic runs roughly **$3–8**. Budget models are ~10×
cheaper but need more editing for literary prose; premium models are for
favorite passages, not whole libraries. Glossaries (character/term sheets
kept in the prompt) improve name consistency at ~500 extra input tokens per
chapter.

**AI covers:** series without a site cover offer a *Generate cover (AI)*
button on their page (all three sites). Art is generated from the title,
genre and synopsis in light-novel cover style, without text baked into the
image, and stored exactly like a downloaded cover — grids, backups and
transfers treat it identically. Typical image models run **~$0.01–0.02 per
cover** (measured: Seedream 5 Flash, $0.018). Series info (synopses, tags)
can also be translated for free on-device via ML Kit — see Settings.

## Roadmap

Planned next: the rest of the Japanese webnovel sites, Syosetu bookmark categories and しおり sync, reading-history sync on Kakuyomu. Shipped already: chapter **translation via OpenAI-compatible APIs** and **AI-generated covers** (your own endpoint + key, e.g. OpenRouter — cost estimates above), free on-device synopsis translation via ML Kit, Kakuyomu login + follows with site-master sync, the in-app reader (browser + extracted views, anti-scrape font-substitution decoding), the full offline ePub library, and the three-site workspace. Parsing hazard to remember: NU hides decoy chapter links with CSS — randomized class names, zero-opacity text, and JS-injected traps — so reader extraction filters by *computed* styles (`getComputedStyle` in the WebView: visible, non-hidden, opacity > 0), never by element presence alone.

## Acknowledgements

Design informed by reverse-engineering the original *Notifications for Novel Updates* v1.9.3–v1.13 (Apache-licensed era, abandoned) — thank you to its author. This codebase shares no code with it.

The reader's font-substitution decoder (for hosts that render text through custom substitution fonts) is also published standalone as [font-decipher](https://github.com/PatchScratch/font-decipher).

## License

[MIT](LICENSE) — © 2026 Wayne Yow (PatchScratch)
