package com.patchscratch.novelnotifier

import org.json.JSONObject

/**
 * Every JavaScript payload the hidden WebViews run, in ONE file.
 *
 * When Novel Updates changes its HTML, this is the only place to edit:
 *   CF_DETECT_JS   — shared "is this a Cloudflare interstitial?" probe.
 *   listPageJs()   — reading-list page → lists + series rows (with last-read).
 *   seriesPageJs() — series page → INFO + RELEASES rows + list dropdown + sid.
 *   coverJs()      — series page → cover image URL only.
 *   moveListJs()    — CHANGE LIST: one GET of updatelist.php (verified).
 *   searchFireJs()  — SEARCH: the site's own live search via admin-ajax.
 *   moveReadMarkerJs() — READ POSITION: one call moves NU's single tick to a
 *                        chapter (rid="0" clears it).
 *
 * Kotlin raw strings keep backslashes literal, so the JS regexes appear
 * exactly as they must reach the WebView. Duplicated copies of these scripts
 * used to live inside NovelListScreen / NovelDetailScreen / SeriesSync /
 * CoverUi / CoverPrefetchService — do not reintroduce them.
 */

/** JS snippet: return 'CF' if this document is a Cloudflare interstitial. */
const val CF_DETECT_JS = """
  function nuIsCf() {
    var t = ((document.body && (document.body.innerText || document.body.textContent)) || '').slice(0, 4000);
    var title = document.title || '';
    var html = (document.documentElement && document.documentElement.innerHTML || '').slice(0, 6000);
    var blob = (t + ' ' + title + ' ' + html).toLowerCase();
    if (/just a moment|verify you are human|checking your browser|attention required|enable javascript and cookies|cf-turnstile|challenge-platform|cf-browser-verification|why have i been blocked/.test(blob)) return true;
    if (document.querySelector('#challenge-form, .cf-browser-verification, .cf-turnstile, iframe[src*="challenges.cloudflare"], iframe[src*="turnstile"]')) return true;
    return false;
  }
  if (nuIsCf()) return 'CF';
"""

/**
 * Reading-list page. Returns
 *   "listId===listName|||...:::NOVELS:::title===chapter===href===img===lastReadCh===lastReadUrl|||..."
 * Novel Updates sometimes hides decoy chapter links with CSS; the [hasListMark]
 * / bracket-number heuristics keep junk rows out (see original app v1.11).
 */
fun listPageJs(): String = """
    (function() {
      $CF_DETECT_JS
      var found = {};
      var order = [];
      function isJunk(name) {
        name = (name || '').replace(/\s+/g, ' ').trim();
        if (!name || name.length > 50) return true;
        if (/^\d+$/.test(name)) return true;
        if (/^(OR|AND|YES|NO|---|--|–|Default|Dark|Dark Crisp|Alphabetical|Unread|Latest|Reading Lists|Reading List|Edit Lists)$/i.test(name)) return true;
        return false;
      }
      function addList(id, name) {
        name = (name || '').replace(/\s+/g, ' ').trim();
        id = (id || '').trim();
        if (isJunk(name)) return;
        if (found[name]) return;
        found[name] = id || name;
        order.push(name);
      }
      var moveSelect = null;
      document.querySelectorAll('select').forEach(function(sel) {
        var texts = [];
        for (var i = 0; i < sel.options.length; i++) {
          texts.push((sel.options[i].textContent || '').trim());
        }
        if (texts.indexOf('---') !== -1 || texts.indexOf('--') !== -1) {
          moveSelect = sel;
        }
      });
      if (moveSelect) {
        for (var j = 0; j < moveSelect.options.length; j++) {
          addList(moveSelect.options[j].value, moveSelect.options[j].textContent);
        }
      }
      document.querySelectorAll('a[href*="/reading-list"]').forEach(function(a) {
        var href = a.href || '';
        var m = href.match(/[?&]list=([^&]+)/);
        addList(m ? decodeURIComponent(m[1]) : '', a.textContent);
      });
      var lists = [];
      for (var n = 0; n < order.length; n++) {
        lists.push(found[order[n]] + '===' + order[n]);
      }
        var novels = [];
        function isJunkTitle(title) {
        return /^(series information|description|latest release|status|notes|latest series)$/i.test(title);
      }
      var candidates = [];
      // The reading-list table = the one containing NU's per-row chklist
      // checkboxes. Found, it scopes every captured row; not found (page
      // redesign), the checkbox-mark fallback below applies.
      var listTable = null;
      var chkInput = document.querySelector("input[id^='chklist']");
      if (chkInput && chkInput.closest) listTable = chkInput.closest('table');
      document.querySelectorAll('a[href*="/series/"]').forEach(function(a) {
        var title = (a.textContent || '').replace(/\s+/g, ' ').trim();
        var href = a.href || '';
        if (!title || isJunkTitle(title)) return;
        if (!/\/series\/[a-z0-9-]+/i.test(href)) return;
        var chapter = '';
        var chapterUrl = '';
        var lastReadCh = '';
        var lastReadUrl = '';
        var img = '';
        var hasListMark = false;
        var row = a.closest ? a.closest('tr') : a.parentElement;
        var inListTable = !!(listTable && listTable.contains(a));
        if (row) {
          var statusA = row.querySelector('[id^="mystatus"], a[id*="mystatus"], #mystatus');
          var latestA = row.querySelector('[id^="mylatest"], a[id*="mylatest"]');
          if (latestA) {
            chapter = (latestA.textContent || '').replace(/\s+/g, ' ').trim();
            chapterUrl = latestA.href || '';
          }
          if (statusA) {
            lastReadCh = (statusA.textContent || '').replace(/\s+/g, ' ').trim();
            lastReadUrl = statusA.getAttribute('href') || '';
          }
        }
        // Scribble Hub detection (Wayne's rule): SH-hosted series' chapter
        // link points at scribblehub.com. The mylatest anchor may be absent
        // on some row variants, so probe the whole row for any SH link.
        if (!chapterUrl && row && row.querySelector) {
          var shA = row.querySelector('a[href*="scribblehub.com"]');
          if (shA) chapterUrl = shA.href || '';
        }
        var node = a.parentElement;
        for (var k = 0; k < 8 && node; k++) {
          var text = node.textContent || '';
          if (!chapter) {
            var found = text.match(/\[\s*([^\]]+)\]/g) || [];
            var nums = [];
            for (var f = 0; f < found.length; f++) {
              var inner = found[f].replace(/[\[\]]/g, '').trim();
              if (/c\d+|ch\s*\d+|chapter\s*\d+|ep\s*\d+|v\d+/i.test(inner)) nums.push(inner);
            }
            if (nums.length >= 2) {
              if (!lastReadCh) lastReadCh = nums[0];
              chapter = nums[nums.length - 1];
            } else if (nums.length === 1) {
              chapter = nums[0];
            }
          }
          if (node.querySelector && (
            node.querySelector('input[id^="chklist"]') ||
            node.querySelector('input[type="checkbox"]')
          )) hasListMark = true;
          var image = node.querySelector ? node.querySelector('img') : null;
          if (image && image.src && image.src.indexOf('data:') !== 0 && image.src.indexOf('icon') === -1) {
            if (!img) img = image.src;
          }
          node = node.parentElement;
        }
        if (!chapter && !hasListMark) return;
        candidates.push({
          title: title, chapter: chapter || 'No chapter info', href: href, img: img,
          chapterUrl: chapterUrl,
          lastReadCh: lastReadCh, lastReadUrl: lastReadUrl, hasListMark: hasListMark,
          inListTable: inListTable
        });
      });
      // Strict row scoping (2026-10-13, tightened 2026-10-14): real
      // reading-list rows live in the table that carries NU's own list
      // checkboxes (chklist inputs). When that table is found, ONLY anchors
      // inside it count — this structurally ignores every side widget
      // (the "Latest Series" panel, suggestion/recommendation boxes) whose
      // rows share the /series/ href + bracket chapter text but are not the
      // user's list. The checkbox-mark heuristic (and, below it, the
      // chapter-text acceptance) survives solely as fallbacks for a page
      // redesign where the table or the checkboxes cannot be found
      // (degrade, don't lose the library).
      var anyMarked = candidates.some(function(c) { return c.hasListMark; });
      // The list header carries NU's own row count — "Series (195)". It is
      // the authoritative scope: the "Latest Series" panel and suggestion
      // widgets append AFTER the real list rows, so trimming to this count
      // removes them without structural guessing about the markup.
      var expectedCount = -1;
      var scopeText = (listTable ? listTable.textContent : document.body.textContent) || '';
      var cm = scopeText.match(/Series\s*\((\d+)\)/);
      if (cm) expectedCount = parseInt(cm[1], 10);
      candidates.forEach(function(c) {
        if (listTable && !c.inListTable) return;
        if (!listTable && anyMarked && !c.hasListMark) return;
        novels.push(c.title + '===' + c.chapter + '===' + c.href + '===' + c.img + '===' + c.lastReadCh + '===' + c.lastReadUrl + '===' + c.chapterUrl);
      });
      // NU shows the personal RSS feed links on this page — the releases feed
      // and the read-activity feed (type=read). Grab every feed-ish anchor so
      // the app can configure chapter checks AND cross-device read sync.
      var feeds = [];
      document.querySelectorAll('a[href*="rss"], a[href*="RSS"], a[href*="/feed"]').forEach(function(a) {
        if (a.href && /^https?:/i.test(a.href) && feeds.indexOf(a.href) === -1) feeds.push(a.href);
      });
      return lists.join('|||') + ':::NOVELS:::' + novels.join('|||') + ':::RSS:::' + feeds.join('|||') +
        ':::COUNT:::' + expectedCount;
    })();
""".trimIndent()

