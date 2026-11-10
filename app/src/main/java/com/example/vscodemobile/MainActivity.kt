package com.example.vscodemobile

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

class MainActivity : Activity() {
    private lateinit var editor: WebView
    private val modifierButtons = mutableMapOf<Int, Button>()
    private var activeModifiers = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.statusBarColor = Color.rgb(30, 30, 30)
        window.navigationBarColor = Color.rgb(30, 30, 30)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(30, 30, 30))
        }

        editor = WebView(this).apply {
            setBackgroundColor(Color.rgb(30, 30, 30))
            isFocusable = true
            isFocusableInTouchMode = true

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settings.safeBrowsingEnabled = true
            }

            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
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
        addSymbolButton(secondRow, "{")
        addSymbolButton(secondRow, "}")
        addSymbolButton(secondRow, "[")
        addSymbolButton(secondRow, "]")
        addSymbolButton(secondRow, "(")
        addSymbolButton(secondRow, ")")
        addSymbolButton(secondRow, ";")
        addSymbolButton(secondRow, ":")
        addSymbolButton(secondRow, "/")
        addSymbolButton(secondRow, "\\")
        addSymbolButton(secondRow, "|")
        addSymbolButton(secondRow, "~")
        addSymbolButton(secondRow, "_")
        addKeyButton(secondRow, "←", KeyEvent.KEYCODE_DPAD_LEFT)
        addKeyButton(secondRow, "↓", KeyEvent.KEYCODE_DPAD_DOWN)
        addKeyButton(secondRow, "↑", KeyEvent.KEYCODE_DPAD_UP)
        addKeyButton(secondRow, "→", KeyEvent.KEYCODE_DPAD_RIGHT)
        addKeyButton(secondRow, "HOME", KeyEvent.KEYCODE_MOVE_HOME)
        addKeyButton(secondRow, "END", KeyEvent.KEYCODE_MOVE_END)
        addKeyButton(secondRow, "DEL", KeyEvent.KEYCODE_DEL)

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
        editor.loadUrl("https://vscode.dev/")
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
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(46)
                )
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
            KeyCharacterMapCompat.VIRTUAL_KEYBOARD, 0, 0, source
        )
        val up = KeyEvent(
            downTime, downTime + 1, KeyEvent.ACTION_UP, keyCode, 0, meta,
            KeyCharacterMapCompat.VIRTUAL_KEYBOARD, 0, 0, source
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

    @Deprecated("Deprecated in Android API; retained for broad device compatibility")
    override fun onBackPressed() {
        if (::editor.isInitialized && editor.canGoBack()) {
            editor.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (::editor.isInitialized) {
            editor.stopLoading()
            editor.destroy()
        }
        super.onDestroy()
    }
}

private object KeyCharacterMapCompat {
    const val VIRTUAL_KEYBOARD = -1
}
