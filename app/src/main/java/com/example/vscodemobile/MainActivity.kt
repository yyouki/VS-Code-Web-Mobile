package com.example.vscodemobile

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Base64
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.webkit.WebMessageCompat
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * WebView subclass that translates a latched CTRL/META + letter into a real key event.
 * Most Android IMEs commit letters through InputConnection.commitText() instead of key events,
 * which otherwise makes the editor receive a plain letter (for example, "A" instead of Ctrl+A).
 */
private class ModifierAwareWebView(
    context: Context,
    private val modifierProvider: () -> Int,
    private val onImeShortcut: (Int, Int) -> Unit
) : WebView(context) {
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val target = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(target, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val modifiers = modifierProvider()
                val committed = text?.toString().orEmpty()
                val hasCommandModifier =
                    modifiers and (KeyEvent.META_CTRL_ON or KeyEvent.META_META_ON) != 0

                if (hasCommandModifier && committed.length == 1) {
                    val character = committed[0].uppercaseChar()
                    if (character in 'A'..'Z') {
                        // Replace any active IME composition with an empty commit, not the typed letter.
                        super.commitText("", 1)
                        this@ModifierAwareWebView.post {
                            onImeShortcut(
                                KeyEvent.KEYCODE_A + (character - 'A'),
                                modifiers
                            )
                        }
                        return true
                    }
                }

                return super.commitText(text, newCursorPosition)
            }
        }
    }
}

class MainActivity : Activity() {
    private lateinit var editor: ModifierAwareWebView
    private lateinit var keyboardBar: HorizontalScrollView
    private lateinit var loadingProgressBar: ProgressBar
    private val modifierButtons = mutableMapOf<Int, Button>()
    private var activeModifiers = 0
    private var editorTouchStartX = 0f
    private var editorTouchStartY = 0f
    private var editorGestureMoved = false
    private var editorTouchSlop = 8f
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private data class PendingPicker(
        val requestId: String,
        val replyProxy: JavaScriptReplyProxy
    )