/**
 * Series page (any ?pg=N). Returns the JSON consumed by parseSeriesPage().
 * rid comes from the chklist checkbox id; sid from #mypostid.
 */
fun seriesPageJs(): String = """
    (function() {
      $CF_DETECT_JS
      function text(sel) {
        var el = document.querySelector(sel);
        return el ? (el.innerText || el.textContent || '').trim() : '';
      }
      function texts(sel) {
        var out = [];
        document.querySelectorAll(sel).forEach(function(a) {
          var t = (a.textContent || '').trim();
          if (t) out.push(t);
        });
        return out.join(', ');
      }
      var img = document.querySelector('.seriesimg img, .serieseditimg img');
      var releases = [];
      document.querySelectorAll('#myTable tbody tr').forEach(function(tr) {
        var tds = tr.querySelectorAll('td');
        if (!tds.length) return;
        var link = tr.querySelector('a.chp-release');
        if (!link) {
          var links = Array.from(tr.querySelectorAll('a[href]'));
          link = links.find(function(a) {
            return /c\d+|v\d+/i.test((a.textContent || ''));
          }) || links[links.length - 1];
        }
        if (!link) return;
        var chapter = (link.textContent || '').replace(/\s+/g, ' ').trim();
        if (!chapter) return;
        if (/^[\\-–—._\\s]+$/.test(chapter)) return;
        var cb = tr.querySelector('input[type=checkbox]');
        var rid = '';
        var read = false;
        if (cb) {
          read = !!(cb.checked || cb.getAttribute('checked') !== null);
          if (cb.id && cb.id.indexOf('chklist') === 0) {
            rid = cb.id.replace('chklist', '');
          } else if (cb.value && cb.value !== 'notset') {
            rid = cb.value;
          }
        }
        var rowClass = (tr.className || '').toLowerCase();
        if (/readcolor|colorme|checked/.test(rowClass)) read = true;
        if (tr.querySelector('.readcolor, .nu_editnotes')) read = true;
        releases.push({
          chapter: chapter,
          group: tds[1] ? tds[1].textContent.replace(/\s+/g, ' ').trim() : '',
          date: tds[0] ? tds[0].textContent.replace(/\s+/g, ' ').trim() : '',
          url: link.href || '',
          rid: rid,
          read: read
        });
      });
      var max = 1;
      document.querySelectorAll('.digg_pagination a').forEach(function(a) {
        var m = ((a.getAttribute('href') || '') + (a.search || '')).match(/[?&]pg=(\d+)/);
        if (m) {
          var n = parseInt(m[1], 10);
          if (n > max) max = n;
        }
      });
      var task = document.querySelector('select#taskOption, select[name="taskOption"], select.taskOption');
      var currentList = '';
      var lists = [];
      if (task) {
        for (var i = 0; i < task.options.length; i++) {
          var t = (task.options[i].textContent || '').replace(/\s+/g, ' ').trim();
          var v = (task.options[i].value || '').trim();
          if (!t || t === '---' || t === '--') continue;
          if (/^select/i.test(t) || /choose list|move to/i.test(t)) continue;
          lists.push({ id: v, name: t });
          if (task.options[i].selected) currentList = t;
        }
      }
      return JSON.stringify({
        title: text('#editsn, .seriestitle, h4.seriestitle') || document.title.replace(' - Novel Updates', ''),
        author: texts('#showauthors a, #editaid a'),
        rating: text('.uvotes, #rateit, span.uvotes'),
        description: text('#editdescription, #editdesc'),
        genres: texts('#seriesgenre a, .seriesgenre a'),
        tags: texts('#showtags a, #edittags a'),
        type: text('#showtype') || text('#edittype'),
        status: text('#editstatus, #showstatus'),
        // Display ids verified against the live series page 2026-10-11:
        // #editassociated/#edityear are DISPLAY elements on NU (no show-twins);
        // artists is plain text (no links); publishers are link lists. Show
        // variants must be tried EXPLICITLY first — a comma selector returns
        // the first match in document order, and the empty edit-mode
        // placeholder (#edittype/#edittranslated) precedes the filled #show*.
        associatedNames: text('#editassociated').replace(/\n+/g, ', '),
        artist: text('#showartists') || text('#editartist'),
        year: text('#edityear'),
        originalPublisher: texts('#showopublisher a') || text('#showopublisher'),
        completelyTranslated: text('#showtranslated') || text('#edittranslated'),
        cover: img ? (img.src || '') : '',
        maxPage: max,
        currentList: currentList,
        sid: (document.querySelector('#mypostid') || {}).value || '',
        lists: lists,
        releases: releases
      });
    })();
""".trimIndent()

/** Series page → cover URL only. Same CF contract as the others ('CF' = challenge). */
fun coverJs(): String = """
    (function() {
      $CF_DETECT_JS
      var img = document.querySelector('.seriesimg img, .serieseditimg img');
      return img ? (img.src || '') : '';
    })();
""".trimIndent()

/**
 * CHANGE LIST — the site's own move call, verified 2026-10-10 from NU's
 * ajax_search_post.js (moveSeries_genre): GET /updatelist.php?sid=<sid>&lid=<lid>&act=move.
 * [sidFallback] is the cached #mypostid when the live page has none.
 * Returns "move <status>" or "missing sid".
 */
