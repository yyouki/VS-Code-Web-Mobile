# VS Code Web Mobile

An Android WebView wrapper for VS Code for the Web, with a two-row coding toolbar for touch devices.

## Included

- Kotlin Android Activity with WebView loading https://vscode.dev/.
- Extra keys: CTRL, ALT, SHIFT, ESC, TAB, F1, F5, P, C, V, X, arrows, HOME, END, DEL.
- Programming symbols: { } [ ] ( ) ; : / \ | ~ _.
- CTRL/ALT/SHIFT apply to the next toolbar key. Example: tap CTRL, SHIFT, then P for Ctrl+Shift+P.
- No JavaScript bridge is exposed to the remote website.

## Build with GitHub Actions

The workflow builds a debug APK on pushes to main, pull requests targeting main, and manual runs. Download the APK from the workflow run's Artifacts section. The artifact is named VSCodeMobile-debug-apk-<run-number>.

## Local build

Install JDK 21, Android SDK Platform 36, and Gradle 9.5.0. From the repository root, run:

    gradle --no-daemon :app:assembleDebug

The APK is generated at app/build/outputs/apk/debug/app-debug.apk.

## Limitations

- VS Code for the Web has browser-based limitations; the full desktop terminal/debugger and some extensions are unavailable.
- WebView keyboard routing varies by Android System WebView version and editor focus state. Test shortcuts on the target device.
- Local folder access, file upload/download handling, and desktop-style tabs are not included yet.
- Internet access is required.
