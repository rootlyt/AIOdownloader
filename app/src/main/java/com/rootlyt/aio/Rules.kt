package com.rootlyt.aio

import java.net.URI

object Rules {
    fun url(text: String): String {
        require(text.length <= 8192) { "Link is too long." }
        val value = text.trim()
        val uri = try { URI(value) } catch (_: Exception) { throw IllegalArgumentException("Enter a valid HTTP or HTTPS link.") }
        require(uri.scheme?.lowercase() in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null) {
            "Enter one HTTP or HTTPS link without embedded credentials."
        }
        require(uri.port in -1..65535) { "Invalid port." }
        return uri.toASCIIString()
    }
    fun twitterId(text: String): String? = runCatching {
        val uri = URI(text)
        require(uri.scheme == "https" && uri.rawUserInfo == null && uri.port == -1 && uri.rawFragment == null)
        require(uri.host?.lowercase() in setOf("x.com", "www.x.com", "twitter.com", "www.twitter.com", "mobile.twitter.com", "m.twitter.com"))
        Regex("/(?:[A-Za-z0-9_]{1,15}/status|i/status)/([1-9][0-9]{0,19})(?:/(?:photo|video)/[1-4])?/?").matchEntire(uri.rawPath)?.groupValues?.get(1)
    }.getOrNull()
    fun twitterImage(text: String): String {
        val uri = URI(text)
        require(uri.scheme == "https" && uri.host == "pbs.twimg.com" && uri.port == -1 && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        val match = Regex("/media/([A-Za-z0-9_-]+)\\.(jpg|png|webp)").matchEntire(uri.rawPath) ?: throw IllegalArgumentException("Invalid photo URL")
        return "https://pbs.twimg.com/media/${match.groupValues[1]}?format=${match.groupValues[2]}&name=orig"
    }
    fun shared(text: String): String = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE).find(text.take(8192))?.value ?: text.take(8192)
    fun filename(title: String, extension: String): String {
        val base = title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").trim(' ', '.').take(100).ifBlank { "media" }
        require(extension.matches(Regex("[a-zA-Z0-9]{1,8}"))) { "Unexpected file type." }
        return "$base.$extension"
    }
    fun failure(message: String?): String {
        val text = message.orEmpty().lowercase()
        return when {
            "no video formats found" in text || "there is no video in this post" in text -> "The extractor did not expose downloadable media. This photo post/carousel is unsupported or unavailable anonymously."
            "no video could be found in this tweet" in text -> "No video found. Anonymous X photo lookup was unavailable or unsupported; yt-dlp cannot extract these photos."
            "domain not found" in text -> "The site's media host is unavailable. This older media link may no longer work."
            "no space" in text || "enospc" in text -> "Storage is full. Free space, then retry."
            "429" in text || "rate-limit" in text || "too many requests" in text -> "The site is limiting requests. Wait before retrying."
            "sign in" in text || "login" in text || "cookies" in text || "bot" in text -> "The site requires login or an anti-bot check, even for public posts. This app cannot supply that session."
            "403" in text -> "The site refused the media request (403). Update the extractor, then retry. Public links can still require authentication."
            "private" in text || "unavailable" in text || "not available" in text -> "This media is unavailable, private or restricted in your region."
            "unsupported url" in text -> "This link is not supported. Share a direct video or reel link."
            "timed out" in text || "network" in text || "resolve" in text -> "Connection failed. Check your network, then retry."
            "requested format" in text -> "That quality is unavailable. Preview again or choose Best available."
            else -> "Could not complete this request. Update the extractor and retry. Site restrictions or unsupported media may prevent saving."
        }
    }
    fun format(choice: String): String = when {
        choice == "best" -> "bv*+ba/b"
        choice == "audio" -> "bestaudio/best"
        choice.matches(Regex("[0-9]{2,4}")) && choice.toInt() in 96..4320 -> "bv*[height<=${choice}]+ba/b[height<=${choice}]"
        else -> throw IllegalArgumentException("Invalid quality selection.")
    }
}
