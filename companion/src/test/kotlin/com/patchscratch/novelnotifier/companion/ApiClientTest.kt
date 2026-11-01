package com.patchscratch.novelnotifier.companion

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/**
 * Contract tests for the /api/v1 client: a fake phone serves exactly the
 * shapes LanApi.kt emits; the client must parse them and reject auth
 * failures. If the phone's contract changes, these break first — that is
 * the point.
 */
class ApiClientTest {

    private lateinit var server: HttpServer
    private lateinit var api: CompanionApi
    private var lastAuthHeader: String? = null
    private var lastBody: JSONObject? = null
    private var lastPath: String? = null

    private val libraryJson = """
        {"ok":true,
         "library":{"id":"default","name":"Library"},
         "shelves":[{"id":"c1","name":"Ongoing","hidden":false}],
         "assignments":{"s1":"c1"},
         "series":[
           {"id":"s1","name":"Ms. Elf!","key":"ms. elf!","description":"d",
            "coverBookId":"b1","volumes":[
              {"id":"b1","title":"Ms. Elf! Vol. 1","author":"Suisui","chapters":40,
               "addedAt":1700000000000,"linked":true,"pos":12}]},
           {"id":"s2","name":"Solo series","key":"solo","description":"",
            "coverBookId":"","volumes":[]}
         ]}
    """.trimIndent()

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        api = CompanionApi("http://127.0.0.1:$port", "test-token")

        server.createContext("/") { exchange ->
            lastAuthHeader = exchange.requestHeaders.getFirst("Authorization")
            lastPath = exchange.requestURI.path
            val authed = lastAuthHeader == "Bearer test-token"
            val response: String = when {
                !authed -> """{"ok":false,"error":"unauthorized"}"""
                exchange.requestURI.path == "/api/v1/status" ->
                    """{"ok":true,"device":"SM-S928B","appVersion":"3.2.4","apiVersion":1,"port":8790,
                        "libraries":[{"id":"default","name":"Library"},{"id":"abc","name":"Light Novels"}]}"""
                exchange.requestURI.path == "/api/v1/library/default" -> libraryJson
                exchange.requestMethod == "POST" -> {
                    lastBody = JSONObject(String(exchange.requestBody.readBytes()))
                    """{"ok":true}"""
                }
                else -> """{"ok":false,"error":"not found"}"""
            }
            val bytes = response.toByteArray()
            val status = if (!authed) 401 else if (response.contains("not found")) 404 else 200
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `status parses device and libraries`() {
        val st = api.status()
        assertEquals("SM-S928B", st.device)
        assertEquals("3.2.4", st.appVersion)
        assertEquals(2, st.libraries.size)
        assertEquals("Light Novels", st.libraries[1].name)
    }

    @Test
    fun `library parses shelves assignments and volumes`() {
        val d = api.library("default")
        assertEquals("Library", d.library.name)
        assertEquals(1, d.shelves.size)
        assertEquals("c1", d.assignments["s1"])
        assertEquals(2, d.series.size)
        val elf = d.series.first { it.id == "s1" }
        assertEquals(1, elf.volumeCount)
        assertTrue(elf.volumes[0].linked)
        assertEquals(12, elf.volumes[0].pos)
        assertNotNull(d.shelfOf("s1"))
        assertNull(d.shelfOf("s2"))
        assertEquals(1, d.unshelvedCount)
    }

    @Test
    fun `mutations post json bodies with the bearer token`() {
        api.shelfAssign("default", listOf("s2"), "c1")
        assertEquals("/api/v1/shelf/assign", lastPath)
        assertEquals("Bearer test-token", lastAuthHeader)
        assertEquals("default", lastBody!!.optString("library"))
        assertEquals("c1", lastBody!!.optString("shelfId"))
        assertEquals("s2", lastBody!!.optJSONArray("seriesIds").optString(0))

        api.seriesMerge("s2", "s1")
        assertEquals("/api/v1/series/merge", lastPath)
        assertEquals("s1", lastBody!!.optString("toId"))
    }

    @Test
    fun `null shelf assign serializes as JSON null`() {
        api.shelfAssign("default", listOf("s2"), null)
        assertTrue(lastBody!!.isNull("shelfId"))
    }

    @Test(expected = ApiException::class)
    fun `wrong token surfaces as auth error`(): Unit {
        CompanionApi("http://127.0.0.1:${server.address.port}", "wrong").status()
    }
}
