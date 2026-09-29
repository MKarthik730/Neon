# Tests

## JVM unit tests (117)

```sh
./gradlew :domain:test :app:testDebugUnitTest
```

| Suite | Covers |
|---|---|
| `domain` `AttendanceCalculatorTest` | pending is never absent; 0/0 is not 0%; holidays (even with records), ranges, cancelled, off days, "up to today"; per-session vs per-subject; record subjects vs timetable; per month; timetable versions never rewrite the past; overlap and same-day tie-break |
| `domain` `PlannerTest` | `floor(p/r − t)` and `ceil((rt − p)/(1 − r))` checked exhaustively for t ≤ 60 across thresholds; `3t − 4p` at 75%; exact threshold; zero sessions; 100% threshold; invalid input |
| `domain` `CsvTest` | quoting, CRLF, BOM, comments, multi-line fields; lenient day/session/time parsing; every validation error with line numbers; merging; warnings; export → import round trip; holiday dates, ranges and validation |
| `domain` `MoodStatsTest` | empty and single-entry periods; daily-mean averages; best/worst; trend; streak; missed days (not counting the future); tag insights; weekly/monthly averages; gentle-message run; level bounds |
| `domain` `RulesLogicTest` | pinned-first ordering; archived hidden; reordering; Begin unlocks only when every rule is ticked and the countdown has finished; read-once mode; report (read %, streak, weekly minutes, most unticked); running jobs |
| `domain` `MigrationsTest` | ordered steps and version stamping; v0 files; newer files refused; gaps rejected; models round-trip; unknown keys ignored |
| `domain` `AlarmPlannerTest` | prompt = session end + delay; holidays and off days skipped; past alarms dropped; mood reminder can skip holidays; event and linked-rules prompts; recurring rule reminders; **the stored plan never contains titles, subjects or mood data** |
| `domain` `EntrySummariesTest` | Monday-based weeks; by period and by category; food streak and per-day counts; travel totals; streak helpers |
| `app` `CryptoTest` | Argon2id determinism; NFKC normalisation; AEAD round trip; **tamper detection**; swapped files rejected; opaque names; **wrong passphrase fails**; **passphrase change keeps data readable**; **recovery code** (formatting, rotation revokes); tampered wrapped key; vault-id binding; destroyed keys; **streaming round trip + 50 random seeks**; tampered media; thumbnails; secure delete; calibration bounds |
| `app` `StorageTest` | **path traversal rejection** (19 malicious paths); SAF tree check; canonical path check; one backup, no temp files; **crash recovery** (stray `.new`, crash between renames, corrupt primary, missing primary, everything damaged); a crash injected at every step of a write leaves the old or the new value; startup scan; concurrent updates don't lose changes; newer schema never overwritten; month probing |
| `app` `SupportTest` | brute-force delays; pending queue keeps only date/session/status; **queued notification marks are merged and then removed**, and are kept if the merge is interrupted; alarm plan store; notification text carries no vault data; mood CSV escaping; restore commit and crash resume |
| `app` `VaultEndToEndTest` | **nothing is written outside the vault folder**; fixed layout with opaque names; **no plaintext anywhere** (subjects, notes, amounts, file names, mood); **`.tmp/` wiped and keys destroyed on lock**; a copied folder unlocks elsewhere with every feature's data intact |

## On-device tests (10)

```sh
./gradlew :app:connectedDebugAndroidTest
# or build once, then run without Gradle (lighter on RAM):
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest && ./gradlew --stop
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.lifevault.debug.test/androidx.test.runner.AndroidJUnitRunner
```

| Test | Covers |
|---|---|
| `NotificationsInstrumentedTest` | next alarm registered; **re-registered by the boot / time-zone receiver**; a due attendance alarm posts a `VISIBILITY_PRIVATE` notification with a generic public version and Present/Absent actions; the Present action writes only date/session/status to the queue; malformed actions are ignored |
| `SecurityInstrumentedTest` | `FLAG_SECURE` on the activity; no network permission; backup disabled; Argon2 calibration and timing on the real device; vault round trip, streaming seek and `.tmp` wipe on ART |
| `SafFolderFlowTest` (UI Automator) | **real system folder picker**: create a folder, grant it, create the vault, confirm the recovery code, reach Today, lock, unlock. Run it on a clean install; it uses Google/AOSP DocumentsUI labels |

All 10 passed on an Android 16 (API 36.1) Google Play emulator image.
