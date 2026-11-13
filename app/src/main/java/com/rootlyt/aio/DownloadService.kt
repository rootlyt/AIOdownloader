package com.rootlyt.aio

import android.app.*
import android.content.*
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.ffmpeg.FFmpeg
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// ponytail: one in-process transfer, no resume; add a persistent job queue only when resume is required.
class DownloadService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var cancelled = false
    @Volatile private var running = false
    private var thread: Thread? = null
    private val photos = TwitterPhotos { ensureActive() }
    private val prefs by lazy { getSharedPreferences("state", MODE_PRIVATE) }
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel("downloads", "Downloads", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(text: String, active: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, "downloads").setSmallIcon(R.drawable.ic_notification)
            .setOnlyAlertOnce(true).setAutoCancel(!active)
            .setContentTitle("AIOdownloader").setContentText(text).setContentIntent(open).setOngoing(active)
        if (active) builder.setProgress(100, prefs.getInt("progress", -1).coerceAtLeast(0), prefs.getInt("progress", -1) < 0)
        if (active) builder.addAction(Notification.Action.Builder(null, "Cancel", PendingIntent.getService(this, 1,
            Intent(this, DownloadService::class.java).setAction("cancel"), PendingIntent.FLAG_IMMUTABLE)).build())
        return builder.build()
    }
    private fun status(text: String, progress: Int = -1) {
        prefs.edit().putString("status", text).putInt("progress", progress).apply()
        // Serialize progress with completion: late wrapper callbacks must not recreate the foreground notification.
        Handler(Looper.getMainLooper()).post { if (running) notifications.notify(1, notification(text, true)) }
    }
    private fun cancel() {
        cancelled = true
        thread?.interrupt()
        photos.cancel()
        // The wrapper's process registry can be populated just after cancellation; retry until worker exits.
        watchdog.execute { YoutubeDL.destroyProcessById("aio") }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") { if (running) cancel() else stopSelf(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        notifications.cancel(2)
        startForeground(1, notification("Starting…", true))
        val url: String
        val choice = intent?.getStringExtra("quality") ?: "best"
        val updateOnly = intent?.action == "update"
        try { url = if (updateOnly) "" else Rules.url(intent?.getStringExtra("url") ?: ""); Rules.format(choice) }
        catch (e: IllegalArgumentException) { prefs.edit().putString("status", e.message).apply(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        val preview = intent?.action == "preview"
        val submittedPreview = prefs.getString("preview", null)
        if (preview) prefs.edit().remove("preview").apply()
        running = true
        cancelled = false
        prefs.edit().putBoolean("busy", true).putInt("progress", -1)
            .putString("accepted", intent?.getStringExtra("requestId") ?: "").apply()
        worker.execute {
            thread = Thread.currentThread()
            val temp = File(filesDir, "transfer")
            var pending: Uri? = null
            var result = "Cancelled."
            var savedPhotos = 0
            var totalPhotos = 0
            val batch = java.util.UUID.randomUUID().toString()
            var groupingUnavailable = false
            val deadline = watchdog.schedule({ cancel() }, if (preview || updateOnly) 2L else 120L, TimeUnit.MINUTES)
            val killer = watchdog.scheduleWithFixedDelay({ if (cancelled) YoutubeDL.destroyProcessById("aio") }, 1, 1, TimeUnit.SECONDS)
            try {
                check(temp.deleteRecursively()) { "Cannot remove previous temporary files." }
                check(temp.mkdirs()) { "Cannot create temporary download folder." }
                // Recover only our unfinished MediaStore row after a previous process death.
                prefs.getString("pending", null)?.let {
                    contentResolver.delete(Uri.parse(it), "${MediaStore.Downloads.IS_PENDING}=1", null)
                    prefs.edit().remove("pending").commit()
                }
                status(if (preview) "Inspecting link…" else "Downloading…")
                val photoInfo = if (updateOnly) null else if (preview) photos.inspect(url) else
                    submittedPreview?.let { JSONObject(it) }?.takeIf { it.optString("url") == url && it.has("photos") }
                if (photoInfo == null) {
                    YoutubeDL.init(applicationContext)
                    FFmpeg.init(applicationContext)
                }
                ensureActive()
                if (photoInfo == null && (updateOnly || System.currentTimeMillis() - prefs.getLong("updateAttempt", prefs.getLong("updated", 0)) > 604_800_000)) {
                    prefs.edit().putLong("updateAttempt", System.currentTimeMillis()).apply()
                    status("Checking official yt-dlp nightly updates…")
                    try {
                        // CLI updater runs in the cancellable process; wrapper updater has an unbounded metadata HTTP read.
                        YoutubeDL.execute(YoutubeDLRequest(emptyList()).addOption("--update-to", "nightly")
                            .addOption("--socket-timeout", "15"), "aio", null)
                        val version = YoutubeDL.execute(YoutubeDLRequest(emptyList()).addOption("--version"), "aio", null).out.trim()
                        prefs.edit().putLong("updated", System.currentTimeMillis()).putString("extractorVersion", version).putString("updateNote", "Extractor is up to date.").apply()
                    } catch (e: Exception) {
                        ensureActive()
                        if (updateOnly) throw e
                        prefs.edit().putString("updateNote", "Update unavailable; using installed extractor.").apply()
                    }
                }
                ensureActive()
                val social = SocialBatch.accepts(url)
                val plugins = File(temp, "plugins")
                if (social) {
                    val plugin = File(plugins, "aio/yt_dlp_plugins/extractor/social_photos.py")
                    check(plugin.parentFile!!.mkdirs())
                    assets.open("social_photos.py").use { input -> plugin.outputStream().use { input.copyTo(it) } }
                }
                fun request(source: String): YoutubeDLRequest {
                    val req = YoutubeDLRequest(if (source.isEmpty()) emptyList() else listOf(source))
                        .addOption("--socket-timeout", "20").addOption("--retries", "2").addOption("--fragment-retries", "2")
                        .addOption("--abort-on-unavailable-fragments").addOption("--newline").addOption("--no-colors")
                    if (social) req.addOption("--playlist-end", "21").addOption("--plugin-dirs", plugins.absolutePath)
                    else req.addOption("--no-playlist").addOption("--playlist-items", "1")
                    return req
                }
                val request = request(url)
                if (updateOnly) {
                    result = "Extractor updated / already current. Preview the link again."
                } else if (preview) {
                    status("Inspecting link…")
                    if (photoInfo != null) {
                        prefs.edit().putString("preview", photoInfo.toString()).apply()
                        result = "Preview ready: ${photoInfo.getJSONArray("photos").length()} photos · Original quality."
                    } else {
                    request.addOption("--dump-single-json").addOption("--skip-download")
                    val info = JSONObject(YoutubeDL.execute(request, "aio", null).out)
                    ensureActive()
                    require(social || !info.has("entries")) { "Collections are unsupported. Share a single media item." }
                    val compact = if (social) SocialBatch.compact(url, info) else JSONObject().put("url", url).put("title", info.optString("title", "Media"))
                        .put("duration", info.optDouble("duration", 0.0)).put("thumbnail", info.optString("thumbnail"))
                    val heights = sortedSetOf<Int>()
                    val formats = info.optJSONArray("formats")
                    if (formats != null) for (i in 0 until formats.length()) {
                        val height = formats.getJSONObject(i).optInt("height")
                        if (height in 96..4320) heights.add(height)
                    }
                    if (!social) compact.put("heights", org.json.JSONArray(heights.toList()))
                    prefs.edit().putString("preview", compact.toString()).apply()
                    result = "Preview ready. Choose quality, then download."
                    }
                } else {
                    val photoItems = photoInfo?.getJSONArray("photos")
                    val batchItems = if (social) submittedPreview?.let { JSONObject(it) }?.takeIf { it.optString("url") == url }?.optJSONArray("items") else null
                    require(!social || batchItems != null) { "Preview this post again before downloading." }
                    totalPhotos = batchItems?.length() ?: photoItems?.length() ?: 0
                    require(photoItems == null || totalPhotos in 1..4)
                    require(batchItems == null || totalPhotos in 1..20)
                    for (index in 0 until (if (totalPhotos == 0) 1 else totalPhotos)) {
                    val item = batchItems?.getJSONObject(index)
                    val request = request(if (item != null) "" else url)
                    val file: File
                    if (item?.has("image") == true) {
                        file = File(temp, "photo.$index.jpg")
                        var last = 0L
                        photos.downloadSocial(item.getString("image"), file) { bytes, length ->
                            val now = SystemClock.elapsedRealtime()
                            if (now - last >= 250) {
                                last = now
                                status("Item ${index + 1}/$totalPhotos · ${bytes / 1024} KiB", if (length > 0) ((index + bytes.toDouble() / length) * 100 / totalPhotos).toInt().coerceIn(0, 99) else -1)
                            }
                        }
                    } else if (photoItems != null) {
                        val source = photoItems.getString(index)
                        Rules.twitterImage(source)
                        file = File(temp, "photo.$index.${source.substringAfterLast('.')}")
                        var last = 0L
                        photos.download(source, file) { bytes, length ->
                            val now = SystemClock.elapsedRealtime()
                            if (now - last >= 250) {
                                last = now
                                val percent = if (length > 0) ((index + bytes.toDouble() / length) * 100 / totalPhotos).toInt().coerceIn(0, 99) else -1
                                status("Photo ${index + 1}/$totalPhotos · ${bytes / 1024} KiB", percent)
                            }
                        }
                    } else {
                    if (item != null) {
                        val infoFile = File(temp, "entry.json")
                        infoFile.writeText(item.getJSONObject("info").toString())
                        request.addOption("--load-info-json", infoFile.absolutePath)
                    }
                    request.addOption("-f", Rules.format(choice)).addOption("-o", File(temp, "media.%(ext)s").absolutePath)
                    if (choice == "audio") {
                        val plugins = File(temp, "plugins")
                        val plugin = File(plugins, "aio/yt_dlp_plugins/postprocessor/optional_art.py")
                        check(plugin.parentFile!!.isDirectory || plugin.parentFile!!.mkdirs())
                        assets.open("optional_art.py").use { input -> plugin.outputStream().use { input.copyTo(it) } }
                        request.addOption("-x").addOption("--audio-format", "mp3")
                            .addOption("--embed-metadata").addOption("--embed-thumbnail")
                            .addOption("--plugin-dirs", plugins.absolutePath)
                    }
                    var lastUpdate = 0L
                    YoutubeDL.execute(request, "aio") { progress, _, _ ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastUpdate >= 1000 && !cancelled) {
                            lastUpdate = now
                            val percent = if (progress.isFinite() && progress >= 0) progress.toInt().coerceIn(0, 100) else -1
                            val prefix = if (totalPhotos > 0) "Item ${index + 1}/$totalPhotos · " else ""
                            status(prefix + if (percent < 0) "Downloading…" else if (percent == 100) "Processing media…" else "Downloading $percent%…", if (percent < 0 || percent == 100) -1 else if (totalPhotos > 0) (index * 100 + percent) / totalPhotos else percent)
                        }
                    }
                    ensureActive()
                    file = temp.listFiles()?.singleOrNull { it.isFile && it.name.startsWith("media.") && it.extension !in listOf("part", "ytdl", "jpg", "jpeg", "png", "webp") && !it.name.contains(".temp.") }
                        ?: error("No single completed media file was produced.")
                    }
                    ensureActive()
                    check(file.length() > 0) { "Downloaded file is empty." }
                    status("Saving to Downloads/AIOdownloader…")
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, Rules.filename((intent?.getStringExtra("title") ?: "media") + if (totalPhotos > 0) "_${index + 1}" else "", file.extension))
                        put(MediaStore.Downloads.MIME_TYPE, MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream")
                        put(MediaStore.Downloads.RELATIVE_PATH, "Download/AIOdownloader")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    pending = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Cannot create saved file.")
                    check(prefs.edit().putString("pending", pending.toString()).commit()) { "Cannot record pending save." }
                    contentResolver.openOutputStream(pending!!, "w")!!.use { output ->
                        file.inputStream().use { input ->
                            val buffer = ByteArray(65536)
                            while (true) { ensureActive(); val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count) }
                        }
                    }
                    ensureActive()
                    try {
                        if (!Gallery.record(this, pending.toString(), batch, url, intent?.getStringExtra("title") ?: "")) groupingUnavailable = true
                    } catch (e: Exception) {
                        android.util.Log.w("AIO", "Gallery grouping could not be saved", e)
                        groupingUnavailable = true
                    }
                    check(contentResolver.update(pending!!, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1) { "Cannot finish saving file." }
                    pending = null
                    if (totalPhotos > 0) savedPhotos++
                    prefs.edit().remove("pending").commit()
                    check(file.delete()) { "Cannot remove temporary media" }
                    }
                    val savedState = prefs.edit().remove("pending")
                    // Clear only the selection this transfer accepted, never a newer preview/link.
                    if (prefs.getString("preview", null) == submittedPreview) savedState.remove("preview")
                    savedState.commit()
                    result = if (totalPhotos > 0) "Saved $savedPhotos/$totalPhotos ${if (photoItems != null) "photos" else "items"} to Downloads/AIOdownloader." else "Saved to Downloads/AIOdownloader."
                }
            } catch (e: Exception) {
                if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                    android.util.Log.e("AIO", "${intent?.action} extraction/transfer failed", e)
                result = if (cancelled || e is InterruptedException || e is YoutubeDL.CanceledException) "Cancelled or timed out. No partial download kept." else
                    Rules.failure(e.message)
                if (savedPhotos > 0) result = "Saved $savedPhotos/$totalPhotos items; completed files kept. " +
                    if (cancelled || e is InterruptedException) "Remaining items cancelled or timed out." else "Remaining items failed. ${Rules.failure(e.message)}"
            } finally {
                Thread.interrupted()
                deadline.cancel(false); killer.cancel(false)
                pending?.let { uri ->
                    try { contentResolver.delete(uri, null, null); prefs.edit().remove("pending").commit() }
                    catch (_: Exception) { result += " Unfinished save will be cleaned on next operation." }
                }
                if (!temp.deleteRecursively()) result += " Temporary cleanup deferred until next operation."
                if (groupingUnavailable) result += " Gallery grouping unavailable; saved files remain accessible."
                thread = null
                Handler(Looper.getMainLooper()).post {
                    running = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    notifications.notify(2, notification(result, false))
                    stopSelf(startId)
                    prefs.edit().putBoolean("busy", false).putInt("progress", -1).putString("status", result).apply()
                }
            }
        }
        return START_NOT_STICKY
    }
    private fun ensureActive() { if (cancelled || Thread.currentThread().isInterrupted) throw InterruptedException() }
    override fun onTimeout(startId: Int, fgsType: Int) { cancel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { if (running) cancel(); worker.shutdownNow(); watchdog.shutdown(); super.onDestroy() }
}