fun moveListJs(listId: String, sidFallback: String): String = """
    (function() {
      var sid = (document.querySelector('#mypostid') || {}).value || ${JSONObject.quote(sidFallback)};
      if (!sid) return 'missing sid';
      var lid = ${JSONObject.quote(listId)};
      try {
        var xhr = new XMLHttpRequest();
        xhr.open('GET', '/updatelist.php?sid=' + encodeURIComponent(sid) + '&lid=' + encodeURIComponent(lid) + '&act=move', false);
        xhr.send(null);
        return 'move ' + xhr.status;
      } catch (e) {
        return 'move err';
      }
    })();
""".trimIndent()

/**
 * SEARCH — fires the site's own live search (verified 2026-10-10: NU's
 * ajax_search_post.js calls admin-ajax.php with action=nd_ajaxsearchmain)
 * same-origin and stores the response HTML on window.__nuSearchResult for the
 * caller to poll. Two-phase because evaluateJavascript cannot await fetch().
 * Returns 'fired' immediately.
 */
fun searchFireJs(query: String): String = """
    (function() {
      window.__nuSearchResult = '';
      window.__nuSearchErr = '';
      try {
        var fd = new FormData();
        fd.append('action', 'nd_ajaxsearchmain');
        fd.append('strType', 'mobile');
        fd.append('strOne', ${JSONObject.quote(query)});
        fd.append('strSearchType', 'series');
        fetch('/wp-admin/admin-ajax.php', { method: 'POST', body: fd, credentials: 'include' })
          .then(function(r) { return r.text() })
          .then(function(t) { window.__nuSearchResult = t.slice(0, -1) })
          .catch(function(e) { window.__nuSearchErr = '' + e });
      } catch (e) { window.__nuSearchErr = '' + e }
      return 'fired';
    })();
""".trimIndent()

/** Reads the stored search response: the HTML, or 'pending', or the error. */
const val SEARCH_READ_JS = """(window.__nuSearchErr || window.__nuSearchResult || 'pending')"""

/**
 * FULL SEARCH — the desktop results page (user-supplied 2026-10-10):
 * /series-finder/?sf=1&sh=<term>&sort=sdate&order=desc[&pg=N]. Server-rendered
 * grid, ~41 rows/page, pager max gives the total. Rows are div.search_title
 * anchors; covers (when present) sit in a surrounding container. Returns JSON
 * {rows:[{t,u,i}], maxPage} — parse with parseFinderResults.
 */
fun finderPageJs(): String = """
    (function() {
      $CF_DETECT_JS
      var rows = [];
      document.querySelectorAll('div.search_title a[href*="/series/"]').forEach(function(a) {
        var title = (a.textContent || '').replace(/\s+/g, ' ').trim();
        var img = '';
        var node = a.parentElement;
        for (var k = 0; k < 4 && node; k++) {
          var im = node.querySelector ? node.querySelector('img') : null;
          if (im && im.src && im.src.indexOf('data:') !== 0) { img = im.src; break; }
          node = node.parentElement;
        }
        rows.push({ t: title, u: a.href, i: img });
      });
      var max = 1;
      document.querySelectorAll('a[href*="pg="]').forEach(function(a) {
        var m = ((a.getAttribute('href') || '')).match(/pg=(\d+)/);
        if (m) { var n = parseInt(m[1], 10); if (n > max) max = n; }
      });
      return JSON.stringify({ rows: rows, maxPage: max });
    })();
""".trimIndent()

/**
 * MOVE READ POSITION — one call. NU keeps a single tick per series (the read
 * position); checking any row's checkbox MOVES the marker there and the site
 * clears the old one itself, so no unticking is ever needed. The special rid
 * "0" is the site's clear-position call (`rid=0`, no checked param — the same
 * endpoint the original app used). [sidFallback] is the cached #mypostid when
 * the live page has none. Returns "ok <status>" or "missing sid".
 */
fun moveReadMarkerJs(rid: String, sidFallback: String): String = """
    (function() {
      var rid = ${JSONObject.quote(rid)};
      var sid = (document.querySelector('#mypostid') || {}).value || ${JSONObject.quote(sidFallback)};
      if (!sid) return 'missing sid';
      var qs = '/readinglist_update.php?rid=' + encodeURIComponent(rid) + '&sid=' + encodeURIComponent(sid);
      if (rid !== '0') qs += '&checked=yes';
      if (rid !== '0') {
        var box = document.getElementById('chklist' + rid);
        if (box) { box.checked = true; box.dispatchEvent(new Event('change', { bubbles: true })); }
      }
      try {
        var xhr = new XMLHttpRequest();
        xhr.open('GET', qs, false);
        xhr.send(null);
        return 'ok ' + xhr.status;
      } catch (e) {
        return 'err';
      }
    })();
    """.trimIndent()


/**
 * Shared runtime cipher-derivation code (plain JS functions, no IIFE) —
 * included by chapterExtractJs() and decodeDomJs(). Same logic ships
 * standalone as PatchScratch/font-decipher derive_browser.js.
 */
