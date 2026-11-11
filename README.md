# Code Web Companion

An independent Android wrapper for the official Visual Studio Code web editor at https://vscode.dev. This project is not affiliated with or endorsed by Microsoft.

## Features

- Android WebView that opens VS Code for the Web.
- Immersive layout: the status bar is hidden, the navigation bar remains available, and a swipe from the top temporarily reveals system bars.
- Display-cutout/notch support with safe insets for app controls.
- Dedicated **⌨** control, modifier-aware Android IME and hardware-key event handling, native WebView zoom controls without an app-imposed total zoom ceiling, configurable viewport profiles, orientation controls, and a PC-style virtual pointer.
- One horizontally scrollable, hideable key strip containing keyboard control, zoom, desktop mode, optional configurable pointer, CTRL, ALT, SHIFT, META, ESC, TAB, function keys, navigation keys, and programming symbols.
- Editor scrolling gestures dismiss the on-screen keyboard without intentionally clearing editor focus; a normal tap on a text input is left to VS Code/WebView to request the keyboard.
- Modifier indicators mirror key states observed by the editor from the Android/physical keyboard.
- Remote menu entry points for the VS Code Command Palette, GitHub Codespaces, and the GitHub Copilot Extensions view.
- External HTTPS pages, including GitHub and Microsoft authentication pages, open in Android's default browser. The VS Code web editor and Codespaces workspaces stay inside the app. Authentication completion must be verified on the target device because Chrome and WebView use separate cookie stores.
- Android Storage Access Framework is invoked when VS Code requests Open Folder or Open File. Bridge objects inherit the FileSystem Access handle prototypes expected by VS Code's HTML file provider.
- A compatibility layer for common File System Access API operations, including rename and provider-dependent move support.
- Android file picker support for web editor uploads and SAF `content://` URI access.
- An original app icon. The official Visual Studio Code logo appears only as a link to the official website.

## Open a folder from Android storage

Use **File → Open Folder** inside VS Code. The app opens Android's system folder picker and returns a compatible folder handle to the editor. The **Folder** button in the app header remains an alternative for preselecting a folder.

The selected folder permission is retained by Android when the provider grants persistable access. When you choose **File → Open Folder** inside VS Code, the app opens Android's system folder picker directly. After the app or editor page reloads, the bridge can restore the selected folder. Document-provider errors are caught and reported instead of being allowed to terminate the activity.

Android grants access only to the folder selected by the user and its descendants. Android 11 and later restrict selection of certain protected locations, including storage roots, Download, Android/data, and Android/obb.

## Build locally

Install JDK 21, Android SDK Platform 36, and Gradle 9.5.0.

    gradle --no-daemon :app:assembleDebug

Debug APK output: app/build/outputs/apk/debug/app-debug.apk

    gradle --no-daemon -PappVersion=0.5.5 :app:assembleRelease

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

On Windows PowerShell (copies the Base64 value to the clipboard):

    [Convert]::ToBase64String([IO.File]::ReadAllBytes('.\code-web-companion-release.jks')) | Set-Clipboard

Add the following four values under **Repository → Settings → Secrets and variables → Actions**. This uses the free JDK `keytool` utility and GitHub repository secrets; no paid signing service is required:

- ANDROID_KEYSTORE_BASE64 — the Base64 output.
- ANDROID_KEYSTORE_PASSWORD — the keystore password.
- ANDROID_KEY_ALIAS — `code-web-companion` (or the alias you chose).
- ANDROID_KEY_PASSWORD — the key password.

Never commit the `.jks` file or place its passwords in the repository. Keep two secure backups outside the repository: losing the keystore prevents signing compatible updates. Add the values only in GitHub repository secrets; do not paste the keystore or passwords into chat. The earlier v0.2.0 preview used a temporary debug key, so it cannot be upgraded in-place with a newly generated production key; uninstall that preview before installing the first production-signed APK.

Without all four secrets, the workflow signs a preview APK with the temporary debug keystore on the runner and marks the GitHub Release as a pre-release. This fallback is for testing only; separate workflow runs can use different debug keys and therefore are not reliable upgrades.

## Local storage bridge limitations

The local-folder integration is an experimental compatibility layer, not a complete browser File System Access API implementation. Its file and directory handles use the prototype types expected by VS Code's HTML file provider. It covers common folder listing, file read/write, create, delete, rename, and move operations. Move support depends on the selected Android document provider. File transfers through the bridge are limited to 32 MB per file. Android may revoke folder permission after the app is reinstalled or app data is cleared. Android's Storage Access Framework does not expose the same capabilities as a desktop filesystem, and some protected locations cannot be selected.

The bridge does not provide reliable live file-watcher notifications because Storage Access Framework providers vary in their change-notification support. VS Code's built-in editor tabs remain available inside the page, but this app does not create separate native WebView tabs or windows.

## GitHub and Microsoft sign-in

