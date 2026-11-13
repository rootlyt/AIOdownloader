package com.rootlyt.aio

fun main() {
    check(Rules.url(" https://example.com/a?v=1 ") == "https://example.com/a?v=1")
    for (bad in listOf("", "file:///etc/passwd", "javascript:alert(1)", "https://user:pass@example.com", "https:///video", "https://example.com:99999", "https://a b/")) {
        check(runCatching { Rules.url(bad) }.isFailure) { "Accepted $bad" }
    }
    check(Rules.shared("Watch this https://example.com/video now") == "https://example.com/video")
    check(Rules.filename("../../a:b", "mp4") == "_.._a_b.mp4")
    check(Rules.filename("...", "mp3") == "media.mp3")
    check(runCatching { Rules.filename("x", "../exe") }.isFailure)
    check(Rules.format("720") == "bv*[height<=720]+ba/b[height<=720]")
    check(runCatching { Rules.format("720; rm") }.isFailure)
    check("403" in Rules.failure("ERROR: unable to download video data: HTTP Error 403: Forbidden"))
    check("login" in Rules.failure("Sign in to confirm you're not a bot. https://github.com/yt-dlp/wiki/cookies"))
    check("http" !in Rules.failure("https://github.com/yt-dlp/wiki/cookies"))
    check("Storage" in Rules.failure("No space left on device"))
    check("limiting" in Rules.failure("HTTP Error 429"))
    check("media host" in Rules.failure("ERROR: [twitter:amplify] Unable to download webpage: HTTP Error 500: Domain Not Found"))
    check("photo lookup was unavailable" in Rules.failure("ERROR: [twitter] 894001459754180609: No video could be found in this tweet"))
    check(Rules.failure(null).length < 200)
    check(Rules.twitterId("https://x.com/infoBMKG/status/2121157219890971057?s=20") == "2121157219890971057")
    check(Rules.twitterId("https://twitter.com/user/status/123/photo/2") == "123")
    for (bad in listOf("https://x.com.evil.org/user/status/123", "https://user@x.com/user/status/123", "http://x.com/user/status/123", "https://x.com:443/user/status/123", "https://x.com/user/status/123/other", "https://x.com/user/status/0", "https://x.com/user/status/%31", "https://x.com/user/status/123#x")) check(Rules.twitterId(bad) == null) { bad }
    check(Rules.twitterImage("https://pbs.twimg.com/media/abc.jpg") == "https://pbs.twimg.com/media/abc?format=jpg&name=orig")
    for (bad in listOf("http://pbs.twimg.com/media/a.jpg", "https://pbs.twimg.com.evil.org/media/a.jpg", "https://u@pbs.twimg.com/media/a.jpg", "https://pbs.twimg.com:443/media/a.jpg", "https://pbs.twimg.com/media/a.jpg?name=orig", "https://pbs.twimg.com/media/a.jpg#x", "https://pbs.twimg.com/media/../a.jpg", "https://pbs.twimg.com/media/%61.jpg", "https://pbs.twimg.com/profile_images/a.jpg")) check(runCatching { Rules.twitterImage(bad) }.isFailure) { bad }
    val url = "https://x.com/user/status/123"
    fun fixture(type: String = "photo", owner: String = "123") = org.json.JSONObject("""{"id_str":"123","mediaDetails":[{"type":"$type","expanded_url":"https://x.com/user/status/$owner/photo/1","ext_media_availability":{"status":"Available"},"media_url_https":"https://pbs.twimg.com/media/a.jpg"}]}""")
    check(TwitterPhotos.parse(url, fixture())!!.getJSONArray("photos").length() == 1)
    check(TwitterPhotos.parse(url, fixture("video")) == null)
    check(TwitterPhotos.parse(url, fixture(owner = "456")) == null)
    check(TwitterPhotos.parse(url, org.json.JSONObject("""{"id_str":"123","quoted_tweet":{"mediaDetails":[]}}""")) == null)
    check(TwitterPhotos.parse(url, fixture().put("id_str", "456")) == null)
    check(TwitterPhotos.parse(url, fixture().put("mediaDetails", org.json.JSONArray())) == null)
    val old = Gallery.Item("1", "same_1.jpg", "image/jpeg")
    val photo = Gallery.Item("2", "photo.jpg", "image/jpeg", "batch", "Post")
    val video = Gallery.Item("3", "video.mp4", "video/mp4", "batch", "Post")
    check(Gallery.groups(listOf(old, old.copy(uri = "4", name = "same_2.jpg"), photo, video)).map { it.size } == listOf(1, 1, 2))
    check(Gallery.groups(listOf(photo, video.copy(batch = "retry"))).size == 2)
    check(Gallery.groups(listOf(video)).single().single() == video)
    check(Gallery.groups(emptyList()).isEmpty())
    check(SocialBatch.accepts("https://www.instagram.com/p/BQ0eAlwhDrw/"))
    check(!SocialBatch.accepts("https://www.instagram.com/instagram/"))
    check(!SocialBatch.accepts("https://facebook.com.evil.org/u/posts/123"))
    for (bad in listOf("https://fbcdn.net.evil.org/a.jpg", "https://evilfbcdn.net/a.jpg", "http://s.fbcdn.net/a.jpg", "https://u@s.fbcdn.net/a.jpg", "https://s.fbcdn.net:443/a.jpg")) check(runCatching { SocialBatch.image(bad) }.isFailure)
    check(SocialBatch.image("https://scontent.xx.fbcdn.net/a.jpg?sig=x").endsWith("?sig=x"))
    val post = org.json.JSONObject("""{"title":"Post by instagram","entries":[{"id":"BQ0dSaohpPW","formats":[{"height":640,"url":"https://s.fbcdn.net/a.mp4"}]},{"id":"BQ0dTpOhuHT","formats":[{"height":720}]},{"id":"BQ0dT7RBFeF","formats":[{"height":640}]}]}""")
    // IDs/count from yt-dlp's BQ0eAlwhDrw upstream fixture; URLs are sanitized test values.
    val batch = SocialBatch.compact("https://www.instagram.com/p/BQ0eAlwhDrw/", post)
    check(batch.getInt("videoCount") == 3 && batch.getJSONArray("heights").length() == 2)
    post.getJSONArray("entries").put(org.json.JSONObject().put("aio_image", "https://s.cdninstagram.com/a.jpg"))
    check(SocialBatch.compact("https://www.instagram.com/p/BQ0eAlwhDrw/", post).getInt("imageCount") == 1)
    post.getJSONArray("entries").put(org.json.JSONObject())
    check(runCatching { SocialBatch.compact("https://www.instagram.com/p/BQ0eAlwhDrw/", post) }.isFailure)
    println("Rules, social batches and gallery grouping checks passed")
}
