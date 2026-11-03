package com.patchscratch.novelnotifier

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections

/**
 * DESKTOP COMPANION LAN API (Wayne's approved desktop vision, 2026-10-31 —
 * management-first): the phone stays the source of truth and the desktop is
 * a remote control. Every mutation this server performs flows through the
 * EXISTING locked store paths — LocalLibraryStore.mutateIndex-backed
 * functions and CategoryStore's move-only model — so a remote session
 * structurally cannot bypass the data-loss hardening:
 *
 *   - no endpoint writes local_libraries.json or categories.json directly;
 *   - bulk deletion is impossible (single-book / single-series deletes only,
 *     in the locked v3.3 REMOVE/TRASH semantics);
 *   - Empty Trash does NOT exist over LAN — it stays a phone-Settings-only
 *     safety brake;
 *   - covers are served from the cache only (no epub is ever opened for a
 *     remote request).
 *
 * Auth: one bearer token per device (Settings → Desktop companion shows and
 * regenerates it). Requests without a constant-time-equal token get 401 and
 * are logged. The server binds all interfaces but serves exactly the
 * /api/v1/… paths below and nothing else.
 *
 * Contract (stable across releases; desktop companion targets it):
 *   GET  /api/v1/status               → device, appVersion, apiVersion, libraries
 *   GET  /api/v1/library/{id}         → shelves, assignments, series+volumes
 *   GET  /api/v1/cover/{bookId}       → cached cover bytes or 404
 *   POST /api/v1/shelf/create         {library, name}
 *   POST /api/v1/shelf/rename         {library, shelfId, name}
 *   POST /api/v1/shelf/delete         {library, shelfId}
 *   POST /api/v1/shelf/assign         {library, seriesIds[], shelfId|null}
 *   POST /api/v1/series/rename        {seriesId, name}
 *   POST /api/v1/series/description   {seriesId, description}
 *   POST /api/v1/series/merge         {fromId, toId}          (same library)
 *   POST /api/v1/series/moveBook      {bookId, seriesId|null} (null = new solo series)
 *   POST /api/v1/series/moveToLibrary {seriesId, toLibraryId} (index-level; files stay put)
 *   POST /api/v1/series/delete        {seriesId, mode: remove|trash}
 *   POST /api/v1/book/meta            {bookId, title?, author?, description?}
 *   POST /api/v1/book/delete          {bookId, mode: remove|trash}
 *   POST /api/v1/library/rename       {libraryId, name}
 *
 * Shelf assignments are keyed by SERIES id (the v3 migration re-keyed them);
 * shelves live in CategoryStore under "local:<libraryId>".
 */
object LanApi {
    const val PORT = 8790
    const val API_VERSION = 1
    private const val PREFS = "novel_prefs"
    private const val ENABLED = "lan_api_enabled"
    private const val TOKEN = "lan_api_token"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(ENABLED, false)

    /** The pairing token, generated on first use (43-char base64url). */
    fun token(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(TOKEN, null)?.let { return it }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val t = android.util.Base64.encodeToString(
            bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        )
        prefs.edit().putString(TOKEN, t).apply()
        return t
    }

    fun regenerateToken(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val t = android.util.Base64.encodeToString(
            bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        )
        prefs.edit().putString(TOKEN, t).apply()
        return t
    }

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(ENABLED, on).apply()
        val intent = Intent(context, LanApiService::class.java)
        if (on) context.startForegroundService(intent)
        else context.stopService(intent)
    }

    /** Non-loopback IPv4 addresses the server will be reachable on. */
    fun lanAddresses(context: Context): List<String> {
        val out = mutableListOf<String>()
        // Hold the wifi lock's MulticastLock? Not needed — plain enumeration.
        runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces()).forEach { nif ->
                if (!nif.isUp || nif.isLoopback) return@forEach
                Collections.list(nif.inetAddresses).forEach { addr ->
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        out.add(addr.hostAddress ?: return@forEach)
                    }
                }
            }
        }
        return out
    }
}

/**
 * The HTTP server. One instance per running service; request handlers run
 * on NanoHTTPD worker threads. Mutations serialize on [writeGate] because
 * CategoryStore's load→mutate→save cycle is not itself locked (the app UI
 * performs it from a single main-thread flow) — a remote session and a
 * phone tap must never interleave.
 */
