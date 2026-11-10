package dev.arrbrants.controller.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Checks the project's GitHub releases for a newer build. */
object UpdateChecker {

    private const val API =
        "https://api.github.com/repos/Cai-kunkun/Controller/releases?per_page=5"

    data class Info(
        val version: String,
        val url: String,
        val notes: String
    )

    sealed class Result {
        data class Available(val info: Info) : Result()
        object Latest : Result()
        object Error : Result()
    }

    fun check(currentVersion: String): Result {
        val conn = try {
            URL(API).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return Result.Error
        }
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "Controller")
            if (conn.responseCode !in 200..299) return Result.Error
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val releases = JSONArray(body)
            for (i in 0 until releases.length()) {
                val release = releases.optJSONObject(i) ?: continue
                if (release.optBoolean("draft", false)) continue
                val tag = release.optString("tag_name").trim().removePrefix("v")
                if (tag.isBlank()) continue
                if (!isNewer(tag, currentVersion)) return Result.Latest
                return Result.Available(
                    Info(
                        version = tag,
                        url = release.optString(
                            "html_url",
                            "https://github.com/Cai-kunkun/Controller/releases"
                        ),
                        notes = release.optString("body", "").take(4000)
                    )
                )
            }
            Result.Latest
        } catch (e: Exception) {
            Result.Error
        } finally {
            conn.disconnect()
        }
    }

    /** Semver-ish comparison; prereleases sort below the final release. */
    fun isNewer(latest: String, current: String): Boolean {
        val a = parse(latest)
        val b = parse(current)
        val size = maxOf(a.numbers.size, b.numbers.size)
        for (i in 0 until size) {
            val x = a.numbers.getOrElse(i) { 0 }
            val y = b.numbers.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        val ap = a.prerelease
        val bp = b.prerelease
        if (ap == null && bp == null) return false
        if (ap == null) return true
        if (bp == null) return false
        return comparePrerelease(ap, bp) > 0
    }

    private data class Version(val numbers: List<Int>, val prerelease: String?)

    private fun parse(version: String): Version {
        val clean = version.trim().removePrefix("v")
        val core = clean.substringBefore('-')
        val pre = clean.substringAfter('-', "").ifBlank { null }
        val numbers = core.split('.').map { it.toIntOrNull() ?: 0 }
        return Version(numbers, pre)
    }

    private fun comparePrerelease(a: String, b: String): Int {
        val an = Regex("(\\d+)$").find(a)?.groupValues?.get(1)?.toIntOrNull()
        val bn = Regex("(\\d+)$").find(b)?.groupValues?.get(1)?.toIntOrNull()
        val aPrefix = a.replace(Regex("\\d+$"), "")
        val bPrefix = b.replace(Regex("\\d+$"), "")
        if (aPrefix != bPrefix) return aPrefix.compareTo(bPrefix)
        return (an ?: 0).compareTo(bn ?: 0)
    }
}
