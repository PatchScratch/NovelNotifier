# NovelNotifier

[English](README.md) · [日本語](README.ja.md)

An Android app for reading web novels: follow your [Novel Updates](https://www.novelupdates.com) reading lists (covers, unread counts, chapter alerts, mark-as-read), and discover/read/download from the Japanese webnovel sites [Syosetu (なろう)](https://syosetu.com) and [Kakuyomu (カクヨム)](https://kakuyomu.jp) — rankings, search, in-app reading, and offline ePub building across all three.

This is a clean-room, from-scratch replacement for the abandoned *"Notifications for Novel Updates"* app (last released 2019, no longer functional). Novel Updates has no public API, so its side reads the site the way a logged-in browser does — through embedded WebViews with your own session — and writes changes back (mark-as-read, change list) exactly as the website does. The Japanese sites instead use their official/open APIs (Syosetu's documented JSON API; Kakuyomu's open GraphQL endpoint), so no scraping is involved there.

## Install

Grab the latest `NovelNotifier-v<version>.apk` from [Releases](https://github.com/PatchScratch/NovelNotifier/releases) and install it (allow "unknown sources" for your browser if asked). Updates install over the top — your library stays.

**Via Obtainium** (recommended): add the app by this GitHub URL and updates arrive automatically.

**First steps:** Settings → *Log in on Novel Updates* (onboarding takes over), and optionally paste an OpenAI-compatible API key under Settings → *Translation* to unlock AI features. The Japanese sites work without any account.

## Features

### Your NU library, kept in sync
- **Reading lists** — a drawer of all your lists, grid or list layout, search, four sort modes, unread badges from your last-read position.
- **Chapter alerts** — new chapters detected through your personal NU RSS feed (captured automatically — no URL copying) or paced background checks; per-list and per-series mute switches.
- **Cross-device read sync** — chapters marked read on one device appear read on the others via NU's read-activity feed.
- **Series pages** — INFO + full RELEASES table, next-chapter card; mark-as-read and change-list write back to the site exactly as the website does.
- **First-run onboarding** — sizes your library, estimates the download, and lets you mark 1–3 priority lists so their chapters arrive first.

### Japanese sites: Syosetu and Kakuyomu
Each site is its own workspace (hamburger → site chips), served by official/open APIs — no scraping.
- **Discover** — rankings, advanced search (full yomou parity on Syosetu), and the R18 sister sites behind an 18+ gate.
- **Machine-translated browsing** — ranking titles, synopses and tags show a dimmed English line under the Japanese (free on-device ML Kit, cached forever; or via your own AI key).
- **Bookmarks** — Syosetu login syncs your bookmark library with your renamed 分類 categories in the drawer; long-press any tile (or the series page) to **move it between categories or remove it** — written back to the site, NU-style. Kakuyomu login syncs follows (site is master).
- **Update notifications** — bookmarked Syosetu series notify on new episodes via the authors' public Atom feeds, same pipeline as NU.
- **Fast series pages** — synopsis and chapter list cache on-device; re-entering a series is instant, and refreshes only happen when a new episode actually lands.

### Reading & offline
- **In-app reader** — ‹ Prev / Next › through the book, sepia/dark/light themes, serif/sans, adjustable font size, per-novel JavaScript toggle, ad/tracker blocking, open-in-browser escape hatch. Extraction filters NU's hidden decoy content by computed styles and falls back to the full-page web view when a host defeats it.
- **Offline library as standard ePubs** — opt in per series; chapters save as you read or bulk-download whole volumes (survives screen-off; the queue survives reboots). Books export/share to any e-reader. Storage stays yours — nothing saves without your say-so.
- **Long-press Japanese dictionary** — tap-and-hold any word in the reader for its reading, part of speech and JMdict senses. Fully offline (built-in Kuromoji tokenizer + a 499k-entry JMdict; ~80MB unpacked once on first enable).

### AI translation & covers (bring your own key)
- **Chapter translation** through any OpenAI-compatible endpoint (OpenRouter, OpenAI, a local server): English renders under each Japanese paragraph and is stored inside the offline ePub. Translate one chapter, or **Translate all stored** for the whole book.
- **Novel-Mill-style lock files** — `prompt.txt` / `glossary.txt` / `voices.txt`, editable per series, and **auto-refined after every chapter** (new names and voice notes are extracted and saved for the next chapter's context). Files from the desktop [novel-mill](https://github.com/PatchScratch/novel-mill) paste straight in.
- **Reader layout switch** — JP+EN, Japanese only, or English only, cycled right from the reader menu; ePub exports follow the same choice.
- **AI cover art** for series without one (all three sites) — generated from title, genre and synopsis with your own prompt; title and author render as cover typography in the artwork's style.
- **Costs are yours to control** — the key lives only on the device, never in backups; typical translation runs ~half a cent per chapter, covers ~$0.01–0.02 (see the cost table below).

### Hidden lists & SFW mode
- **Flag any list or category as hidden** — NU reading lists, Syosetu 分類, and Kakuyomu's client-side categories (Kakuyomu's follows are flat on the site, so the app adds local folders; they live in app data and ride backups). Hidden series vanish from every screen, cover grids, counts, search and notifications until you unlock.
- **SFW/NSFW mode** — the app starts locked every time. A drawer toggle unlocks with your fingerprint or screen lock (Android's own prompt — no new PIN to invent); hiding again is instant, and the app re-locks itself whenever it leaves the foreground. R18 content (Syosetu's sister sites, Kakuyomu 18+) sits behind the same unlock. Hidden series never notify. Nothing is encrypted or deleted — it's a display gate over data that still syncs and backs up.

### Backup, themes, and diagnostics
- **One-zip backups** — lists, chapter caches, covers and settings through the system file picker; never contains your password, sessions, or feed tokens. **Share covers (zip)** moves cover art between your devices on its own.
- **Themes** — light/dark/AMOLED-black/Material You, plus custom themes from a JSON file.
- **On-device debug log** — every Cloudflare encounter, scrape outcome and image failure, pullable via adb.

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
| `ReaderScreen.kt` | In-app reader: WebView + extracted-text views, chapter nav, translation controls, the long-press dictionary hookup. |
| `SeriesSync.kt` | Per-series merge after a new chapter is detected. |
| `DebugLog.kt` | Rotating file log for on-device diagnosis. |
| `Syosetu.kt` | Syosetu provider: API client + parsers, bookmarks, the settings-form write-back (move/remove), series cache. |
| `SyosetuFeeds.kt` | Update notifications via author Atom feeds (one fetch per author, paced, timeout-guarded). |
| `Kakuyomu.kt` | Kakuyomu provider: open GraphQL client, work-page data extraction, follow/unfollow calls. |
| `TranslationClient.kt` | Chapter translation: indexed-paragraph protocol, reply repair, chunking, retries. |
| `XlateStack.kt` | novel-mill lock files (prompt/glossary/voices) + per-chapter auto-refinement. |
| `TranslationQueue.kt` | Bulk "translate all stored chapters" runner with live progress. |
| `MetadataTranslator.kt` | Free site-info translation (ML Kit) with a hash-keyed sidecar cache; serialized, retry-on-failure. |
| `JaDict.kt` | Long-press dictionary: Kuromoji tokenizer + bundled JMdict, unpacked on first enable. |
| `CoverGen.kt` | AI cover generation through OpenRouter's /images endpoint, prompt templating, the Generate/Regenerate button. |
| `BookmarksScreen.kt`, `XlateFilesScreen.kt` | Bookmark library grid/list with long-press management; the lock-file editor. |

### Data flow

1. Lists and series pages paint instantly from `filesDir` caches (`list_cache.json`, `series_cache/`).
2. Fresh login → **onboarding** reads your lists, sizes the library, and queues the full download (covers → chapter pages, priority lists first).
3. New chapters are detected by the releases feed (auto-captured) or paced list checks; changed series get one page re-scraped and merged into the cache.
4. The read-activity feed merges marks from your other devices; local progress can never be rewound.
5. Everything the engine does is checkpointed in `filesDir/sync_queue.json` and resumable.

## Building from source

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

To move just cover art between devices (including AI-generated covers, which have no site to re-download from), **Share covers (zip)** in the same section writes a covers-only zip; importing it on the other device merges, never deletes.

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
genre and synopsis in light-novel cover style — with the title and author
rendered as cover typography that matches the artwork — and stored exactly
like a downloaded cover, so grids, backups and transfers treat it
identically. Typical cost is **~$0.02 per cover** (default Seedream 5
Flash, $0.018); when Seedream refuses a synopsis through content
moderation, generation automatically retries once on Nano Banana 2.1
(~$0.04), which passes suggestive material and renders the title/author
typography better. The prompt is yours to customise. Series info
(synopses, tags) translates for free on-device via ML Kit — see Settings.

## Roadmap

Planned next: read-aloud refinements (speed sliders, sleep timer, media
notification), visual polish, and then the wider Japanese webnovel sites.

Parsing hazard to remember: NU hides decoy chapter links with CSS — randomized class names, zero-opacity text, and JS-injected traps — so reader extraction filters by *computed* styles (`getComputedStyle` in the WebView: visible, non-hidden, opacity > 0), never by element presence alone.

## Acknowledgements

Design informed by reverse-engineering the original *Notifications for Novel Updates* v1.9.3–v1.13 (Apache-licensed era, abandoned) — thank you to its author. This codebase shares no code with it.

The reader's font-substitution decoder (for hosts that render text through custom substitution fonts) is also published standalone as [font-decipher](https://github.com/PatchScratch/font-decipher).

## License

[MIT](LICENSE) — © 2026 Wayne Yow (PatchScratch)
