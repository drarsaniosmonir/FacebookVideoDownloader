package com.arsany.facebookvideodownloader

import android.content.Intent
import android.media.MediaExtractor
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var urlView: TextView
    private lateinit var selectFolder: Button
    private lateinit var download: Button
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var webView: WebView

    private var pageUrl: String? = null
    private var directVideoUrl: String? = null
    private var folderUri: Uri? = null
    private var title = "facebook_video"
    private val videoCandidates = LinkedHashSet<String>()
    private val webResourceCandidates = HashSet<String>()

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                folderUri = uri
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                status.text = "Save folder selected."
                refreshButtons()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        urlView = findViewById(R.id.url)
        selectFolder = findViewById(R.id.selectFolder)
        download = findViewById(R.id.download)
        progress = findViewById(R.id.progress)
        progressText = findViewById(R.id.progressText)
        webView = findViewById(R.id.webview)

        selectFolder.setOnClickListener { folderPicker.launch(null) }
        download.setOnClickListener { downloadVideo() }

        configureWebView()
        receiveIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveIntent(intent)
    }

    private fun receiveIntent(intent: Intent?) {
        val candidates = mutableListOf<String>()

        intent?.getStringExtra(Intent.EXTRA_TEXT)?.let { candidates.add(it) }
        intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let { candidates.add(it) }
        intent?.dataString?.let { candidates.add(it) }

        intent?.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).text?.toString()?.let { candidates.add(it) }
                clip.getItemAt(i).uri?.toString()?.let { candidates.add(it) }
            }
        }

        val urls = candidates.flatMap { text ->
            Regex("""https?://[^\s\"'<>]+""")
                .findAll(text)
                .map { it.value.trimEnd('.', ',', ')', ']', '>') }
                .toList()
        }

        val incoming = urls
            .filter(::isHttpUrl)
            .sortedByDescending {
                when {
                    it.contains("facebook.com") && it.length > "https://www.facebook.com/".length -> 3
                    it.contains("fb.watch") -> 3
                    it.contains("facebook.com") -> 2
                    else -> 1
                }
            }
            .firstOrNull()

        if (incoming != null) {
            pageUrl = incoming
            directVideoUrl = null
            videoCandidates.clear()
            webResourceCandidates.clear()
            urlView.text = incoming
            urlView.visibility = View.VISIBLE
            status.text = "Opening Facebook video…"
            refreshButtons()
            resolve(incoming)
        } else {
            pageUrl = null
            directVideoUrl = null
            videoCandidates.clear()
            webResourceCandidates.clear()
            urlView.visibility = View.GONE
            status.text = "No usable Facebook video link was received."
            refreshButtons()
        }
    }

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("https://") || value.startsWith("http://")

    private fun configureWebView() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString =
                "Mozilla/5.0 (Linux; Android 16) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/131.0.0.0 Mobile Safari/537.36"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val candidate = request.url.toString()
                if (!request.isForMainFrame &&
                    request.method.equals("GET", ignoreCase = true) &&
                    looksLikeVideo(candidate) &&
                    request.requestHeaders["Range"].isNullOrBlank()
                ) {
                    addVideoCandidate(candidate, fromWebResource = true)
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (isFacebookPage(url)) {
                    pageUrl = url
                    urlView.text = url
                    urlView.visibility = View.VISIBLE
                }

                extractMediaFromPage(view)
                lifecycleScope.launch {
                    delay(2500)
                    extractMediaFromPage(view)
                    delay(3000)
                    extractMediaFromPage(view)
                }
            }
        }
    }

    private fun isFacebookPage(url: String): Boolean {
        val x = url.lowercase(Locale.US)
        return x.startsWith("https://www.facebook.com/") ||
            x.startsWith("https://facebook.com/") ||
            x.startsWith("https://m.facebook.com/")
    }

    private fun resolve(url: String) {
        directVideoUrl = null
        videoCandidates.clear()
        webResourceCandidates.clear()
        refreshButtons()
        status.text = "Loading Facebook…"
        webView.loadUrl(url)
    }

    private fun extractMediaFromPage(view: WebView) {
        val javascript = """
            (function() {
                try {
                    var found = [];
                    function add(u) {
                        if (!u) return;
                        try { u = String(u); } catch(e) { return; }
                        if (!u || u.indexOf("blob:") === 0 || u.indexOf("data:") === 0) return;
                        found.push(u);
                    }

                    var videos = document.querySelectorAll("video");
                    for (var i = 0; i < videos.length; i++) {
                        var v = videos[i];
                        add(v.currentSrc);
                        add(v.src);
                        try {
                            v.muted = true;
                            var p = v.play();
                            if (p && p.catch) p.catch(function(){});
                        } catch(e) {}
                        var sources = v.querySelectorAll("source");
                        for (var j = 0; j < sources.length; j++) add(sources[j].src);
                    }

                    var metas = document.querySelectorAll(
                        'meta[property="og:video"],' +
                        'meta[property="og:video:url"],' +
                        'meta[property="og:video:secure_url"]'
                    );
                    for (var m = 0; m < metas.length; m++) add(metas[m].content);

                    try {
                        var resources = performance.getEntriesByType("resource");
                        for (var r = 0; r < resources.length; r++) {
                            var ru = resources[r].name;
                            if (ru.indexOf(".mp4") >= 0 || ru.indexOf(".m3u8") >= 0 || ru.indexOf("fbcdn") >= 0) add(ru);
                        }
                    } catch(e) {}

                    // Facebook commonly exposes progressive media URLs in these fields.
                    try {
                        var html = document.documentElement.innerHTML;
                        var keys = [
                            "playable_url_quality_hd",
                            "playable_url",
                            "browser_native_hd_url",
                            "browser_native_sd_url"
                        ];
                        for (var k = 0; k < keys.length; k++) {
                            var key = keys[k];
                            var pos = 0;
                            while ((pos = html.indexOf(key, pos)) >= 0) {
                                var start = html.indexOf(":\"", pos + key.length);
                                if (start < 0) break;
                                start += 2;
                                var end = start;
                                while (end < html.length) {
                                    if (html.charAt(end) === '"' && html.charAt(end - 1) !== '\\') break;
                                    end++;
                                }
                                if (end > start) add(html.substring(start, end));
                                pos = end + 1;
                            }
                        }
                    } catch(e) {}

                    return found.join("\\n");
                } catch(e) {
                    return "";
                }
            })();
        """.trimIndent()

        view.evaluateJavascript(javascript) { result ->
            val decoded = decodeJavascriptString(result)
            decoded.split("\n")
                .map { it.trim() }
                .filter(::isHttpUrl)
                .distinct()
                .forEach { addVideoCandidate(it, fromWebResource = false) }

            val preferred = videoCandidates
                .maxByOrNull(::videoCandidateScore)

            if (preferred != null) {
                directVideoUrl = preferred
                status.text = "Video stream found. Choose a folder and download."
            } else if (pageUrl?.contains("/login") == true || pageUrl?.contains("checkpoint") == true) {
                status.text = "Facebook requires login to access this video."
            } else {
                status.text = "Looking for the video stream…"
            }
            refreshButtons()
        }
    }

    private fun addVideoCandidate(url: String, fromWebResource: Boolean) {
        if (!looksLikeVideo(url)) return
        videoCandidates.add(url)
        if (fromWebResource) webResourceCandidates.add(url)
        val best = videoCandidates.maxByOrNull(::videoCandidateScore)
        if (best != null) directVideoUrl = best
    }

    private fun videoCandidateScore(url: String): Int {
        val x = url.lowercase(Locale.US)
        var score = 0
        if (x.contains(".mp4")) score += 100
        if (x.contains("playable_url")) score += 80
        if (x.contains("browser_native")) score += 70
        if (x.contains("fbcdn")) score += 20
        if (webResourceCandidates.contains(url)) score -= 15
        if (x.contains("m3u8")) score -= 30
        if (x.contains("thumbnail") || x.contains("poster")) score -= 100
        if (x.contains("/p360x360/") || x.contains("/s360x360/")) score -= 10
        if (x.contains("/v/t")) score += 5
        return score
    }

    private fun looksLikeVideo(url: String): Boolean {
        val x = url.lowercase(Locale.US)
        if (x.startsWith("blob:") || x.startsWith("data:")) return false
        if (isFacebookPage(x)) return false
        if (x.contains("thumbnail") || x.contains("profile_pic") || x.contains("avatar")) return false
        return x.contains(".mp4") ||
            x.contains(".m3u8") ||
            x.contains("playable_url") ||
            x.contains("browser_native") ||
            (x.contains("fbcdn") && (x.contains("video") || x.contains("playable") || x.contains("/v/")))
    }

    private fun decodeJavascriptString(value: String): String {
        var x = value
        if (x.length >= 2 && x.startsWith("\"") && x.endsWith("\"")) {
            x = x.substring(1, x.length - 1)
        }
        return x
            .replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\u0026", "&")
            .replace("\\u003C", "<")
            .replace("\\u003E", ">")
            .replace("\\u0025", "%")
    }

    private fun refreshButtons() {
        download.isEnabled = pageUrl != null && folderUri != null
    }

    private fun downloadVideo() {
        val folder = folderUri ?: return
        val page = pageUrl ?: return
        val source = directVideoUrl

        if (source == null) {
            download.isEnabled = false
            status.text = "Finding the video stream…"
            extractMediaFromPage(webView)
            lifecycleScope.launch {
                delay(3000)
                if (directVideoUrl == null) extractMediaFromPage(webView)
                delay(4000)
                val found = directVideoUrl
                if (found == null) {
                    withContext(Dispatchers.Main) {
                        status.text = "Facebook did not expose the actual video stream."
                        progressText.text = "Unable to download this video."
                        refreshButtons()
                    }
                } else {
                    downloadVideo()
                }
            }
            return
        }

        download.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.progress = 0
        progressText.text = "Starting…"

        val userAgent = webView.settings.userAgentString
        lifecycleScope.launch(Dispatchers.IO) {
            var lastError: Exception? = null
            try {
                val safeName = title
                    .replace(Regex("""[\\/:*?"<>|]"""), "_")
                    .ifBlank { "facebook_video" }
                    .take(90)

                val candidates = videoCandidates
                    .sortedByDescending(::videoCandidateScore)
                    .filterNot { it.contains(".m3u8", ignoreCase = true) }
                    .ifEmpty { listOf(source) }

                for ((index, candidate) in candidates.withIndex()) {
                    var tempFile: File? = null
                    try {
                        withContext(Dispatchers.Main) {
                            progressText.text = if (index == 0) "Starting…" else "Trying another video stream…"
                        }

                        val connection = (URL(candidate).openConnection() as HttpURLConnection)
                        connection.apply {
                            connectTimeout = 30000
                            readTimeout = 60000
                            requestMethod = "GET"
                            instanceFollowRedirects = true
                            useCaches = false
                            setRequestProperty("User-Agent", userAgent)
                            setRequestProperty("Accept", "video/mp4,video/*,*/*;q=0.8")
                            setRequestProperty("Accept-Encoding", "identity")
                            setRequestProperty("Referer", page)
                            setRequestProperty("Origin", "https://www.facebook.com")
                            CookieManager.getInstance().getCookie(page)?.takeIf { it.isNotBlank() }?.let {
                                setRequestProperty("Cookie", it)
                            }
                            connect()
                        }

                        try {
                            val code = connection.responseCode
                            if (code !in 200..299) throw Exception("HTTP $code")

                            val contentType = connection.contentType?.lowercase(Locale.US) ?: ""
                            if (contentType.contains("text/html") || contentType.contains("application/json")) {
                                throw Exception("webpage data")
                            }

                            val total = connection.contentLengthLong
                            tempFile = File.createTempFile("facebook_video_", ".mp4", cacheDir)

                            BufferedInputStream(connection.inputStream, 128 * 1024).use { input ->
                                input.mark(64)
                                val header = ByteArray(32)
                                val headerCount = input.read(header)
                                input.reset()

                                if (headerCount > 0 && looksLikeHtmlOrJson(header, headerCount)) {
                                    throw Exception("webpage data")
                                }

                                tempFile!!.outputStream().buffered(128 * 1024).use { out ->
                                    val buffer = ByteArray(128 * 1024)
                                    var downloaded = 0L
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        out.write(buffer, 0, count)
                                        downloaded += count
                                        if (total > 0) {
                                            val percent = (downloaded * 100L / total).toInt().coerceIn(0, 100)
                                            withContext(Dispatchers.Main) {
                                                progress.progress = percent
                                                progressText.text = "$percent%"
                                            }
                                        }
                                    }
                                    out.flush()
                                }
                            }
                        } finally {
                            connection.disconnect()
                        }

                        val localFile = tempFile ?: throw Exception("empty temporary file")
                        if (localFile.length() <= 0L) throw Exception("empty response")

                        withContext(Dispatchers.Main) { progressText.text = "Checking video…" }
                        validateVideoFile(localFile)

                        val document = DocumentFile.fromTreeUri(this@MainActivity, folder)
                            ?: throw Exception("The selected folder is unavailable.")
                        val output = document.createFile("video/mp4", "$safeName.mp4")
                            ?: throw Exception("Could not create the video file.")

                        FileInputStream(localFile).use { input ->
                            contentResolver.openOutputStream(output.uri)?.use { out ->
                                input.copyTo(out, 128 * 1024)
                                out.flush()
                            } ?: throw Exception("Could not open the destination.")
                        }

                        localFile.delete()
                        tempFile = null

                        withContext(Dispatchers.Main) {
                            progress.progress = 100
                            progressText.text = "Saved successfully."
                            status.text = "The video was saved to your selected folder."
                            refreshButtons()
                            Toast.makeText(this@MainActivity, "Video saved", Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    } catch (e: Exception) {
                        tempFile?.delete()
                        lastError = e
                    }
                }

                throw lastError ?: Exception("No playable Facebook video stream was found.")
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressText.text = "Download failed."
                    status.text = e.message ?: "The video could not be downloaded."
                    refreshButtons()
                }
            }
        }
    }

    private fun looksLikeHtmlOrJson(bytes: ByteArray, count: Int): Boolean {
        val text = bytes.copyOf(count).toString(Charsets.UTF_8).trimStart().lowercase(Locale.US)
        return text.startsWith("<!doctype") ||
            text.startsWith("<html") ||
            text.startsWith("<head") ||
            text.startsWith("{") ||
            text.startsWith("[")
    }

    private fun validateVideoFile(file: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var hasVideo = false
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    val width = format.getIntegerOrNull(android.media.MediaFormat.KEY_WIDTH) ?: 0
                    val height = format.getIntegerOrNull(android.media.MediaFormat.KEY_HEIGHT) ?: 0
                    val duration = format.getLongOrNull(android.media.MediaFormat.KEY_DURATION) ?: 0L
                    if (width > 0 && height > 0 && duration > 0L) {
                        hasVideo = true
                        break
                    }
                }
            }
            if (!hasVideo) throw Exception("The downloaded file is not a playable video stream.")
        } catch (e: Exception) {
            if (e.message?.contains("playable video stream") == true) throw e
            throw Exception("The downloaded file is not a playable MP4 video.")
        } finally {
            extractor.release()
        }
    }

    private fun android.media.MediaFormat.getIntegerOrNull(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null

    private fun android.media.MediaFormat.getLongOrNull(key: String): Long? =
        if (containsKey(key)) getLong(key) else null
}
