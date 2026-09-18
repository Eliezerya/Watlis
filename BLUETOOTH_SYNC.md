# Bluetooth sync — phone + tablet

Open **Settings → Bluetooth sync** in Watlis on both devices. Both devices need this version of Watlis.

## First connection

1. Pair your phone and tablet in Android's Bluetooth settings. Return to Watlis.
2. Tap **Show paired devices** and allow Nearby devices access when asked.
3. On the tablet, select **Wait for [your phone]**.
4. On the phone, select **Connect & sync with [your tablet]**.
5. Review conflicts and possible duplicates on the phone. Send the result for approval.
6. Review and approve the result on the tablet. Keep both screens open until completion.

Only the selected paired device is accepted. No account, internet upload, background service, location permission, or in-app discovery is involved. The connection uses secure Bluetooth Classic RFCOMM, following Android's [paired-device permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions) and [socket connection guidance](https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices).

## How duplicates and conflicts are handled

| Situation | Result |
| --- | --- |
| Sync again without editing | Same identities; no extra titles or reading-history entries. |
| Edit a previously shared title on one device | The newer revision is proposed on the other device. |
| Change chapter 25 to 24.5 intentionally | 24.5 is preserved as a correction. Highest chapter does **not** win. |
| Edit the same title on both devices while apart | Choose **Keep this device** or **Keep other device**. Neither device's clock decides. |
| Add the same title independently on both devices | Review a possible duplicate: merge using either complete version, or keep both as distinct editions. |
| Delete a shared title on one device | Review the deletion. The deletion marker is retained for future syncs. |
| Delete on one device and edit on the other | Explicitly choose deletion or the surviving version. |
| Delete a genre/type still used by a retained title | Its definition is kept; the review explains why. |
| Disconnect during transfer | Incomplete frames are discarded. Reconnect to retry. |
| Disconnect after one device commits | One device may already have the result. Reconnect; revisions make retry idempotent. |

A title's **whole version** includes its metadata, progress, matching history, notes, characters and genre assignments. Concurrent versions are not field-by-field merged in this first version. Choosing one can discard the other version's edits from the active collection, so check the review carefully. The losing device retains its previous collection in its recovery backup.

Names are only a suggestion for duplicate review, never automatic proof that two titles are the same. Possible duplicates are detected by matching normalized title and media-type name. Different spellings are not fuzzy-matched. Titles already coexisting on either device remain distinct. Genres and media types are matched by normalized name; conflicting definitions require a choice.

## Photos and performance

- Transfers saved cover and character thumbnails, crop positions and zoom; not full-size local photo files.
- The original photo remains linked on its owning device, including when characters move within a title. On the other device, image preview can use the transferred thumbnail. Web image references remain available for original previews when online.
- A photo without a saved thumbnail cannot be transferred as an original local file. Open that title on its owning device to let the thumbnail finish saving, then sync again.
- Nothing scans or hashes the collection at app startup. The extra database table is read during sync/backup work on a worker thread, not while binding list rows.
- This first version exchanges a bounded full snapshot, not an incremental attachment stream. Frames are compressed and checked with SHA-256. Limits: 16 MiB uncompressed / 8 MiB compressed per frame, 10,000 sync records including deletion markers, and 128 device identities per record. Larger transfers fail with an explanation; use Export / Import data instead.
- Connection/wait timeout: 60 seconds. Transfer timeout: 3 minutes per phase. Review timeout: 10 minutes per phase. Leaving the screen, rotating it, or cancelling closes the connection; reconnect safely afterward.

## Recovery and backups

Before sync changes a device's collection, it atomically saves an app-private JSON recovery backup: `files/before-bluetooth-sync.json`. Use **Export pre-sync recovery backup** on the sync screen to copy it somewhere you choose, then the usual **Import data** action to restore it. Import replaces the whole collection, so export your current data first if needed.

There is **one latest recovery backup per device**, replaced by the next sync that changes collection content. Export it before another sync if you need to retain an older competing version. Repeating an already-applied sync does not replace it.

Normal JSON exports now include sync identities and revisions (backup version 6). Restoring a backup preserves identities but records the restore as a new local change, rather than rewinding sync counters. Older backups still import, but do not contain shared identities; duplicate review may be needed when syncing them.

Importer settings remain device-local. Original external photo files are never deleted by sync. Device identity is stored in Android's no-backup directory so a restored installation does not impersonate the original device.

## Implementation boundaries

- Java, no new runtime dependencies, existing screens/data model kept intact.
- Room migration 7 → 8 only adds `sync_state`; it does not recreate existing collection tables or seed media.
- Vector counters detect causality/concurrent edits, independent of wall-clock time. Missing items and explicit deletions are distinct.
- Apply checks that the local snapshot is still current, validates progress/history together, and commits collection plus sync metadata in one Room transaction. A failed validation rolls back all database changes. Thumbnails are immutable app-owned files; a failed import can leave an unused thumbnail, not overwrite an existing original.
- Two separate devices cannot share a single database transaction. Both approve before either applies, but a radio failure can leave only one committed. Retrying is the recovery mechanism; the UI never calls an interrupted session successful.

## Testing before relying on it

Automated tests exercise isolated phone/tablet databases and the real frame/session protocol over duplex streams. They do not prove Bluetooth radio compatibility on physical devices. First use: export both collections, sync a phone/tablet pair, make an offline edit on each, review the conflict, retry a cancelled transfer, and check the result on both devices.

September 19 verification: debug/app-test builds, unit tests and lint succeeded (zero lint errors). The final instrumentation run reported 72 tests: 70 passed and two opt-in live-network checks were skipped. All three sync UI tests also passed at 320dp width with 1.3× text. Physical-device Bluetooth testing remains outstanding.