val CIPHER_DERIVE_JS = """
// --- runtime cipher derivation (rotation / PUA / partial substitution) --
// When the built-in ladder doesn't apply, DERIVE the cipher in this page:
// render each suspicious codepoint and each ASCII reference on a canvas
// in the chapter's own font and match by pixel IoU. Font rotation per
// page load (Qidian-style renamed families) is irrelevant — the family is
// read off a live paragraph, never stored. Handles Private Use ladders
// (Chaleuria-style), non-linear permutations, and partial substitution
// (only the substituted set needs to decode). Fails gracefully (null)
// when the font ships no ASCII of its own — then there is nothing
// in-font to compare against and the web view stays the answer.
// Same code ships standalone: PatchScratch/font-decipher (derive_browser.js).
var DERIVE_SIZE = 48, DERIVE_RENDER = 96;
function glyphMask(ch, fontFamily) {
  var canvas = document.createElement('canvas');
  canvas.width = DERIVE_RENDER; canvas.height = DERIVE_RENDER;
  var ctx = canvas.getContext('2d', { willReadFrequently: true });
  ctx.fillStyle = '#000';
  ctx.font = (DERIVE_RENDER * 0.7) + 'px ' + fontFamily;
  ctx.textBaseline = 'alphabetic';
  ctx.fillText(ch, DERIVE_RENDER * 0.2, DERIVE_RENDER * 0.8);
  var data = ctx.getImageData(0, 0, DERIVE_RENDER, DERIVE_RENDER).data;
  var minX = DERIVE_RENDER, minY = DERIVE_RENDER, maxX = -1, maxY = -1;
  for (var y = 0; y < DERIVE_RENDER; y++) {
    for (var x = 0; x < DERIVE_RENDER; x++) {
      if (data[(y * DERIVE_RENDER + x) * 4 + 3] > 40) {
        if (x < minX) minX = x;
        if (x > maxX) maxX = x;
        if (y < minY) minY = y;
        if (y > maxY) maxY = y;
      }
    }
  }
  if (maxX < 0) return null;
  var mask = new Uint8Array(DERIVE_SIZE * DERIVE_SIZE);
  var bw = maxX - minX + 1, bh = maxY - minY + 1;
  var scale = DERIVE_SIZE / Math.max(bw, bh);
  var dw = Math.max(1, Math.round(bw * scale)), dh = Math.max(1, Math.round(bh * scale));
  var ox = (DERIVE_SIZE - dw) >> 1, oy = (DERIVE_SIZE - dh) >> 1;
  for (var yy = 0; yy < dh; yy++) {
    var sy = minY + Math.floor(yy / scale);
    for (var xx = 0; xx < dw; xx++) {
      var sx = minX + Math.floor(xx / scale);
      if (data[((sy | 0) * DERIVE_RENDER + (sx | 0)) * 4 + 3] > 60) {
        mask[(oy + yy) * DERIVE_SIZE + ox + xx] = 1;
      }
    }
  }
  return mask;
}
function maskIou(a, b) {
  var inter = 0, union = 0;
  for (var i = 0; i < a.length; i++) { union += a[i] | b[i]; inter += a[i] & b[i]; }
  return union ? inter / union : 0;
}
function suspiciousCount(t) {
  var sus = 0, cjk = 0;
  for (var i = 0; i < t.length; i++) {
    var c = t.charCodeAt(i);
    if ((c >= 0x2F00 && c <= 0x2FDF) || (c >= 0xE000 && c <= 0xF8FF) || (c >= 0x3400 && c <= 0x4DBF)) sus++;
    else if (c >= 0x4E00 && c <= 0x9FFF) cjk++;
  }
  return { sus: sus, cjk: cjk, len: t.length };
}
function deriveCipher(containerText, probeEl) {
  var stats = suspiciousCount(containerText);
  if (stats.len === 0 || stats.sus / stats.len < 0.08) return null;
  if (stats.cjk / stats.len >= 0.15) return null; // real CJK prose
  // Font from an actual paragraph — the container inherits the site face.
  var fontFamily = window.getComputedStyle(probeEl).fontFamily;
  var used = {};
  for (var i = 0; i < containerText.length; i++) {
    var c = containerText.charCodeAt(i);
    if ((c >= 0x2F00 && c <= 0x2FDF) || (c >= 0xE000 && c <= 0xF8FF) || (c >= 0x3400 && c <= 0x4DBF)) {
      used[containerText[i]] = true;
    }
  }
  var suspects = Object.keys(used);
  if (!suspects.length) return null;
  var refs = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'.,!?-";
  var refMasks = {};
  for (var r = 0; r < refs.length; r++) {
    var m = glyphMask(refs[r], fontFamily);
    if (m) refMasks[refs[r]] = m;
  }
  var map = {};
  for (var s = 0; s < suspects.length; s++) {
    var sm = glyphMask(suspects[s], fontFamily);
    if (!sm) continue;
    var best = null, bestScore = 0, second = 0;
    for (var k in refMasks) {
      var sc = maskIou(sm, refMasks[k]);
      if (sc > bestScore) { second = bestScore; bestScore = sc; best = k; }
      else if (sc > second) second = sc;
    }
    if (best && bestScore >= 0.70 && bestScore - second >= 0.08) {
      map[suspects[s]] = best;
    }
  }
  if (Object.keys(map).length < Math.max(3, suspects.length * 0.6)) return null;
  // sanity: a decoded sample must read like English, else we may have
  // matched against the wrong fallback font
  var sample = containerText.slice(0, 1200).replace(/[\s\S]/g, function(c) { return map[c] || c; });
  var lower = ' ' + sample.toLowerCase() + ' ';
  var words = [' the ', ' and ', ' to ', ' of ', ' in ', ' that ', ' was ', ' it ', ' with '];
  var hits = 0;
  for (var w = 0; w < words.length; w++) if (lower.indexOf(words[w]) !== -1) hits++;
  if (hits < 2) return null;
  return map;
}
"""

/**
 * Reader-mode extraction (Phase 3.2). Runs in the READER WebView after the
 * chapter page settles. Strategy: readability-lite scoring.
 *
 *   1. Every visible <p> credits its text length to each ancestor.
 *   2. The highest-scoring ancestor (most visible paragraph text) is the
 *      chapter container.
 *   3. Its visible <p>s come back in DOM order, minus nav junk, ads, and
 *      consecutive duplicates; a duplicated second half (mirror-site quirk)
 *      is dropped.
 *
 * "Visible" is computed-style filtering, NOT element presence — NU-adjacent
 * pages hide decoy text with display:none, visibility:hidden, opacity:0,
 * zero-size boxes and JS honeypots (roadmap hazard). This payload is the
 * piece most likely to break when a host changes markup: keep the Kotlin
 * parser (parseChapterExtract) tolerant and the FAIL path graceful.
 */
/**
 * [wanted] = the chapter label the caller expects ("v2c29", "c29", "第29話")
 * from the NU release row / bulk job / probe harvest. On cover-page hosts
 * (verified: stabbingwithasyringe.home.blog — the NU link lands on the
 * BLOG INDEX whose entries are chapter links) the script returns a
 * directive instead of an extract: COVER-DIRECT:<url> (the wanted chapter
 * is linked here), COVER-TOC:<url> (site ToC link — match there), or COVER
 * (cover detected, unresolvable). Callers follow at most two hops; a real
 * chapter page with an embedded ToC falls through to normal extraction
 * (self-link guard + body-volume guard).
 */
/**
 * Manual ToC harvest for the reader's ⋮ menu (Wayne, 2026-10-20): collect
 * chapter-looking links (numeric dialects + worded ordinals) and <select>
 * chapter dropdowns from WHATEVER page the WebView currently shows.
 * [seriesTitle] scopes a MULTI-SERIES blog's index to one series' entries
 * (live-caught: stabbingwithasyringe lists 3 series on one page — an
 * unscoped harvest made "Prologue 1" open another series' chapter); when
 * no anchor carries the title (dedicated per-series ToC pages) the full
 * list is kept and flagged unscoped. Returns {s: scoped, c: [[t,u],…]}.
 */
fun readerTocHarvestJs(seriesTitle: String = ""): String = """
(function() {
  try {
    var out = [], seen = {};
    function norm(t) { return (t || '').replace(/\s+/g, ' ').trim().toLowerCase(); }
    function add(t, u) {
      t = (t || '').replace(/\s+/g, ' ').trim();
      if (!t || t.length > 90 || !u || seen[u]) return;
      seen[u] = 1;
      out.push({ t: t, u: u });
    }
    var pat = /v\d+\s*c\d+|ch(apter)?[\s.\-]*\d+|第\d+話|(prologue|interlude|epilogue|extra|omake)/i;
    var links = document.querySelectorAll('a[href]');
    for (var i = 0; i < links.length && out.length < 2000; i++) {
      var a = links[i];
      var at = (a.innerText || '').trim();
      if (!at || !pat.test(at)) continue;
      if (a.origin !== location.origin) continue;
      add(at, a.href);
    }
    var opts = document.querySelectorAll('select option[value]');
    for (var s = 0; s < opts.length && out.length < 2000; s++) {
      var o = opts[s], v = o.getAttribute('value') || '';
      if (!/^https?:/i.test(v) && !v.startsWith('/')) continue;
      add(o.textContent, new URL(v, location.href).href);
    }
    var title = norm('${seriesTitle.replace("\\", "\\\\").replace("'", "\\'")}');
    if (title.length > 3) {
      var scoped = out.filter(function(e) { return norm(e.t).indexOf(title) >= 0; });
      if (scoped.length > 0) {
        return JSON.stringify({ s: true, c: scoped.map(function(e) { return [e.t, e.u]; }) });
      }
    }
    return JSON.stringify({ s: false, c: out.map(function(e) { return [e.t, e.u]; }) });
  } catch (e) { return '{}'; }
})()
"""

/** Decode readerTocHarvestJs's JSON: chapters + whether the series-title
 *  scoping applied (unscoped lists may mix a multi-series blog's entries). */
fun parseReaderToc(raw: String): Pair<List<Release>, Boolean> {
    val body = unwrapJsResult(raw).trim()
    if (body.isBlank() || !body.startsWith("{")) return emptyList<Release>() to false
    return runCatching {
        val o = org.json.JSONObject(body)
        val arr = o.optJSONArray("c") ?: org.json.JSONArray()
        val chapters = (0 until arr.length()).mapNotNull { i ->
            val pair = arr.optJSONArray(i) ?: return@mapNotNull null
            val t = pair.optString(0).trim()
            val u = pair.optString(1).trim()
            if (t.isNotEmpty() && u.startsWith("http")) {
                Release(chapter = t, group = "", date = "", url = u)
            } else null
        }
        chapters to o.optBoolean("s", false)
    }.getOrDefault(emptyList<Release>() to false)
}

