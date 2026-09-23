# LabelScan

Photograph warehouse case labels as boxes come in, and slowly build up a
searchable **UPC repository** for inventory day.

## Install

Download `labelscan.apk` from the
[latest release](../../releases/latest) and open it on your phone (allow
"install unknown apps"). 64-bit ARM Android 8+ — i.e. any phone from the last
several years.


## How it works

1. **Scan** — tap Scan, fill the frame with the case label, shoot. Any angle
   is fine: the app tries all four rotations and keeps the best reading.
   Poor light? Hit **LIGHT**. Already have photos? **PHOTOS** picks from the gallery.
2. **Check** — the parsed fields appear next to the photo. Case labels print
   the UPC *without* its check digit (`UPC#89049700030`), so the app adds it
   (→ `890497000306`, the code on the actual product) and shows the printed
   digits so you can compare them with the photo. If a retail barcode is in
   the shot it's read directly and wins. Fix anything and hit
   **Save + next** to go straight to the next box.
3. **Repository** — one entry per UPC with name, item #, size, category, dept,
   last slot, and how many cases you've seen. Scanning the same product again
   bumps its count; scanning the *same case* twice (same case barcode) warns you.
4. **Inventory** — search by any fragment of name, UPC, item #, slot or dept.
   Open a product and its UPC is drawn as a **real UPC-A/EAN-13 barcode** at
   full screen brightness, so your inventory scan gun can read it straight off
   the phone.

Everything is on-device (Google ML Kit, bundled models) — works offline in a
freezer with no signal.

## What gets read off a label

| Field | Example | Found by |
|---|---|---|
| Name | `ACE BISTRO LOAF SOUR` | longest all-letters line |
| Category | `DOUGH` | words before a size |
| UPC | `890497000306` | `UPC#89049700030` — labels omit the check digit, the app adds it; a retail barcode wins |
| Item # | `870628` | `ITM…` |
| Size | `21 OZ` | number + OZ/LB/CT/PK/… |
| Dept | `BKY`, `FROZ` | known dept codes, else a standalone 3–4 letter code |
| PLU | `299` | `PLU #:` on store-printed labels |
| Slot | `A-MF-36-06-004` | `X-XX-##-##-###` |
| Case | `3 of 7` | `N of M` |
| Door | `C8-30-S` | `X#-##-X` |
| ASG # | `1029801153` | `ASG#…` |
| Case ID | `1355470932` | the label's own barcode |

Store-printed retail labels work too: the name is taken from the biggest
type (so ingredient lists don't win), and the package barcode gives the UPC.

OCR slips like `O`→`0`, `I`→`1`, `S`→`5` are corrected in numeric fields.
"Show OCR text" on the check screen shows exactly what was recognised.

## Backup

⋮ → **Export CSV** shares the whole repository (open in Sheets/Excel, email
it, whatever). **Import CSV** merges one back in — use it to move to a new
phone. UPCs whose leading zeros a spreadsheet stripped are repaired on import.
Android's automatic backup also covers the database.

## Building

```sh
./gradlew testDebugUnitTest assembleDebug
```

CI builds an APK on every push to `main` (workflow run artifacts). Push a
`v*` tag to publish a release with `labelscan.apk` attached.
