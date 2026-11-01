package com.patchscratch.novelnotifier.companion

import org.json.JSONObject
import java.io.File

/**
 * Local pairing config: host, port, token, last-open library. One JSON
 * file under the user's home — machine-local by design (the phone is the
 * source of truth; nothing here ever syncs).
 */
data class CompanionConfig(
    val host: String = "",
    val token: String = "",
    val libraryId: String = ""
) {
    val baseUrl: String get() = "http://$host"
    val valid: Boolean get() = host.isNotBlank() && token.isNotBlank()

    fun toJson(): String = JSONObject()
        .put("host", host)
        .put("token", token)
        .put("libraryId", libraryId)
        .toString()

    companion object {
        private fun file(): File =
            File(System.getProperty("user.home"), ".novelnotifier-companion.json")

        fun load(): CompanionConfig {
            val f = file()
            if (!f.isFile) return CompanionConfig()
            return runCatching {
                val o = JSONObject(f.readText())
                CompanionConfig(
                    host = o.optString("host"),
                    token = o.optString("token"),
                    libraryId = o.optString("libraryId")
                )
            }.getOrDefault(CompanionConfig())
        }

        fun save(config: CompanionConfig) {
            runCatching { file().writeText(config.toJson()) }
        }
    }
}