fun chapterExtractJs(wanted: String = "", seriesTitle: String = ""): String = """
(function() {
  try {
    function nodeVisible(el) {
      var p = el;
      while (p && p !== document.body) {
        var s = window.getComputedStyle(p);
        if (s.display === 'none') return false;
        if (s.visibility === 'hidden') return false;
        if (parseFloat(s.opacity) <= 0.01) return false;
        p = p.parentElement;
      }
      var r = el.getBoundingClientRect();
      return r.height > 0;
    }

    // --- anti-scrape decode (font glyph substitution) ----------------------
    // Some hosts (verified: storyseedling.com) ship a custom font whose Latin
    // letters are drawn from Kangxi-radical codepoints — the page RENDERS
    // English while the DOM text is radical gibberish, which defeats every
    // DOM extractor. Their font maps U+2F42..U+2F5B -> A-Z and U+2F5C..U+2F75
    // -> a-z (glyph outlines are identical to the font's own ASCII; only the
    // dotted i/j compare differently). Decode ONLY when that 52-codepoint
    // ladder dominates the text — genuine Chinese prose lives in the wide CJK
    // ranges, not this block.
    function decodeSubstituted(t) {
      var ladder = 0;
      for (var i = 0; i < t.length; i++) {
        var c = t.charCodeAt(i);
        if (c >= 0x2F42 && c <= 0x2F75) ladder++;
      }
      if (ladder < t.length * 0.3) return t;
      return t.replace(/[\u2F42-\u2F75]/g, function(ch) {
        var c = ch.charCodeAt(0);
        return c <= 0x2F5B
          ? String.fromCharCode(65 + c - 0x2F42)
          : String.fromCharCode(97 + c - 0x2F5C);
      });
    }

    $CIPHER_DERIVE_JS

    // Watermark tokens the same hosts embed inside paragraphs ("cls1b7bcfa179…").
    function cleanText(t) {
      var cleaned = t.replace(/cls[0-9a-f]{12,}/g, ' ');
      // Foxteller (verified 2026-10-15) injects its anti-piracy line INTO
      // every content paragraph — strip the sentence, keep the prose.
      cleaned = cleaned.replace(/Please contact us at [\w.@-]+ if you see this on (another|any other) site\.?\s*\d+(\.\d+)?/gi, ' ');
      cleaned = cleaned.replace(/\s+/g, ' ').trim();
      return decodeSubstituted(cleaned);
    }

    // --- cover-page detection (Wayne, 2026-10-20) -------------------------
    // Some hosts point the NU chapter link at the site INDEX, not the
    // chapter (verified: stabbingwithasyringe.home.blog — post list whose
    // entry titles carry the chapter key, e.g. "Series v2c29"). Detection:
    // >=5 same-host chapter-pattern anchors. A real chapter page with an
    // embedded ToC also has many chapter anchors — the self-link guard and
    // the body-volume fallthrough keep it extracting normally.
    function parseKey(t) {
      var m = t.match(/v(\d+)\s*c(\d+)/i); if (m) return m[1] + ':' + m[2];
      m = t.match(/(?:ch(?:apter)?[\s.\-]*|(^|\s)c)(\d+)/i); if (m) return ':' + m[2];
      m = t.match(/第(\d+)話/); if (m) return ':' + m[1];
      // Worded ordinals (Wayne 2026-10-20: cover hosts list "Prologue 1/2/3"
      // entries that no numeric pattern matches — "chapter detection fails").
      m = t.match(/(prologue|interlude|epilogue|extra|omake)\s*[#.\-]?\s*(\d+)?/i);
      if (m) return m[1].substring(0, 2).toUpperCase() + ':' + (m[2] || '0');
      return null;
    }
    function keyMatches(want, k) {
      if (want === k) return true;
      // bare "c29" wanted matches the site's vol'd "v2c29" key
      return want.charAt(0) === ':' && k.charAt(0) !== ':' && k.endsWith(want);
    }
    var links = document.querySelectorAll('a[href]');
    var chapAnchors = [];
    var seenHref = {};
    var tocLink = '';
    for (var li = 0; li < links.length; li++) {
      var a = links[li];
      if (!nodeVisible(a)) continue;
      if (a.origin !== location.origin) continue;
      var href = a.href;
      if (seenHref[href]) continue;
      seenHref[href] = 1;
      var at = (a.innerText || '').trim();
      var k = parseKey(at);
      if (k) chapAnchors.push({ k: k, href: href, t: at });
      if (!tocLink && /^(toc|table of contents|index|目次|chapters?|novel index)$/i.test(at)) tocLink = href;
    }
    if (chapAnchors.length >= 5) {
      // Multi-series norm (Wayne, 2026-10-20): MOST translator sites host
      // several series — when any anchor carries this series' title, ONLY
      // those anchors are eligible for following, so a bare key match can
      // never jump to another series' chapter.
      function normTitle(t) {
        return (t || '').toLowerCase().replace(/[^a-z0-9\u3040-\u30ff\u4e00-\u9fff]/g, '');
      }
      var seriesNorm = normTitle('${seriesTitle.replace("\\", "\\\\").replace("'", "\\'")}');
      if (seriesNorm.length > 3) {
        var scopedAnchors = chapAnchors.filter(function(e) {
          return normTitle(e.t).indexOf(seriesNorm) >= 0;
        });
        if (scopedAnchors.length > 0) chapAnchors = scopedAnchors;
      }
      var wantedText = '${wanted.replace("\\", "\\\\").replace("'", "\\'")}';
      var wantedKey = parseKey(wantedText);
      var wantedRaw = wantedText.toLowerCase().replace(/\s+/g, ' ').trim();
      for (var wi = 0; wi < chapAnchors.length; wi++) {
        var ca = chapAnchors[wi];
        if (ca.href === location.href) continue;
        var keyHit = wantedKey && keyMatches(wantedKey, ca.k);
        // Substring fallback: label dialects no pattern covers ("Prologue",
        // named extras) match by containment on a cover page.
        var textHit = !keyHit && wantedRaw.length > 2 &&
          ca.t.toLowerCase().replace(/\s+/g, ' ').indexOf(wantedRaw) >= 0;
        if (keyHit || textHit) {
          return 'COVER-DIRECT:' + ca.href;
        }
      }
      // No wanted match here (or it's this page's own ToC self-link):
      // without real body volume this is a pure index — resolve via ToC.
      var idxP = document.querySelectorAll('p'), idxChars = 0;
      for (var ip = 0; ip < idxP.length; ip++) {
        if (!nodeVisible(idxP[ip])) continue;
        var ipt = (idxP[ip].innerText || '').trim();
        if (ipt.length > 1) idxChars += ipt.length;
      }
      if (idxChars < 1200) {
        if (tocLink && tocLink !== location.href) return 'COVER-TOC:' + tocLink;
        return 'COVER';
      }
      // else: real prose on the page (chapter with embedded ToC) — extract.
    }

    // --- announcement/landing hop (ported from Wayne's lnpipe 2026-10-20) --
    // Many NU release URLs are a dated ANNOUNCEMENT post ("Chapter 12 is up
    // — click here") whose article body links the real permalink. Only runs
    // when extraction is about to fail, so a real chapter never re-routes;
    // plenty-of-narrative pages stay put (sidebar ToCs can't trigger it).
    function isDatedPost(u) {
      try { return /\/20\d{2}\/\d{1,2}\/\d{1,2}\//.test(new URL(u).pathname); }
      catch (e) { return false; }
    }
    function landingHop() {
      if (!best) return 'FAIL';
      var ps = best.querySelectorAll('p'), chars = 0, longs = 0;
      for (var lh = 0; lh < ps.length; lh++) {
        if (!nodeVisible(ps[lh])) continue;
        var lt = (ps[lh].innerText || '').trim();
        if (lt.length > 1) { chars += lt.length; if (lt.length > 80) longs++; }
      }
      // Real chapter that failed for another reason — don't rewrite it.
      if (longs >= 18 && chars >= 5000) return 'FAIL';
      var announcePath = /\/(announcements?|releases?|updates?|status|news)(\/|$)/i.test(location.pathname);
      var head = (best.innerText || '').slice(0, 1200);
      var announceText = /(here you go|here it is|click here|read (it )?here|is (now )?up|now available|new chapter|thanks for waiting|posted)/i.test(head);
      var hereDated = isDatedPost(location.href);
      var cands = best.querySelectorAll('a[href]');
      for (var lc = 0; lc < cands.length; lc++) {
        var la = cands[lc];
        if (!nodeVisible(la)) continue;
        if (la.origin !== location.origin) continue;
        var lText = (la.innerText || '').trim();
        // Strong candidate: a chapter-pattern label, or a dated-post page
        // pointing at an undated permalink.
        var strong = parseKey(lText) || (hereDated && !isDatedPost(la.href));
        if (strong && (chars < 1500 || announcePath || announceText)) {
          return 'COVER-DIRECT:' + la.href;
        }
      }
      return 'FAIL';
    }

    // --- known content containers (field-verified platform selectors) -------
    // Comment sections can hold more paragraph text than the chapter itself
    // (Wuxiaworld et al), and ancestor scoring alone would then crown the
    // comments. When a known platform selector matches AND holds real visible
    // paragraph text, start there; scoring stays the fallback for everything
    // else. Selectors verified per platform reports 2026-10-11; validate any
    // new host before adding to this list.
    var KNOWN_SELECTORS = ['div.js-episode-body', 'div.chapter-inner', '.cha-words', '.novel-content',
      '#chr-content', '.chapter-content', '#chapter-c', '.post-content',
      '.entry-content', 'article', '#novelBody',
      // JP natives (deterministic beats ancestor scoring; the shared image
      // walk rides whichever container wins): Syosetu + R18 sisters,
      // Kakuyomu's site body.
      '#novel_honbun', 'div.widget-episodeBody'];
    var best = null;
    for (var ks = 0; ks < KNOWN_SELECTORS.length && !best; ks++) {
      var cand = document.querySelector(KNOWN_SELECTORS[ks]);
      if (!cand) continue;
      var cps = cand.querySelectorAll('p');
      var cvis = 0, cchars = 0;
      for (var vp = 0; vp < cps.length; vp++) {
        if (!nodeVisible(cps[vp])) continue;
        var cvt = (cps[vp].innerText || '').trim();
        if (cvt.length > 1) { cvis++; cchars += cvt.length; }
      }
      if (cvis >= 3 && cchars >= 400) best = cand;
      // Image-led chapters (illustration episodes, manga-style posts) have
      // little text: a known container with real illustrations still wins.
      else if (cvis >= 1 && cand.querySelectorAll('img').length >= 2) best = cand;
    }

    if (!best) {
      var paras = Array.prototype.slice.call(document.querySelectorAll('p'));
      var score = new Map();
      paras.forEach(function(p) {
        if (!nodeVisible(p)) return;
        var text = (p.innerText || '').trim();
        if (text.length < 2) return;
        var climb = p.parentElement;
        while (climb && climb !== document.body) {
          var cur = score.get(climb) || 0;
          score.set(climb, cur + text.length);
          climb = climb.parentElement;
        }
      });
      var bestScore = 0;
      score.forEach(function(v, el) { if (v > bestScore) { best = el; bestScore = v; } });
      if (!best || bestScore < 400) return landingHop();
    }

    // Title first: letters that appear ONLY in the heading (uppercase C, O…)
    // must be part of the derivation sample or they decode as gaps.
    var title = '';
    var heads = best.querySelectorAll('h1, h2, h3, h4');
    for (var h = 0; h < heads.length; h++) {
      if (nodeVisible(heads[h])) {
        title = cleanText(heads[h].innerText || '');
        break;
      }
    }

    function isJunk(t, p) {
      // Anti-piracy watermarks (decoded or plain) are not prose.
      if (/content is owned by/i.test(t)) return true;
      // Foxteller's watermark ends with a bare chapter id; after the
      // sentence strip in cleanText only the number survives.
      if (/^\d{1,6}$/.test(t)) return true;
      if (t.length > 60) return false;
      // Footer/copyright lines travel inside the content container sometimes.
      // (Single backslash on purpose: Kotlin raw strings don't process
      // escapes, so this must arrive at the WebView as a JS unicode escape.)
      if (t.indexOf('\u00A9') >= 0 || t.indexOf('(c)') === 0) return true;
      var low = t.toLowerCase();
      if (low.indexOf('no comments') === 0) return true; // comments teaser + its ad text
      var junk = ['prev', 'next', 'previous', 'next chapter', 'prev chapter',
        'table of contents', 'advertisement', 'ads', 'click here', 'comments',
        'chapter list', 'join discord', 'patreon', 'donate', 'to be continued',
        '<< prev', 'next >>', 'home', 'index'];
      for (var i = 0; i < junk.length; i++) if (low === junk[i]) return true;
      // A short paragraph that is basically one link = navigation.
      var links = p.querySelectorAll('a');
      if (links.length && t.length < 30) {
        var linkText = 0;
        links.forEach = Array.prototype.forEach; // NodeList safety
        Array.prototype.forEach.call(links, function(a) { linkText += (a.innerText || '').length; });
        if (linkText >= t.length * 0.8) return true;
      }
      return false;
    }

    var out = [];
    // Inline illustrations (all sites), harvested in the SAME walk that
    // emits paragraphs so anchors match the emitted index — a separate
    // walk would count junk/short paragraphs that get dropped below and
    // drift every anchor after them (Syosetu illustration <p>s are exactly
    // that: image-only paragraphs produce no text). Guards: absolute URLs,
    // no data:/1px/emoji-sized, lazy attrs honored (Wayne's 2026-10-18
    // "every site's epub captures images").
    var imgs = [];
    function imgOf(nd) {
      // An anchor wrapping the img whose href IS an image wins over the src
      // (AlphaPolis: story_image/<id>/<uuid> serves openly while the src's
      // /pc/ variant is CloudFront-blocked; extension-gated so lightbox
      // PAGE links on other hosts are never mistaken for the image).
      var u = '';
      var ia = nd.closest ? nd.closest('a[href]') : null;
      if (ia) {
        var ah = ia.getAttribute('href') || '';
        if (/\.(jpe?g|png|webp|gif)(\?|$)/i.test(ah)) u = ah;
      }
      if (!u) u = nd.getAttribute('src') || nd.getAttribute('data-src') ||
        nd.getAttribute('data-original') || nd.getAttribute('data-lazy-src') ||
        (nd.getAttribute('srcset') || '').split(',')[0].trim().split(' ')[0] || '';
      if (!u || u.indexOf('data:') === 0) return null;
      if (u.indexOf('//') === 0) u = 'https:' + u;
      else if (u.indexOf('/') === 0) u = location.protocol + '//' + location.host + u;
      var w = nd.naturalWidth || nd.width || 0;
      if (w && w < 60) return null;
      return u;
    }
    function emitParagraph(p) {
      if (!nodeVisible(p)) return;
      // Anti-scrape decoys (verified: foxteller 2026-10-15): position:fixed
      // spans injected into EVERY paragraph — watermark chaff plus the
      // chapter's first sentence as filler. innerText includes them; real
      // prose is never position:fixed, so strip those descendants first.
      var t;
      var all = p.querySelectorAll('*');
      var fixedIdx = [];
      for (var fi = 0; fi < all.length; fi++) {
        try {
          if (window.getComputedStyle(all[fi]).position === 'fixed') fixedIdx.push(fi);
        } catch (e) {}
      }
      if (fixedIdx.length) {
        var clone = p.cloneNode(true);
        var cloneAll = clone.querySelectorAll('*');
        for (var k = fixedIdx.length - 1; k >= 0; k--) {
          var victim = cloneAll[fixedIdx[k]];
          if (victim.parentNode) victim.parentNode.removeChild(victim);
        }
        t = cleanText(clone.textContent || '');
      } else {
        t = cleanText(p.innerText || '');
      }
      // Illustration-only paragraphs (Syosetu's <p><a><img></a></p>): emit
      // the image at this position, not an empty text slot.
      var pImgs = Array.prototype.slice.call(p.querySelectorAll('img')).map(imgOf).filter(Boolean);
      if (t.length < 2 || isJunk(t, p)) {
        pImgs.forEach(function(u) { imgs.push([out.length, u]); });
        return;
      }
      if (out.length && out[out.length - 1] === t) return; // consecutive repeat
      out.push(t);
      pImgs.forEach(function(u) { imgs.push([out.length, u]); });
    }
    // Document-order walk at every depth: <p> emits text (+its inline imgs
    // after it); bare <img> children (figure illustrations, host quirks)
    // anchor at the current position; anything else recurses.
    (function walkEl(el) {
      Array.prototype.slice.call(el.children).forEach(function(child) {
        if (child.tagName === 'IMG') {
          var u = imgOf(child);
          if (u) imgs.push([out.length, u]);
        } else if (child.tagName === 'P') {
          emitParagraph(child);
        } else {
          walkEl(child);
        }
      });
    })(best);

    // Built-in ladder didn't fully decode (rotation, PUA ladder, partial
    // substitution)? Derive the cipher from the page's own fonts and re-decode.
    // The sample includes the title so heading-only letters map too.
    var joined = out.join(' ') + ' ' + title;
    var stats = suspiciousCount(joined);
    if (out.length >= 3 && stats.sus / Math.max(1, stats.len) >= 0.08 && stats.cjk / Math.max(1, stats.len) < 0.15) {
      var probe = best.querySelector('p') || best;
      var derived = deriveCipher(joined, probe);
      if (derived) {
        out = out.map(function(t) {
          return t.replace(/[\s\S]/g, function(c) { return derived[c] || c; });
        }).filter(function(t) { return !isJunk(t, null); });
      }
    }

    // Mirror sites sometimes repeat the whole chapter: drop an identical tail.
    var half = Math.floor(out.length / 2);
    if (out.length % 2 === 0 && half >= 4) {
      var dup = true;
      for (var i = 0; i < half; i++) {
        if (out[i] !== out[half + i]) { dup = false; break; }
      }
      if (dup) out = out.slice(0, half);
    }

    // Image-led chapters pass with fewer text lines when illustrations
    // were harvested (pure-text chapters keep the 3-paragraph floor).
    if (out.length < 3 && imgs.length === 0) return landingHop();

    // Title was extracted before the paragraphs (it feeds the derivation
    // sample); decode it with the derived map when one exists.
    if (typeof derived !== 'undefined' && derived) {
      title = title.replace(/[\s\S]/g, function(c) { return derived[c] || c; });
    }

    var json = out.map(function(t) { return JSON.stringify(t); }).join(',');
    var imgJson = imgs.map(function(p2) { return '[' + p2[0] + ',' + JSON.stringify(p2[1]) + ']'; }).join(',');
    return '{"title":' + JSON.stringify(title) + ',"paragraphs":[' + json + ']' +
      (imgs.length ? ',"images":[' + imgJson + ']' : '') + '}';
  } catch (e) {
    return 'FAIL:' + (e && e.message ? e.message : String(e)).slice(0, 120) + ' @out' + (typeof out !== 'undefined' ? out.length : -1);
  }
})();
""".trimIndent()

