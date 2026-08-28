# NovelNotifier

Android app (Kotlin + Jetpack Compose) that lists your Novel Updates reading lists, shows series details, and writes list moves / mark-as-read back to the site.

This is a clean-room replacement for the abandoned “Notifications for Novel Updates” app. It scrapes the public site through a logged-in WebView. There is no official API. If Novel Updates changes its HTML, parsers in this project will need updating.

Package: `com.patchscratch.novelnotifier`

## What to copy where

Put the `.kt` files from a zip drop into:

`app/src/main/java/com/patchscratch/novelnotifier/`

Leave `ui/theme/` alone. Theme stays in the Android Studio project.

`README.md` belongs in the Android Studio project root (the folder that contains `.git` and `app/`).

## File map

| File | Role |
|---|---|
| `Models.kt` | Data shapes: `Novel`, `ReadingList`, `Release`, `SeriesPage`. **Start here.** |
| `Prefs.kt` | SharedPreferences helpers (covers, last-read, list sync age). |
| `Cache.kt` | On-disk JSON for lists and series pages. Merge + “does RSS look newer?” helpers. |
| `Parsers.kt` | RSS XML + series-page JSON coming back from JavaScript. |
| `UiInsets.kt` | `screenInsets()` so buttons never sit under the status / nav bars. |
| `MainActivity.kt` | App launch. Chooses which screen to show. Owns RSS fetch, cover cache, queues. |
| `NovelListScreen.kt` | Drawer, list grid, pull-to-refresh, hidden WebView that reads a reading list. |
| `ListOptionsScreen.kt` | Per-list notify / update switches. |
| `NovelDetailScreen.kt` | Series INFO / RELEASES, CHANGE LIST, mark as read, ⋮ menu. |
| `CoverUi.kt` | Cover thumbnail + in-app cover WebView (used for a single list). |
| `CoverPrefetchService.kt` | Foreground service that prefetches missing covers with a notification. |
| `SeriesSync.kt` | After RSS, scrape only series whose chapter number went up. |
| `SettingsScreen.kt` | RSS URL, login, check interval, cover prefetch. |
| `LoginWebScreen.kt` | Novel Updates login page. |
| `EditListsScreen.kt` | Site list editor. |
| `ChapterAlerts.kt` | shouldNotify + grouped chapter notifications. |
| `UpdateCheck.kt` | Check now + WorkManager RSS worker. |

## Reading the code (new contributors)

Each `.kt` file starts with a block comment (`/** … */`) that says what the file is for and the rules it follows.

Then read in this order:

1. `Models.kt` — the data. Everything else is “how we fill these”.
2. `Prefs.kt` + `Cache.kt` + `Parsers.kt` — how data is stored and parsed. No UI.
3. `MainActivity.kt` — which screen is showing, RSS fetch, shared state.
4. `NovelListScreen.kt` — the home grid + hidden list scrape.
5. `NovelDetailScreen.kt` — series page + CHANGE LIST + mark as read.
6. `CoverPrefetchService.kt` — background covers.

Kotlin / Compose notes you will see a lot:

- `@Composable` functions *draw* UI. They can re-run often; that is normal.
- `remember { mutableStateOf(value) }` keeps a value across those re-runs.
- `var x by remember { mutableStateOf(...) }` uses a *property delegate*. The imports `getValue` and `setValue` look unused in Android Studio. **They are required. Do not delete them.** Unused-import cleanup that removes those two will not compile.
- `LaunchedEffect(key) { ... }` runs the block when `key` changes. `LaunchedEffect(Unit)` runs once when the screen appears.
- `AndroidView` embeds a classic Android `WebView` inside Compose. Hidden ones are 1dp and offset off-screen.
- `evaluateJavascript` returns a *quoted* string. `unwrapJsResult` strips that quoting.
- A `Service` can keep work going after you leave the activity. A foreground service must show a notification.
- `SharedPreferences` is a small key/value store. Larger snapshots use JSON files in `filesDir`.

Unused imports do not make the app faster at runtime (the compiler already drops them). We still remove true orphans so the file is easier to read. The `getValue` / `setValue` pair is not an orphan.

## Manifest extras for cover prefetch

In `app/src/main/AndroidManifest.xml`, inside `<manifest>` add if missing:

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
```

Inside `<application>` add:

```xml
<service
    android:name=".CoverPrefetchService"
    android:exported="false"
    android:foregroundServiceType="dataSync" />
```

## Gradle extra for chapter checks

In `app/build.gradle.kts` dependencies:

```kotlin
implementation("androidx.work:work-runtime-ktx:2.9.1")
```

Then Sync Gradle. Without this, `UpdateCheckWorker` will not resolve.

## This drop (0.1.2-alpha fixes)

Copy **all** `.kt` files from the zip. GitHub currently only has `MainActivity.kt` — the rest of these files live in the zip until you paste them into Android Studio and commit.

Fixes in this zip:

- First launch / empty selection now auto-picks the first reading list and loads `?list=id`. Before, novels were discarded because `selectedListId` was blank.
- CHANGE LIST and MARK AS READ now turn the series WebView on. Cached series pages used to skip the WebView, so site writes never ran.
- Saved login cookies are pushed back into `CookieManager` on app start.
- List scrape treats a Cloudflare “Just a moment” page as a retry, not an empty library.
- Series page scrape now reads the current list + list dropdown from `select#taskOption`.
- List rows show last-read / New when a last-read marker exists.
- Tapping a chapter notification opens that series page in the browser.
- Prefetch missing covers hydrates every Update-on list first, then fetches covers for the whole account (not only the open list).

## How data moves

1. On open, lists paint from `filesDir/list_cache.json`.
2. If the last successful list pull is older than Settings → check interval, one reading-list page is fetched.
3. RSS is compared to cached chapter numbers. Only series with a **higher** `c` number are queued.
4. Series pages paint from `filesDir/series_cache/`. ⋮ → Refresh scrapes now. Opening a series does not scrape if cache exists.
5. Covers: Coil + `cover_cache` in prefs. Settings → **Prefetch missing covers** starts `CoverPrefetchService`. Uncached lists are scraped first (8s apart), then one series page every 8 seconds. Notification stays up so you can leave the app. Stop from Settings or the notification. Updates-off lists are skipped.

## Cloudflare

Hidden WebViews look like bots. Rules we follow:

- One series-page hit at a time.
- 8 seconds between cover prefetch hits (20 seconds after a “Just a moment” page).
- Cover prefetch and chapter-merge fetch should not both hammer the site.
- There is no bypass. If the site shows a check, wait and retry later.

## Git workflow (owner)

1. Commit local changes in Android Studio.
2. Push with the IDE (not a terminal password).
3. Do not overwrite `MainActivity.kt` through the GitHub file API with a stub.
4. Pull / Sync only after a real push you expect.
