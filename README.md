# Code Web Companion

An independent Android wrapper for the official Visual Studio Code web editor at https://vscode.dev. This project is not affiliated with or endorsed by Microsoft.

## Features

- Android WebView that opens VS Code for the Web.
- Immersive layout: the status bar is hidden, the navigation bar remains available, and a swipe from the top temporarily reveals system bars.
- Display-cutout/notch support with safe insets for app controls.
- Dedicated **⌨** control to request the Android keyboard, WebView zoom in/out/reset, and mobile/desktop user-agent modes.
- One horizontally scrollable, hideable key strip containing the keyboard control, CTRL, ALT, SHIFT, META, ESC, TAB, function keys, navigation keys, and programming symbols.
- Editor scrolling gestures dismiss the on-screen keyboard without intentionally clearing editor focus; a normal tap on a text input is left to VS Code/WebView to request the keyboard.
- Modifier indicators mirror key states observed by the editor from the Android/physical keyboard.
- Remote menu shortcuts for GitHub repositories, VS Code Remote Tunnels, GitHub Codespaces, and the GitHub Copilot Marketplace page.
- Android WebView supports GitHub sign-in popup windows and shared WebView cookies; OAuth compatibility still depends on the provider and Android WebView.
- Android Storage Access Framework is invoked when VS Code requests Open Folder or Open File; the last selected folder permission is remembered.
- A compatibility layer for common File System Access API operations, including rename and provider-dependent move support.
- Android file picker support for web editor uploads.
- An original app icon. The official Visual Studio Code logo appears only as a link to the official website.

## Open a folder from Android storage

1. Tap **Open Folder** in the app header.
2. Choose a folder in Android's system folder picker.
3. Return to the editor.
4. In the editor, choose **File → Open Folder**. The app passes the selected folder handle to the editor.
5. To switch projects, repeat these steps.

The selected folder permission is retained by Android. When you choose **File → Open Folder** inside VS Code, the app opens Android's system folder picker directly. After the app or editor page reloads, the bridge preselects the last folder again. You can also choose **Open Folder** in the native header. Document-provider failures are caught and reported rather than allowed to crash the activity.

Android grants access only to the folder selected by the user and its descendants. Android 11 and later restrict selection of certain protected locations, including storage roots, Download, Android/data, and Android/obb.

## Build locally

Install JDK 21, Android SDK Platform 36, and Gradle 9.5.0.

    gradle --no-daemon :app:assembleDebug

Debug APK output: app/build/outputs/apk/debug/app-debug.apk

    gradle --no-daemon -PappVersion=0.5.0 :app:assembleRelease

Release APK output: app/build/outputs/apk/release/app-release.apk

## GitHub Actions

- The Android APK workflow builds a debug APK on pushes to main and pull requests.
- The app shows a narrow page-load progress line, preserves WebView state across Activity recreation, and uses a compact header to reduce clipping on narrow screens.
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

The local-folder integration is an experimental compatibility layer, not a complete browser File System Access API implementation. It covers common folder listing, file read/write, create, delete, rename, and move operations. Move support depends on the selected Android document provider. File transfers through the bridge are limited to 32 MB per file. Android may revoke folder permission after the app is reinstalled or app data is cleared. Android's Storage Access Framework does not expose the same capabilities as a desktop filesystem, and some protected locations cannot be selected.

The bridge does not provide reliable live file-watcher notifications because Storage Access Framework providers vary in their change-notification support. VS Code's built-in editor tabs remain available inside the page, but this app does not create separate native WebView tabs or windows.

## GitHub Settings Sync

Settings Sync is handled by VS Code itself; there is no separate native Sync button. In VS Code, open the Manage gear menu or Accounts menu, choose **Backup and Sync Settings**, select the categories, and sign in with GitHub. The wrapper enables WebView popups and third-party cookies for the sign-in flow. Some OAuth providers restrict embedded WebViews, so GitHub sign-in must still be verified on the target Android device. Do not enter credentials on pages unless the address belongs to the expected provider.

## GitHub repositories and Remote Tunnels

Open **Remote** in the app header, then choose **Open GitHub repository** and enter `owner/repository`. The app navigates to `https://vscode.dev/github/owner/repository`.

To use **Connect to VS Code Tunnel**, first start a tunnel on another computer that has VS Code and the Remote Tunnels support installed. Paste the full `https://vscode.dev/tunnel/<machine>/<folder>` URL printed by that computer. The remote computer must remain online with the tunnel running.

## GitHub Codespaces terminal

Open **Remote → Open GitHub Codespaces**, sign in, then create or resume a Codespace and open it in the browser editor. When the Codespace browser editor opens, this app routes the `*.github.dev` editor into the main WebView. The integrated terminal then runs inside the Codespace's remote environment.

The normal `vscode.dev` editor does not have a local shell or runtime. A Codespace is a remote VM/container and its availability and usage allowance depend on the GitHub account plan. This project does not implement a local Android terminal.

## GitHub Copilot

Open **Remote → GitHub Copilot extension** to view the Marketplace listing, or use VS Code's Extensions view and search for **GitHub Copilot**. Install it from the VS Code editor and sign in with the GitHub account that has Copilot access. Copilot in Codespaces is supported through the extension. Extension compatibility in `vscode.dev` depends on whether the extension supports the web extension host; some desktop-only features remain unavailable.

## Navigation and display controls

- **Back** navigates WebView history and returns to `vscode.dev` when leaving another page; it does not finish the app.
- **⌨** focuses Monaco's input area and explicitly requests the Android on-screen keyboard.
- Scrolling/dragging in the editor dismisses the IME after the gesture; a normal tap on a text input is handled by the editor.
- **− / + / 100%** control programmatic WebView zoom; built-in pinch zoom is disabled.
- **Desktop** changes the WebView user-agent and reloads the current page. A desktop user-agent cannot add desktop-only runtime capabilities to the browser.

A real-device test is required before treating local folder editing as production-ready.

## Brand and attribution

The app has its own name and icon. The official Visual Studio Code logo is used only as a link to the official Visual Studio Code website, consistent with the published brand guidance.