/**
 * Web-view DOM decode (runs in ReaderScreen's WEB mode on substituted pages).
 * Derives the cipher with the shared code, then rewrites the page's live text
 * nodes in place — after this, select/copy/share from the web view yields
 * real text instead of radical gibberish. Read-only elsewhere: returns
 * 'DECODED:<n>' when it rewrote <n> nodes, 'SKIP' when nothing to do,
 * 'FAIL' when derivation had no confident map.
 */
fun decodeDomJs(): String = """
(function() {
  try {
    $CIPHER_DERIVE_JS

    // Where is the chapter text? Same scoring as the extractor, minimised.
    function visible(el) {
      var p = el;
      while (p && p !== document.body) {
        var s = window.getComputedStyle(p);
        if (s.display === 'none' || s.visibility === 'hidden' || parseFloat(s.opacity) <= 0.01) return false;
        p = p.parentElement;
      }
      return el.getBoundingClientRect().height > 0;
    }
    var score = new Map();
    document.querySelectorAll('p').forEach(function(p) {
      if (!visible(p)) return;
      var t = (p.innerText || '').trim();
      if (t.length < 2) return;
      var climb = p.parentElement;
      while (climb && climb !== document.body) {
        score.set(climb, (score.get(climb) || 0) + t.length);
        climb = climb.parentElement;
      }
    });
    var probe = document.querySelector('p');
    if (!probe) return 'SKIP';
    // Derivation sample = visible paragraphs + headings: letters that appear
    // only in the chapter title must be in the sample or they decode as gaps.
    var sample = probe.innerText;
    document.querySelectorAll('p, h1, h2, h3, h4').forEach(function(p) {
      if (visible(p)) sample += ' ' + p.innerText;
    });
    sample = sample.replace(/cls[0-9a-f]{12,}/g, ' ');

    var stats = suspiciousCount(sample);
    if (stats.len === 0 || stats.sus / stats.len < 0.08) return 'SKIP';
    if (stats.cjk / stats.len >= 0.15) return 'SKIP';
    var map = deriveCipher(sample, probe);
    if (!map) return 'FAIL';

    // Built-in ladder first: it is exact for known hosts.
    var builtIn = {};
    for (var c = 0x2F42; c <= 0x2F5B; c++) builtIn[String.fromCharCode(c)] = String.fromCharCode(65 + c - 0x2F42);
    for (var c2 = 0x2F5C; c2 <= 0x2F75; c2++) builtIn[String.fromCharCode(c2)] = String.fromCharCode(97 + c2 - 0x2F5C);

    var useBuiltIn = false;
    if (stats.sus / Math.max(1, stats.len) >= 0.3) {
      // dominance of exactly the built-in ladder range -> prefer it
      var ladderHits = 0;
      for (var i = 0; i < sample.length; i++) {
        var cp = sample.charCodeAt(i);
        if (cp >= 0x2F42 && cp <= 0x2F75) ladderHits++;
      }
      useBuiltIn = ladderHits >= stats.sus * 0.9;
    }
    var active = useBuiltIn ? builtIn : map;

    var rewritten = 0;
    function walk(node) {
      if (node.nodeType === Node.TEXT_NODE) {
        var t = node.nodeValue;
        if (!t) return;
        if (/^cls[0-9a-f]{12,}$/.test(t.trim())) { node.nodeValue = ''; return; }
        var out = '';
        var changed = false;
        for (var i = 0; i < t.length; i++) {
          var rep = active[t[i]];
          if (rep !== undefined) { out += rep; changed = true; }
          else out += t[i];
        }
        if (changed) { node.nodeValue = out; rewritten++; }
      } else if (node.nodeType === Node.ELEMENT_NODE) {
        var tag = node.tagName;
        if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'CANVAS') return;
        for (var c = 0; c < node.childNodes.length; c++) walk(node.childNodes[c]);
      }
    }
    walk(document.body);
    return rewritten > 0 ? 'DECODED:' + rewritten : 'SKIP';
  } catch (e) {
    return 'FAIL';
  }
})();
""".trimIndent()

