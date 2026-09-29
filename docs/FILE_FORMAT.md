# Vault file format (version 1)

## Layout

```
<vault folder>/
  vault.json           plaintext JSON header (format, KDF params + salts, wrapped master key)
  vault.json.bak       previous header (after a passphrase change / new recovery code)
  data/<40 hex>        encrypted JSON documents   (+ .bak previous version, transient .new)
  media/<uuid>         encrypted media blobs (Streaming AEAD), no extension
  thumbs/<uuid>        encrypted JPEG thumbnails (AEAD)
  .tmp/                scratch space (restore staging); wiped on lock and on start
```

## vault.json

```json
{
  "format": "lifevault-vault",
  "formatVersion": 1,
  "vaultId": "4b64f31d-…",
  "createdAt": 1790653328690,
  "cipherSuite": "tink:AES256_GCM+AES256_GCM_HKDF_4KB+HMAC_SHA256;kek:argon2id+AES256_GCM",
  "passphrase": { "kdf": KDF, "wrappedKey": "base64" },
  "recovery":   { "kdf": KDF, "wrappedKey": "base64" }
}
KDF = { "algorithm": "argon2id", "version": 19, "memoryKiB": 32768, "iterations": 2..24, "parallelism": 1, "salt": "base64 (16 bytes)" }
```

- `KEK = Argon2id(secret, salt, t, m, p)`, 32 bytes. The secret is:
  - passphrase: UTF-8 of the NFKC-normalised text
  - recovery: the 32-character canonical Crockford Base32 code (upper case, no dashes; `O`→`0`, `I`/`L`→`1`)
- `wrappedKey = AesGcmJce(KEK).encrypt(bundle, AAD = "lifevault/v1/master/<vaultId>/<passphrase|recovery>")`, laid out as 12-byte IV ‖ ciphertext ‖ 16-byte tag.
- **Master key bundle**, big-endian binary:

  ```
  u8 version = 1
  repeat 3 { i32 length; bytes TinkProtoKeyset }   // AEAD AES256_GCM, StreamingAEAD AES256_GCM_HKDF_4KB, MAC HMAC_SHA256_256BITTAG
  ```

- A reader must refuse `formatVersion` greater than it supports.

## Data files

- **Physical name:** `hex(HMAC(macKeyset, "lifevault/name/v1/" + logicalName))`, taking bytes 0..19 of the 32-byte tag (Tink's 5-byte key-id prefix is dropped). That gives 40 lower-case hex characters.
- **Content:** Tink AEAD ciphertext of UTF-8 JSON, with `AAD = "lifevault/data/v1/" + physicalName`.
- **Schema:** every document has a top-level `"schemaVersion"`. Readers migrate step by step (`SchemaMigrator`); a missing version means 0. A newer version than the app knows is refused and never overwritten.
- **Unknown JSON keys are ignored**, so older apps can read newer minor additions.

### Logical names

| Logical name | Model (`domain/model`) | Notes |
|---|---|---|
| `settings` | `Settings` | security, attendance, mood, rules, notifications, trackers |
| `timetable` | `TimetableFile` | versions with `effectiveFrom`/`effectiveTo`, slots `(day 1–7, session, HH:mm–HH:mm, subjects)` |
| `holidays` | `HolidaysFile` | holidays `(start, end, label)`, `weeklyOffDays` |
| `attendance-YYYY-MM` | `AttendanceMonth` | records `(date, session, PRESENT/ABSENT/CANCELLED, subjects, updatedAt)`; no record means Pending |
| `entries-YYYY-MM` | `EntriesMonth` | shared `Entry(type MONEY/FOOD/TRAVEL, at, amount, unit, category, note, cost?, mediaIds)` |
| `mood-YYYY-MM` | `MoodMonth` | `MoodCheckIn(date, slot DAY/MORNING/EVENING, level 1–5, tags, note)` |
| `rules` | `RulesFile` | rule sets (ordered rules with pinned/archived) and recurring reminders |
| `job-log-YYYY-MM` | `JobLogMonth` | jobs with rules shown/ticked, `rulesRead`, start/end, reflection |
| `events` | `EventsFile` | events with reminder, linked rule set, mediaIds |
| `study-index` | `StudyIndex` | subjects (id, name, linked rule set) |
| `study-<subjectId>` | `StudyPage` | notes (Markdown), checklist, mediaIds |
| `media-index` | `MediaIndex` | the only place original names, MIME types, sizes, durations, dimensions, tags and thumbnail ids exist |

Dates are ISO `yyyy-MM-dd`, local date-times `yyyy-MM-ddTHH:mm[:ss]`, times `HH:mm`, and months `yyyy-MM`.

Monthly files are found by probing hashed names for each month against the cached directory listing, so no plaintext catalogue is needed.

## Media

- **Blob:** `media/<uuid>` holds Tink Streaming AEAD `AES256_GCM_HKDF_4KB` ciphertext of the original bytes, with `AAD = "lifevault/media/v1/<uuid>"`. It supports random-access decryption, which the audio player uses to seek.
- **Recordings** are AAC-LC 44.1 kHz mono in ADTS framing, produced by `AudioRecord` + `MediaCodec` and written straight into the encrypting stream.
- **Thumbnail:** `thumbs/<uuid>` holds AEAD ciphertext of a JPEG, longest side 320 px, with `AAD = "lifevault/thumb/v1/<uuid>"`.
- **Delete:** the blob and thumbnail are overwritten with random bytes and then deleted (best effort on flash).

## Encrypted backup (.zip)

```
lifevault-backup.json   {"format":"lifevault-backup","version":1,"vaultId":…,"createdAt":…,"files":N}
vault.json
data/<name>             primary files only (no .bak/.new/.corrupt)
media/<uuid>
thumbs/<uuid>
```

Every entry except the manifest is copied byte for byte from the vault, so it is already ciphertext. Restore works in four steps:

1. Stage the entries into `.tmp/` as flat names: `h-vault.json`, `d-…`, `m-…`, `t-…`.
2. Unlock the staged header with the backup's passphrase and verify every data file and thumbnail, plus the first and last segments of each media blob.
3. Commit: write the marker `.tmp/restore-commit` with phase `delete`, delete the old files, switch the marker to phase `copy`, copy the staged files, and write `vault.json` last.
4. If the app is killed during the commit, it resumes on the next start.
