# Security model

## Goals

1. Everything the user records stays on the device, inside one folder they chose, and is encrypted at rest: content, media, thumbnails and file names.
2. Someone holding a copy of the folder, but not the passphrase or recovery code, learns nothing beyond rough file sizes, file counts and timestamps.
3. The app never talks to the network.

## Out of scope

- A compromised or rooted phone, or malware running while the vault is unlocked.
- Someone watching you type your passphrase.
- Timing and size side channels in the folder, such as how many files exist, how large they are and when they changed.
- Guaranteed wiping of RAM or flash. The JVM cannot promise that memory is overwritten, and flash wear-levelling keeps old blocks. The app zeroes every key and plaintext buffer it owns and drops all references on lock; everything on disk is encrypted, so leftover blocks are ciphertext.

## Key hierarchy

```
passphrase ─NFKC─► Argon2id(salt₁, m=32 MiB, t=calibrated, p=1) ─► KEK₁ ─AES-256-GCM─┐
recovery code ───► Argon2id(salt₂, …)                            ─► KEK₂ ─AES-256-GCM─┤─► master key bundle
Android Keystore AES-256-GCM key (biometric-bound, this device only) ──AES-256-GCM────┘
```

- **Master key bundle.** Three random 256-bit Tink keysets, generated when the vault is created:
  - AEAD `AES256_GCM`, for data files and thumbnails
  - Streaming AEAD `AES256_GCM_HKDF_4KB`, for media, so it can stream and seek
  - `HMAC_SHA256`, for opaque file names
- **Passphrase.** Argon2id with a fresh 16-byte salt. Iterations are calibrated at creation to about 400 ms on the device (at least 2, 32 MiB). Parameters are stored with each wrapped key, so a copied vault opens on any phone. Changing the passphrase only re-wraps the bundle; no data is re-encrypted.
- **Recovery code.** 160 random bits shown once as 32 Crockford Base32 characters (`XXXX-XXXX-…`), which also wraps the bundle. Creating a new recovery code revokes the old one.
- **Biometric quick-unlock (optional).** A third copy of the bundle is wrapped with a Keystore key that requires strong biometric authentication for every use (BiometricPrompt + CryptoObject) and is invalidated when new biometrics are enrolled. It lives in app-private storage, is useless on any other device, and is reset after a recovery.
- **Associated data.** Each wrapped key is bound to `vaultId` and its purpose. Each data file is bound to its on-disk name, and each media blob and thumbnail to its id. A ciphertext moved to another name fails authentication.
- **No hand-rolled crypto.** Tink provides every primitive (the wrapping uses `AesGcmJce`, which generates a random nonce per call). Argon2id comes from Bouncy Castle.

## Storage

- **Folder access.** Only through the Storage Access Framework: `ACTION_OPEN_DOCUMENT_TREE` with a persisted read/write grant. The app never requests broad storage permissions and holds a grant for exactly one folder.
- **Path jail.** Every file operation goes through `VaultFs` and a `VaultPath`, which only exists for the fixed layout (`vault.json`, `data/`, `media/`, `thumbs/`, `.tmp/`) with flat names matching `[A-Za-z0-9][A-Za-z0-9._-]{0,127}` and no `..`. Document ids only ever come from listing the vault's own directories, and URIs returned by the provider are checked to belong to the same tree. Backup restore validates each zip entry the same way, which prevents zip-slip.
- **Atomic writes.** Each write goes to `<name>.new`, is read back and decrypted, then `<name>` → `<name>.bak` and `<name>.new` → `<name>`. Reads and a startup scan repair every interrupted state. A damaged file is set aside as `.corrupt` and restored from `.bak`. Files written by a newer app version are refused rather than "repaired".
- **One writer per file.** A mutex per logical file, with all I/O on `Dispatchers.IO`.

## What lives outside the vault folder

All of this is in app-private storage and excluded from cloud backup and device transfer (`allowBackup=false`, `dataExtractionRules`).

| Item | Content | Why |
|---|---|---|
| Device prefs | Folder URI, vault id, failed-attempt counter, quick-action setting | Needed before unlock |
| Biometric blob (optional) | Master key bundle encrypted by a biometric-bound Keystore key | Quick unlock |
| Alarm plan | Times, alarm types, opaque ids (event id / rule-set id), date+session for attendance prompts | Rebuild alarms after reboot while locked |
| Pending-actions queue (optional, off by default) | `(date, session, Present/Absent, timestamp)` | Mark from a notification without unlocking; merged and deleted on next unlock |

None of these ever contain subject names, event titles, amounts, notes, mood values or media. Tests check the serialised shapes.

## Runtime protections

- `FLAG_SECURE` on the single activity blocks screenshots, screen recording and the recents thumbnail. On Android 13+, `setRecentsScreenshotEnabled(false)` is also set.
- Auto-lock after N minutes without interaction (default 5) and when the app goes to the background (default on). System pickers that Daylock opens itself are exempt. Locking cancels playback and recording, clears ViewModels and bitmap caches, wipes `.tmp/` and destroys the keys.
- Brute-force delay: 3 free attempts, then 5 s, 10 s, 20 s … up to 15 min. The counter survives restarts, and moving the clock back does not shorten the wait.
- Notifications use `VISIBILITY_PRIVATE`, a generic public version ("Daylock · You have a reminder") and generic text. They are built from the alarm plan only, so they cannot contain vault data.
- Release builds are minified and strip `android.util.Log` calls. The app itself never logs.
- The only ways plaintext leaves the vault are explicit exports to a location you choose: "Export decrypted copy" of a media item, mood PDF/CSV, timetable/holiday CSV, and the recovery-code text file. Each one warns first.

## Manual security checklist (before a release)

Use a file manager, or `adb shell ls -la` on a debug device.

1. [ ] The vault folder contains only `vault.json`, `data/`, `media/`, `thumbs/` and `.tmp/` (plus `vault.json.bak` after a passphrase change).
2. [ ] `data/` names are 40 hex characters (optionally `.bak`). `media/` and `thumbs/` names are UUIDs with no extension. No readable names anywhere.
3. [ ] Open a few files in a hex viewer: no readable text, no JPEG/AAC headers. `vault.json` shows only format, ids, KDF params and base64 blobs.
4. [ ] After locking, `.tmp/` is empty.
5. [ ] Nothing new was created outside the vault folder: compare a listing of `/sdcard` before and after a session that adds photos, a recording, entries and a mood check-in.
6. [ ] `aapt dump permissions app-release.apk` lists no `INTERNET` or `ACCESS_NETWORK_STATE`.
7. [ ] A screenshot or screen recording of Daylock is black. The recents thumbnail is blank.
8. [ ] The lock-screen notification shows only "Daylock · You have a reminder".
9. [ ] Wrong passphrases trigger growing waits that survive force-stopping the app.
10. [ ] Copy the folder to another device (or clear app data), choose it, and it unlocks with the passphrase and with the recovery code. Biometrics must be enabled again there.
11. [ ] `adb backup` and cloud backup do not include app data (`allowBackup=false`).
