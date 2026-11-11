# Code Web Companion

An independent Android wrapper for the official Visual Studio Code web editor at https://vscode.dev. This project is not affiliated with or endorsed by Microsoft.

## Features

- Android WebView that opens VS Code for the Web.
- Immersive layout: the status bar is hidden, the navigation bar remains available, and a swipe from the top temporarily reveals system bars.
- Display-cutout/notch support with safe insets for app controls.
- Touch coding toolbar: CTRL, ALT, SHIFT, ESC, TAB, function keys, navigation keys and programming symbols.
- Physical keyboard input through Android WebView, with the touch toolbar as a fallback.
- Android Storage Access Framework folder picker and remembered folder permission.
- A compatibility layer for common File System Access API operations, including rename and provider-dependent move support.
- Android file picker support for web editor uploads.
- An original app icon. The official Visual Studio Code logo appears only as a link to the official website.

## Open a folder from Android storage

1. Tap **Open Folder** in the app header.
2. Choose a folder in Android's system folder picker.
3. Return to the editor.
4. In the editor, choose **File → Open Folder**. The app passes the selected folder handle to the editor.
5. To switch projects, repeat these steps.

The selected folder permission is retained by Android. After the app or editor page reloads, the bridge preselects the last folder again; choose **File → Open Folder** to reopen it. You can also use File → Open Folder without preselecting a folder; the app will show the Android folder picker.

Android grants access only to the folder selected by the user and its descendants. Android 11 and later restrict selection of certain protected locations, including storage roots, Download, Android/data, and Android/obb.

## Build locally

Install JDK 21, Android SDK Platform 36, and Gradle 9.5.0.

    gradle --no-daemon :app:assembleDebug

Debug APK output: app/build/outputs/apk/debug/app-debug.apk

    gradle --no-daemon -PappVersion=0.3.0 :app:assembleRelease

Release APK output: app/build/outputs/apk/release/app-release.apk

## GitHub Actions

- The Android APK workflow builds a debug APK on pushes to main and pull requests.
- The Release APK workflow publishes a GitHub Release when a v-prefixed tag is pushed, or when the workflow is run manually.
- Release asset name: CodeWebCompanion-vX.Y.Z.apk

### Persistent release signing

To generate release APKs that can update earlier production-signed installations, create one private keystore and keep using that same file for every future release. For example, run this locally:

    keytool -genkeypair -v -keystore code-web-companion-release.jks -alias code-web-companion -keyalg RSA -keysize 4096 -validity 10000

Convert the keystore file to a single-line Base64 value. On Linux:

    base64 -w 0 code-web-companion-release.jks

On macOS:

    base64 code-web-companion-release.jks | tr -d '\n'

Add the following four values under **Repository → Settings → Secrets and variables → Actions**:

- ANDROID_KEYSTORE_BASE64 — the Base64 output.
- ANDROID_KEYSTORE_PASSWORD — the keystore password.
- ANDROID_KEY_ALIAS — `code-web-companion` (or the alias you chose).
- ANDROID_KEY_PASSWORD — the key password.

Never commit the `.jks` file or place its passwords in the repository. Keep a secure backup: losing the keystore prevents signing compatible updates. The earlier v0.2.0 preview used a temporary debug key, so it cannot be upgraded in-place with a newly generated production key; uninstall that preview before installing the first production-signed APK.

Without all four secrets, the workflow signs a preview APK with the temporary debug keystore on the runner and marks the GitHub Release as a pre-release. This fallback is for testing only; separate workflow runs can use different debug keys and therefore are not reliable upgrades.

## Local storage bridge limitations

The local-folder integration is an experimental compatibility layer, not a complete browser File System Access API implementation. It covers common folder listing, file read/write, create, delete, rename, and move operations. Move support depends on the selected Android document provider. File transfers through the bridge are limited to 32 MB per file. Android may revoke folder permission after the app is reinstalled or app data is cleared.

The bridge does not provide reliable live file-watcher notifications because Storage Access Framework providers vary in their change-notification support. VS Code's built-in editor tabs remain available inside the page, but this app does not create separate native WebView tabs or windows. A local Android terminal is not included; vscode.dev does not provide a general-purpose local shell through this wrapper.

A real-device test is required before treating local folder editing as production-ready.

## Brand and attribution

The app has its own name and icon. The official Visual Studio Code logo is used only as a link to the official Visual Studio Code website, consistent with the published brand guidance.
