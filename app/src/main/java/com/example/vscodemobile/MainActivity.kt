package com.example.vscodemobile

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.provider.DocumentsContract
import android.util.Base64
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.webkit.WebMessageCompat
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
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

class MainActivity : Activity() {
    private lateinit var editor: WebView
    private lateinit var keyboardBar: HorizontalScrollView
    private val modifierButtons = mutableMapOf<Int, Button>()
    private var activeModifiers = 0
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
                view.setPadding(
                    safeInsets.left,
                    safeInsets.top,
                    safeInsets.right,
                    safeInsets.bottom
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
                text = getString(R.string.app_name)
                textSize = 13f
                setTextColor(Color.WHITE)
                setPadding(dp(8), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )

        header.addView(makeHeaderButton("Open Folder") { openFolderFromToolbar() })
        header.addView(makeHeaderButton("Remote") { showRemoteDialog() })
        header.addView(makeHeaderButton("Keys") { toggleKeyboardBar() })
        header.addView(makeHeaderButton("About") { showAboutDialog() })
        root.addView(
            header,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
        )

        editor = WebView(this).apply {
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
            settings.setSupportZoom(true) // Programmatic zoom works; built-in pinch controls stay disabled.
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

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    val loadedUri = Uri.parse(url)
                    CookieManager.getInstance().flush()
                    applySavedPageZoom(view)
                    if (loadedUri.scheme == "https" && loadedUri.host == "vscode.dev") {
                        restoreRememberedDirectory(view)
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    // HTTPS sign-in pages may be required; native file access remains origin-restricted.
                    return request.url.scheme != "https"
                }
            }

            webChromeClient = object : WebChromeClient() {
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

                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(
                            Intent.EXTRA_ALLOW_MULTIPLE,
                            fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                        )
                    }

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

        val keyboardBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(37, 37, 38))
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }

        val controlRow = createKeyRow()
        addControlButton(controlRow, "⌨") { showInternalKeyboard() }
        addControlButton(controlRow, "−") { changePageZoom(-ZOOM_STEP) }
        zoomLabelButton = makeKeyButton("100%").apply {
            setOnClickListener { resetPageZoom() }
        }
        rowContent(controlRow).addView(zoomLabelButton)
        addControlButton(controlRow, "+") { changePageZoom(ZOOM_STEP) }
        desktopModeButton = makeKeyButton(if (desktopMode) "Desktop ✓" else "Desktop").apply {
            setOnClickListener { toggleDesktopMode() }
        }
        rowContent(controlRow).addView(desktopModeButton)
        addControlButton(controlRow, "Sync") { showSyncHelp() }
        addControlButton(controlRow, "Codespaces") { openCodespaces() }

        val firstRow = createKeyRow()
        addModifierButton(firstRow, "CTRL", KeyEvent.META_CTRL_ON)
        addModifierButton(firstRow, "ALT", KeyEvent.META_ALT_ON)
        addModifierButton(firstRow, "SHIFT", KeyEvent.META_SHIFT_ON)
        addKeyButton(firstRow, "ESC", KeyEvent.KEYCODE_ESCAPE)
        addKeyButton(firstRow, "TAB", KeyEvent.KEYCODE_TAB)
        addKeyButton(firstRow, "F1", KeyEvent.KEYCODE_F1)
        addKeyButton(firstRow, "F5", KeyEvent.KEYCODE_F5)
        addKeyButton(firstRow, "P", KeyEvent.KEYCODE_P)
        addKeyButton(firstRow, "C", KeyEvent.KEYCODE_C)
        addKeyButton(firstRow, "V", KeyEvent.KEYCODE_V)
        addKeyButton(firstRow, "X", KeyEvent.KEYCODE_X)

        val secondRow = createKeyRow()
        listOf("{", "}", "[", "]", "(", ")", ";", ":", "/", "\\", "|", "~", "_")
            .forEach { addSymbolButton(secondRow, it) }
        addKeyButton(secondRow, "←", KeyEvent.KEYCODE_DPAD_LEFT)
        addKeyButton(secondRow, "↓", KeyEvent.KEYCODE_DPAD_DOWN)
        addKeyButton(secondRow, "↑", KeyEvent.KEYCODE_DPAD_UP)
        addKeyButton(secondRow, "→", KeyEvent.KEYCODE_DPAD_RIGHT)
        addKeyButton(secondRow, "HOME", KeyEvent.KEYCODE_MOVE_HOME)
        addKeyButton(secondRow, "END", KeyEvent.KEYCODE_MOVE_END)
        addKeyButton(secondRow, "DEL", KeyEvent.KEYCODE_DEL)

        keyboardBar.addView(controlRow)
        keyboardBar.addView(firstRow)
        keyboardBar.addView(secondRow)
        root.addView(
            keyboardBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        enableImmersiveStatusBar()
        installAndroidFileSystemBridge()
        editor.loadUrl(VSCODE_WEB_URL)
    }

    private fun showAuthenticationPopup(resultMsg: Message): Boolean {
        if (authPopupWebView != null) return false

        val popup = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settings.safeBrowsingEnabled = true
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean = request.url.scheme != "https"
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
              else if (document.activeElement) document.activeElement.focus();
            })();
        """.trimIndent()

        editor.evaluateJavascript(focusScript) {
            editor.postDelayed({
                val inputMethodManager =
                    getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                inputMethodManager.showSoftInput(
                    editor,
                    InputMethodManager.SHOW_IMPLICIT
                )
            }, 120)
        }
    }

    private fun changePageZoom(delta: Float) {
        val nextZoom = (pageZoom + delta).coerceIn(MIN_PAGE_ZOOM, MAX_PAGE_ZOOM)
        if (nextZoom == pageZoom) return

        editor.zoomBy(nextZoom / pageZoom)
        pageZoom = nextZoom
        zoomLabelButton?.text = "${(pageZoom * 100).toInt()}%"
    }

    private fun resetPageZoom() {
        if (pageZoom != 1.0f) {
            editor.zoomBy(1.0f / pageZoom)
            pageZoom = 1.0f
        }
        zoomLabelButton?.text = "100%"
    }

    private fun asDesktopUserAgent(userAgent: String): String {
        return userAgent
            .replace(Regex("\\s\\(Linux; Android [^)]*\\)"), " (X11; Linux x86_64)")
            .replace(" Mobile", "")
            .replace("; wv", "")
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
        desktopModeButton?.text = if (desktopMode) "Desktop ✓" else "Desktop"

        val currentUrl = editor.url
            ?.takeIf { Uri.parse(it).scheme == "https" }
            ?: VSCODE_WEB_URL
        editor.loadUrl(currentUrl)
        Toast.makeText(
            this,
            if (desktopMode) "Desktop site enabled. Reloading the editor." else "Mobile site enabled.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun openCodespaces() {
        editor.loadUrl(CODESPACES_URL)
        Toast.makeText(
            this,
            "Sign in to GitHub, then create or open a Codespace. Its remote environment provides the terminal.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun showSyncHelp() {
        AlertDialog.Builder(this)
            .setTitle("Backup & Sync")
            .setMessage(
                "In VS Code, open the Manage gear menu or Accounts menu, choose " +
                    "Backup and Sync Settings, select what to sync, then sign in with GitHub. " +
                    "Authentication popups are enabled in this app."
            )
            .setPositiveButton("Got it", null)
            .show()
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
            "pickFile" -> {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, args.optBoolean("multiple", false))
                }
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
        runCatching { contentResolver.takePersistableUriPermission(uri, grants) }
        selectedTreeUri = uri
        getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .edit()
            .putString(KEY_TREE_URI, uri.toString())
            .apply()
    }

    private fun displayName(uri: Uri): String {
        var name = "Selected folder"
        contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) name = cursor.getString(0) ?: name
        }
        return name
    }

    @Deprecated("Legacy activity result API retained for broad Android compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

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
        val grants = flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        runCatching { contentResolver.takePersistableUriPermission(uri, grants) }
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
            button.text = if ((activeModifiers and flag) != 0) "$label ✓" else label
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
        val meta = activeModifiers or extraModifiers
        val downTime = SystemClock.uptimeMillis()
        val source = InputDevice.SOURCE_KEYBOARD

        val down = KeyEvent(
            downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0, meta,
            -1, 0, 0, source
        )
        val up = KeyEvent(
            downTime, downTime + 1, KeyEvent.ACTION_UP, keyCode, 0, meta,
            -1, 0, 0, source
        )

        editor.dispatchKeyEvent(down)
        editor.dispatchKeyEvent(up)

        activeModifiers = 0
        modifierButtons.forEach { (flag, button) ->
            button.text = when (flag) {
                KeyEvent.META_CTRL_ON -> "CTRL"
                KeyEvent.META_ALT_ON -> "ALT"
                KeyEvent.META_SHIFT_ON -> "SHIFT"
                else -> button.text
            }
        }
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
        enableImmersiveStatusBar()
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
