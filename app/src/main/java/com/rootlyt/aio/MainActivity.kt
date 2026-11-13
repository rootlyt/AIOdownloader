package com.rootlyt.aio

import android.Manifest
import android.content.*
import android.graphics.BitmapFactory
import android.os.*
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("state", MODE_PRIVATE) }
    private lateinit var input: TextInputEditText
    private lateinit var status: TextView
    private lateinit var title: TextView
    private lateinit var image: ImageView
    private lateinit var quality: Spinner
    private lateinit var download: MaterialButton
    private lateinit var cancel: MaterialButton
    private lateinit var progress: com.google.android.material.progressindicator.LinearProgressIndicator
    private lateinit var saved: LinearLayout
    private lateinit var update: MaterialButton
    private var queuedShare: String? = null
    private var submitted = false
    private var requestId: String? = null
    private var clearRequest: String? = null
    private var clearText: String? = null
    private lateinit var downloadPage: ScrollView
    private lateinit var libraryPage: ScrollView
    private lateinit var pager: androidx.viewpager.widget.ViewPager
    private val libraryWorker = Executors.newSingleThreadExecutor()
    private var librarySignal = CancellationSignal()
    private var libraryGeneration = 0
    private var pendingDelete: String? = null
    private var groupDialog: androidx.appcompat.app.AlertDialog? = null
    private var groupBatch: String? = null
    private var groupItems = emptyList<Gallery.Item>()
    private var groupAdapter: BaseAdapter? = null
    private var groupSignal = CancellationSignal()
    private var wasBusy = false
    private var preview: JSONObject? = null
    private var choices = listOf("best", "audio")
    private var rendered = ""
    private val images = Executors.newSingleThreadExecutor()
    @Volatile private var connection: HttpURLConnection? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> runOnUiThread { render() } }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        // A fresh idle Home is not a saved selection; recreation during work keeps it.
        if (state == null && !prefs.getBoolean("busy", false)) prefs.edit().remove("preview").apply()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(8)) }
        downloadPage = ScrollView(this).apply { isFillViewport = true; addView(root) }
        val library = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), dp(8)) }
        libraryPage = ScrollView(this).apply { isFillViewport = true; addView(library) }
        val pageNames = listOf("Home", "Gallery")
        val pages = listOf(downloadPage, libraryPage)
        pager = androidx.viewpager.widget.ViewPager(this).apply {
            id = R.id.page_download
            adapter = object : androidx.viewpager.widget.PagerAdapter() {
                override fun getCount() = pages.size
                override fun isViewFromObject(view: View, item: Any) = view === item
                override fun instantiateItem(container: android.view.ViewGroup, position: Int): Any =
                    pages[position].also { container.addView(it) }
                override fun destroyItem(container: android.view.ViewGroup, position: Int, item: Any) { container.removeView(item as View) }
                override fun getPageTitle(position: Int) = pageNames[position]
            }
        }
        val primary = com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, 0)
        val muted = com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant, 0)
        val indicators = pageNames.mapIndexed { index, name ->
            FrameLayout(this).apply {
                isClickable = true; isFocusable = true
                contentDescription = "$name, page ${index + 1} of 2"
                // Keep feedback inside the small track, not a borderless circular ripple.
                addView(View(this@MainActivity).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; isDuplicateParentStateEnabled = true },
                    FrameLayout.LayoutParams(dp(24), dp(3), android.view.Gravity.CENTER))
                setOnClickListener { pager.setCurrentItem(index, true) }
            }
        }
        fun indicate(position: Int) {
            indicators.forEachIndexed { index, target ->
                target.isSelected = index == position
                target.getChildAt(0).background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(2).toFloat()
                    setColor(android.content.res.ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf(android.R.attr.state_focused), intArrayOf()),
                        intArrayOf(primary, primary, if (index == position) primary else muted)))
                }
            }
        }
        pager.addOnPageChangeListener(object : androidx.viewpager.widget.ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) { indicate(position) }
        })
        indicate(0)
        val indicator = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(com.google.android.material.color.MaterialColors.getColor(this@MainActivity, com.google.android.material.R.attr.colorSurfaceContainer, 0))
            }
            elevation = dp(2).toFloat()
            clipToOutline = true
            indicators.forEach { addView(it, LinearLayout.LayoutParams(dp(48), dp(48))) }
        }
        // Scrollable clearance, not a reserved bar: content can pass behind the pill.
        root.setPadding(dp(24), dp(24), dp(24), dp(64))
        library.setPadding(dp(24), dp(16), dp(24), dp(64))
        val shell = FrameLayout(this).apply {
            addView(pager, FrameLayout.LayoutParams(-1, -1))
            addView(indicator, FrameLayout.LayoutParams(dp(96), dp(48), android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(8)
            })
        }
        setContentView(shell)
        shell.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom); insets
        }
        fun label(text: String, size: Float = 16f) = TextView(this).apply { this.text = text; textSize = size; setPadding(0, dp(12), 0, dp(12)); root.addView(this) }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(com.google.android.material.color.MaterialColors.getColor(this@MainActivity, com.google.android.material.R.attr.colorPrimaryContainer, 0))
            }
            addView(TextView(this@MainActivity).apply {
                text = "AIOdownloader"; textSize = 26f; gravity = android.view.Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(com.google.android.material.color.MaterialColors.getColor(this@MainActivity, com.google.android.material.R.attr.colorOnPrimaryContainer, 0))
            })
        }, LinearLayout.LayoutParams(-1, -2))
        label("Download", 20f)
        val field = TextInputLayout(this).apply {
            hint = "HTTP or HTTPS media link"; boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(dp(16).toFloat(), dp(16).toFloat(), dp(16).toFloat(), dp(16).toFloat())
        }
        input = TextInputEditText(field.context).apply {
            id = View.generateViewId(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true); filters = arrayOf(android.text.InputFilter.LengthFilter(8192))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, action, event ->
                if (action == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                    (event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_UP)) {
                    previewInput(); true
                } else false
            }
        }
        field.addView(input); root.addView(field)
        fun button(text: String, action: () -> Unit) = MaterialButton(this).apply {
            this.text = text; isAllCaps = false; cornerRadius = dp(16); minHeight = dp(48)
            setOnClickListener { action() }
            root.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
        val paste = button("Paste & preview") {
            val clipboard = getSystemService(ClipboardManager::class.java)
            val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
            if (text.isNullOrBlank()) input.error = "Copy a media link first." else acceptLink(text)
        }
        update = button("Update Youtube Site Extractor") { launch("update", "") }
        val compactActions = resources.configuration.screenWidthDp >= 320 && resources.configuration.fontScale <= 1.3f
        val previewActions = LinearLayout(this).apply {
            isBaselineAligned = false
            orientation = if (compactActions) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        root.removeView(paste); root.removeView(update); root.addView(previewActions)
        listOf(paste, update).forEachIndexed { index, control ->
            control.textSize = 14f; control.minimumWidth = 0; control.minHeight = dp(64)
            control.insetTop = 0; control.insetBottom = 0
            control.setPadding(dp(8), dp(10), dp(8), dp(10))
            previewActions.addView(control, LinearLayout.LayoutParams(if (compactActions) 0 else -1, -2, if (compactActions) 1f else 0f).apply {
                topMargin = dp(8); if (compactActions && index == 1) marginStart = dp(8)
            })
        }
        image = ImageView(this).apply { adjustViewBounds = true; maxHeight = dp(220); contentDescription = "Media thumbnail"; visibility = View.GONE }
        root.addView(image)
        title = label("Preview appears here", 20f)
        label("Quality / format")
        quality = Spinner(this).apply { minimumHeight = dp(48); contentDescription = "Download quality and format" }; root.addView(quality)
        download = button("Download selected quality") {
            val info = preview ?: return@button
            try {
                require(Rules.url(input.text.toString()) == info.getString("url")) { "Link changed. Preview it again first." }
                launch("download", info.getString("url"), choices[quality.selectedItemPosition], info.optString("title", "media"))
            } catch (e: IllegalArgumentException) { input.error = e.message }
        }
        cancel = button("Cancel current operation") { startService(Intent(this, DownloadService::class.java).setAction("cancel")) }
        progress = com.google.android.material.progressindicator.LinearProgressIndicator(this).apply {
            trackThickness = dp(6); trackCornerRadius = dp(3); contentDescription = "Operation progress"; visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(12)))
        status = label("Ready. Paste or share one link.").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        val footer = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        root.addView(footer)
        footer.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "About"; isAllCaps = false; textSize = 14f; minHeight = dp(48)
            setPadding(dp(16), dp(12), dp(16), dp(12)); insetTop = 0; insetBottom = 0; cornerRadius = dp(16)
            setOnClickListener { showAbout() }
        }, LinearLayout.LayoutParams(-2, -2))
        footer.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Supported Site"; isAllCaps = false; textSize = 14f; minHeight = dp(48)
            setPadding(dp(16), dp(12), dp(16), dp(12)); cornerRadius = dp(16)
            insetTop = 0; insetBottom = 0; minimumWidth = 0
            contentDescription = "Site support and photo limitations"
            setOnClickListener {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Site support")
                    .setMessage("Facebook · Reels / videos, including multiple videos returned for a post. Photos / albums unavailable; mixed posts may expose videos only.\n\nInstagram · Reels / videos and multi-video posts (up to 20 items). Experimental photo products: only explicitly identified JPEGs returned by the first-party extractor; current photo fixtures have not succeeded. Not guaranteed photo/carousel support.\n\nX / Twitter · Videos and public photo-only posts (1–4 photos).\n\nYouTube · Videos / audio.\n\nSoundCloud · Audio; some tracks expose previews only.\n\nMP3 available from supported video/audio. Public access and extractor availability vary; no login, cookies or playlist downloads.")
                    .setPositiveButton("Close", null).show()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        library.addView(TextView(this).apply { text = "Gallery"; textSize = 28f })
        library.addView(TextView(this).apply { text = "Downloads/AIOdownloader"; setPadding(0, dp(8), 0, dp(16)) })
        library.addView(MaterialButton(this).apply { text = "Refresh saved files"; setOnClickListener { refreshSaved() } })
        val layouts = com.google.android.material.button.MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true; isSelectionRequired = true
            listOf("List", "Grid").forEachIndexed { index, name ->
                addView(MaterialButton(this@MainActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    id = View.generateViewId(); text = name; minHeight = dp(48); contentDescription = "$name gallery layout"
                    isChecked = prefs.getBoolean("galleryGrid", false) == (index == 1)
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            check(getChildAt(if (prefs.getBoolean("galleryGrid", false)) 1 else 0).id)
            addOnButtonCheckedListener { group, id, checked -> if (checked) {
                prefs.edit().putBoolean("galleryGrid", group.indexOfChild(group.findViewById(id)) == 1).apply()
                refreshSaved()
            } }
        }
        library.addView(layouts)
        saved = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; library.addView(saved)
        pager.setCurrentItem(if (state?.getInt("page") == 1) 1 else 0, false)
        pendingDelete = state?.getString("pendingDelete")
        input.setText(state?.getString("input") ?: if (intent.action == Intent.ACTION_SEND) Rules.shared(intent.getStringExtra(Intent.EXTRA_TEXT) ?: "") else "")
        queuedShare = state?.getString("queuedShare")
        clearRequest = state?.getString("clearRequest"); clearText = state?.getString("clearText")
        submitted = state?.getBoolean("submitted") ?: false
        requestId = state?.getString("requestId")
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                clearRequest = null; clearText = null
                if (queuedShare != s.toString()) queuedShare = null
                render()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        prefs.registerOnSharedPreferenceChangeListener(listener)
        render()
        if (choices.isNotEmpty()) quality.setSelection((state?.getInt("quality", 0) ?: 0).coerceIn(0, choices.lastIndex))
        downloadPage.post { downloadPage.scrollTo(0, state?.getInt("downloadScroll", 0) ?: 0) }
        if (state == null && intent.action == Intent.ACTION_SEND) acceptLink(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }
    private fun launch(action: String, url: String, choice: String = "best", title: String = "media") {
        if (submitted || prefs.getBoolean("busy", false)) return
        val requestId = java.util.UUID.randomUUID().toString()
        this.requestId = requestId
        submitted = true
        if (action == "download") { clearRequest = requestId; clearText = input.text.toString() }
        render()
        try {
            startForegroundService(Intent(this, DownloadService::class.java).setAction(action).putExtra("url", url).putExtra("quality", choice).putExtra("title", title).putExtra("requestId", requestId))
        } catch (_: RuntimeException) { submitted = false; clearRequest = null; render(); status.text = "Cannot start operation. Keep the app open and retry." }
    }
    private fun render() {
        val active = prefs.getBoolean("busy", false)
        if (active || (requestId != null && requestId == prefs.getString("accepted", ""))) submitted = false
        val busy = active || submitted
        update.isEnabled = !busy; cancel.isEnabled = active; quality.isEnabled = !busy
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        val percent = prefs.getInt("progress", -1)
        progress.isIndeterminate = percent < 0
        if (percent >= 0) progress.setProgressCompat(percent, true)
        status.text = if (submitted) "Starting…" else prefs.getString("status", "Ready. Paste or share one link.")
        if (clearRequest != null && clearRequest == prefs.getString("accepted", "")) {
            val oldText = clearText
            clearRequest = null; clearText = null
            if (input.text.toString() == oldText) input.text?.clear()
        }
        if (wasBusy && !busy) refreshSaved()
        wasBusy = busy
        if (!busy && queuedShare != null) {
            val next = queuedShare!!; queuedShare = null
            if (input.text.toString() == next) { launch("preview", next); return }
        }
        val raw = prefs.getString("preview", "") ?: ""
        if (raw.isEmpty()) {
            rendered = ""; preview = null
            connection?.disconnect()
            image.setImageDrawable(null); image.visibility = View.GONE
            title.text = "Preview appears here"
            choices = emptyList()
            quality.adapter = null
        } else if (raw != rendered) {
            rendered = raw
            preview = JSONObject(raw)
            val info = preview!!
            val photoCount = info.optJSONArray("photos")?.length() ?: info.optInt("imageCount")
            val videoCount = info.optInt("videoCount")
            val photoOnly = photoCount > 0 && videoCount == 0
            title.text = info.optString("title") + if (info.has("items")) " · $photoCount photos · $videoCount videos" else if (photoCount > 0) " · $photoCount photos" else ""
            val heights = info.optJSONArray("heights")
            choices = if (photoOnly) listOf("best") else listOf("best") + (0 until (heights?.length() ?: 0)).map { heights!!.getInt(it).toString() }.reversed() + "audio"
            quality.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, choices.map {
                if (photoOnly) "Original photos" else when (it) { "best" -> "Best available video"; "audio" -> "Audio only · MP3"; else -> "Up to ${it}p" }
            })
            loadImage(info.optString("thumbnail"), raw)
        }
        val photoCount = preview?.optJSONArray("photos")?.length() ?: 0
        val itemCount = preview?.optJSONArray("items")?.length() ?: 0
        download.text = if (itemCount > 0) "Download all $itemCount items" else if (photoCount > 0) "Download all $photoCount photos" else "Download selected quality"
        download.isEnabled = !busy && preview != null && runCatching { Rules.url(input.text.toString()) == preview?.optString("url") }.getOrDefault(false)
    }
    private fun loadImage(url: String, token: String) {
        image.visibility = View.GONE
        connection?.disconnect()
        images.execute {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(Rules.url(url)).openConnection() as HttpURLConnection
                connection = conn
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 10000; conn.readTimeout = 10000
                require(conn.responseCode == 200)
                val bytes = conn.inputStream.use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) { val n = stream.read(buffer); if (n < 0) break; require(output.size() + n <= 2_000_000); output.write(buffer, 0, n) }
                    output.toByteArray()
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val options = BitmapFactory.Options().apply { inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 800) }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                runOnUiThread { if (!isDestroyed && token == rendered && bitmap != null) { image.setImageBitmap(bitmap); image.visibility = View.VISIBLE } }
            } catch (_: Exception) {
                // Optional thumbnail failure does not block metadata or downloading.
            } finally { conn?.disconnect(); if (connection === conn) connection = null }
        }
    }
    private fun showAbout() {
        val credits = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(16)) }
        fun text(parent: LinearLayout, value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
            text = value; textSize = size; setPadding(0, dp(8), 0, dp(4))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            parent.addView(this)
        }
        fun link(parent: LinearLayout, label: String, url: String, description: String = label) {
            parent.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = label; isAllCaps = false; minHeight = dp(48); contentDescription = description
                setOnClickListener {
                    try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                    catch (_: ActivityNotFoundException) { Toast.makeText(this@MainActivity, "No browser installed.", Toast.LENGTH_SHORT).show() }
                }
            })
        }
        fun group(): LinearLayout {
            val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(12)) }
            credits.addView(com.google.android.material.card.MaterialCardView(this).apply {
                radius = dp(20).toFloat(); cardElevation = 0f
                setCardBackgroundColor(com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainer, 0))
                addView(content)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
            return content
        }
        text(credits, "AIOdownloader", 26f, true)
        text(credits, "Your media, saved simply.", 14f)
        val identity = group()
        text(identity, "Made by rootlyt", 18f, true)
        link(identity, "GitHub · rootlyt", "https://github.com/rootlyt", "Author rootlyt on GitHub")
        val versions = group()
        val appVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "Unknown"
        text(versions, "Versions", 18f, true)
        text(versions, "App · $appVersion", 16f)
        // Existing installs stored the successful background version query in updateNote.
        val legacyVersion = prefs.getString("updateNote", null)?.takeIf { it.startsWith("yt-dlp ") }?.removePrefix("yt-dlp ")
        val extractorVersion = prefs.getString("extractorVersion", legacyVersion)
        text(versions, "yt-dlp · ${extractorVersion ?: "Not checked yet"}", 16f)
        text(versions, "Extractor version at last successful update check. Update it from Home.", 12f)
        prefs.getString("updateNote", null)?.takeUnless { it.startsWith("yt-dlp ") }?.let { text(versions, it, 12f) }
        text(credits, "Built with open source", 20f, true)
        listOf(
            Triple("yt-dlp · Unlicense (source)", "https://github.com/yt-dlp/yt-dlp", "https://github.com/yt-dlp/yt-dlp/blob/master/LICENSE"),
            Triple("youtubedl-android 0.18.1 · GPL-3.0", "https://github.com/yausername/youtubedl-android/tree/0.18.1", "https://github.com/yausername/youtubedl-android/blob/0.18.1/LICENSE"),
            Triple("FFmpeg 7.1.1 · GPL-3.0-or-later (bundled build)", "https://github.com/FFmpeg/FFmpeg/tree/n7.1.1", "https://ffmpeg.org/legal.html"),
            Triple("QuickJS · MIT", "https://bellard.org/quickjs/", "https://github.com/bellard/quickjs/blob/master/LICENSE"),
            Triple("Python · PSF License", "https://github.com/python/cpython", "https://docs.python.org/3/license.html"),
            Triple("Material Components · Apache-2.0", "https://github.com/material-components/material-components-android", "https://github.com/material-components/material-components-android/blob/master/LICENSE"),
            Triple("AndroidX · Apache-2.0", "https://android.googlesource.com/platform/frameworks/support/", "https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt")
        ).forEach { (name, source, license) ->
            val item = group()
            text(item, name.substringBefore(" · "), 16f, true)
            text(item, name.substringAfter(" · "), 12f)
            val links = LinearLayout(this)
            item.addView(links)
            link(links, "Source", source, "Source: $name")
            link(links, "License", license, "License: $name")
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("About")
            .setView(ScrollView(this).apply { addView(credits) }).setPositiveButton("Close", null).show()
    }
    private fun previewInput() {
        try { launch("preview", Rules.url(input.text.toString())) }
        catch (e: IllegalArgumentException) { input.error = e.message }
    }
    private fun acceptLink(text: String) {
        try {
            val url = Rules.url(Rules.shared(text))
            pager.setCurrentItem(0, true)
            input.setText(url)
            queuedShare = url
            render()
        } catch (e: IllegalArgumentException) { input.error = e.message }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (intent.action == Intent.ACTION_SEND) acceptLink(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
    }
    override fun onResume() { super.onResume(); refreshSaved() }
    private fun refreshSaved() {
        librarySignal.cancel()
        val signal = CancellationSignal().also { librarySignal = it }
        val generation = ++libraryGeneration
        libraryWorker.execute {
            try {
                val rows = mutableListOf<Gallery.Item>()
                val store = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                contentResolver.query(store, arrayOf("_id", "_display_name", "mime_type"),
                    "relative_path=? AND is_pending=0 AND owner_package_name=?", arrayOf("Download/AIOdownloader/", packageName), "date_added DESC, _id DESC", signal)?.use { cursor ->
                    while (cursor.moveToNext()) rows.add(Gallery.Item(ContentUris.withAppendedId(store, cursor.getLong(0)).toString(), cursor.getString(1), cursor.getString(2) ?: "application/octet-stream"))
                } ?: error("MediaStore unavailable")
                signal.throwIfCanceled()
                val records = Gallery.records(this, rows.map { it.uri }.toSet())
                // ponytail: newest 50 complete groups; add paging for a full library browser.
                val groups = Gallery.groups(rows.map { item -> records[item.uri]?.let { item.copy(batch = it.first, title = it.second) } ?: item }).take(50)
                runOnUiThread {
                    if (isDestroyed || generation != libraryGeneration) return@runOnUiThread
                    if (groupDialog != null) {
                        groupSignal.cancel(); groupSignal = CancellationSignal()
                        groupItems = groups.flatten().filter { it.batch == groupBatch }
                        if (groupItems.isEmpty()) groupDialog?.dismiss()
                        else { groupDialog?.setTitle("${groupItems.size} saved items"); groupAdapter?.notifyDataSetChanged() }
                    }
                    saved.removeAllViews()
                    if (rows.isEmpty()) saved.addView(TextView(this).apply { text = "No saved files yet." })
                    val grid = prefs.getBoolean("galleryGrid", false) && resources.configuration.fontScale <= 1.3f
                    val columns = if (grid && resources.configuration.screenWidthDp >= 400 && resources.configuration.fontScale <= 1.3f) 2 else 1
                    groups.chunked(columns).forEach { section ->
                        val row = LinearLayout(this)
                        saved.addView(row)
                        section.forEach { items ->
                            val card = com.google.android.material.card.MaterialCardView(this).apply {
                                radius = dp(20).toFloat(); cardElevation = 0f
                                setCardBackgroundColor(com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainer, 0))
                            }
                            val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(8)) }
                            card.addView(content)
                            row.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), dp(6), dp(4), dp(6)) })
                            if (items.size == 1) savedItem(content, items.first(), grid, signal, generation)
                            else {
                                val cover = ImageView(this).apply {
                                    scaleType = ImageView.ScaleType.CENTER_CROP; contentDescription = null
                                    setImageResource(android.R.drawable.ic_menu_gallery)
                                }
                                content.addView(cover, LinearLayout.LayoutParams(-1, dp(if (grid) 100 else 144)))
                                loadSavedThumbnail(cover, items, signal, generation)
                                content.addView(savedTitle(items.first().title.ifBlank { "Downloaded post" }, grid).apply {
                                    if (!grid) { textSize = 16f; setPadding(0, 0, 0, 0) }
                                })
                                content.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                                    text = "Show ${items.size} items"; minHeight = dp(48); isAllCaps = false
                                    if (grid) {
                                        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                                        minimumWidth = 0; insetTop = 0; insetBottom = 0; setPadding(0, 0, 0, 0)
                                    }
                                    contentDescription = "Show ${items.size} items: ${items.first().title}"
                                    setOnClickListener { showGroup(items) }
                                }, LinearLayout.LayoutParams(-1, if (grid) dp(48) else -2))
                            }
                        }
                        if (section.size < columns) row.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
                    }
                }
            } catch (_: Exception) {
                runOnUiThread { if (!isDestroyed && generation == libraryGeneration) { saved.removeAllViews(); saved.addView(TextView(this).apply { text = "Cannot read saved files. Tap Refresh to retry." }) } }
            }
        }
    }
    private fun showGroup(items: List<Gallery.Item>) {
        groupDialog?.dismiss()
        groupItems = items; groupBatch = items.first().batch; groupSignal = CancellationSignal()
        // Native ListView bounds work to visible rows even for large batches.
        val list = ListView(this).apply { dividerHeight = dp(8); setPadding(dp(16), dp(8), dp(16), dp(8)) }
        groupAdapter = object : BaseAdapter() {
            override fun getCount() = groupItems.size
            override fun getItem(position: Int) = groupItems[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                row.removeAllViews()
                savedItem(row, getItem(position), false, groupSignal, libraryGeneration)
                return row
            }
        }
        list.adapter = groupAdapter
        groupDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("${items.size} saved items").setView(list).setPositiveButton("Close", null).create().apply {
                setOnDismissListener {
                    groupSignal.cancel(); list.adapter = null
                    groupDialog = null; groupAdapter = null; groupBatch = null; groupItems = emptyList()
                }
                show()
                window?.setLayout(-1, (resources.displayMetrics.heightPixels * 0.75f).toInt())
            }
    }
    private fun loadSavedThumbnail(thumbnail: ImageView, items: List<Gallery.Item>, signal: CancellationSignal, generation: Int) {
        libraryWorker.execute {
            for (item in items) {
                if (signal.isCanceled || Thread.currentThread().isInterrupted) return@execute
                try {
                    val bitmap = contentResolver.loadThumbnail(android.net.Uri.parse(item.uri), android.util.Size(480, 270), signal)
                    runOnUiThread {
                        if (!isDestroyed && !signal.isCanceled && generation == libraryGeneration && thumbnail.isAttachedToWindow)
                            thumbnail.setImageBitmap(bitmap)
                        else bitmap.recycle()
                    }
                    return@execute
                } catch (_: java.io.IOException) { /* Try the next child; keep placeholder if none load. */ }
                catch (_: SecurityException) { /* Removed or inaccessible child. */ }
                catch (_: OperationCanceledException) { return@execute }
                catch (e: RuntimeException) { android.util.Log.w("AIO", "Thumbnail unavailable", e) }
            }
        }
    }
    private fun savedTitle(name: String, grid: Boolean) = TextView(this).apply {
        text = name; contentDescription = name; textSize = 14f
        maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
        if (grid) setLines(2)
        setPadding(dp(8), dp(8), dp(4), dp(8))
    }
    private fun savedItem(parent: LinearLayout, item: Gallery.Item, grid: Boolean, signal: CancellationSignal, generation: Int) {
        val uri = android.net.Uri.parse(item.uri)
        val body = LinearLayout(this).apply { orientation = if (grid) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        parent.addView(body)
        val thumbnail = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; contentDescription = null
            setImageResource(android.R.drawable.ic_menu_gallery)
        }
        body.addView(thumbnail, LinearLayout.LayoutParams(if (grid) -1 else dp(72), dp(if (grid) 100 else 72)))
        body.addView(savedTitle(item.name, grid), if (grid) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(0, -2, 1f))
        loadSavedThumbnail(thumbnail, listOf(item), signal, generation)
        val actions = LinearLayout(this)
        parent.addView(actions, LinearLayout.LayoutParams(-1, if (grid) dp(48) else -2))
        fun action(label: String, click: () -> Unit) {
            actions.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = label; contentDescription = "$label ${item.name}"; minHeight = dp(48); minimumWidth = 0
                insetTop = 0; insetBottom = 0; setPadding(0, 0, 0, 0); isAllCaps = false
                if (grid) { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
                setOnClickListener { click() }
            }, LinearLayout.LayoutParams(0, if (grid) -1 else -2, 1f))
        }
        action("Open") {
            try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            catch (_: ActivityNotFoundException) { Toast.makeText(this, "No app can open this format.", Toast.LENGTH_LONG).show() }
            catch (_: SecurityException) { Toast.makeText(this, "File is no longer accessible. Refresh saved files.", Toast.LENGTH_LONG).show() }
        }
        action("Delete") {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Delete downloaded file?").setMessage("${item.name}\nDeletes only this file, not other items in the post. This permanently removes it from device storage.")
                .setNegativeButton("Keep", null).setPositiveButton("Delete") { _, _ -> deleteSaved(uri) }.show()
        }
    }
    private fun deleteSaved(uri: android.net.Uri) {
        if (uri.scheme != "content" || uri.authority != "media" || !Regex("/external/downloads/[0-9]+").matches(uri.path.orEmpty())) return
        libraryWorker.execute {
            try {
                // Recheck ownership and folder at deletion time; never delete arbitrary shared files.
                val count = contentResolver.delete(uri, "owner_package_name=? AND relative_path=? AND is_pending=0",
                    arrayOf(packageName, "Download/AIOdownloader/"))
                runOnUiThread { if (!isDestroyed) {
                    Toast.makeText(this, if (count > 0) "File deleted." else "File no longer available.", Toast.LENGTH_SHORT).show()
                    refreshSaved()
                } }
            } catch (e: android.app.RecoverableSecurityException) {
                runOnUiThread { if (!isDestroyed) {
                    pendingDelete = uri.toString()
                    try { startIntentSenderForResult(e.userAction.actionIntent.intentSender, 7, null, 0, 0, 0) }
                    catch (_: IntentSender.SendIntentException) { pendingDelete = null; Toast.makeText(this, "Cannot request deletion permission.", Toast.LENGTH_LONG).show() }
                } }
            } catch (e: Exception) {
                android.util.Log.e("AIO", "Delete failed", e)
                runOnUiThread { if (!isDestroyed) Toast.makeText(this, "Cannot delete file. Refresh and retry.", Toast.LENGTH_LONG).show() }
            }
        }
    }
    @Deprecated("Platform consent result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7) {
            val uri = pendingDelete; pendingDelete = null
            if (resultCode == RESULT_OK && uri != null) deleteSaved(android.net.Uri.parse(uri))
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("page", pager.currentItem); outState.putString("pendingDelete", pendingDelete)
        outState.putInt("quality", quality.selectedItemPosition); outState.putInt("downloadScroll", downloadPage.scrollY)
        outState.putString("input", input.text.toString()); outState.putString("queuedShare", queuedShare)
        outState.putString("clearRequest", clearRequest); outState.putString("clearText", clearText)
        outState.putBoolean("submitted", submitted); outState.putString("requestId", requestId); super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { groupDialog?.dismiss(); groupSignal.cancel(); prefs.unregisterOnSharedPreferenceChangeListener(listener); connection?.disconnect(); librarySignal.cancel(); libraryWorker.shutdownNow(); images.shutdownNow(); super.onDestroy() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
