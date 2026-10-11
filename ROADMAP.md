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
2. **Reader mode** (large): sepia/dark/light themes, serif/sans + font size,
   content extraction. **Extraction must filter by computed styles**
   (`getComputedStyle` in the WebView: `display != none`,
   `visibility != hidden`, `opacity > 0`) — NU uses randomized class names,
   zero-opacity text, inverted flexbox ordering, and JS-injected honeypots.
   Expect this parser to break and design for fast fixes.
3. **Offline reading** (large): gated on the reader existing — chapter storage
   attaches to reader display; includes the conflict-resolution prompt
   (local vs site read position) designed here for the first time.

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
