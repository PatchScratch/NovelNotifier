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
      document.querySelectorAll('a[href*="/series/"]').forEach(function(a) {
        var title = (a.textContent || '').replace(/\s+/g, ' ').trim();
        var href = a.href || '';
        if (!title || isJunkTitle(title)) return;
        if (!/\/series\/[a-z0-9-]+/i.test(href)) return;
        var chapter = '';
        var lastReadCh = '';
        var lastReadUrl = '';
        var img = '';
        var hasListMark = false;
        var row = a.closest ? a.closest('tr') : a.parentElement;
        if (row) {
          var statusA = row.querySelector('[id^="mystatus"], a[id*="mystatus"], #mystatus');
          var latestA = row.querySelector('[id^="mylatest"], a[id*="mylatest"]');
          if (latestA) chapter = (latestA.textContent || '').replace(/\s+/g, ' ').trim();
          if (statusA) {
            lastReadCh = (statusA.textContent || '').replace(/\s+/g, ' ').trim();
            lastReadUrl = statusA.getAttribute('href') || '';
          }
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
        novels.push(title + '===' + (chapter || 'No chapter info') + '===' + href + '===' + img + '===' + lastReadCh + '===' + lastReadUrl);
      });
      // NU shows the personal RSS feed links on this page — the releases feed
      // and the read-activity feed (type=read). Grab every feed-ish anchor so
      // the app can configure chapter checks AND cross-device read sync.
      var feeds = [];
      document.querySelectorAll('a[href*="rss"], a[href*="RSS"], a[href*="/feed"]').forEach(function(a) {
        if (a.href && /^https?:/i.test(a.href) && feeds.indexOf(a.href) === -1) feeds.push(a.href);
      });
      return lists.join('|||') + ':::NOVELS:::' + novels.join('|||') + ':::RSS:::' + feeds.join('|||');
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
        type: text('#showtype, #edittype'),
        status: text('#editstatus, #showstatus'),
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
fun chapterExtractJs(): String = """
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
    // ranges, not this block. If a host rotates its font, this is the single
    // table to update (derive the new one by outline comparison against the
    // font's ASCII glyphs; see ROADMAP 3.2 notes).
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

    // Watermark tokens the same hosts embed inside paragraphs ("cls1b7bcfa179…").
    function cleanText(t) {
      var cleaned = t.replace(/cls[0-9a-f]{12,}/g, ' ');
      cleaned = cleaned.replace(/\s+/g, ' ').trim();
      return decodeSubstituted(cleaned);
    }

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
    var best = null, bestScore = 0;
    score.forEach(function(v, el) { if (v > bestScore) { best = el; bestScore = v; } });
    if (!best || bestScore < 400) return 'FAIL';

    function isJunk(t, p) {
      if (t.length > 60) return false;
      // Footer/copyright lines travel inside the content container sometimes.
      if (t.indexOf('\\u00A9') === 0 || t.indexOf('\\u00A9') > 0 || t.indexOf('(c)') === 0) return true;
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
    Array.prototype.slice.call(best.querySelectorAll('p')).forEach(function(p) {
      if (!nodeVisible(p)) return;
      var t = cleanText(p.innerText || '');
      if (t.length < 2 || isJunk(t, p)) return;
      if (out.length && out[out.length - 1] === t) return; // consecutive repeat
      out.push(t);
    });

    // Mirror sites sometimes repeat the whole chapter: drop an identical tail.
    var half = Math.floor(out.length / 2);
    if (out.length % 2 === 0 && half >= 4) {
      var dup = true;
      for (var i = 0; i < half; i++) {
        if (out[i] !== out[half + i]) { dup = false; break; }
      }
      if (dup) out = out.slice(0, half);
    }

    if (out.length < 3) return 'FAIL';

    var title = '';
    var heads = best.querySelectorAll('h1, h2, h3, h4');
    for (var h = 0; h < heads.length; h++) {
      if (nodeVisible(heads[h])) {
        title = cleanText(heads[h].innerText || '');
        break;
      }
    }

    var json = out.map(function(t) { return JSON.stringify(t); }).join(',');
    return '{"title":' + JSON.stringify(title) + ',"paragraphs":[' + json + ']}';
  } catch (e) {
    return 'FAIL';
  }
})();
""".trimIndent()