/**
 * Image-chapter detection: some hosts render whole chapters as sequential
 * image slices (copy-paste-proof light novels). True when the content area
 * is image-dense and nearly textless — ReaderScreen then says so and points
 * at the web view, which renders images natively. Honors data-src/lazy-src
 * (lazy loaders) so the count reflects what would actually display.
 */
fun imageChapterJs(): String = """
(function() {
  try {
    function visible(el) {
      var p = el;
      while (p && p !== document.body) {
        var s = window.getComputedStyle(p);
        if (s.display === 'none' || s.visibility === 'hidden') return false;
        p = p.parentElement;
      }
      var r = el.getBoundingClientRect();
      return r.width > 0 && r.height > 0;
    }
    var area = document.querySelector('article, .chapter-content, .entry-content, #chapter-content')
      || document.body;
    var imgs = Array.prototype.slice.call(area.querySelectorAll('img'));
    var usable = imgs.filter(function(img) {
      var src = img.src || img.getAttribute('data-src') || img.getAttribute('data-lazy-src') || '';
      return src.startsWith('http') || src.startsWith('data:image');
    });
    var alpha = (area.innerText || '').replace(/[^a-zA-Z]/g, '').length;
    return (usable.length >= 3 && alpha < 300) ? 'IMAGES:' + usable.length : 'NO';
  } catch (e) {
    return 'NO';
  }
})();
""".trimIndent()

