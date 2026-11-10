# VS Code Web Mobile

Android WebView wrapper for https://vscode.dev with an on-screen coding key toolbar.

## CI build

GitHub Actions builds a debug APK on pushes to `main`, pull requests targeting `main`, and manual runs. Download the APK from the workflow run's **Artifacts** section (`VSCodeMobile-debug-apk-*`).

## Project source

The initial Kotlin/Gradle project is provided in `VSCodeMobile-starter.zip`. The workflow extracts it and builds `app:assembleDebug`.

## Local build

Extract `VSCodeMobile-starter.zip`, install JDK 21 and Android SDK Platform 36, then use Gradle 9.5.0 from the extracted `VSCodeMobile` project directory:

```bash
gradle --no-daemon assembleDebug
```

The APK is generated at `VSCodeMobile/app/build/outputs/apk/debug/app-debug.apk`.

## Notes

This is a prototype wrapper for VS Code Web. A debug APK is for testing; a signed release build and on-device validation are separate steps.
