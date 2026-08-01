package com.clarifi.data.updates

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** What the About screen shows: the published release, and whether it is newer. */
data class ReleaseInfo(
    val tag: String,
    val notes: List<String>,
    val releaseUrl: String,
    val updateAvailable: Boolean,
)

/**
 * Reads the repository's latest GitHub release, the same call the desktop's
 * Updates tab makes (`api_version_check` in app.py).
 *
 * Straight `HttpURLConnection` and `org.json`: one unauthenticated GET does not
 * justify pulling an HTTP stack into the app (see CLAUDE.md).
 */
class ReleaseChecker(private val currentVersion: String) {

    /** @throws IOException when GitHub cannot be reached or answers with an error. */
    fun latest(): ReleaseInfo? {
        val connection = (URL(RELEASES_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ClariFi-Android")
            connectTimeout = 8_000
            readTimeout = 8_000
        }

        try {
            // 404 is the honest answer before the first release is published, and it
            // is not a failure worth showing as one.
            if (connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (connection.responseCode !in 200..299) {
                throw IOException("GitHub answered ${connection.responseCode}")
            }

            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name")
            return ReleaseInfo(
                tag = tag,
                notes = whatsNew(json.optString("body")),
                releaseUrl = json.optString("html_url").ifEmpty { "$REPO_URL/releases" },
                updateAvailable = semver(tag) > semver(currentVersion),
            )
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val REPO = "federicoroldos/clarifi"
        const val REPO_URL = "https://github.com/$REPO"
        private const val RELEASES_URL = "https://api.github.com/repos/$REPO/releases/latest"

        /**
         * The bullets under the release body's `## What's new` heading, exactly as
         * the desktop's `_extractWhatsNew` picks them. Everything below it (the
         * install instructions) is for people downloading, not for this screen.
         */
        fun whatsNew(body: String?): List<String> {
            if (body.isNullOrBlank()) return emptyList()
            val bullets = mutableListOf<String>()
            var inSection = false
            for (raw in body.replace("\r\n", "\n").split("\n")) {
                val line = raw.trim()
                if (line.startsWith("##")) {
                    inSection = line.removePrefix("##").trim().startsWith("what's new", ignoreCase = true) ||
                        line.removePrefix("##").trim().startsWith("whats new", ignoreCase = true)
                    continue
                }
                if (!inSection) continue
                if (line.startsWith("- ") || line.startsWith("* ")) {
                    bullets += line.drop(2).trim()
                }
            }
            return bullets
        }

        /** `v1.2.3` → `[1, 2, 3]`, padded and truncated, mirroring `_parse_semver`. */
        fun semver(tag: String?): List<Int> {
            val cleaned = tag.orEmpty().trimStart('v', 'V').trim().substringBefore('-')
            val parts = cleaned.split(".").map { it.toIntOrNull() ?: 0 }
            return List(3) { parts.getOrElse(it) { 0 } }
        }

        private operator fun List<Int>.compareTo(other: List<Int>): Int {
            for (i in indices) {
                val diff = this[i].compareTo(other[i])
                if (diff != 0) return diff
            }
            return 0
        }
    }
}
