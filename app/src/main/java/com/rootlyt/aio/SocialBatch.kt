package com.rootlyt.aio

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

object SocialBatch {
    fun accepts(url: String): Boolean = runCatching {
        val u = URI(url)
        require(u.scheme == "https" && u.rawUserInfo == null && u.port == -1)
        when (u.host?.lowercase()) {
            "instagram.com", "www.instagram.com" -> Regex("/(?:p|reel|reels|tv)/[A-Za-z0-9_-]+/?").matches(u.path)
            "facebook.com", "www.facebook.com", "m.facebook.com" ->
                Regex("/[^/]+/(?:posts|videos)/[^/]+/?|/reel/[0-9]+/?").matches(u.path) ||
                    u.path in listOf("/story.php", "/permalink.php", "/watch/", "/watch", "/video.php")
            else -> false
        }
    }.getOrDefault(false)

    fun image(url: String): String {
        val u = URI(url)
        val host = u.host?.lowercase().orEmpty()
        require(u.scheme == "https" && u.rawUserInfo == null && u.port == -1 && u.rawFragment == null &&
            (host.endsWith(".cdninstagram.com") || host.endsWith(".fbcdn.net"))) { "Invalid official image CDN URL" }
        require(u.rawPath.isNotBlank() && url.length <= 8192) { "Invalid image URL" }
        return url
    }

    fun compact(url: String, info: JSONObject): JSONObject {
        val entries = info.optJSONArray("entries") ?: JSONArray().put(info)
        require(entries.length() in 1..20) { "Post must contain 1–20 available media items." }
        val items = JSONArray()
        val heights = sortedSetOf<Int>()
        var images = 0
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            require(!entry.has("entries")) { "Nested collections are unsupported." }
            val source = entry.optString("aio_image")
            if (source.isNotBlank()) {
                image(source); images++
                items.put(JSONObject().put("image", source))
            } else {
                val formats = entry.optJSONArray("formats")
                require(formats != null && formats.length() > 0) { "Post contains unavailable media; preview cannot establish all items." }
                for (f in 0 until formats.length()) {
                    val height = formats.getJSONObject(f).optInt("height")
                    if (height in 96..4320) heights.add(height)
                }
                val media = JSONObject()
                for (key in listOf("id", "title", "formats", "http_headers", "thumbnails", "thumbnail", "duration", "uploader", "artist", "album", "track", "webpage_url", "extractor", "extractor_key")) {
                    if (entry.has(key)) media.put(key, entry.get(key))
                }
                items.put(JSONObject().put("info", media))
            }
        }
        return JSONObject().put("url", url).put("title", info.optString("title", "Post"))
            .put("thumbnail", info.optString("thumbnail", entries.getJSONObject(0).optString("thumbnail")))
            .put("items", items).put("imageCount", images).put("videoCount", items.length() - images)
            .put("heights", JSONArray(heights.toList()))
    }
}
