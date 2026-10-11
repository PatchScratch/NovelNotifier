# NovelNotifier Roadmap

Status: **v1.0.1 in testing** · Phase 2 planning below.

## v1.0.1 (current)

- **Session-expiry detection** — scrapes that land on NU's login page park the
  sync queue (no request budget burned logged-out), the sync notification
  changes to "Session expired — tap to log in", and tapping it deep-links
  straight into the login screen. A successful login resumes the queue where
  it stopped. List/series screens show a "Session expired — log in from
  Settings" status instead of failing cryptically.
- Conflict-resolution prompt for read positions: **design deferred** until
  offline reading exists (higher-wins merge + back-track floors already cover
  the common cases).

## Phase 2 — feature parity with the original app

Order below is build order. Every scraper lands in `ScrapeJs.kt` with its
parser in `Parsers.kt` and unit tests on fixture payloads — the original app's
history says search/ranking parsers rot fastest, so they stay isolated and
fixture-tested.

### 2.0 Endpoint verification spike — ✅ DONE (2026-10-10)

All Phase 2 endpoints verified against the live site, logged in through the
app's own WebView (probe tooling in `EndpointProbe.kt`, triggered only via
`am start --ez probe_endpoints true`; findings under `[PROBE]` in the debug
log). Remove the probe file with 2.2's cleanup.

| Capability | Verified endpoint | Notes |
|---|---|---|
| Search / autocomplete | POST `/wp-admin/admin-ajax.php` — `action=nd_ajaxsearchmain`, `strType=mobile`, `strOne=<query>`, `strSearchType=series` | Source: the site's own `cdn.novelupdates.com/js/ajax_search_post.js` (CDN serves it without a challenge). Response is HTML rows (`li.search_li_results > a.a_search` with href + `img.search_profile_image` + title, `<span class="search_hl">` marks the match). Their own code strips one trailing character (`slice(0,-1)`). Fixture: `app/src/test/resources/fixture-search-response.html`. |
| Targeted search | Same URL — `action=nd_ajaxsearch`, `str`, `strID`, `strType` | Untested variant used by inline pickers. |
| Lists by series | Same URL — `action=nu_genre_getlists`, `strPostID=<sid>`, `strType` | Returns the user's lists as HTML. |
| Change list | GET `/updatelist.php?sid=<sid>&lid=<listId>&act=move` | Exact params from the site's `moveSeries_genre()` — replaces our jQuery-trigger approach (2.1). |
| Discover / rankings | `/series-ranking/?rank=<value>&pg=<n>` | Live rank tabs: `popmonth`, `popular`, `week`, `month`, `sixmonths`. Grid renders server-side (41 series links on page 1). |

Dead end noted: `/series-search/?src=` renders nothing — do not use.

### 2.1 Change list via `updatelist.php` (small) — ✅ DONE (2026-10-10)

Replace the jQuery `change`-trigger JS in `moveToListJs()` with the direct GET
endpoint (same pattern as the read marker). Lower failure rate, one round trip.
*Acceptance: moving a series between lists survives a hard refresh on the NU
site, and works when jQuery is absent.*

### 2.2 Site search (medium) — ✅ DONE (2026-10-10)

- `SearchScreen`: query field, autocomplete suggestions (the original's v1.13
  feature — the AJAX endpoint serves them), paged result grid reusing
  `NovelGridTile`.
- Tapping a result opens `NovelDetailScreen`; "add to list" available without
  visiting the site.
- *Acceptance: searching a known title finds it; paging loads more; a series
  can be added to a list without leaving the app.*

### 2.3 Discover (medium) — ✅ DONE (2026-10-10)

- Drawer item → `DiscoverScreen`: rankings grid, paged, rank-type tabs from
  the spike results.
- Same detail/add-to-list flow as search.
- *Acceptance: browse N pages without tripping Cloudflare at normal use speed;
  parser failure shows a retry, never an empty "no results".*

### 2.4 Notification polish (small–medium) — ✅ DONE (2026-10-11)

