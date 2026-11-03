package com.patchscratch.novelnotifier.companion

import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class ApiException(message: String, val status: Int = 0) : Exception(message)

/**
 * Thin synchronous client for the phone's `/api/v1` LAN API. Every method
 * blocks — call from Dispatchers.IO. JSON is org.json, the same library the
 * phone builds responses with, so the contract has one implementation
 * language end to end.
 */
class CompanionApi(baseUrl: String, private val token: String) {

    private val base = baseUrl.trimEnd('/')
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private fun request(path: String, method: String = "GET", body: String? = null, timeoutMs: Long = 60_000): String {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create("$base$path"))
            .timeout(Duration.ofMillis(timeoutMs))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
        when (method) {
            "GET" -> builder.GET()
            else -> builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body ?: "{}"))
        }
        val resp = try {
            http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: java.io.IOException) {
            throw ApiException("Cannot reach the phone (${e.message ?: e.javaClass.simpleName})")
        }
        if (resp.statusCode() == 401) throw ApiException("Pairing token rejected", 401)
        if (resp.statusCode() >= 400) {
            val msg = runCatching { JSONObject(resp.body()).optString("error") }
                .getOrDefault(resp.body().take(120))
            throw ApiException(msg.ifBlank { "HTTP ${resp.statusCode()}" }, resp.statusCode())
        }
        return resp.body()
    }

    private fun post(op: String, body: JSONObject = JSONObject()): JSONObject =
        JSONObject(request("/api/v1/$op", "POST", body.toString()))

    // ---------------------------------------------------------------- reads

    /**
     * The pairing probe. [timeoutMs] is deliberately configurable so the
     * connect screen can bound the whole attempt tightly — an unreachable
     * LAN address must fail in seconds, not the general 60s request cap.
     */
    fun status(timeoutMs: Long = 60_000): DeviceStatus {
        val o = JSONObject(request("/api/v1/status", timeoutMs = timeoutMs))
        return DeviceStatus(
            device = o.optString("device"),
            appVersion = o.optString("appVersion"),
            apiVersion = o.optInt("apiVersion"),
            port = o.optInt("port"),
            libraries = o.optJSONArray("libraries").toObjList().map {
                LibRef(it.optString("id"), it.optString("name"))
            }
        )
    }

    fun library(id: String): LibraryData {
        val o = JSONObject(request("/api/v1/library/$id"))
        val shelves = o.optJSONArray("shelves").toObjList().map {
            Shelf(it.optString("id"), it.optString("name"), it.optBoolean("hidden"))
        }
        val assignments = buildMap {
            val a = o.optJSONObject("assignments") ?: return@buildMap
            a.keys().forEach { k -> put(k, a.optString(k)) }
        }
        val series = o.optJSONArray("series").toObjList().map { s ->
            SeriesRow(
                id = s.optString("id"),
                name = s.optString("name"),
                key = s.optString("key"),
                description = s.optString("description"),
                coverBookId = s.optString("coverBookId"),
                volumes = s.optJSONArray("volumes").toObjList().map { v ->
                    Volume(
                        id = v.optString("id"),
                        title = v.optString("title"),
                        author = v.optString("author"),
                        chapters = v.optInt("chapters"),
                        addedAt = v.optLong("addedAt"),
                        linked = v.optBoolean("linked"),
                        pos = v.optInt("pos")
                    )
                }
            )
        }
        val lib = o.optJSONObject("library")
        return LibraryData(
            library = LibRef(lib?.optString("id") ?: id, lib?.optString("name") ?: id),
            shelves = shelves, assignments = assignments, series = series
        )
    }

    /** Cached cover bytes, or null — the phone never opens an epub for this. */
    fun cover(bookId: String): ByteArray? = try {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("$base/api/v1/cover/$bookId"))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer $token")
            .GET().build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
        if (resp.statusCode() == 200) resp.body() else null
    } catch (_: Exception) { null }

    // ------------------------------------------------------------ mutations

    fun shelfCreate(libraryId: String, name: String) =
        post("shelf/create", JSONObject().put("library", libraryId).put("name", name))

    fun shelfRename(libraryId: String, shelfId: String, name: String) =
        post("shelf/rename", JSONObject().put("library", libraryId)
            .put("shelfId", shelfId).put("name", name))

    fun shelfDelete(libraryId: String, shelfId: String) =
        post("shelf/delete", JSONObject().put("library", libraryId).put("shelfId", shelfId))

    fun shelfAssign(libraryId: String, seriesIds: List<String>, shelfId: String?) =
        post("shelf/assign", JSONObject().put("library", libraryId)
            .put("seriesIds", org.json.JSONArray(seriesIds))
            .put("shelfId", shelfId ?: JSONObject.NULL))

    fun seriesRename(seriesId: String, name: String) =
        post("series/rename", JSONObject().put("seriesId", seriesId).put("name", name))

    fun seriesSetDescription(seriesId: String, description: String) =
        post("series/description", JSONObject().put("seriesId", seriesId)
            .put("description", description))

    fun seriesMerge(fromId: String, toId: String) =
        post("series/merge", JSONObject().put("fromId", fromId).put("toId", toId))

    /** Series + all its volumes to another library (index-level on the
     *  phone — files stay in their storage location; lands Unshelved). */
    fun seriesMoveToLibrary(seriesId: String, toLibraryId: String) =
        post("series/moveToLibrary", JSONObject().put("seriesId", seriesId)
            .put("toLibraryId", toLibraryId))

    fun moveBook(bookId: String, seriesId: String?) =
        post("series/moveBook", JSONObject().put("bookId", bookId)
            .put("seriesId", seriesId ?: JSONObject.NULL))

    fun seriesDelete(seriesId: String, trash: Boolean) =
        post("series/delete", JSONObject().put("seriesId", seriesId)
            .put("mode", if (trash) "trash" else "remove"))

    fun bookMeta(bookId: String, title: String? = null, author: String? = null) =
        JSONObject().put("bookId", bookId).apply {
            title?.let { put("title", it) }
            author?.let { put("author", it) }
        }.let { post("book/meta", it) }

    fun bookDelete(bookId: String, trash: Boolean) =
        post("book/delete", JSONObject().put("bookId", bookId)
            .put("mode", if (trash) "trash" else "remove"))

    fun libraryRename(libraryId: String, name: String) =
        post("library/rename", JSONObject().put("libraryId", libraryId).put("name", name))
}

private fun org.json.JSONArray?.toObjList(): List<org.json.JSONObject> =
    if (this == null) emptyList()
    else (0 until length()).mapNotNull { i -> optJSONObject(i) }
