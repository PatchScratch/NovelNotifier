package com.patchscratch.novelnotifier

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

/**
 * After RSS reports a *higher* c-number, scrape that one series page and merge
 * new RELEASES rows into series_cache. Used from MainActivity while the list
 * screen is showing (chapterFetchQueue).
 *
 * Rules:
 *   8 seconds between hits so we do not trip Cloudflare.
 *   "Just a moment" → one retry, then skip that title.
 *   mergeReleases never drops old rows and never duplicates chapter+url.
 *   applyListChapter writes the new c-number into list_cache.json.
 *
 * getValue / setValue are used by the `by` keyword. Do not delete those imports.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SeriesChapterFetcher(
    queue: List<Novel>,
    onFinished: (String, Boolean) -> Unit
) {
    val context = LocalContext.current
    val next = queue.firstOrNull() ?: return
    var canLoad by remember(next.seriesUrl) { mutableStateOf(false) }
    var tries by remember(next.seriesUrl) { mutableIntStateOf(0) }

    // Gap before each series-page hit. Keyed on seriesUrl so a new title resets the timer.
    LaunchedEffect(next.seriesUrl) {
        canLoad = false
        delay(8.seconds)
        canLoad = true
    }

    if (!canLoad) return

    // Hidden 1dp WebView, parked off-screen.
    AndroidView(
        modifier = Modifier
            .size(1.dp)
            .alpha(0f)
            .offset(x = 4000.dp),
        factory = { viewContext ->
            WebView(viewContext).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptCookie(true)
            }
        },
        update = { webView ->
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    val script = """
                        (function() {
                          if (document.body && document.body.innerText.indexOf('Just a moment') !== -1) {
                            return 'CF';
                          }
                          function text(sel) {
                            var el = document.querySelector(sel);
                            return el ? (el.innerText || el.textContent || '').trim() : '';
                          }
                          var releases = [];
                          var rows = document.querySelectorAll('#myTable tbody tr');
                          rows.forEach(function(tr) {
                            var tds = tr.querySelectorAll('td');
                            if (!tds.length) return;
                            var links = [];
                            tr.querySelectorAll('a[href]').forEach(function(a) { links.push(a); });
                            var link = links.find(function(a) {
                              return /c\d+|v\d+/i.test((a.textContent || ''));
                            }) || links[links.length - 1];
                            if (!link) return;
                            var chapter = (link.textContent || '').replace(/\s+/g, ' ').trim();
                            if (!chapter || /^[\-\–—._\s]+$/.test(chapter)) return;
                            var cb = tr.querySelector('input[type=checkbox]');
                            var rid = '';
                            var read = false;
                            if (cb) {
                              read = !!(cb.checked || cb.getAttribute('checked') !== null);
                              if (cb.id && cb.id.indexOf('chklist') === 0) rid = cb.id.replace('chklist', '');
                            }
                            var rowClass = (tr.className || '').toLowerCase();
                            if (/readcolor|colorme/.test(rowClass)) read = true;
                            releases.push({
                              chapter: chapter,
                              group: tds[1] ? tds[1].textContent.replace(/\s+/g, ' ').trim() : '',
                              date: tds[0] ? tds[0].textContent.replace(/\s+/g, ' ').trim() : '',
                              url: link.href || '',
                              rid: rid,
                              read: read
                            });
                          });
                          return JSON.stringify({
                            title: text('#editsn, .seriestitle, h4.seriestitle'),
                            description: text('#editdescription, #editdesc'),
                            genres: text('#seriesgenre'),
                            tags: text('#showtags'),
                            sid: (document.querySelector('#mypostid') || {}).value || '',
                            cover: (document.querySelector('.seriesimg img, .serieseditimg img') || {}).src || '',
                            maxPage: 1,
                            releases: releases
                          });
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(script) { result ->
                        val raw = unwrapJsResult(result)
                        if (raw == "CF" || raw.isBlank()) {
                            if (tries < 1) {
                                tries += 1
                                canLoad = false
                            } else {
                                onFinished(next.title, false)
                            }
                            return@evaluateJavascript
                        }
                        val parsed = parseSeriesPage(raw)
                        val old = loadSeriesCache(context, next.seriesUrl)?.first ?: SeriesPage()
                        val merged = mergeReleases(old.releases, parsed.releases)
                        val page = old.copy(
                            title = parsed.title.ifBlank { old.title },
                            description = parsed.description.ifBlank { old.description },
                            genres = parsed.genres.ifBlank { old.genres },
                            tags = parsed.tags.ifBlank { old.tags },
                            coverUrl = parsed.coverUrl.ifBlank { old.coverUrl },
                            sid = parsed.sid.ifBlank { old.sid },
                            releases = merged,
                            maxPage = maxOf(old.maxPage, parsed.maxPage)
                        )
                        saveSeriesCache(context, next.seriesUrl, page, maxOf(1, loadSeriesCache(context, next.seriesUrl)?.second ?: 1))
                        applyListChapter(context, next.title, next.latestChapter)
                        onFinished(next.title, parsed.releases.isNotEmpty())
                    }
                }
            }
            val target = next.seriesUrl.trimEnd('/') + "/"
            if ((webView.url ?: "") != target) webView.loadUrl(target)
        }
    )
}