class LanApiServer(private val ctx: Context) : NanoHTTPD("0.0.0.0", LanApi.PORT) {

    private val writeGate = Any()

    override fun serve(session: IHTTPSession): Response {
        val token = LanApi.token(ctx)
        // NanoHTTPD lowercases header names; scan defensively anyway.
        val authHeader = session.headers.entries
            .firstOrNull { it.key.equals("authorization", ignoreCase = true) }?.value
        val presented = authHeader
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)?.trim()
        if (presented == null || !MessageDigest.isEqual(token.toByteArray(), presented.toByteArray())) {
            DebugLog.log("LANAPI", "rejected ${session.method} ${session.uri} (bad or missing token)")
            return json(Response.Status.UNAUTHORIZED, JSONObject().put("ok", false).put("error", "unauthorized"))
        }
        val path = session.uri.trimEnd('/')
        return try {
            when {
                session.method == Method.GET && path == "/api/v1/status" -> status()
                session.method == Method.GET && path.startsWith("/api/v1/library/") ->
                    library(path.removePrefix("/api/v1/library/"))
                session.method == Method.GET && path.startsWith("/api/v1/cover/") ->
                    cover(path.removePrefix("/api/v1/cover/"))
                session.method == Method.POST && path.startsWith("/api/v1/") ->
                    post(path.removePrefix("/api/v1/"), body(session))
                else -> json(Response.Status.NOT_FOUND, JSONObject().put("ok", false).put("error", "not found"))
            }
        } catch (e: Exception) {
            DebugLog.log("LANAPI", "error on $path: ${e.javaClass.simpleName}: ${e.message}", e)
            json(Response.Status.INTERNAL_ERROR,
                JSONObject().put("ok", false).put("error", e.message ?: "internal error"))
        }
    }

    /**
     * Request body as JSON. The companion sends Content-Type: application/
     * json (arrives via parseBody's "postData"); a plain `curl -d` sends
     * urlencoded fields, which NanoHTTPD decodes into [IHTTPSession.parms]
     * (NOT the files map) — fold those back into the object so ad-hoc curl
     * tests work too (caught live against the tablet 2026-11-02: every curl
     * without the header died with "name required").
     */
    private fun body(session: IHTTPSession): JSONObject {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val raw = files.remove("postData").orEmpty()
        if (raw.isNotBlank()) return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        val obj = JSONObject()
        session.parameters.forEach { (k, values) ->
            // Skip NanoHTTPD internals ("NanoHttpd.QUERY_STRING" etc).
            if (!k.startsWith("NanoHttpd.")) values.firstOrNull()?.let { obj.put(k, it) }
        }
        return obj
    }

    private fun json(status: Response.Status, o: JSONObject): Response =
        newFixedLengthResponse(status, "application/json", o.toString())

    private fun ok(extra: JSONObject.() -> Unit = {}): Response =
        json(Response.Status.OK, JSONObject().apply { put("ok", true); extra() })

    private fun bad(msg: String): Response =
        json(Response.Status.BAD_REQUEST, JSONObject().put("ok", false).put("error", msg))

    // ---------------------------------------------------------------- reads

    private fun status(): Response = ok {
        put("device", Build.MODEL ?: "Android")
        put("appVersion", installedVersion(ctx))
        put("apiVersion", LanApi.API_VERSION)
        put("port", LanApi.PORT)
        put("libraries", JSONArray().apply {
            LocalLibraryStore.libraries(ctx).forEach {
                put(JSONObject().put("id", it.id).put("name", it.name))
            }
        })
    }

    private fun library(id: String): Response {
        val lib = LocalLibraryStore.libraries(ctx).firstOrNull { it.id == id }
            ?: return bad("unknown library")
        val shelves = CategoryStore.load(ctx, "local:$id")
        // Three full index parses (books, series, id map) — same cost class
        // as opening the library screen once. Never called per-frame.
        val books = LocalLibraryStore.books(ctx, id)
        val series = LocalLibraryStore.seriesList(ctx, id)
        val seriesIdOf = LocalLibraryStore.seriesIdMap(ctx)
        val prefs = ctx.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
        val bySeries = books.groupBy { seriesIdOf[it.id] ?: "" }
        return ok {
            put("library", JSONObject().put("id", lib.id).put("name", lib.name))
            put("shelves", JSONArray().apply {
                shelves.cats.forEach {
                    put(JSONObject().put("id", it.id).put("name", it.name).put("hidden", it.hidden))
                }
            })
            put("assignments", JSONObject().apply { shelves.assign.forEach { (k, v) -> put(k, v) } })
            put("series", JSONArray().apply {
                series.forEach { s ->
                    val vols = bySeries[s.id].orEmpty()
                        .sortedWith(compareBy({ LocalLibraryStore.volumeNumber(it.title) }, { it.addedAt }))
                    put(JSONObject()
                        .put("id", s.id).put("name", s.name).put("key", s.key)
                        .put("description", s.description).put("coverBookId", s.coverBookId)
                        .put("volumes", JSONArray().apply {
                            vols.forEach { b ->
                                put(JSONObject()
                                    .put("id", b.id).put("title", b.title).put("author", b.author)
                                    .put("chapters", b.chapterCount).put("addedAt", b.addedAt)
                                    .put("linked", b.linked)
                                    .put("pos", prefs.getInt("local_pos_${b.id}", 0)))
                            }
                        }))
                }
            })
        }
    }

    private fun cover(bookId: String): Response {
        // Cache-only by law: a remote cover request must never open an epub.
        val f: File = LocalLibraryStore.coverCached(ctx, bookId)
            ?: return json(Response.Status.NOT_FOUND, JSONObject().put("ok", false).put("error", "no cover"))
        val bytes = f.readBytes()
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg",
            java.io.ByteArrayInputStream(bytes), bytes.size.toLong())
    }

    // ------------------------------------------------------------- mutations
    // Every handler delegates to the store's locked functions; none of them
    // touch the index file shape themselves.

    private fun post(op: String, body: JSONObject): Response = synchronized(writeGate) {
        DebugLog.log("LANAPI", "post $op ${body.toString().take(200)}")
        when (op) {
            "shelf/create" -> {
                val lib = body.optString("library")
                val name = body.optString("name")
                if (name.isBlank()) return bad("name required")
                val st = CategoryStore.load(ctx, "local:$lib")
                CategoryStore.save(ctx, "local:$lib", CategoryStore.add(st, name))
                ok()
            }
            "shelf/rename" -> {
                val st = CategoryStore.load(ctx, "local:" + body.optString("library"))
                val id = body.optString("shelfId")
                if (st.cats.none { it.id == id }) return bad("unknown shelf")
                CategoryStore.save(ctx, "local:" + body.optString("library"),
                    CategoryStore.rename(st, id, body.optString("name")))
                ok()
            }
            "shelf/delete" -> {
                val lib = body.optString("library")
                val st = CategoryStore.load(ctx, "local:$lib")
                val id = body.optString("shelfId")
                if (st.cats.none { it.id == id }) return bad("unknown shelf")
                CategoryStore.save(ctx, "local:$lib", CategoryStore.delete(st, id))
                ok()
            }
            "shelf/assign" -> {
                val lib = body.optString("library")
                val shelfId = body.opt("shelfId")?.let { it as? String }?.takeIf { it.isNotBlank() }
                var st = CategoryStore.load(ctx, "local:$lib")
                if (shelfId != null && st.cats.none { it.id == shelfId }) return bad("unknown shelf")
                val ids = body.optJSONArray("seriesIds") ?: return bad("seriesIds required")
                for (i in 0 until ids.length()) {
                    st = CategoryStore.move(st, ids.optString(i), shelfId)
                }
                CategoryStore.save(ctx, "local:$lib", st)
                ok()
            }
            "series/rename" -> {
                val name = body.optString("name")
                if (name.isBlank()) return bad("name required")
                LocalLibraryStore.renameSeries(ctx, body.optString("seriesId"), name)
                ok()
            }
            "series/description" -> {
                LocalLibraryStore.setSeriesDescription(ctx, body.optString("seriesId"),
                    body.optString("description"))
                ok()
            }
            "series/merge" -> {
                val from = body.optString("fromId")
                val to = body.optString("toId")
                val list = LocalLibraryStore.seriesList(ctx)
                val f = list.firstOrNull { it.id == from } ?: return bad("unknown fromId")
                val t = list.firstOrNull { it.id == to } ?: return bad("unknown toId")
                if (f.libraryId != t.libraryId) return bad("cannot merge across libraries")
                LocalLibraryStore.mergeSeries(ctx, from, to)
                ok()
            }
            "series/moveBook" -> {
                val bookId = body.optString("bookId")
                if (LocalLibraryStore.book(ctx, bookId) == null) return bad("unknown bookId")
                val seriesId = body.opt("seriesId")?.let { it as? String }?.takeIf { it.isNotBlank() }
                LocalLibraryStore.moveBookToSeries(ctx, bookId, seriesId)
                ok()
            }
            "series/moveToLibrary" -> {
                val id = body.optString("seriesId")
                val toLib = body.optString("toLibraryId")
                val series = LocalLibraryStore.seriesList(ctx).firstOrNull { it.id == id }
                    ?: return bad("unknown seriesId")
                if (LocalLibraryStore.library(ctx, toLib) == null) return bad("unknown toLibraryId")
                if (series.libraryId == toLib) return bad("series already in that library")
                LocalLibraryStore.moveSeriesToLibrary(ctx, id, toLib)
                ok()
            }
            "series/delete" -> {
                val id = body.optString("seriesId")
                val series = LocalLibraryStore.seriesList(ctx).firstOrNull { it.id == id }
                    ?: return bad("unknown seriesId")
                val mode = if (body.optString("mode") == "trash")
                    LocalLibraryStore.DeleteMode.TRASH else LocalLibraryStore.DeleteMode.REMOVE
                // kept = volumes whose file could not be trashed and stay
                // in the library (v3.3 fail-safe; index always matches disk).
                val kept = LocalLibraryStore.deleteSeries(ctx, id, mode)
                ok {
                    put("mode", if (mode == LocalLibraryStore.DeleteMode.TRASH) "trash" else "remove")
                    put("kept", kept)
                }
            }
            "book/meta" -> {
                val bookId = body.optString("bookId")
                if (LocalLibraryStore.book(ctx, bookId) == null) return bad("unknown bookId")
                LocalLibraryStore.setBookMeta(
                    ctx, bookId,
                    title = body.optString("title", "").ifBlank { null },
                    author = body.optString("author", "").ifBlank { null },
                    description = body.optString("description", "").ifBlank { null }
                )
                ok()
            }
            "book/delete" -> {
                val bookId = body.optString("bookId")
                if (LocalLibraryStore.book(ctx, bookId) == null) return bad("unknown bookId")
                val mode = if (body.optString("mode") == "trash")
                    LocalLibraryStore.DeleteMode.TRASH else LocalLibraryStore.DeleteMode.REMOVE
                // moved = the epub actually left for Trash; false = kept in
                // place (fail-safe when the SAF move is refused).
                val moved = LocalLibraryStore.deleteBook(ctx, bookId, mode)
                ok {
                    put("mode", if (mode == LocalLibraryStore.DeleteMode.TRASH) "trash" else "remove")
                    put("fileMoved", moved)
                }
            }
            "library/rename" -> {
                val id = body.optString("libraryId")
                val name = body.optString("name")
                if (name.isBlank()) return bad("name required")
                if (LocalLibraryStore.library(ctx, id) == null) return bad("unknown libraryId")
                LocalLibraryStore.renameLibrary(ctx, id, name)
                ok()
            }
            else -> bad("unknown operation")
        }
    }
}

/** Foreground host for the server: a management session can outlast the
 *  UI being backgrounded (specialUse — this is not dataSync work). */
class LanApiService : Service() {

    private var server: LanApiServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Desktop companion", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (LanApi.isEnabled(this)) {
            startInForeground()
            if (server == null) {
                server = LanApiServer(applicationContext)
                    .also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
                DebugLog.log("LANAPI", "server started on port ${LanApi.PORT}")
            }
        } else {
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        DebugLog.log("LANAPI", "server stopped")
        super.onDestroy()
    }

    private fun startInForeground() {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val addresses = LanApi.lanAddresses(this).joinToString(", ")
        val text = "http://$addresses:${LanApi.PORT} — manage from the desktop companion"
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("Companion server running")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        const val CHANNEL_ID = "lan_api"
        const val NOTIF_ID = 4711
    }
}
