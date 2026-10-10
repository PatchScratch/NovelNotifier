package com.patchscratch.novelnotifier

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/**
 * TEMPORARY Phase-2 endpoint probe (ROADMAP 2.0). No UI entry point — it is
 * reached only via:
 *   adb shell am start -n com.patchscratch.novelnotifier/.MainActivity \
 *       --ez probe_endpoints true
 * Findings land in DebugLog under [PROBE]. Remove this file once the
 * endpoint-verification spike is done and fixtures are checked in.
 *
 * 1. /series-ranking/ — live rank= values + grid sanity.
 * 2. a series page — script inventory (found cdn.novelupdates.com/js/ajax_search_post.js).
 * 3. from that same page: POST the site's own search AJAX (nd_ajaxsearchmain)
 *    same-origin and capture the response HTML for fixture tests.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EndpointProbeScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) } // 0 wait … 6 done
    var findings by remember { mutableStateOf(listOf<String>()) }
    var probeWebView by remember { mutableStateOf<WebView?>(null) }

    fun record(label: String, raw: String) {
        val line = "[$label] $raw"
        findings = findings + line
        DebugLog.log("PROBE", line)
    }

    LaunchedEffect(Unit) {
        applySavedCookies(context.getSharedPreferences("novel_prefs", android.content.Context.MODE_PRIVATE))
        delay(2_000)
        step = 1
    }

    // Step 3 fires the same-origin search POST on the loaded series page;
    // step 5 reads the stored result.
    LaunchedEffect(step) {
        when (step) {
            3 -> {
                probeWebView?.evaluateJavascript(SEARCH_FIRE_JS) {}
                delay(5_000)
                step = 5
            }
            5 -> {
                probeWebView?.evaluateJavascript("(window.__nuProbeResult||'pending')") { result ->
                    record("search-ajax", unwrapJsResult(result).take(3000))
                    step = 6
                }
            }
        }
    }

    if (step in 1..6) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = chromeMobileUserAgent(settings.userAgentString)
                    CookieManager.getInstance().setAcceptCookie(true)
                    probeWebView = this
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            val script = when (step) {
                                1 -> RANKING_CAPTURE_JS
                                6 -> FINDER_CAPTURE_JS
                                else -> SERIES_PAGE_CAPTURE_JS
                            }
                            view?.evaluateJavascript(script) { result ->
                                record("step$step ${url.orEmpty().take(70)}", unwrapJsResult(result).take(2200))
                                step += 1
                            }
                        }
                    }
                }
            },
            update = { webView ->
                when (step) {
                    1 -> webView.loadUrl("https://www.novelupdates.com/series-ranking/?rank=popmonth")
                    2 -> if ((webView.url ?: "").contains("series-ranking")) {
                        // Replicate the search screen's base page exactly.
                        webView.loadUrl("https://www.novelupdates.com/")
                    }
                    6 -> if ((webView.url ?: "").contains("novelupdates.com")) {
                        // The desktop full-search page (user-supplied URL, 2026-10-10).
                        webView.loadUrl("https://www.novelupdates.com/series-finder/?sf=1&sh=harem&sort=sdate&order=desc")
                    }
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Endpoint probe", style = MaterialTheme.typography.titleMedium)
        Text(
            if (step == 0) "starting…" else if (step >= 6) "done — see [PROBE] in files/debug.log" else "step $step…",
            style = MaterialTheme.typography.bodySmall
        )
        findings.takeLast(8).forEach {
            Text(it.take(400), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
        if (step >= 6) {
            Button(onClick = onFinished, modifier = Modifier.padding(top = 12.dp)) { Text("Close") }
        }
    }
}

private val RANKING_CAPTURE_JS = """
    (function(){
      var ranks = [];
      document.querySelectorAll('a[href*="rank="]').forEach(function(a){
        var m = (a.getAttribute('href')||'').match(/rank=([a-z]+)/i);
        if (m) ranks.push(m[1].toLowerCase());
      });
      var series = document.querySelectorAll('a[href*="/series/"]').length;
      var first = document.querySelector('a[href*="/series/"]');
      return JSON.stringify({uniqueRanks: ranks.filter(function(v,i,s){return s.indexOf(v)===i}), seriesLinks: series, sample: first?first.href:''});
    })();
""".trimIndent()

private val SERIES_PAGE_CAPTURE_JS = """
    (function(){
      var scripts = [];
      document.querySelectorAll('script[src]').forEach(function(s){
        scripts.push((s.getAttribute('src')||'').split('?')[0]);
      });
      return JSON.stringify({scripts: scripts.slice(0,14)});
    })();
""".trimIndent()

/** The desktop full-search page (series-finder): row structure + pager. */
private val FINDER_CAPTURE_JS = """
    (function(){
      var links = document.querySelectorAll('a[href*="/series/"]');
      var first = links[0];
      var row = first ? (first.closest('tr') || first.closest('[class]') || first.parentElement) : null;
      var pager = [];
      document.querySelectorAll('a[href*="pg="], .digg_pagination a').forEach(function(a){
        pager.push(a.getAttribute('href')||'');
      });
      return JSON.stringify({
        total: links.length,
        firstRow: row ? row.outerHTML.slice(0, 1400) : '',
        pager: pager.slice(0, 6)
      });
    })();
""".trimIndent()

/** Fires the site's own main search (from ajax_search_post.js: nd_ajaxsearchmain). */
private val SEARCH_FIRE_JS = """
    (function(){
      try {
        var fd = new FormData();
        fd.append('action','nd_ajaxsearchmain');
        fd.append('strType','mobile');
        fd.append('strOne','harem');
        fd.append('strSearchType','series');
        fetch('/wp-admin/admin-ajax.php', {method:'POST', body:fd, credentials:'include'})
          .then(function(r){return r.text()})
          .then(function(t){window.__nuProbeResult = t.slice(0,4000)})
          .catch(function(e){window.__nuProbeResult = 'ERR '+e});
      } catch (e) { window.__nuProbeResult = 'ERR '+e }
      return 'fired';
    })();
""".trimIndent()
