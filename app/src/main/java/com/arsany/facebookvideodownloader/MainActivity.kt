package com.arsany.facebookvideodownloader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {

    private lateinit var status: android.widget.TextView
    private lateinit var urlView: android.widget.TextView
    private lateinit var selectFolder: android.widget.Button
    private lateinit var download: android.widget.Button
    private lateinit var progress: android.widget.ProgressBar
    private lateinit var progressText: android.widget.TextView
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
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
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
        receiveIntent(intent)
    }

    private fun receiveIntent(intent: Intent?) {
        val candidates = mutableListOf<String>()

        // Facebook/Android may place the URL in any of these locations.
        intent?.getStringExtra(Intent.EXTRA_TEXT)?.let { candidates.add(it) }
        intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let { candidates.add(it) }
        intent?.dataString?.let { candidates.add(it) }

        intent?.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).text?.toString()?.let { candidates.add(it) }
                clip.getItemAt(i).uri?.toString()?.let { candidates.add(it) }
            }
        }

        // Prefer an actual Facebook URL containing a path/id over the
        // Facebook home page, if multiple values were supplied.
        val urls = candidates.flatMap { text ->
            Regex("""https?://[^\s"'<>]+""").findAll(text)
                .map { it.value.trimEnd('.', ',', ')', ']', '>') }
                .toList()
        }

        val incoming = urls
            .filter { isHttpUrl(it) }
            .sortedByDescending {
                when {
                    it.contains("facebook.com") && it.length > "https://www.facebook.com/".length -> 3
                    it.contains("fb.watch") -> 3
                    it.contains("facebook.com") -> 1
                    else -> 2
                }
            }
            .firstOrNull()

        if (!incoming.isNullOrEmpty()) {
            pageUrl = incoming
            urlView.text = incoming
            urlView.visibility = View.VISIBLE

            directVideoUrl = null
            status.text = "Facebook link received. Finding the video…"
            refreshButtons()
            resolve(incoming)
        } else {
            status.text = "No usable Facebook video link was received."
            urlView.visibility = View.GONE
            pageUrl = null
            directVideoUrl = null
            refreshButtons()
        }
    }

    private fun isHttpUrl(s: String): Boolean =
        s.startsWith("https://") || s.startsWith("http://")

    private fun configureWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val u = request.url.toString()
                if (looksLikeVideo(u)) {
                    runOnUiThread {
                        directVideoUrl = u
                        status.text = "Video stream detected. Choose a folder and download."
                        refreshButtons()
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView, url: String) {
                // Facebook share/r/ URLs normally redirect to the actual
                // post/video page. Keep the final URL instead of the share URL.
                if (url.startsWith("https://www.facebook.com/") ||
                    url.startsWith("https://facebook.com/") ||
                    url.startsWith("https://m.facebook.com/")) {
                    pageUrl = url
                    runOnUiThread {
                        urlView.text = url
                    }
                }

                view.evaluateJavascript(
                    "(function(){return document.documentElement.innerHTML;})()"
                ) { result ->
                    val html = decodeJs(result)
                    val found = findVideoUrl(html)

                    if (found != null) {
                        directVideoUrl = found
                        status.text = "Video found. Choose a folder and download."
                        refreshButtons()
                    } else {
                        title = findTitle(html) ?: title

                        if (url.contains("/login") || url.contains("checkpoint")) {
                            status.text = "Facebook requires login to access this video."
                        } else if (url != pageUrl) {
                            status.text = "Following Facebook video page…"
                        }
                    }
                }
            }
        }
    }

    private fun resolve(url: String) {
        directVideoUrl = null
        refreshButtons()
        webView.loadUrl(url)
    }

    private fun looksLikeVideo(u: String): Boolean {
    val x = u.lowercase()

    // A normal Facebook webpage is not a downloadable video file.
    if (x.startsWith("https://www.facebook.com/") ||
        x.startsWith("https://facebook.com/") ||
        x.startsWith("https://m.facebook.com/")) {
        return false
    }

    // Accept only actual media resources.
    return x.contains(".mp4") ||
           x.contains(".m3u8") ||
           (x.contains("fbcdn") &&
            (x.contains("video") ||
             x.contains("playable") ||
             x.contains("/v/")))
}

