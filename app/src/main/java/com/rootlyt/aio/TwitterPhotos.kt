package com.rootlyt.aio

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

// ponytail: anonymous photo-only posts; mixed/video posts stay with yt-dlp, no session bypass.
class TwitterPhotos(private val active: () -> Unit) {
    @Volatile private var connection: HttpURLConnection? = null
    fun cancel() { connection?.disconnect() }

    private fun fetch(url: String, output: OutputStream, limit: Int, mime: String, progress: (Long, Long) -> Unit = { _, _ -> }) {
        active()
        val conn = URL(url).openConnection() as HttpURLConnection
        connection = conn
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 10000; conn.readTimeout = 10000
            conn.setRequestProperty("Accept", mime)
            active()
            require(conn.responseCode == 200) { "Photo server HTTP ${conn.responseCode}" }
            require(conn.contentType?.substringBefore(';') == mime) { "Unexpected photo response type" }
            val length = conn.contentLengthLong
            require(length <= limit) { "Photo response too large" }
            conn.inputStream.use { input ->
                val buffer = ByteArray(65536)
                var total = 0L
                while (true) {
                    active()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= limit) { "Photo response too large" }
                    output.write(buffer, 0, count)
                    progress(total, length)
                }
                require(total > 0 && (length < 0 || total == length)) { "Incomplete photo response" }
            }
            active()
        } finally { conn.disconnect(); if (connection === conn) connection = null }
    }
    fun inspect(url: String): JSONObject? {
        val id = Rules.twitterId(url) ?: return null
        return try {
            val bytes = ByteArrayOutputStream()
            fetch("https://cdn.syndication.twimg.com/tweet-result?id=$id&lang=en&token=1", bytes, 1_000_000, "application/json")
            parse(url, JSONObject(bytes.toString("UTF-8")))
        } catch (e: Exception) {
            active()
            android.util.Log.d("AIO", "Anonymous photo lookup unavailable; using yt-dlp", e)
            null
        }
    }
    fun download(source: String, file: File, progress: (Long, Long) -> Unit) {
        val original = Rules.twitterImage(source)
        val mime = when (file.extension) { "jpg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"; else -> error("Invalid image type") }
        file.outputStream().use { fetch(original, it, 30_000_000, mime, progress) }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outMimeType == mime) { "Invalid image bytes" }
    }
    fun downloadSocial(source: String, file: File, progress: (Long, Long) -> Unit) {
        // Reject all redirects: CDN signatures must resolve at the validated official host.
        SocialBatch.image(source)
        file.outputStream().use { fetch(source, it, 30_000_000, "image/jpeg", progress) }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outMimeType == "image/jpeg") { "Invalid image bytes" }
    }
    companion object {
        fun parse(url: String, info: JSONObject): JSONObject? {
            val id = Rules.twitterId(url) ?: return null
            if (info.optString("id_str") != id) return null
            // Only top-level attachments belonging to this exact status; never quoted_tweet or cards.
            val media = info.optJSONArray("mediaDetails") ?: return null
            if (media.length() !in 1..4) return null
            val photos = org.json.JSONArray()
            for (i in 0 until media.length()) {
                val item = media.optJSONObject(i) ?: return null
                if (item.optString("type") != "photo" || Rules.twitterId(item.optString("expanded_url")) != id) return null
                if (item.optJSONObject("ext_media_availability")?.optString("status") != "Available") return null
                val source = item.optString("media_url_https")
                try { Rules.twitterImage(source) } catch (_: Exception) { return null }
                photos.put(source)
            }
            return JSONObject().put("url", url).put("title", "X $id").put("photos", photos)
                .put("thumbnail", photos.getString(0)).put("heights", org.json.JSONArray())
        }
    }
}
