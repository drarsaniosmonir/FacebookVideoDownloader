package com.arsany.facebookvideodownloader

import android.content.Intent
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
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.regex.Pattern

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

        selectFolder.setOnClickListener {
            folderPicker.launch(null)
        }

        download.setOnClickListener {
            downloadVideo()
        }

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

        intent?.getStringExtra(Intent.EXTRA_TEXT)?.let {
            candidates.add(it)
        }

        intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let {
            candidates.add(it)
        }

        intent?.dataString?.let {
            candidates.add(it)
        }

        intent?.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).text?.toString()?.let {
                    candidates.add(it)
                }

                clip.getItemAt(i).uri?.toString()?.let {
                    candidates.add(it)
                }
            }
        }

        val urls = candidates.flatMap { text ->
            Regex("""https?://[^\s"'<>]+""")
                .findAll(text)
                .map {
                    it.value.trimEnd('.', ',', ')', ']', '>')
                }
                .toList()
        }

        val incoming = urls
            .filter { isHttpUrl(it) }
            .sortedByDescending {
                when {
                    it.contains("facebook.com") &&
                        it.length > "https://www.facebook.com/".length -> 3

                    it.contains("fb.watch") -> 3

                    it.contains("facebook.com") -> 2

                    else -> 1
                }
            }
            .firstOrNull()

        if (incoming != null) {

            pageUrl = incoming
            directVideoUrl = null

            urlView.text = incoming
            urlView.visibility = View.VISIBLE

            status.text = "Opening Facebook video…"

            refreshButtons()
            resolve(incoming)

        } else {

            pageUrl = null
            directVideoUrl = null

            urlView.visibility = View.GONE
            status.text = "No usable Facebook video link was received."

            refreshButtons()
        }
    }

    private fun isHttpUrl(value: String): Boolean {
        return value.startsWith("https://") ||
            value.startsWith("http://")
    }

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

                if (looksLikeVideo(candidate)) {

                    runOnUiThread {

                        directVideoUrl = candidate

                        status.text =
                            "Video stream detected. Choose a folder and download."

                        refreshButtons()
                    }
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(
                view: WebView,
                url: String
            ) {

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

        val x = url.lowercase()

        return x.startsWith("https://www.facebook.com/") ||
            x.startsWith("https://facebook.com/") ||
            x.startsWith("https://m.facebook.com/")
    }

    private fun resolve(url: String) {

        directVideoUrl = null
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

                        try {
                            u = String(u);
                        } catch(e) {
                            return;
                        }

                        if (!u) return;
                        if (u.indexOf("blob:") === 0) return;
                        if (u.indexOf("data:") === 0) return;

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

                            if (p && p.catch) {
                                p.catch(function(){});
                            }
                        } catch(e) {}

                        var sources = v.querySelectorAll("source");

                        for (var j = 0; j < sources.length; j++) {
                            add(sources[j].src);
                        }
                    }

                    var metas = document.querySelectorAll(
                        'meta[property="og:video"],' +
                        'meta[property="og:video:url"],' +
                        'meta[property="og:video:secure_url"]'
                    );

                    for (var m = 0; m < metas.length; m++) {
                        add(metas[m].content);
                    }

                    try {
                        var resources =
                            performance.getEntriesByType("resource");

                        for (var r = 0; r < resources.length; r++) {
                            var ru = resources[r].name;

                            if (
                                ru.indexOf(".mp4") >= 0 ||
                                ru.indexOf(".m3u8") >= 0 ||
                                ru.indexOf("fbcdn") >= 0
                            ) {
                                add(ru);
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

            val candidates = decoded
                .split("\n")
                .map { it.trim() }
                .filter { it.startsWith("http") }
                .distinct()

            val preferred = candidates.firstOrNull {
                looksLikeVideo(it) && it.contains("mp4", ignoreCase = true)
            } ?: candidates.firstOrNull {
                looksLikeVideo(it)
            }

            if (preferred != null) {

                directVideoUrl = preferred

                status.text =
                    "Video found. Choose a folder and download."

                refreshButtons()

            } else {

                if (pageUrl?.contains("/login") == true ||
                    pageUrl?.contains("checkpoint") == true
                ) {

                    status.text =
                        "Facebook requires login to access this video."

                } else {

                    status.text =
                        "Looking for the video stream…"

                }

                refreshButtons()
            }
        }
    }

    private fun looksLikeVideo(url: String): Boolean {

        val x = url.lowercase()

        if (x.startsWith("blob:")) return false
        if (x.startsWith("data:")) return false

        if (isFacebookPage(x)) return false

        return x.contains(".mp4") ||
            x.contains(".m3u8") ||
            (
                x.contains("fbcdn") &&
                    (
                        x.contains("video") ||
                        x.contains("playable") ||
                        x.contains("/v/")
                    )
                )
    }

    private fun decodeJavascriptString(value: String): String {

        var x = value

        if (x.length >= 2 &&
            x.startsWith("\"") &&
            x.endsWith("\"")
        ) {
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

        download.isEnabled =
            pageUrl != null &&
                folderUri != null
    }

    private fun downloadVideo() {

        val folder = folderUri ?: return
        val page = pageUrl ?: return

        if (directVideoUrl == null) {

            download.isEnabled = false

            status.text = "Finding the video stream…"

            extractMediaFromPage(webView)

            lifecycleScope.launch {

                delay(3000)

                if (directVideoUrl == null) {

                    extractMediaFromPage(webView)

                    delay(4000)
                }

                if (directVideoUrl == null) {

                    withContext(Dispatchers.Main) {

                        status.text =
                            "Facebook did not expose the actual video stream."

                        progressText.text =
                            "Unable to download this video."

                        refreshButtons()
                    }

                } else {

                    downloadVideo()
                }
            }

            return
        }

        val source = directVideoUrl ?: return

        download.isEnabled = false

        progress.visibility = View.VISIBLE
        progress.progress = 0
        progressText.text = "Starting…"

        lifecycleScope.launch(Dispatchers.IO) {

            try {

                if (source.contains(".m3u8", ignoreCase = true)) {

                    throw Exception(
                        "Facebook supplied an HLS stream instead of an MP4 stream."
                    )
                }

                val safeName =
                    title
                        .replace(Regex("""[\\/:*?"<>|]"""), "_")
                        .ifBlank { "facebook_video" }
                        .take(90)

                val document =
                    DocumentFile.fromTreeUri(
                        this@MainActivity,
                        folder
                    )
                        ?: throw Exception(
                            "The selected folder is unavailable."
                        )

                val output =
                    document.createFile(
                        "video/mp4",
                        "$safeName.mp4"
                    )
                        ?: throw Exception(
                            "Could not create the video file."
                        )

                val connection =
                    (URL(source).openConnection() as HttpURLConnection)

                connection.apply {

                    connectTimeout = 30000
                    readTimeout = 60000

                    requestMethod = "GET"

                    instanceFollowRedirects = true

                    useCaches = false

                    setRequestProperty(
                        "User-Agent",
                        webView.settings.userAgentString
                    )

                    setRequestProperty(
                        "Accept",
                        "video/mp4,video/*,*/*;q=0.8"
                    )

                    setRequestProperty(
                        "Accept-Encoding",
                        "identity"
                    )

                    setRequestProperty(
                        "Referer",
                        page
                    )

                    val facebookCookies =
                        CookieManager
                            .getInstance()
                            .getCookie(page)

                    if (!facebookCookies.isNullOrBlank()) {

                        setRequestProperty(
                            "Cookie",
                            facebookCookies
                        )
                    }

                    connect()
                }

                val code = connection.responseCode

                if (code !in 200..299) {

                    throw Exception(
                        "Facebook returned HTTP $code."
                    )
                }

                val contentType =
                    connection.contentType?.lowercase() ?: ""

                if (
                    contentType.contains("text/html") ||
                    contentType.contains("application/json")
                ) {

                    throw Exception(
                        "Facebook returned a webpage instead of video data."
                    )
                }

                val total =
                    connection.contentLengthLong

                var downloaded = 0L

                BufferedInputStream(
                    connection.inputStream,
                    128 * 1024
                ).use { input ->

                    contentResolver
                        .openOutputStream(output.uri)
                        ?.use { out ->

                            val buffer =
                                ByteArray(128 * 1024)

                            while (true) {

                                val count =
                                    input.read(buffer)

                                if (count < 0) break

                                out.write(
                                    buffer,
                                    0,
                                    count
                                )

                                downloaded += count

                                if (total > 0) {

                                    val percent =
                                        (
                                            downloaded * 100L /
                                                total
                                            ).toInt()

                                    withContext(
                                        Dispatchers.Main
                                    ) {

                                        progress.progress =
                                            percent

                                        progressText.text =
                                            "$percent%"
                                    }
                                }
                            }

                            out.flush()

                        }
                        ?: throw Exception(
                            "Could not open the destination."
                        )
                }

                connection.disconnect()

                if (downloaded <= 0) {

                    throw Exception(
                        "Facebook returned an empty video."
                    )
                }

                withContext(Dispatchers.Main) {

                    progress.progress = 100

                    progressText.text =
                        "Saved successfully."

                    status.text =
                        "The video was saved to your selected folder."

                    refreshButtons()

                    Toast.makeText(
                        this@MainActivity,
                        "Video saved",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {

                withContext(Dispatchers.Main) {

                    progressText.text =
                        "Download failed."

                    status.text =
                        e.message
                            ?: "The video could not be downloaded."

                    refreshButtons()
                }
            }
        }
    }
}
