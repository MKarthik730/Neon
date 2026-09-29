# Daylock

An offline Android app that combines a daily organiser (attendance, timetable, holidays, events, study pages, money/food/travel trackers, a mood check-in, and "ground rules" you read before starting a job) with an encrypted vault for notes, photos and recordings.

- **Offline only.** The app has no `INTERNET` permission (any library that tries to add one is stripped at merge time). There are no analytics, ads or crash reporters.
- **One folder.** You pick one folder with the system folder picker. The app reads and writes only inside that folder.
- **Everything is encrypted.** Data files, media, thumbnails and even file names are encrypted. The only plaintext file is `vault.json`, which holds the KDF parameters and the wrapped keys.
- **Portable.** Copy the folder to another phone, choose it in Daylock, and unlock it with your passphrase.

The internal package and applicationId stay `com.lifevault`, so installs and vault folders from earlier builds keep working. The visible name lives in one constant, `Brand.NAME` (plus `app_name` in `res/values/strings.xml`).

Kotlin · Jetpack Compose (Material 3) · MVVM with repositories · Google Tink · Argon2id (Bouncy Castle) · Media3 · CameraX. Minimum SDK 26; target and compile SDK 36.

---

## Build

Requirements: JDK 17 or newer (Android Studio's bundled JBR works), Android SDK platform 36 and build-tools. Gradle comes with the wrapper.

```sh
# Debug APK (applicationId com.lifevault.debug, can sit next to the release app)
./gradlew :app:assembleDebug            # -> app/build/outputs/apk/debug/app-debug.apk

# Signed, minified release APK (about 5 MB). Needs keystore.properties; see docs/RELEASE.md
./gradlew :app:assembleRelease          # -> app/build/outputs/apk/release/app-release.apk

# Tests
./gradlew :domain:test :app:testDebugUnitTest      # JVM unit tests (117)
./gradlew :app:connectedDebugAndroidTest           # on-device tests (needs a device/emulator)
```

On Windows, use `gradlew.bat`. Set `JAVA_HOME` if `java` on your PATH is older than 17.

**Low-memory machines.** `gradle.properties` runs a single Gradle JVM (1.5 GB heap, Kotlin compiled in-process, two workers). With a stricter memory limit, don't run the emulator and a Gradle build at the same time. Build first, run `./gradlew --stop`, then start the emulator.

## Project layout

```
domain/   Pure Kotlin/JVM, no Android. Models (kotlinx.serialization), timetable expansion,
          attendance calculator + planner, CSV import, mood stats, rules gate/report,
          tracker summaries, alarm planning, schema migrations.  Portable to KMP/iOS later.
app/
  vault/          Key hierarchy (VaultCrypto, VaultKeys, Argon2Kdf, RecoveryCode), path jail
                  (VaultPath), storage (VaultFs, SafVaultFs), atomic encrypted store
                  (AtomicFiles, EncryptedStore, BlobStore, HeaderStore), VaultManager (lifecycle),
                  VaultSession (everything that exists only while unlocked), AutoLockController,
                  BruteForceGuard, BiometricKeys, DevicePrefs.
  data/           Encrypted repositories with in-memory caches (DocRepository / MonthlyRepository).
  notifications/  AlarmPlanner -> AlarmScheduler (one exact alarm at a time), AlarmReceiver,
                  BootReceiver, Notifier (lock-screen-safe text), NotificationActionReceiver,
                  PendingActionsQueue + PendingMerge, AlarmSync.
  media/          MediaManager (import/capture/thumbnails/export/delete), AudioRecorder
                  (AudioRecord -> AAC -> encrypted stream), EncryptedBlobDataSource (Media3), VaultAudioPlayer.
  backup/         Encrypted export / verified restore.
  reports/        Mood report PDF/CSV export (explicit, user-chosen location).
  ui/             Compose screens + ViewModels, one package per feature.
  di/             Manual DI (AppContainer, UnlockedGraph).
```

Data flow is one-way: repositories expose `Flow`, ViewModels expose `StateFlow`, and composables are stateless. The unlocked UI gets its own `ViewModelStore`, and locking the vault clears it, so no decrypted state outlives a lock.

## Features

| Area | What you get |
|---|---|
| Vault | Create (pick folder, passphrase, recovery code shown once and confirmed), unlock (passphrase, optional biometrics), lock now, auto-lock after inactivity and on leaving the app, change passphrase, new recovery code, recover with the recovery code, open an existing vault folder. |
| Timetable | Weekly Morning/Evening sessions with start/end times and subjects. CSV import with a validation preview, a manual editor, CSV export. Each timetable has its own date range, so a new semester never rewrites past attendance. |
| Holidays | Single days or ranges with labels, CSV import/export, and weekly off days. Holidays remove both sessions from totals and from notifications. |
| Attendance | Today view, month calendar, and marking of past days (Present / Absent / Cancelled; unmarked = Pending). Shows overall, per-subject and per-month percentages with a threshold warning, plus a planner: how many sessions you can skip and how many in a row you need to attend. |
| Events | Classes, tests and custom events with an optional reminder, a linked rule set, and attachments. |
| Study pages | One page per subject: Markdown notes (auto-saved, with preview), a checklist, attachments, and a "start study job" shortcut. |
| Trackers | One shared `Entry` model for money, food and travel. Summaries by day/week/month and by category, a food logging streak, and travel distance and cost. |
| Mood | 1–5 emoji check-in with tags and a private note, once or twice a day. The report shows averages, trend, distribution, best and lowest days, streak, missed days, and tag observations, next to attendance and spending for the same weeks. PDF/CSV export. An optional gentle message after a run of low check-ins. |
| Ground rules | Your own named rule sets (ordered, pinned, archived). Start-job flow: rules full screen, then tick each one (or one "I've read them" tap), an optional countdown, Begin, job timer, and a reflection. The job log reports rules read %, streak, weekly time and rules most often left unticked. Rule sets can be linked to events, subjects and recurring reminders. |
| Media vault | Import images and audio (then optionally delete the originals). In-app camera and recorder write straight into the encrypted stream. Encrypted thumbnails, a gallery with search and tags, a zoomable image viewer, an audio player with seek, an "Export decrypted copy" action, and secure delete. |
| Dashboard | Attendance %, this week's spend, food streak, rules-read streak, media count, this week's mood, upcoming events and quick actions. All of it is calculated from stored data and never saved separately. |
| Backup | The folder is the backup. "Export encrypted backup" writes one .zip. "Restore" checks every file with the backup's passphrase before replacing anything, and resumes if it is interrupted. |
| Notifications | Attendance prompts after each session, event reminders, mood check-in, "read your rules", and a daily summary. They use exact alarms, are rebuilt after a reboot or time change, and skip holidays. A reliability check screen covers permissions, battery optimisation and OEM tips. |

## Defaults chosen for the open decisions

| Question | Default (changeable in Settings) |
|---|---|
| Attendance counting | Per session (Morning/Evening). Per subject is a setting; subjects are always stored, so either view can be derived. |
| Required attendance | 75% |
| Present/Absent from the notification | **Off**: the button opens Daylock and you confirm after unlocking. Turn on "Mark from the notification without unlocking" to use the small pending-actions queue (date, session and status only), which is merged into the vault on the next unlock. |
| Other platforms | All business logic is in the pure-Kotlin `:domain` module, ready for Kotlin Multiplatform. |
| Mood scale | 1–5 with emoji, one check-in per day (two per day is a setting). |
| Ground rules | Every rule must be ticked before Begin unlocks. A 5-second countdown is on by default. Both are settings. |

## More documentation

- [docs/SECURITY.md](docs/SECURITY.md): security model, what is stored where, limits, and the manual security checklist
- [docs/FILE_FORMAT.md](docs/FILE_FORMAT.md): vault folder layout and file format spec
- [docs/CSV_FORMATS.md](docs/CSV_FORMATS.md): timetable and holiday CSV import formats
- [docs/RELEASE.md](docs/RELEASE.md): signed release build guide
- [docs/TESTING.md](docs/TESTING.md): what the tests cover and how to run them