private fun decodeJs(s: String): String {
        var x = s
        if (x.startsWith("\"") && x.endsWith("\"")) x = x.substring(1, x.length - 1)
        return x.replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003C", "<")
            .replace("\\u003E", ">")
            .replace("\\u0025", "%")
    }

    private fun findVideoUrl(html: String): String? {
        val patterns = listOf(
            "\"browser_native_hd_url\":\"(.*?)\"",
            "\"browser_native_sd_url\":\"(.*?)\"",
            "\"playable_url_quality_hd\":\"(.*?)\"",
            "\"playable_url\":\"(.*?)\"",
            "\"hd_src\":\"(.*?)\"",
            "\"sd_src\":\"(.*?)\"",
            "\"contentUrl\":\"(.*?)\"",
            "<meta[^>]+property=[\\\"]og:video[\\\"][^>]+content=[\\\"](https?://.*?)[\\\"]",
            "<meta[^>]+property=[\\\"]og:video:url[\\\"][^>]+content=[\\\"](https?://.*?)[\\\"]"
        )
        for (pattern in patterns) {
            val m = Pattern.compile(pattern).matcher(html)
            if (m.find()) {
                var u = m.group(1) ?: continue
                u = u.replace("\\/", "/").replace("\\u0026", "&")
                try { u = URLDecoder.decode(u, "UTF-8") } catch (_: Exception) {}
                if (u.startsWith("http")) return u
            }
        }
        return null
    }

    private fun findTitle(html: String): String? {
        val m = Pattern.compile("\"title\":\"(.*?)\"").matcher(html)
        return if (m.find()) {
            m.group(1)?.replace("\\\"", "\"")
                ?.replace(Regex("[^A-Za-z0-9 _-]"), "")
                ?.trim()?.take(80)
        } else null
    }

    private fun refreshButtons() {
        // The user can press Download as soon as a shared URL and destination
        // folder exist. Stream discovery may still be in progress.
        download.isEnabled = pageUrl != null && folderUri != null
    }

    private fun downloadVideo() {
        val folder = folderUri ?: return
        val page = pageUrl ?: return

        if (directVideoUrl == null) {
            download.isEnabled = false
            status.text = "Finding the video stream…"
            webView.loadUrl(page)

            lifecycleScope.launch {
                delay(7000)
                if (directVideoUrl == null) {
                    withContext(Dispatchers.Main) {
                        status.text = "Facebook did not expose a downloadable public video stream."
                        progressText.text = "Unable to download this video."
                        refreshButtons()
                    }
                } else {
                    downloadVideo()
                }
            }
            return
        }

        val source = directVideoUrl

        download.isEnabled = false
        progress.visibility = View.VISIBLE
        progressText.text = "Starting…"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val safeName = title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    .ifBlank { "facebook_video" }
                    .take(90)

                val document = DocumentFile.fromTreeUri(this@MainActivity, folder)
                    ?: error("The selected folder is unavailable.")

                val output = document.createFile("video/mp4", "$safeName.mp4")
                    ?: error("Could not create the video file.")

                val c = (URL(source).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 30000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36")
                    setRequestProperty("Referer", "https://www.facebook.com/")
                }

                c.connect()
                if (c.responseCode !in 200..299) error("Download server returned ${c.responseCode}.")

                val total = c.contentLengthLong
                var done = 0L

                c.inputStream.buffered(128 * 1024).use { input ->
                    contentResolver.openOutputStream(output.uri)?.use { out ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                withContext(Dispatchers.Main) {
                                    progress.progress = pct
                                    progressText.text = "$pct%"
                                }
                            }
                        }
                    } ?: error("Could not open the destination.")
                }
                c.disconnect()

                withContext(Dispatchers.Main) {
                    progress.progress = 100
                    progressText.text = "Saved successfully."
                    status.text = "The video was saved to your selected folder."
                    refreshButtons()
                    Toast.makeText(this@MainActivity, "Video saved", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressText.text = "Download failed."
                    status.text = e.message ?: "The video could not be downloaded."
                    refreshButtons()
                }
            }
        }
    }
}
