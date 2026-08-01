# Proofmark — Android

A native Android wrapper around the existing Proofmark Image QR Studio web
app. The web app's HTML/CSS/JS is unchanged and bundled under
`app/src/main/assets/`; this project only adds the Android shell around it
(WebView host, offline handling, uploads, downloads, splash screen, theming).

## Requirements

- Android Studio Ladybug (2024.2) or newer
- JDK 17
- Android SDK Platform 35, Build-Tools 35.x (Android Studio installs these
  automatically on first sync)

## Build & run locally

```bash
git clone <this-repo-url>
cd proofmark-android
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
Or just open the project folder in Android Studio and press **Run** — no
extra configuration is needed. `local.properties` (SDK path) is generated
automatically by Android Studio / Gradle on first sync and is intentionally
git-ignored, since it's machine-specific.

## Automatic builds (GitHub Actions)

`.github/workflows/android.yml` runs automatically:

| Trigger | What happens |
|---|---|
| Push or PR to `main`/`master` | Lints, runs unit tests, builds a debug APK, uploads it as a workflow artifact ("proofmark-debug-apk") |
| Push a tag like `v1.0.0` | Additionally builds a release APK and publishes it to a GitHub Release with the APK attached |

No configuration is required for the debug/artifact path. To get a
**properly signed** release build (rather than debug-signed), add these
repository secrets under Settings → Secrets and variables → Actions:

- `RELEASE_KEYSTORE_BASE64` — your `.jks`/`.keystore` file, base64-encoded
  (`base64 -w0 your.keystore`)
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

Without those secrets, tagged builds still succeed and still produce a
downloadable release APK — it's just signed with the debug key, which is
fine for internal testing but not for Play Store submission.

To cut a release:

```bash
git tag v1.0.0
git push origin v1.0.0
```

## How the web app is loaded

The bundled site is served through `androidx.webkit.WebViewAssetLoader`
over the reserved `https://appassets.androidx.local/assets/` virtual
domain, rather than `file://`. This is the current Android-recommended
approach: it gives the page a real HTTPS origin (so `fetch()`,
`localStorage`, and relative asset URLs all behave exactly as they would
on the web), avoids the security pitfalls of raw `file://` access, and
requires no server. See `MainActivity.configureWebView()`.

The only change made to the web project itself was setting Vite's `base`
to `./` so built asset URLs are relative — required for them to resolve
correctly under the asset-loader's virtual path, and unrelated to any
app functionality.

## What's implemented on the Android side

- **WebView**: JavaScript, DOM storage, database storage, cookies
  (including third-party, needed for the ImgBB upload), no `file://`
  access, HTTPS-only (cleartext traffic blocked at the network-security-
  config level).
- **File upload**: `onShowFileChooser` wires the web app's image `<input>`
  to a system chooser that offers both the gallery (`ACTION_GET_CONTENT`)
  and, when camera permission is granted, direct camera capture via
  `FileProvider`.
- **Downloads**: the web app exports QR codes as `blob:`/`data:` URLs via
  `<a download>`, which Chromium WebView doesn't resolve to real files on
  its own. `android-download-bridge.js` (injected after each page load)
  intercepts those clicks and forwards the content to the
  `AndroidDownloader` JS-bridge class, which writes the file to the
  system Downloads collection (`MediaStore.Downloads` on API 29+, the
  legacy public Downloads directory below that) and shows a completion
  notification. A separate `WebView.setDownloadListener` handles genuine
  `http(s)` attachment downloads via the system `DownloadManager`.
- **Offline handling**: the app is bundled locally and always loads
  regardless of connectivity. A live `ConnectivityManager.NetworkCallback`
  shows a dismissible banner when the connection drops (relevant to the
  ImgBB-hosted Dynamic QR feature specifically); a native fallback screen
  (`view_offline.xml`) only appears if the bundled page itself fails to
  render.
- **Splash screen**: `androidx.core.splashscreen`, so there's no white
  flash on cold start; it hands off to the Material theme once the
  activity is ready.
- **Edge-to-edge + dark mode**: `WindowCompat.setDecorFitsSystemWindows`
  with transparent system bars; `values-night/` resources swap the splash
  background, window background, and status/nav bar icon contrast to
  match the system theme, matching the web app's own dark mode.
- **Adaptive icon**: `mipmap-anydpi-v26/ic_launcher.xml` (background +
  foreground layers) with legacy PNG fallbacks in each `mipmap-*` density
  for API 24–25.
- **Back button**: closes an active fullscreen video view first, then
  walks WebView history, then falls through to the default system back
  behavior once there's no more in-app history.
- **Pull-to-refresh**: enabled only when the page is scrolled to the top,
  so it doesn't fight with in-page scrolling.
- **Progress bar**: a thin top-of-screen `LinearProgressIndicator` driven
  by `WebChromeClient.onProgressChanged`.
- **Lifecycle**: `WebView` state is saved/restored across process death
  (`onSaveInstanceState`/`restoreState`), and paused/resumed with the
  activity to avoid burning battery in the background.
- **Orientation**: unrestricted (`screenOrientation="unspecified"`), with
  `configChanges` declared so rotation doesn't reload the page or lose
  in-progress state.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Web fonts, the ImgBB image-hosting call, connectivity monitoring |
| `CAMERA` (+ optional camera features) | "Use camera" image upload option; app still installs on camera-less devices |
| `WRITE_EXTERNAL_STORAGE` (API ≤28 only) | Writing exported QR files on the legacy storage model |
| `POST_NOTIFICATIONS` (API 33+) | The "download complete" notification; downloads still work if denied |

## Project structure

```
proofmark-android/
├── .github/workflows/android.yml   CI: lint, test, build, release
├── app/
│   ├── build.gradle.kts            App module build config
│   ├── proguard-rules.pro          R8 keep rules
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/                 The bundled web app (unmodified logic)
│       │   ├── index.html
│       │   ├── assets/*.js, *.css
│       │   └── android-download-bridge.js
│       ├── java/com/proofmark/qrstudio/
│       │   ├── ProofmarkApplication.kt
│       │   ├── MainActivity.kt
│       │   └── AndroidDownloader.kt
│       └── res/                    Layouts, icons, themes, strings
├── gradle/
│   ├── libs.versions.toml          Version catalog
│   └── wrapper/
├── build.gradle.kts                Root build config
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat
├── LICENSE
└── README.md
```

## Updating the bundled web app

If the source web project changes, rebuild it and copy the output over
`app/src/main/assets/`, replacing everything except
`android-download-bridge.js`:

```bash
# from the web project directory
npm run build
rm -rf /path/to/proofmark-android/app/src/main/assets/assets \
       /path/to/proofmark-android/app/src/main/assets/index.html \
       /path/to/proofmark-android/app/src/main/assets/icon.svg
cp -r dist/* /path/to/proofmark-android/app/src/main/assets/
```

Keep `vite.config.js`'s `base: './'` setting — without it, built asset
URLs are absolute and won't resolve under the WebView's virtual asset
domain.