    private var pendingBridgePicker: PendingPicker? = null
    private var pendingFileChooser: ValueCallback<Array<Uri>>? = null
    private var selectedTreeUri: Uri? = null
    private var authPopupDialog: AlertDialog? = null
    private var authPopupWebView: WebView? = null
    private var originalUserAgent: String? = null
    private var desktopMode = false
    private var pageZoom = 1.0f
    private var keyBarVisible = true
    private var zoomLabelButton: Button? = null
    private var desktopModeButton: Button? = null
    private var backInvokedCallback: OnBackInvokedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Allow content into display cutouts while keeping interactive controls in safe insets.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    } else {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
            }
        }
        enableImmersiveStatusBar()
        registerSystemBackCallback()

        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        selectedTreeUri = preferences.getString(KEY_TREE_URI, null)
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
        desktopMode = preferences.getBoolean(KEY_DESKTOP_MODE, false)
        pageZoom = preferences.getFloat(KEY_PAGE_ZOOM, 1.0f).coerceIn(MIN_PAGE_ZOOM, MAX_PAGE_ZOOM)
        keyBarVisible = preferences.getBoolean(KEY_KEYBAR_VISIBLE, true)

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        editorTouchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        window.statusBarColor = Color.rgb(30, 30, 30)
        window.navigationBarColor = Color.rgb(30, 30, 30)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(30, 30, 30))

            // Respect the notch and navigation gesture area in edge-to-edge mode.
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val safeInsets = insets.getInsets(
                    WindowInsetsCompat.Type.displayCutout() or
                        WindowInsetsCompat.Type.navigationBars()
                )
                val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
                view.setPadding(
                    safeInsets.left,
                    safeInsets.top,
                    safeInsets.right,
                    maxOf(safeInsets.bottom, imeInsets.bottom)
                )
                insets
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(3), dp(8), dp(3))
            setBackgroundColor(Color.rgb(37, 37, 38))
        }

        header.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_launcher)
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            }
        )

        header.addView(
            TextView(this).apply {
                text = "Code Web"
                textSize = 12f
                isSingleLine = true
                setTextColor(Color.WHITE)
                setPadding(dp(8), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )

        header.addView(makeHeaderButton("Folder") { openFolderFromToolbar() })
        header.addView(makeHeaderButton("Remote") { showRemoteDialog() })
        header.addView(makeHeaderButton("Keys") { toggleKeyboardBar() })
        header.addView(makeHeaderButton("Info") { showAboutDialog() })
        root.addView(
            header,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
        )

        loadingProgressBar = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
            isIndeterminate = false
            visibility = View.GONE
        }
        root.addView(
            loadingProgressBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2))
        )

        editor = ModifierAwareWebView(
            context = this,
            modifierProvider = { activeModifiers },
            onImeShortcut = { keyCode, modifiers ->
                activeModifiers = modifiers
                sendKey(keyCode)
            }
        ).apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            setBackgroundColor(Color.rgb(30, 30, 30))
            isFocusable = true
            isFocusableInTouchMode = true

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(true)
            // Zoom is controlled with CSS page zoom; native WebView pinch gestures remain disabled.
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = desktopMode
            settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            settings.textZoom = 100
            originalUserAgent = settings.userAgentString
            if (desktopMode) {
                settings.userAgentString = asDesktopUserAgent(originalUserAgent.orEmpty())
            }
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settings.safeBrowsingEnabled = true
            }

            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        editorTouchStartX = event.x
                        editorTouchStartY = event.y
                        editorGestureMoved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = kotlin.math.abs(event.x - editorTouchStartX)
                        val deltaY = kotlin.math.abs(event.y - editorTouchStartY)
                        if (deltaX > editorTouchSlop || deltaY > editorTouchSlop) {
                            editorGestureMoved = true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (editorGestureMoved) {
                            // A swipe/scroll should dismiss the IME without blurring Monaco's editor.
                            view.postDelayed({ hideKeyboardAfterEditorScroll() }, 90)
                        }
                        editorGestureMoved = false
                    }
                }
                false // Preserve WebView taps, text selection, and scrolling behavior.
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(
                    view: WebView,
                    url: String,
                    favicon: android.graphics.Bitmap?
                ) {
                    super.onPageStarted(view, url, favicon)
                    loadingProgressBar.progress = 5
                    loadingProgressBar.visibility = View.VISIBLE
                }

                override fun onPageFinished(view: WebView, url: String) {
                    loadingProgressBar.progress = 100
                    loadingProgressBar.visibility = View.GONE
                    super.onPageFinished(view, url)
                    val loadedUri = Uri.parse(url)
                    CookieManager.getInstance().flush()
                    applyPagePresentation(view)
                    if (loadedUri.scheme == "https" && loadedUri.host == "vscode.dev") {
                        restoreRememberedDirectory(view)
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean = handleExternalUrl(request.url)
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    loadingProgressBar.progress = newProgress
                    loadingProgressBar.visibility =
                        if (newProgress >= 100) View.GONE else View.VISIBLE
                }

                override fun onCreateWindow(
                    view: WebView,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message
                ): Boolean {
                    return showAuthenticationPopup(resultMsg)
                }

                override fun onCloseWindow(window: WebView) {
                    if (window === authPopupWebView) closeAuthenticationPopup()
                }

                override fun onShowFileChooser(
                    webView: WebView,
                    filePathCallback: ValueCallback<Array<Uri>>,
                    fileChooserParams: FileChooserParams
                ): Boolean {
                    pendingFileChooser?.onReceiveValue(null)
                    pendingFileChooser = filePathCallback

                    val intent = buildOpenFileIntent(
                        fileChooserParams.acceptTypes.toList(),
                        fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                    )

                    return try {
                        startActivityForResult(intent, REQUEST_UPLOAD_FILE)
                        true
                    } catch (_: Exception) {
                        pendingFileChooser = null
                        filePathCallback.onReceiveValue(null)
                        Toast.makeText(
                            this@MainActivity,
                            "Android file picker is unavailable.",
                            Toast.LENGTH_LONG
                        ).show()
                        false
                    }
                }
            }
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(editor, true)
        }

        root.addView(
            editor,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        // All mobile keys live in one horizontally scrollable strip.
        keyboardBar = createKeyRow().apply {
            setBackgroundColor(Color.rgb(37, 37, 38))
            setPadding(dp(3), dp(2), dp(3), dp(2))
            visibility = if (keyBarVisible) View.VISIBLE else View.GONE
        }

        addControlButton(keyboardBar, "⌨") { showInternalKeyboard() }
        addControlButton(keyboardBar, "−") { changePageZoom(-ZOOM_STEP) }
        zoomLabelButton = makeKeyButton("${(pageZoom * 100).toInt()}%").apply {
            setOnClickListener { resetPageZoom() }
        }
        rowContent(keyboardBar).addView(zoomLabelButton)
        addControlButton(keyboardBar, "+") { changePageZoom(ZOOM_STEP) }
        desktopModeButton = makeKeyButton(if (desktopMode) "Desktop ✓" else "Desktop").apply {
            setOnClickListener { toggleDesktopMode() }
        }
        rowContent(keyboardBar).addView(desktopModeButton)

        addModifierButton(keyboardBar, "CTRL", KeyEvent.META_CTRL_ON)
        addModifierButton(keyboardBar, "ALT", KeyEvent.META_ALT_ON)
        addModifierButton(keyboardBar, "SHIFT", KeyEvent.META_SHIFT_ON)
        addModifierButton(keyboardBar, "META", KeyEvent.META_META_ON)
        addKeyButton(keyboardBar, "ESC", KeyEvent.KEYCODE_ESCAPE)
        addKeyButton(keyboardBar, "TAB", KeyEvent.KEYCODE_TAB)
        addKeyButton(keyboardBar, "F1", KeyEvent.KEYCODE_F1)
        addKeyButton(keyboardBar, "F5", KeyEvent.KEYCODE_F5)
        addKeyButton(keyboardBar, "P", KeyEvent.KEYCODE_P)
        addKeyButton(keyboardBar, "C", KeyEvent.KEYCODE_C)
        addKeyButton(keyboardBar, "V", KeyEvent.KEYCODE_V)
        addKeyButton(keyboardBar, "X", KeyEvent.KEYCODE_X)
        listOf("{", "}", "[", "]", "(", ")", ";", ":", "/", "\\", "|", "~", "_")
            .forEach { addSymbolButton(keyboardBar, it) }
        addKeyButton(keyboardBar, "←", KeyEvent.KEYCODE_DPAD_LEFT)
        addKeyButton(keyboardBar, "↓", KeyEvent.KEYCODE_DPAD_DOWN)
        addKeyButton(keyboardBar, "↑", KeyEvent.KEYCODE_DPAD_UP)
        addKeyButton(keyboardBar, "→", KeyEvent.KEYCODE_DPAD_RIGHT)
        addKeyButton(keyboardBar, "HOME", KeyEvent.KEYCODE_MOVE_HOME)
        addKeyButton(keyboardBar, "END", KeyEvent.KEYCODE_MOVE_END)
        addKeyButton(keyboardBar, "DEL", KeyEvent.KEYCODE_DEL)

        root.addView(
            keyboardBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        )

        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        enableImmersiveStatusBar()
        installAndroidFileSystemBridge()
        val restoredWebView = savedInstanceState?.let { editor.restoreState(it) } != null
        if (!restoredWebView) {
            editor.loadUrl(VSCODE_WEB_URL)
        }
    }

    private fun showAuthenticationPopup(resultMsg: Message): Boolean {
        if (authPopupWebView != null) return false

        val popup = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            // Keep OAuth popups in this same authentication window instead of spawning unhandled nested windows.
            settings.setSupportMultipleWindows(false)
            settings.setSupportZoom(true)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.userAgentString = editor.settings.userAgentString
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settings.safeBrowsingEnabled = true
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    CookieManager.getInstance().flush()
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val host = request.url.host.orEmpty()
                    if (host.endsWith(".github.dev") ||
                        (host == "vscode.dev" && request.url.path?.startsWith("/tunnel/") == true)
                    ) {
                        // Route a Codespace or tunnel editor opened in a popup into the main view.
                        editor.loadUrl(request.url.toString())
                        closeAuthenticationPopup()
                        return true
                    }
                    return handleExternalUrl(request.url)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onCloseWindow(window: WebView) {
                    closeAuthenticationPopup()
                }
            }
        }

        val popupLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(30, 30, 30))
            addView(
                makeHeaderButton("Close sign-in") { closeAuthenticationPopup() },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(48)
                )
            )
            addView(
                popup,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
        }

        val transport = resultMsg.obj as? WebView.WebViewTransport ?: run {
            popup.destroy()
            return false
        }
        transport.webView = popup

        val dialog = AlertDialog.Builder(this)
            .setView(popupLayout)
            .create()
        authPopupWebView = popup
        authPopupDialog = dialog
        dialog.setOnDismissListener {
            popup.stopLoading()
            popup.destroy()
            if (authPopupWebView === popup) authPopupWebView = null
            if (authPopupDialog === dialog) authPopupDialog = null
        }
        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        resultMsg.sendToTarget()
        return true
    }

    private fun closeAuthenticationPopup() {
        authPopupDialog?.dismiss()
    }

    private fun handleExternalUrl(uri: Uri): Boolean {
        if (uri.scheme == "https") return false

        return when (uri.scheme?.lowercase()) {
            "mailto", "tel", "sms", "geo", "intent", "vscode" -> {
                try {
                    val intent = if (uri.scheme == "intent") {
                        Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                    } else {
                        Intent(Intent.ACTION_VIEW, uri)
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(
                        this,
                        "This link needs a compatible Android app or browser.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                true
            }
            else -> true
        }
    }

    private fun toggleKeyboardBar() {
        keyBarVisible = !keyBarVisible
        keyboardBar.visibility = if (keyBarVisible) View.VISIBLE else View.GONE
        getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_KEYBAR_VISIBLE, keyBarVisible)
            .apply()
    }

    private fun syncModifierButtons() {
        modifierButtons.forEach { (flag, button) ->
            val label = when (flag) {
                KeyEvent.META_CTRL_ON -> "CTRL"
                KeyEvent.META_ALT_ON -> "ALT"
                KeyEvent.META_SHIFT_ON -> "SHIFT"
                KeyEvent.META_META_ON -> "META"
                else -> button.text.toString().substringBefore(" ✓")
            }
            button.text = if ((activeModifiers and flag) != 0) "$label ✓" else label
        }
    }

    private fun applyModifierState(args: JSONObject) {
        activeModifiers = 0
        if (args.optBoolean("ctrl")) activeModifiers = activeModifiers or KeyEvent.META_CTRL_ON
        if (args.optBoolean("alt")) activeModifiers = activeModifiers or KeyEvent.META_ALT_ON
        if (args.optBoolean("shift")) activeModifiers = activeModifiers or KeyEvent.META_SHIFT_ON
        if (args.optBoolean("meta")) activeModifiers = activeModifiers or KeyEvent.META_META_ON
        runOnUiThread { syncModifierButtons() }
    }

    private fun changePageZoom(delta: Float) {
        val nextZoom = (pageZoom + delta).coerceIn(MIN_PAGE_ZOOM, MAX_PAGE_ZOOM)
        if (nextZoom == pageZoom) return

        pageZoom = nextZoom
        persistPageZoom()
        if (::editor.isInitialized) applyPagePresentation(editor)
    }

    private fun resetPageZoom() {
        pageZoom = 1.0f
        persistPageZoom()
        if (::editor.isInitialized) applyPagePresentation(editor)
    }

    private fun persistPageZoom() {
        getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .edit()
            .putFloat(KEY_PAGE_ZOOM, pageZoom)
            .apply()
        zoomLabelButton?.text = "${(pageZoom * 100).toInt()}%"
    }

    /**
     * Apply actual document zoom instead of WebView.zoomBy(), which is ignored by
     * pages that lock the viewport (as vscode.dev does).
     */
    private fun applyPagePresentation(view: WebView) {
        val isDesktop = desktopMode
        val zoom = pageZoom
        val script = """
            (() => {
              const desktop = $isDesktop;
              const zoom = $zoom;
              let viewport = document.querySelector('meta[name="viewport"]');
              if (!viewport) {
                viewport = document.createElement('meta');
                viewport.name = 'viewport';
                document.head.appendChild(viewport);
              }
              viewport.setAttribute(
                'content',
                desktop
                  ? 'width=1280, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes'
                  : 'width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes'
              );
              document.documentElement.style.setProperty('zoom', String(zoom), 'important');
              document.documentElement.style.setProperty('transform-origin', 'top left');
              window.__androidPageZoom = zoom;
              window.__androidDesktopViewport = desktop;
            })();
        """.trimIndent()
        view.post {
            if (!isFinishing && !isDestroyed) {
                view.evaluateJavascript(script, null)
            }
        }
        zoomLabelButton?.text = "${(pageZoom * 100).toInt()}%"
        desktopModeButton?.text = if (desktopMode) "Desktop ✓" else "Desktop"
    }

    private fun asDesktopUserAgent(userAgent: String): String {
        val chromeVersion = Regex("Chrome/([0-9.]+)")
            .find(userAgent)
            ?.groupValues
            ?.getOrNull(1)
            ?: "131.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$chromeVersion Safari/537.36"
    }

    private fun toggleDesktopMode() {
        desktopMode = !desktopMode
        getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DESKTOP_MODE, desktopMode)
            .apply()

        val userAgent = originalUserAgent ?: editor.settings.userAgentString
        editor.settings.userAgentString =
            if (desktopMode) asDesktopUserAgent(userAgent) else userAgent
        editor.settings.useWideViewPort = true
        editor.settings.loadWithOverviewMode = desktopMode
        editor.settings.setSupportZoom(false)
        desktopModeButton?.text = if (desktopMode) "Desktop ✓" else "Desktop"

        val currentUrl = editor.url
            ?.takeIf { Uri.parse(it).scheme == "https" }
            ?: VSCODE_WEB_URL
        editor.loadUrl(currentUrl)
    }

    private fun showRemoteDialog() {
        AlertDialog.Builder(this)
            .setTitle("VS Code remote tools")
            .setItems(
                arrayOf(
                    "Open VS Code Command Palette",
                    "Open Codespaces dashboard",
                    "Create / resume Codespace for a repository",
                    "Open existing Codespace by name",
                    "GitHub Copilot extension"
                )
            ) { _, which ->
                when (which) {
                    0 -> openVsCodeRemoteCommandPalette()
                    1 -> openCodespaces()
                    2 -> promptCreateCodespace()
                    3 -> promptOpenCodespaceByName()
                    4 -> openCopilotExtension()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * Repository and tunnel navigation stays inside VS Code's own command system.
     * No repository URL or tunnel URL is assembled by the Android wrapper.
     */
    private fun openVsCodeRemoteCommandPalette() {
        sendKey(
            KeyEvent.KEYCODE_P,
            KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON
        )
        Toast.makeText(
            this,
            "In VS Code, run GitHub Repositories: Open Repository... or Remote Tunnels: Connect to Tunnel.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun openCodespaces() {
        editor.loadUrl(CODESPACES_URL)
    }

    private fun promptCreateCodespace() {
        val input = EditText(this).apply {
            hint = "owner/repository"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.WHITE)
        }
        val container = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("Create or resume Codespace")
            .setMessage("Enter a GitHub repository. GitHub will resume a matching Codespace or offer to create one.")
            .setView(container)
            .setPositiveButton("Continue") { _, _ ->
                val repository = input.text.toString().trim()
                if (!Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(repository)) {
                    Toast.makeText(this, "Use the format owner/repository.", Toast.LENGTH_LONG).show()
                } else {
                    editor.loadUrl("https://codespaces.new/$repository?quickstart=1")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptOpenCodespaceByName() {
        val input = EditText(this).apply {
            hint = "permanent-codespace-name"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.WHITE)
        }
        val container = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("Open existing Codespace")
            .setMessage("Enter the permanent Codespace name from GitHub. This opens its VS Code web client and remote terminal.")
            .setView(container)
            .setPositiveButton("Open") { _, _ ->
                val name = input.text.toString().trim().lowercase()
                if (!Regex("^[a-z0-9][a-z0-9-]*$").matches(name)) {
                    Toast.makeText(this, "Enter the permanent Codespace name.", Toast.LENGTH_LONG).show()
                } else {
                    editor.loadUrl("https://$name.github.dev")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openCopilotExtension() {
        // Open VS Code's own Extensions view instead of leaving the editor for the Marketplace website.
        sendKey(
            KeyEvent.KEYCODE_X,
            KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON
        )
        Toast.makeText(
            this,
            "Extensions opened. Search for GitHub Copilot and install it here.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun addControlButton(
        row: HorizontalScrollView,
        label: String,
        onClick: () -> Unit
    ) {
        val button = makeKeyButton(label)
        button.setOnClickListener { onClick() }
        rowContent(row).addView(button)
    }

    private fun showInternalKeyboard() {
        editor.requestFocusFromTouch()
        val focusScript = """
            (function () {
              const input = document.querySelector(
                ".monaco-editor textarea.inputarea, .monaco-editor textarea"
              );
              if (input) input.focus();
            })();
        """.trimIndent()

        editor.evaluateJavascript(focusScript) {
            editor.postDelayed({
                val inputMethodManager =
                    getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                WindowCompat.getInsetsController(window, window.decorView)
                    .show(WindowInsetsCompat.Type.ime())
                inputMethodManager.showSoftInput(
                    editor,
                    InputMethodManager.SHOW_IMPLICIT
                )
            }, 120)
        }
    }

    private fun hideKeyboardAfterEditorScroll() {
        WindowCompat.getInsetsController(window, window.decorView)
            .hide(WindowInsetsCompat.Type.ime())
        val inputMethodManager =
            getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(editor.windowToken, 0)
    }

    private fun makeHeaderButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 10f
            isAllCaps = false
            minHeight = dp(38)
            minimumHeight = dp(38)
            minWidth = dp(52)
            minimumWidth = dp(52)
            setPadding(dp(4), 0, dp(4), 0)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(65, 65, 68))
            setOnClickListener { onClick() }
        }
    }

    private fun installAndroidFileSystemBridge() {
        val supported =
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

        if (!supported) {
            Toast.makeText(
                this,
                "Local folder support needs an updated Android System WebView.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Expose the native storage bridge only to the exact VS Code Web origin.
        WebViewCompat.addWebMessageListener(
            editor,
            "AndroidFs",
            setOf(VSCODE_ORIGIN)
        ) { _, message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean, replyProxy: JavaScriptReplyProxy ->
            if (!isMainFrame || sourceOrigin.toString() != VSCODE_ORIGIN) {
                return@addWebMessageListener
            }
            handleBridgeMessage(message.data ?: return@addWebMessageListener, replyProxy)
        }

        // Install before navigation so the editor sees the compatibility APIs at startup.
        val shim = assets.open("android-fs-access.js").bufferedReader().use { it.readText() }
        WebViewCompat.addDocumentStartJavaScript(editor, shim, setOf(VSCODE_ORIGIN))
    }

    private fun handleBridgeMessage(rawMessage: String, replyProxy: JavaScriptReplyProxy) {
        val request = runCatching { JSONObject(rawMessage) }.getOrNull() ?: return
        val requestId = request.optString("id")
        val method = request.optString("method")
        val args = request.optJSONObject("args") ?: JSONObject()

        when (method) {
            "pickDirectory" -> {
                startBridgePicker(
                    requestId,
                    replyProxy,
                    Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),
                    REQUEST_BRIDGE_FOLDER
                )
            }
            "modifierState" -> {
                applyModifierState(args)
            }
            "pickFile" -> {
                val acceptTypes = args.optJSONArray("types")?.let { values ->
                    buildList {
                        for (index in 0 until values.length()) {
                            val item = values.optJSONObject(index) ?: continue
                            val accept = item.optJSONObject("accept") ?: continue
                            val keys = accept.keys()
                            while (keys.hasNext()) add(keys.next())
                        }
                    }
                }.orEmpty()
                val intent = buildOpenFileIntent(
                    acceptTypes,
                    args.optBoolean("multiple", false)
                )
                startBridgePicker(requestId, replyProxy, intent, REQUEST_BRIDGE_FILE)
            }
            else -> ioExecutor.execute {
                try {
                    val result = performStorageOperation(method, args)
                    runOnUiThread { postBridgeSuccess(replyProxy, requestId, result) }
                } catch (error: Exception) {
                    runOnUiThread {
                        postBridgeError(
                            replyProxy,
                            requestId,
                            error.message ?: "Storage operation failed.",
                            "NotAllowedError"
                        )
                    }
                }
            }
        }
    }

    private fun startBridgePicker(
        requestId: String,
        replyProxy: JavaScriptReplyProxy,
        intent: Intent,
        requestCode: Int
    ) {
        runOnUiThread {
            if (pendingBridgePicker != null) {
                postBridgeError(
                    replyProxy,
                    requestId,
                    "Another Android storage picker is already open.",
                    "InvalidStateError"
                )
                return@runOnUiThread
            }

            pendingBridgePicker = PendingPicker(requestId, replyProxy)
            try {
                startActivityForResult(intent, requestCode)
            } catch (_: Exception) {
                pendingBridgePicker = null
                postBridgeError(
                    replyProxy,
                    requestId,
                    "Android could not open the storage picker.",
                    "NotAllowedError"
                )
            }
        }
    }

    private fun openFolderFromToolbar() {
        if (pendingBridgePicker != null) {
            Toast.makeText(this, "A storage picker is already open.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),
                REQUEST_TOOLBAR_FOLDER
            )
        } catch (_: Exception) {
            Toast.makeText(this, "Android folder picker is unavailable.", Toast.LENGTH_LONG).show()
        }
    }

    private fun restoreRememberedDirectory(view: WebView) {
        val treeUri = selectedTreeUri ?: return
        val permissionGranted = contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission
        }

        if (!permissionGranted) {
            selectedTreeUri = null
            getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .edit()
                .remove(KEY_TREE_URI)
                .apply()
            return
        }

        val name = runCatching { displayName(treeUri) }.getOrDefault("Selected folder")
        val script = "window.__androidProvideDirectory(" +
            JSONObject.quote(treeUri.toString()) + "," +
            JSONObject.quote(name) + ");"
        view.evaluateJavascript(script, null)
    }

    private fun buildOpenFileIntent(acceptTypes: List<String>, allowMultiple: Boolean): Intent {
        val mimeTypes = acceptTypes
            .mapNotNull { value ->
                val candidate = value.trim().lowercase()
                when {
                    candidate.contains("/") -> candidate
                    candidate.startsWith(".") -> MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(candidate.removePrefix("."))
                    else -> null
                }
            }
            .distinct()

        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = when (mimeTypes.size) {
                0 -> "*/*"
                1 -> mimeTypes.first()
                else -> "*/*"
            }
            if (mimeTypes.size > 1) {
                putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
            }
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
    }

    private fun performStorageOperation(method: String, args: JSONObject): Any {
        return when (method) {
            "list" -> listDirectory(args.getString("uri"))
            "read" -> readFile(args.getString("uri"))
            "write" -> {
                writeFile(args.getString("uri"), args.getString("base64"))
                JSONObject().put("written", true)
            }
            "createDirectory" -> createEntry(
                args.getString("parentUri"),
                args.getString("name"),
                DocumentsContract.Document.MIME_TYPE_DIR
            )
            "createFile" -> createEntry(
                args.getString("parentUri"),
                args.getString("name"),
                args.optString("mimeType", "application/octet-stream")
            )
            "delete" -> {
                deleteEntry(args.getString("uri"), args.optBoolean("recursive", false))
                JSONObject().put("deleted", true)
            }
            "rename" -> renameEntry(args.getString("uri"), args.getString("name"))
            "move" -> moveEntry(
                args.getString("uri"),
                args.getString("sourceParentUri"),
                args.getString("destinationUri"),
                args.getString("name")
            )
            else -> throw IllegalArgumentException("Unsupported storage operation: $method")
        }
    }

    private fun getSelectedTreeUri(): Uri {
        val uri = selectedTreeUri
            ?: getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getString(KEY_TREE_URI, null)
                ?.let(Uri::parse)
        return uri ?: throw IllegalStateException("Select a local folder first.")
    }

    private fun documentIdFor(uri: Uri, treeUri: Uri): String {
        return if (uri.toString() == treeUri.toString()) {
            DocumentsContract.getTreeDocumentId(treeUri)
        } else {
            DocumentsContract.getDocumentId(uri)
        }
    }

    private fun listDirectory(uriString: String): JSONArray {
        val treeUri = getSelectedTreeUri()
        val parentUri = Uri.parse(uriString)
        val parentId = documentIdFor(parentUri, treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        val result = JSONArray()
        contentResolver.query(childrenUri, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val documentId = cursor.getString(
                    cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                )
                val name = cursor.getString(
                    cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                ) ?: "untitled"
                val mimeType = cursor.getString(
                    cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                ) ?: "application/octet-stream"

                val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val modified = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                    cursor.getLong(modifiedIndex)
                } else {
                    0L
                }

                result.put(
                    JSONObject()
                        .put("name", name)
                        .put(
                            "uri",
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId).toString()
                        )
                        .put("isDirectory", mimeType == DocumentsContract.Document.MIME_TYPE_DIR)
                        .put("mimeType", mimeType)
                        .put("size", size)
                        .put("lastModified", modified)
                )
            }
        } ?: throw IllegalStateException("Android could not list this folder.")

        return result
    }

    private fun readFile(uriString: String): JSONObject {
        val uri = Uri.parse(uriString)
        val output = ByteArrayOutputStream()
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"

        contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_FILE_BYTES) {
                    throw IllegalArgumentException("Files larger than 32 MB are not supported yet.")
                }
                output.write(buffer, 0, count)
            }
        } ?: throw IllegalStateException("Android could not read this file.")

        return JSONObject()
            .put("base64", Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP))
            .put("mimeType", mimeType)
            .put("lastModified", System.currentTimeMillis())
    }

    private fun writeFile(uriString: String, base64: String) {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        if (bytes.size > MAX_FILE_BYTES) {
            throw IllegalArgumentException("Files larger than 32 MB are not supported yet.")
        }

        contentResolver.openOutputStream(Uri.parse(uriString), "wt")?.use { output ->
            output.write(bytes)
            output.flush()
        } ?: throw IllegalStateException("Android could not write this file.")
    }

    private fun createEntry(parentUriString: String, name: String, mimeType: String): JSONObject {
        requireSafeName(name)
        val treeUri = getSelectedTreeUri()
        val parentId = documentIdFor(Uri.parse(parentUriString), treeUri)
        val parentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
        val createdUri = DocumentsContract.createDocument(
            contentResolver,
            parentDocumentUri,
            mimeType,
            name
        ) ?: throw IllegalStateException("Android could not create this item.")

        return JSONObject()
            .put("name", name)
            .put("uri", createdUri.toString())
            .put("isDirectory", mimeType == DocumentsContract.Document.MIME_TYPE_DIR)
            .put("mimeType", mimeType)
    }

    private fun deleteEntry(uriString: String, recursive: Boolean) {
        val uri = Uri.parse(uriString)
        val mimeType = contentResolver.getType(uri)

        if (recursive && mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            val nested = listDirectory(uriString)
            for (index in 0 until nested.length()) {
                val child = nested.getJSONObject(index)
                deleteEntry(child.getString("uri"), true)
            }
        }

        if (!DocumentsContract.deleteDocument(contentResolver, uri)) {
            throw IllegalStateException("Android could not delete this item.")
        }
    }

    private fun renameEntry(uriString: String, newName: String): JSONObject {
        requireSafeName(newName)
        val renamedUri = DocumentsContract.renameDocument(
            contentResolver,
            Uri.parse(uriString),
            newName
        ) ?: throw IllegalStateException("Android could not rename this item.")

        return JSONObject()
            .put("uri", renamedUri.toString())
            .put("name", newName)
    }

    private fun moveEntry(
        uriString: String,
        sourceParentUriString: String,
        destinationUriString: String,
        newName: String
    ): JSONObject {
        requireSafeName(newName)
        val treeUri = getSelectedTreeUri()
        val itemUri = Uri.parse(uriString)
        val sourceParentId = documentIdFor(Uri.parse(sourceParentUriString), treeUri)
        val destinationParentId = documentIdFor(Uri.parse(destinationUriString), treeUri)
        val sourceParent = DocumentsContract.buildDocumentUriUsingTree(treeUri, sourceParentId)
        val destinationParent = DocumentsContract.buildDocumentUriUsingTree(treeUri, destinationParentId)
        val currentName = displayName(itemUri)

        val movedUri = if (sourceParentId == destinationParentId) {
            if (currentName == newName) {
                itemUri
            } else {
                DocumentsContract.renameDocument(contentResolver, itemUri, newName)
                    ?: throw IllegalStateException("Android could not rename this item.")
            }
        } else {
            var result = DocumentsContract.moveDocument(
                contentResolver,
                itemUri,
                sourceParent,
                destinationParent
            ) ?: throw IllegalStateException("This storage provider does not support moving items.")

            if (currentName != newName) {
                result = DocumentsContract.renameDocument(contentResolver, result, newName)
                    ?: throw IllegalStateException("The item moved, but Android could not rename it.")
            }
            result
        }

        return JSONObject()
            .put("uri", movedUri.toString())
            .put("name", displayName(movedUri))
    }

    private fun requireSafeName(name: String) {
        require(name.isNotBlank() && name != "." && name != "..") { "Invalid item name." }
        require(!name.contains("/") && !name.contains("\\")) { "Names cannot contain path separators." }
    }

    private fun postBridgeSuccess(
        replyProxy: JavaScriptReplyProxy,
        requestId: String,
        data: Any
    ) {
        val response = JSONObject()
            .put("id", requestId)
            .put("ok", true)
            .put("data", data)
        runCatching { replyProxy.postMessage(response.toString()) }
    }

    private fun postBridgeError(
        replyProxy: JavaScriptReplyProxy,
        requestId: String,
        error: String,
        errorName: String
    ) {
        val response = JSONObject()
            .put("id", requestId)
            .put("ok", false)
            .put("error", error)
            .put("errorName", errorName)
        runCatching { replyProxy.postMessage(response.toString()) }
    }

    private fun persistTreePermission(uri: Uri, flags: Int) {
        val grants = flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        if (grants != 0) {
            runCatching { contentResolver.takePersistableUriPermission(uri, grants) }
                .onFailure {
                    Toast.makeText(
                        this,
                        "Folder selected for this session, but Android could not persist its permission.",
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
        selectedTreeUri = uri
        getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .edit()
            .putString(KEY_TREE_URI, uri.toString())
            .apply()
    }

    private fun displayName(uri: Uri): String {
        // ACTION_OPEN_DOCUMENT can return MediaStore, Downloads, cloud-provider, or document URIs.
        val openableName = runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                } else null
            }
        }.getOrNull()
        if (!openableName.isNullOrBlank()) return openableName

        val documentName = runCatching {
            val queryUri = if (DocumentsContract.isTreeUri(uri)) {
                val rootId = DocumentsContract.getTreeDocumentId(uri)
                DocumentsContract.buildDocumentUriUsingTree(uri, rootId)
            } else uri

            contentResolver.query(
                queryUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()

        return documentName
            ?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "Selected item"
    }

    @Deprecated("Legacy activity result API retained for broad Android compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        try {
            when (requestCode) {
                REQUEST_BRIDGE_FOLDER, REQUEST_TOOLBAR_FOLDER -> {
                val selectedUri = if (resultCode == RESULT_OK) data?.data else null

                if (selectedUri == null) {
                    if (requestCode == REQUEST_BRIDGE_FOLDER) {
                        val pending = pendingBridgePicker
                        pendingBridgePicker = null
                        pending?.let {
                            postBridgeError(
                                it.replyProxy,
                                it.requestId,
                                "Folder selection was cancelled.",
                                "AbortError"
                            )
                        }
                    }
                    return
                }

                persistTreePermission(selectedUri, data?.flags ?: 0)
                val name = displayName(selectedUri)

                if (requestCode == REQUEST_BRIDGE_FOLDER) {
                    val pending = pendingBridgePicker
                    pendingBridgePicker = null
                    pending?.let {
                        postBridgeSuccess(
                            it.replyProxy,
                            it.requestId,
                            JSONObject().put("uri", selectedUri.toString()).put("name", name)
                        )
                    }
                } else {
                    // The VS Code Web File > Open Folder action consumes this selected handle.
                    val script = "window.__androidProvideDirectory(" +
                        JSONObject.quote(selectedUri.toString()) + "," +
                        JSONObject.quote(name) + ");"
                    editor.evaluateJavascript(script, null)
                    Toast.makeText(
                        this,
                        "Folder selected. In the editor, choose File > Open Folder.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            REQUEST_BRIDGE_FILE -> {
                val pending = pendingBridgePicker
                pendingBridgePicker = null
                if (pending == null) return

                if (resultCode != RESULT_OK || data == null) {
                    postBridgeError(
                        pending.replyProxy,
                        pending.requestId,
                        "File selection was cancelled.",
                        "AbortError"
                    )
                    return
                }

                val uris = selectedUris(data)
                val result = JSONArray()
                uris.forEach { uri ->
                    persistFilePermission(uri, data.flags)
                    result.put(
                        JSONObject()
                            .put("uri", uri.toString())
                            .put("name", displayName(uri))
                            .put("mimeType", contentResolver.getType(uri) ?: "application/octet-stream")
                    )
                }
                postBridgeSuccess(pending.replyProxy, pending.requestId, result)
            }

            REQUEST_UPLOAD_FILE -> {
                val callback = pendingFileChooser
                pendingFileChooser = null
                if (callback == null) return

                if (resultCode != RESULT_OK || data == null) {
                    callback.onReceiveValue(null)
                    return
                }

                val uris = selectedUris(data)
                uris.forEach { persistFilePermission(it, data.flags) }
                callback.onReceiveValue(uris.toTypedArray())
                }
            }
        } catch (error: Exception) {
            val pending = pendingBridgePicker
            pendingBridgePicker = null
            pending?.let {
                postBridgeError(
                    it.replyProxy,
                    it.requestId,
                    error.message ?: "Android could not open the selected item.",
                    "NotAllowedError"
                )
            }

            pendingFileChooser?.onReceiveValue(null)
            pendingFileChooser = null
            Toast.makeText(
                this,
                error.message ?: "Android could not access the selected item.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun selectedUris(data: Intent): List<Uri> {
        val result = mutableListOf<Uri>()
        data.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                result.add(clip.getItemAt(index).uri)
            }
        } ?: data.data?.let(result::add)
        return result
    }

    private fun persistFilePermission(uri: Uri, flags: Int) {
        val persistable = flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        val grants = flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        if (persistable != 0 && grants != 0) {
            runCatching { contentResolver.takePersistableUriPermission(uri, grants) }
        }
    }

    private fun showAboutDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }

        content.addView(
            TextView(this).apply {
                text = "An independent Android wrapper for the official Visual Studio Code web editor. This application is not affiliated with or endorsed by Microsoft."
                textSize = 14f
                setTextColor(Color.DKGRAY)
            }
        )

        val officialLink = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, dp(4))
            setOnClickListener {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://code.visualstudio.com/"))
                )
            }
        }

        officialLink.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_vscode_link)
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            }
        )
        officialLink.addView(
            TextView(this).apply {
                text = "  Visit Visual Studio Code"
                textSize = 14f
                setTextColor(Color.rgb(0, 122, 204))
            }
        )
        content.addView(officialLink)

        AlertDialog.Builder(this)
            .setTitle("About Code Web Companion")
            .setView(content)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun createKeyRow(): HorizontalScrollView {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            addView(
                content,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46))
            )
            tag = content
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(46)
            )
        }
    }

    private fun rowContent(row: HorizontalScrollView): LinearLayout = row.tag as LinearLayout

    private fun addModifierButton(row: HorizontalScrollView, label: String, flag: Int) {
        val button = makeKeyButton(label)
        modifierButtons[flag] = button
        button.setOnClickListener {
            activeModifiers = activeModifiers xor flag
            syncModifierButtons()
            editor.requestFocus()
        }
        rowContent(row).addView(button)
    }

    private fun addKeyButton(row: HorizontalScrollView, label: String, keyCode: Int) {
        val button = makeKeyButton(label)
        button.setOnClickListener { sendKey(keyCode) }
        rowContent(row).addView(button)
    }

    private fun addSymbolButton(row: HorizontalScrollView, symbol: String) {
        val mapping = symbolKey(symbol) ?: return
        val button = makeKeyButton(symbol)
        button.setOnClickListener { sendKey(mapping.first, mapping.second) }
        rowContent(row).addView(button)
    }

    private fun makeKeyButton(label: String): Button = Button(this).apply {
        text = label
        textSize = 11f
        isAllCaps = false
        isFocusable = false
        isFocusableInTouchMode = false
        minWidth = dp(42)
        minimumWidth = dp(42)
        minHeight = dp(40)
        minimumHeight = dp(40)
        setPadding(dp(7), 0, dp(7), 0)
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(65, 65, 68))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(42)
        ).apply {
            setMargins(dp(2), dp(1), dp(2), dp(1))
        }
    }

    private fun sendKey(keyCode: Int, extraModifiers: Int = 0) {
        editor.requestFocus()
        val requestedMeta = activeModifiers or extraModifiers
        val meta = KeyEvent.normalizeMetaState(requestedMeta)
        val downTime = SystemClock.uptimeMillis()
        val source = InputDevice.SOURCE_KEYBOARD

        // Send a paired key event carrying the modifier state to Chromium's input pipeline.
        val down = KeyEvent(
            downTime,
            downTime,
            KeyEvent.ACTION_DOWN,
            keyCode,
            0,
            meta,
            KeyEvent.VIRTUAL_KEYBOARD,
            0,
            KeyEvent.FLAG_FROM_SYSTEM,
            source
        )
        val up = KeyEvent(
            downTime,
            downTime + 1,
            KeyEvent.ACTION_UP,
            keyCode,
            0,
            meta,
            KeyEvent.VIRTUAL_KEYBOARD,
            0,
            KeyEvent.FLAG_FROM_SYSTEM,
            source
        )

        editor.dispatchKeyEvent(down)
        editor.dispatchKeyEvent(up)

        activeModifiers = 0
        syncModifierButtons()
    }

    private fun symbolKey(symbol: String): Pair<Int, Int>? {
        val shift = KeyEvent.META_SHIFT_ON
        return when (symbol) {
            "[" -> KeyEvent.KEYCODE_LEFT_BRACKET to 0
            "]" -> KeyEvent.KEYCODE_RIGHT_BRACKET to 0
            "{" -> KeyEvent.KEYCODE_LEFT_BRACKET to shift
            "}" -> KeyEvent.KEYCODE_RIGHT_BRACKET to shift
            "(" -> KeyEvent.KEYCODE_9 to shift
            ")" -> KeyEvent.KEYCODE_0 to shift
            ";" -> KeyEvent.KEYCODE_SEMICOLON to 0
            ":" -> KeyEvent.KEYCODE_SEMICOLON to shift
            "/" -> KeyEvent.KEYCODE_SLASH to 0
            "\\" -> KeyEvent.KEYCODE_BACKSLASH to 0
            "|" -> KeyEvent.KEYCODE_BACKSLASH to shift
            "~" -> KeyEvent.KEYCODE_GRAVE to shift
            "_" -> KeyEvent.KEYCODE_MINUS to shift
            else -> null
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun enableImmersiveStatusBar() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            // A swipe from the top edge temporarily reveals the status bar.
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
        }
    }

    override fun onResume() {
        super.onResume()
        if (::editor.isInitialized) editor.onResume()
        enableImmersiveStatusBar()
    }

    override fun onPause() {
        if (::editor.isInitialized) editor.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::editor.isInitialized) editor.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveStatusBar()
    }

    private fun registerSystemBackCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val callback = OnBackInvokedCallback { handleBackNavigation() }
            backInvokedCallback = callback
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback
            )
        }
    }

    private fun handleBackNavigation() {
        if (authPopupDialog?.isShowing == true) {
            closeAuthenticationPopup()
            return
        }

        if (!::editor.isInitialized) return

        // Back navigates inside the editor; it never closes the wrapper activity.
        if (editor.canGoBack()) {
            editor.goBack()
            return
        }

        val currentHost = runCatching { Uri.parse(editor.url).host }.getOrNull()
        if (currentHost != "vscode.dev") {
            editor.loadUrl(VSCODE_WEB_URL)
        }
    }

    @Deprecated("Legacy callback retained for Android versions below 13.")
    override fun onBackPressed() {
        handleBackNavigation()
    }

    override fun onDestroy() {
        pendingFileChooser?.onReceiveValue(null)
        pendingFileChooser = null
        pendingBridgePicker = null
        authPopupDialog?.dismiss()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backInvokedCallback?.let {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
            }
        }
        backInvokedCallback = null
        authPopupDialog = null
        authPopupWebView = null
        ioExecutor.shutdownNow()

        if (::editor.isInitialized) {
            editor.stopLoading()
            editor.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val VSCODE_WEB_URL = "https://vscode.dev/"
        private const val VSCODE_ORIGIN = "https://vscode.dev"
        private const val CODESPACES_URL = "https://github.com/codespaces"
        private const val PREFERENCES = "local_workspace"
        private const val KEY_TREE_URI = "selected_tree_uri"
        private const val KEY_DESKTOP_MODE = "desktop_mode"
        private const val KEY_PAGE_ZOOM = "page_zoom"
        private const val KEY_KEYBAR_VISIBLE = "keybar_visible"
        private const val ZOOM_STEP = 0.1f
        private const val MIN_PAGE_ZOOM = 0.6f
        private const val MAX_PAGE_ZOOM = 2.0f
        private const val REQUEST_BRIDGE_FOLDER = 4201
        private const val REQUEST_BRIDGE_FILE = 4202
        private const val REQUEST_TOOLBAR_FOLDER = 4203
        private const val REQUEST_UPLOAD_FILE = 4204
        private const val MAX_FILE_BYTES = 32 * 1024 * 1024
    }
}
