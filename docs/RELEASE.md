# Signed release build

## 1. Create (or reuse) a signing key

Keep this key forever. Android only installs an update that is signed with the same key.

```sh
keytool -genkeypair -v -keystore keystore/lifevault-release.jks -alias lifevault \
        -keyalg RSA -keysize 4096 -validity 10000
```

`keytool` ships with any JDK, including Android Studio's `jbr/bin/keytool`.

## 2. Point the build at it

Create `keystore.properties` in the project root. It is git-ignored, and so is `keystore/`.

```properties
storeFile=keystore/lifevault-release.jks
storePassword=…
keyAlias=lifevault
keyPassword=…
```

If this file is missing, `assembleRelease` still builds a minified APK, but it is unsigned.

## 3. Build

```sh
./gradlew clean :app:assembleRelease
# -> app/build/outputs/apk/release/app-release.apk  (~5 MB, R8-minified, resources shrunk, no logging)
```

Before shipping, check the result:

```sh
$ANDROID_HOME/build-tools/36.1.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
$ANDROID_HOME/build-tools/36.1.0/aapt dump permissions app/build/outputs/apk/release/app-release.apk   # no INTERNET
```

Then go through the manual checklist in [SECURITY.md](SECURITY.md).

## 4. Install

```sh
adb install -r app/build/outputs/apk/release/app-release.apk
```

Or copy the APK to the phone and open it, which needs "Install unknown apps" allowed for your file manager.

Upgrading in place keeps the vault. The folder grant and the vault itself are untouched by an update, and the vault only lives in the folder you chose.

## Versioning

Bump `versionCode` (an integer that always goes up) and `versionName` in `app/build.gradle.kts`. If a data model changes, bump its `CURRENT_SCHEMA` and add a migration step in `domain/.../migration/Migrations.kt` together with a test.

## Notes

- **Exact alarms.** The app declares `USE_EXACT_ALARM` (Android 13+) and `SCHEDULE_EXACT_ALARM` (Android 12 only), because on-time reminders are a core feature. If you publish on Google Play, check the current exact-alarm policy. If needed, remove `USE_EXACT_ALARM` from the manifest; the app then asks for "Alarms & reminders" access (Reliability check) and falls back to slightly inexact alarms when that is refused.
- **Target SDK.** `targetSdk`/`compileSdk` is 36. Moving to 37 needs an AGP version that supports it (AGP 9.x) and a pass over the Android 17 behaviour changes.
