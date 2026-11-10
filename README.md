# Code Web Companion

An independent Android wrapper for the official Visual Studio Code web editor at https://vscode.dev. This project is not affiliated with or endorsed by Microsoft.

## Features

- Android WebView that opens VS Code for the Web.
- Touch coding toolbar: CTRL, ALT, SHIFT, ESC, TAB, function keys, navigation keys and programming symbols.
- Android Storage Access Framework folder picker.
- A compatibility layer for common File System Access API operations.
- Android file picker support for web editor uploads.
- An original app icon. The official Visual Studio Code logo appears only as a link to the official website.

## Open a folder from Android storage

1. Tap **Open Folder** in the app header.
2. Choose a folder in Android's system folder picker.
3. Return to the editor.
4. In the editor, choose **File → Open Folder**. The app passes the selected folder handle to the editor.
5. To switch projects, repeat these steps.

You can also use File → Open Folder without preselecting a folder; the app will show the Android folder picker.

Android grants access only to the folder selected by the user and its descendants. Android 11 and later restrict selection of certain protected locations, including storage roots, Download, Android/data, and Android/obb.

## Build locally

Install JDK 21, Android SDK Platform 36, and Gradle 9.5.0.

    gradle --no-daemon :app:assembleDebug

Debug APK output: app/build/outputs/apk/debug/app-debug.apk

    gradle --no-daemon -PappVersion=0.2.0 :app:assembleRelease

Release APK output: app/build/outputs/apk/release/app-release.apk

## GitHub Actions

- The Android APK workflow builds a debug APK on pushes to main and pull requests.
- The Release APK workflow publishes a GitHub Release when a v-prefixed tag is pushed, or when the workflow is run manually.
- Release asset name: CodeWebCompanion-vX.Y.Z.apk

### Persistent release signing

To generate release APKs that can update earlier installations, configure these GitHub Actions repository secrets:

- ANDROID_KEYSTORE_BASE64
- ANDROID_KEYSTORE_PASSWORD
- ANDROID_KEY_ALIAS
- ANDROID_KEY_PASSWORD

Without all four secrets, the workflow signs a preview APK with the temporary debug keystore on the runner and marks the GitHub Release as a pre-release. This fallback is for testing only; it is not suitable for app-store publication or reliable upgrades between releases.

## Local storage bridge limitations

The local-folder integration is an experimental compatibility layer, not a complete browser File System Access API implementation. It covers common folder listing, file read/write, create, rename and delete operations. Some VS Code Web workflows may still require browser APIs that Android WebView does not implement. File transfers through the bridge are limited to 32 MB per file. The folder permission may need to be granted again after reinstalling the app or clearing app data.

A real-device test is required before treating local folder editing as production-ready.

## Brand and attribution

The app has its own name and icon. The official Visual Studio Code logo is used only as a link to the official Visual Studio Code website, consistent with the published brand guidance.
