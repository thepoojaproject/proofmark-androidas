package com.proofmark.qrstudio

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import com.google.android.material.snackbar.Snackbar
import com.proofmark.qrstudio.databinding.ActivityMainBinding
import java.io.File

/**
 * Hosts the Proofmark web app (bundled under `assets/`) inside a single
 * WebView. All product functionality lives in the web bundle; this class
 * only wires up the Android-specific plumbing the page needs to behave
 * like a native app: offline detection, file/camera uploads, downloads,
 * progress reporting, back-button navigation, edge-to-edge display, and
 * lifecycle handling.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var assetLoader: WebViewAssetLoader
    private lateinit var connectivityManager: ConnectivityManager

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingCameraUri: Uri? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var offlineSnackbar: Snackbar? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    companion object {
        private const val APP_START_URL = "https://${WebViewAssetLoader.DEFAULT_DOMAIN}/assets/index.html"
        private const val ASSET_PATH_PREFIX = "/assets/"
    }

    // ------------------------------------------------------------------
    // Permissions
    // ------------------------------------------------------------------

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, R.string.permission_denied_camera, Toast.LENGTH_SHORT).show()
        }
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Downloads still succeed silently without the completion notification. */ }

    // ------------------------------------------------------------------
    // File chooser (image upload input, with camera capture option)
    // ------------------------------------------------------------------

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null
        if (callback == null) return@registerForActivityResult

        val data = result.data
        val results: Array<Uri>? = when {
            result.resultCode != RESULT_OK -> null
            data?.clipData != null -> {
                val clip = data.clipData!!
                Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
            }
            data?.data != null -> arrayOf(data.data!!)
            pendingCameraUri != null -> arrayOf(pendingCameraUri!!)
            else -> null
        }
        callback.onReceiveValue(results)
        pendingCameraUri = null
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        setUpEdgeToEdge()
        setUpBackNavigation()
        setUpSwipeToRefresh()
        setUpOfflineRetry()

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler(ASSET_PATH_PREFIX, WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        configureWebView(binding.webView)

        if (savedInstanceState != null) {
            binding.webView.restoreState(savedInstanceState)
        } else {
            loadApp()
        }

        maybeRequestNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        registerNetworkCallback()
    }

    override fun onStop() {
        unregisterNetworkCallback()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
        binding.webView.resumeTimers()
    }

    override fun onPause() {
        binding.webView.onPause()
        binding.webView.pauseTimers()
        super.onPause()
    }

    override fun onDestroy() {
        binding.webView.apply {
            (parent as? ViewGroup)?.removeView(this)
            stopLoading()
            webChromeClient = null
            webViewClient = object : WebViewClient() {}
            removeJavascriptInterface("AndroidDownloader")
            destroy()
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Edge-to-edge / system bars
    // ------------------------------------------------------------------

    private fun setUpEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootContainer) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // The WebView's own page manages safe-area padding via CSS
            // (viewport-fit=cover / env(safe-area-inset-*)), so only the
            // native offline-state view and progress bar need explicit
            // inset handling here.
            binding.offlineView.root.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            binding.progressBar.setPadding(bars.left, bars.top, bars.right, 0)
            insets
        }
    }

    // ------------------------------------------------------------------
    // Back navigation
    // ------------------------------------------------------------------

    private fun setUpBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> binding.webView.webChromeClient?.onHideCustomView()
                    binding.webView.canGoBack() -> binding.webView.goBack()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    // ------------------------------------------------------------------
    // Pull-to-refresh
    // ------------------------------------------------------------------

    private fun setUpSwipeToRefresh() {
        binding.swipeRefresh.setColorSchemeResources(R.color.brand_accent_dark, R.color.brand_coral)
        binding.swipeRefresh.setOnRefreshListener { binding.webView.reload() }
        // Only allow the pull gesture to trigger while the page is
        // scrolled to the top, so it doesn't fight with in-page scrolling.
        binding.webView.viewTreeObserver.addOnScrollChangedListener {
            binding.swipeRefresh.isEnabled = binding.webView.scrollY == 0
        }
    }

    // ------------------------------------------------------------------
    // Offline state (native fallback view + connectivity banner)
    // ------------------------------------------------------------------

    private fun setUpOfflineRetry() {
        binding.offlineView.retryButton.setOnClickListener {
            if (!isOnline()) {
                Toast.makeText(this, R.string.error_no_connection_message, Toast.LENGTH_SHORT).show()
            }
            loadApp()
        }
    }

    private fun loadApp() {
        // The app is bundled locally under assets/ and works fully offline,
        // so it always loads regardless of connectivity. Only a genuine
        // failure to render the bundled page (see onReceivedError below)
        // falls back to the native offline view. A live network callback
        // separately informs the user when connectivity drops, since
        // specific in-app features (e.g. ImgBB image hosting in Dynamic
        // QR mode) do need it.
        showOfflinePage(false)
        binding.webView.loadUrl(APP_START_URL)
    }

    private fun showOfflinePage(offline: Boolean) {
        binding.offlineView.root.visibility = if (offline) View.VISIBLE else View.GONE
        binding.swipeRefresh.visibility = if (offline) View.GONE else View.VISIBLE
    }

    private fun registerNetworkCallback() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread { offlineSnackbar?.dismiss(); offlineSnackbar = null }
            }

            override fun onLost(network: Network) {
                runOnUiThread {
                    if (offlineSnackbar?.isShown != true) {
                        offlineSnackbar = Snackbar.make(
                            binding.rootContainer,
                            R.string.error_no_connection_title,
                            Snackbar.LENGTH_INDEFINITE
                        ).apply { show() }
                    }
                }
            }
        }
        networkCallback = callback
        connectivityManager.registerNetworkRequest(request, callback)
    }

    private fun ConnectivityManager.registerNetworkRequest(
        request: NetworkRequest,
        callback: ConnectivityManager.NetworkCallback
    ) = registerNetworkCallback(request, callback)

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            runCatching { connectivityManager.unregisterNetworkCallback(it) }
        }
        networkCallback = null
        offlineSnackbar?.dismiss()
        offlineSnackbar = null
    }

    private fun isOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ------------------------------------------------------------------
    // WebView configuration
    // ------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            // Local content is served through WebViewAssetLoader over a
            // real https:// origin, so cleartext/file:// access is never
            // required and stays locked down for security.
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            textZoom = 100
        }

        WebView.setWebContentsDebuggingEnabled(isDebuggable())

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.addJavascriptInterface(AndroidDownloader(applicationContext), "AndroidDownloader")

        // Handles real HTTP(S) downloads (content-disposition: attachment)
        // that navigate away from the page, as opposed to the blob:/data:
        // downloads the JS bridge in android-download-bridge.js handles.
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            enqueueSystemDownload(url, contentDisposition, mimeType)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url
                if (url.host == WebViewAssetLoader.DEFAULT_DOMAIN) return false // in-app navigation
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    true
                } catch (_: ActivityNotFoundException) {
                    false
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                binding.swipeRefresh.isRefreshing = false
                injectDownloadBridge(view)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    showOfflinePage(true)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                binding.progressBar.apply {
                    if (newProgress in 1..99) {
                        visibility = View.VISIBLE
                        setProgressCompat(newProgress, true)
                    } else {
                        visibility = View.GONE
                    }
                }
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                launchFileChooser(fileChooserParams)
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                val needsCamera = request.resources.any { it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }
                val granted = needsCamera &&
                    ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) request.grant(request.resources) else request.deny()
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                binding.fullscreenContainer.visibility = View.VISIBLE
                binding.fullscreenContainer.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                binding.swipeRefresh.visibility = View.GONE
            }

            override fun onHideCustomView() {
                binding.fullscreenContainer.visibility = View.GONE
                binding.fullscreenContainer.removeAllViews()
                binding.swipeRefresh.visibility = View.VISIBLE
                customViewCallback?.onCustomViewHidden()
                customView = null
                customViewCallback = null
            }
        }
    }

    private fun injectDownloadBridge(webView: WebView) {
        runCatching {
            val script = assets.open("android-download-bridge.js").bufferedReader().use { it.readText() }
            webView.evaluateJavascript(script, null)
        }
    }

    private fun isDebuggable(): Boolean {
        val appInfo = applicationContext.applicationInfo
        return (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    // ------------------------------------------------------------------
    // System DownloadManager (real http/https attachment downloads)
    // ------------------------------------------------------------------

    private fun enqueueSystemDownload(url: String, contentDisposition: String?, mimeType: String?) {
        runCatching {
            val filename = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                addRequestHeader("cookie", CookieManager.getInstance().getCookie(url))
                setDescription(filename)
                setTitle(filename)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
            }
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------
    // File chooser / camera capture
    // ------------------------------------------------------------------

    private fun launchFileChooser(params: WebChromeClient.FileChooserParams) {
        val acceptTypes = params.acceptTypes?.filter { it.isNotBlank() }.orEmpty()
        val mimeType = acceptTypes.firstOrNull()?.takeIf { it.contains('/') } ?: "image/*"

        val getContentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = mimeType
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
        }

        val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
            putExtra(Intent.EXTRA_INTENT, getContentIntent)
            putExtra(Intent.EXTRA_TITLE, "Select image")
        }

        val hasCameraPermission = ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

        if (mimeType.startsWith("image")) {
            if (hasCameraPermission) {
                createCameraCaptureIntent()?.let { cameraIntent ->
                    chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
                }
            } else {
                requestCameraPermission.launch(Manifest.permission.CAMERA)
            }
        }

        fileChooserLauncher.launch(chooserIntent)
    }

    private fun createCameraCaptureIntent(): Intent? = runCatching {
        val capturesDir = File(cacheDir, "captures").apply { mkdirs() }
        val photoFile = File(capturesDir, "capture-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photoFile)
        pendingCameraUri = uri
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }.getOrNull()
}