- **Tap → in-app detail page** instead of the browser (introduces the first
  real deep link: notification → `NovelDetailScreen`; needs the nav handoff
  between `MainActivity` state and the detail screen — also groundwork for
  Phase 4's navigation refactor).
- **Stack toggle** in Settings: single grouped summary vs individual
  notifications (`ChapterAlerts` already groups; expose the choice).
- **Boot recovery**: a `BOOT_COMPLETED`/`MY_PACKAGE_REPLACED` receiver re-posts
  the active chapter notifications (original app's
  `NotificationClearedReceiver` pattern).

### 2.5 Themes (small) — ✅ DONE (2026-10-11)

Shipped: green identity palette (light/dark/auto), AMOLED black variant,
optional Material You wallpaper colors (Android 12+). Picker in Settings →
Display; choice persists and travels in backups.

### 2.6 Custom theme files (small) — ✅ DONE (2026-10-11)

User themes from a JSON file: Settings → Display → import via the file
picker; "Save a theme template" writes a complete editable starter. Strict
validation (unknown roles / malformed colors abort with a message), omitted
roles fall back to the built-in green, light+dark follow the system switch,
single-mode files pin that mode. Stored in the app and carried in backups.
Format documented in README → "Custom themes".

Material 3 color schemes: light / dark / **auto (system)** default, plus the
original's green and black variants. Settings picker; persisted choice.

## Phase 3 — the reader (largest, riskiest, most-loved)

1. **Browser mode** (medium) — ✅ DONE (2026-10-11): `ReaderScreen.kt`.
   Chapter rows and READ CHAPTER open in-app; ‹ Prev / Next › walk the book in
   reading order from the loaded RELEASES pages; ⋮ menu = per-novel JavaScript
   toggle (persisted), Reload, Open in browser; ~40 ad/tracker hosts blocked
   (requests AND navigations); pop-ups disabled; shares the app cookie session
   so Cloudflare gates can be completed in place. Edge-to-edge insets applied —
   without them the bars sit under the status/gesture bars and taps vanish.
2. **Reader mode** (large) — ✅ DONE (2026-10-11): `chapterExtractJs()` +
   `parseChapterExtract` + reader text surface in `ReaderScreen.kt`.
   Readability-lite scoring: every visible <p> credits text length to its
   ancestors; the top container's visible paragraphs come back minus nav
   junk, link-only stubs, consecutive duplicates and a mirrored duplicate
   tail. Computed-style filtering exactly as the hazard note demands
   (display/visibility/opacity + box height, ancestors included). Verified
   against a hazard fixture (hidden ad, honeypot, zero-opacity ghost,
   duplicate chapter) in real Chromium: 40/40 paragraphs exact, zero decoy
   leakage. Reading prefs persisted: view (text/web), page theme
   (light/sepia/dark), serif/sans, font size; per-page fallback to web view
   when extraction fails. Live-host spot-check still pending a Cloudflare
   cooldown after heavy testing.
3. **Offline reading** (large) — ✅ DONE (2026-10-11): `EpubStore.kt`.
   Chapters are written as standard ePub (EPUB 3 + EPUB-2 NCX, hand-rolled
   with java.util.zip — no dependency), one book per series in
   filesDir/epubs/, cover embedded from CoverStore, reading order by chapter
   number. Every successfully web-extracted chapter auto-appends (dedup by
   chapter URL); failed extraction falls back to the stored copy ("Offline
   copy — saved earlier") when one exists. Export via the file picker and
   share via FileProvider from the reader's ⋮ menu. EpubStoreTest covers
   structure rules (mimetype first/stored), round-trips incl. XML specials,
   merge/replace, ordering, corrupt-file tolerance. Saving is PER-SERIES
   opt-in (user decision: a 1400-series library must not silently fill device
   storage) — an enabled series auto-saves chapters as read; "Save this
   chapter offline" is an explicit one-off any time; "Download all N loaded
   chapters" bulk-walks the loaded pages (one load per chapter, 1.8 s
   courtesy gap, saved chapters skipped without network, stoppable). Reader bulk is now
   TOC-FIRST (user design, 2026-10-11): every page the bulk downloader loads
   is harvested for same-host chapter links (tocHarvestJs — most translator
   sites embed their chapter list on the page), so NU-routed chapters usually
   resolve to direct URLs with ZERO NU hits; whatever the ToC can't resolve
   fails over to NU's links at the engine's 10 s pacing, strictly after the
   4 s direct pass. Direct links pace 4 s; NU-link hammering was escalating
   Cloudflare and breaking auto-updates. Still future: engine-paced bulk
   BEYOND loaded pages, the bulk read-position catch-up prompt; epubs stay
   out of backup zips.

   **Storage format decision (user, 2026-10-11): chapters are written as
   standard ePub files** — one book per series, chapters appended as they are
   cached — not a proprietary cache. Serves two purposes:
   - in-app offline reading renders straight from the stored ePub;
   - other e-reader apps can open the same file, and the user's existing
     "(ePub)" library workflow (Calibre-style) can ingest what NovelNotifier
     produces.

   Design notes:
   - Write ePubs by hand (java.util.zip + our own OPF/nav XHTML — no external
     dependency); embed the series cover from CoverStore as the book cover.
   - Internal copy lives in filesDir/epubs/ for the app; getting it OUT to
     other apps goes through Android's storage rules (scoped storage):
     export/share via SAF file picker (Google Drive/Downloads/USB all work)
     and/or a FileProvider share intent — decide during implementation.
   - Reading our own ePub back is trivial (we control the XHTML), but keep
     the import tolerant: future versions may meet files written by older ones.
   - Update-in-place: appending a chapter rewrites the zip entry list —
     cheap at book sizes we'll see (a few hundred chapters).

## Phase 4 — architecture debt (interleavable with Phase 2/3)

- **SiteClient unification** (medium): one shared WebView + request queue for
  all scrapes, replacing per-screen WebViews and the counter state machines
  (`siteSync` / `listReloadKey` / `loadKey`); ends the `update {}` client
  reassignment waste. The sync engine already proves the pattern.
- **ViewModels** (medium): `NovelApp`'s ~20 `remember` states survive process
  death and configuration changes.
- Engine polish (small): queue/notification reads off the main thread.

## Deferred / by decision

- **Google Play**: automated review flags single-site scrapers as repetitive
  content / impersonation. Only viable rebranded as a generic "web-novel RSS
  & reading-list manager" with NU as one provider — revisit when (if) a
  second data source exists.
- **F-Droid proper**: metadata drafted; their builders need JDK 25 / AGP 9.4.
  Re-check quarterly. Distribution today: GitHub Releases + IzzyOnDroid
  (submission drafted) + Obtainium.
- **More cloud backup targets**: the system file picker already reaches
  OneDrive, Dropbox, USB, etc.
