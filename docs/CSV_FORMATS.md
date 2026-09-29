# CSV import formats

Both importers accept UTF-8 (a BOM is fine), comma-separated values, fields in double quotes with `""` for a literal quote, and LF or CRLF line endings. Blank lines and lines starting with `#` are ignored. The header row is optional.

Nothing is saved until the preview shows **no errors**. Every problem is listed with its line number. Warnings (for example, a Morning session that overlaps Evening) don't block saving.

## Timetable

```csv
day,session,start,end,subject
Mon,Morning,09:00,12:30,Maths
Mon,Evening,14:00,17:00,Physics|Chemistry
Tue,Morning,09:00,12:30,English
Tue,Morning,09:00,12:30,Lab
```

| Column | Accepted values |
|---|---|
| `day` | `Mon`…`Sun`, full names (`Monday`), `Tues`/`Thur`/`Thurs`, or `1`–`7` (1 = Monday) |
| `session` | `Morning`/`M`/`AM`, or `Evening`/`E`/`PM`/`Afternoon` |
| `start`, `end` | `HH:mm` 24-hour, `9:00`, `9.00`, `9am`, `2:30 PM`. `end` must be after `start` |
| `subject` | Free text. Repeat the row for more subjects, or separate several with `|` |

Rows for the same day and session are merged into one session with several subjects, and their times must match. When you import, you choose a name and the date the timetable **applies from**. An earlier open-ended timetable is closed the day before, so past attendance keeps its original schedule. If two timetables start on the same day, the one added last is used.

## Holidays

```csv
start,end,label
2026-10-02,,Gandhi Jayanti
2026-10-20,2026-10-24,Diwali break
25/12/2026,,Christmas
```

| Column | Accepted values |
|---|---|
| `start` | ISO `yyyy-MM-dd`, or day-first `dd-MM-yyyy` / `dd/MM/yyyy` (also `d/M/yyyy`) |
| `end` | Optional. Empty means a single day. Same formats as `start`; must not be before it |
| `label` | Free text. Empty becomes "Holiday" (with a warning) |

Weekly off days (Sunday by default) are set on the Holidays screen, not in the CSV.

Both screens can export the current data in the same format, so you can edit it in a spreadsheet and import it again.