/**
 * ToC-first bulk (3.3.1, user design): harvest same-host chapter links from
 * the CURRENTLY LOADED page — most translator sites embed their chapter list
 * (a ToC panel, a chapter <select>, prev/next bars) right on the chapter
 * page. Runs on pages the bulk downloader already loaded, so discovery costs
 * ZERO extra requests; NU-routed chapters can then resolve to direct URLs
 * without ever touching novelupdates.com. Returns [{u,t}] of same-host links
 * whose text looks like a chapter label.
 */
fun tocHarvestJs(parentScope: Boolean = true): String = """
(function() {
  try {
    var out = [];
    var seen = {};
    function looksLikeChapter(t) {
      return /(?:^|\s)(?:v(?:ol(?:ume)?)?\.?\s*\d+\s*[-:]\s*)?c(?:h(?:apter)?)?\.?\s*\d+|ep(?:isode)?\.?\s*\d+|^\s*#\s*\d+|^[»›→»]+|^‹+\d*|^[«‹←]+|next\s+chapter|previous\s+chapter|^\d{1,4}\s*[-–—.:)]\s*\S/i.test(t);
    }
    // SERIES SCOPING: chapter links must live under the current series' path —
    // custom platforms (verified: lightnovelstranslations.com) show OTHER
    // series' chapter links in sidebars, and number-only matching would
    // resolve chapters to the wrong book. Chapter pages scope to the PARENT
    // directory (siblings); a series/listing page scopes to its OWN path.
    var PARENT_SCOPE = $parentScope; // interpolated by Kotlin (true/false)
    var segs = location.pathname.split('/').filter(Boolean);
    var scope = null;
    if (segs.length >= 2) {
      var take = PARENT_SCOPE ? Math.max(1, segs.length - 1) : segs.length;
      scope = '/' + segs.slice(0, take).join('/') + '/';
    }
    function inScope(href) {
      if (!scope) return true;
      try {
        var u = new URL(href, location.href);
        return u.host === location.host && (u.pathname + '/').indexOf(scope) === 0;
      } catch (e) { return false; }
    }
    var here = location.protocol + '//' + location.host;
    // 1) same-host anchor links whose text looks like a chapter label
    document.querySelectorAll('a[href]').forEach(function(a) {
      var href = a.href || '';
      if (href.indexOf('#') === href.length - 1) return;
      if (!inScope(href)) return;
      var t = (a.textContent || '').replace(/\s+/g, ' ').trim();
      if (!t || t.length > 80) return;
      if (!looksLikeChapter(t)) return;
      if (seen[href]) return;
      seen[href] = true;
      out.push({ u: href, t: t.slice(0, 80) });
      if (out.length >= 500) return;
    });
    // 2) chapter DROPDOWNS — the standard WordPress translator ToC: a <select>
    //    (name/id mentions chapter, or most options look like chapter labels)
    //    whose option values are chapter permalinks. Verified live on
    //    asianhobbyist.com: <select name="chapter"> with 281 options.
    document.querySelectorAll('select').forEach(function(sel) {
      var ident = ((sel.name || '') + ' ' + (sel.id || '') + ' ' + (sel.className || '')).toLowerCase();
      var opts = sel.options;
      if (ident.indexOf('chap') === -1) {
        if (opts.length < 5) return;
        var chapish = 0;
        var check = Math.min(opts.length, 20);
        for (var i = 0; i < check; i++) {
          if (looksLikeChapter((opts[i].textContent || '').trim())) chapish++;
        }
        if (chapish < check * 0.6) return;
      }
      for (var j = 0; j < opts.length; j++) {
        var o = opts[j];
        var t = (o.textContent || '').replace(/\s+/g, ' ').trim();
        var v = (o.value || '').trim();
        if (!t || t.length > 80 || !v) continue;
        var abs = '';
        try { abs = new URL(v, location.href).href; } catch (e) { continue; }
        if (abs.indexOf('http') !== 0) continue;
        if (!inScope(abs)) continue;
        if (seen[abs]) continue;
        seen[abs] = true;
        out.push({ u: abs, t: t.slice(0, 80) });
        if (out.length >= 500) return;
      }
    });
    return JSON.stringify(out);
  } catch (e) {
    return '[]';
  }
})();
""".trimIndent()

/**
 * Foxteller chapter resolver (2026-10-15, Wayne's broken series): NU's
 * release rows for this host link the site's /rss/<id> URL, which renders
 * the series OVERVIEW, never the chapter. The rendered page always links
 * the latest chapter (/novel/<id>/<n>/slug) and the series base derives
 * from any novel link; the series page lists every chapter URL.
 *
 * Returns "DIRECT:<url>" when the wanted ordinal is linked, "BASE:<url>"
 * when only the series base is derivable (navigate there and re-run), or
 * "" while the page is still booting (SPA behind Cloudflare).
 */
fun foxtellerRssResolveJs(ordinal: Int): String = """
(function() {
  var links = Array.prototype.slice.call(document.querySelectorAll('a[href*="/novel/"]'));
  var want = new RegExp('/novel/\\d+/${ordinal}/');
  for (var i = 0; i < links.length; i++) {
    var h = links[i].href || '';
    if (want.test(h)) return 'DIRECT:' + h;
  }
  for (var j = 0; j < links.length; j++) {
    var mm = (links[j].href || '').match(/^https?:\/\/[^\/]+\/novel\/\d+/);
    if (mm) return 'BASE:' + mm[0];
  }
  return '';
})()
""".trimIndent()