Use **Remote → Accounts: Sign In** to open VS Code's provider picker, or **Remote → Settings Sync: Turn On**. The OAuth popup is now displayed as a visible in-app WebView so provider pages and the callback can remain in the same window. If a provider blocks embedded WebViews, use **Remote → Open VS Code in system browser** as a fallback. The system browser and app WebView use separate cookie stores, so signing in externally may not authenticate the in-app session; return to the app and retry the VS Code Accounts flow if needed. Only enter credentials on expected GitHub or Microsoft provider pages.

## GitHub repositories and Remote Tunnels

These actions are intentionally initiated inside VS Code, not through an APK-specific repository or tunnel form. Open the VS Code Command Palette with **Ctrl+Shift+P**. To open a repository, run **GitHub Repositories: Open Repository...** (the GitHub Repositories extension may need to be installed first). To connect to a machine that is already hosting a tunnel, run **Remote Tunnels: Connect to Tunnel**. The VS Code documentation describes these commands and requirements: https://code.visualstudio.com/docs/remote/vscode-web and https://code.visualstudio.com/docs/remote/tunnels/.

## VS Code integrated terminal

Use **Remote → Create / resume Codespace for a repository** or **Open existing Codespace by name**. When its `*.github.dev` workspace loads, the app attempts to create an integrated terminal automatically. The **TERM** shortcut or **Remote → Toggle integrated terminal** can show or hide it; **Remote → Create new integrated terminal** creates another one. You can also connect to a PC already running VS Code by using **Remote Tunnels**.

The plain `vscode.dev` web editor has no local shell/runtime. A terminal runs on a Codespace or connected remote computer, not inside the Android WebView. Codespaces have monthly account-plan usage quotas. A local Android shell would require a separate native terminal/runtime, and is not equivalent to the VS Code integrated terminal.

## GitHub Codespaces

The Remote menu offers three paths:
- **Open Codespaces dashboard** opens your account's Codespaces list.
- **Create / resume Codespace for a repository** accepts `owner/repository` and opens GitHub's quickstart page. GitHub can resume a matching Codespace or offer to create one.
- **Open existing Codespace by name** accepts the permanent Codespace name and opens `https://CODESPACE-NAME.github.dev` directly in the main WebView.

Once the `*.github.dev` editor opens, the Codespace provides the remote compute environment and integrated terminal. The Codespace has to finish starting before the terminal is ready. GitHub documents both the quickstart route and the permanent-name URL pattern for existing Codespaces.

## GitHub Copilot

Choose **Remote → GitHub Copilot extension** to open the Extensions view inside VS Code. Search for **GitHub Copilot**, then install it from the editor. GitHub documents Copilot support in the VS Code web client when opened inside a Codespace. On plain `vscode.dev`, extensions run in the browser web extension host; extensions without web-host support may show as unavailable. Copilot should therefore be installed and used in a Codespace for the most reliable setup.

## Navigation and display controls

- **Back** navigates WebView history and returns to `vscode.dev` when leaving another page; it does not finish the app.
- **⌨** focuses Monaco's input area and explicitly requests the Android on-screen keyboard. **ALL** focuses the Monaco editor and sends Ctrl+A when the on-screen keyboard's shortcut handling is unreliable.
- Scrolling/dragging in the editor dismisses the IME after the gesture; a normal tap on a text input is handled by the editor.
- **− / + / zoom label** change native WebView page scale. The app imposes no total zoom ceiling; zoom updates are coalesced to display frames. Android WebView still applies its own renderer limits. Pinch zoom can be enabled or disabled in **Settings**. Zoomed content can be panned using ordinary WebView touch scrolling.
- **Ptr** toggles a virtual pointer. **Settings** includes PC-style shapes (arrow, hand, I-beam, crosshair, move, resize, forbidden, ring, and dot), 6–48dp size, speed, and color. Drag one finger to move the pointer; tap to click; hold briefly then drag to send a mouse drag for text selection; use two fingers to scroll or pinch.
- In desktop mode, the page uses a wide viewport; touch panning and pinch zoom are handled by WebView.
- **Cursor / Ptr** enables a virtual pointer. Hold then drag to select text; normal drag moves the cursor, tap clicks, and two fingers scroll. Physical mice use the native WebView pointer path.
- **Settings → Display viewport** offers the device default and 1280×720, 1366×768, 1600×900, 1920×1080, or 2560×1440 viewport profiles. With Desktop enabled and the default profile selected, the app uses 1280×720. These are browser viewport hints, not changes to the phone panel's physical resolution. **Settings → Orientation** can follow device rotation or lock portrait/landscape.
- **Desktop** changes the WebView user-agent and reloads the current page. Resolution profiles adjust the page's CSS viewport; they do not add desktop-only runtime capabilities to the browser. **Settings → Web pages** controls whether ordinary HTTP(S) pages stay inside this app or open in the system browser.

A real-device test is required before treating local folder editing as production-ready.

## Brand and attribution

The app has its own name and icon. The official Visual Studio Code logo is used only as a link to the official Visual Studio Code website, consistent with the published brand guidance.
