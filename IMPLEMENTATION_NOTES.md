# Watlis implementation and verification

The application remains Java in `com.watlis.app`, with native Android views, the existing ViewModel/repository classes, and Room. Covers load through Coil. No sample collection is seeded.

The current `tsugi-design-handoff/` folder is empty. The written handoff was recovered from the earlier session record; its black/lime tokens, drawer navigation, compact rows, filters, progress controls, form behavior, and story-memory organization informed this implementation. The original HTML prototype and design assets are unavailable for direct visual comparison.

Implemented controls include search and clear, multi-select filters with draft/reset/apply behavior, removable filters, sorting in both directions, Home and Detail progress updates, cover picking and preview, media editing, focused story/rating/status/notes editors, character management, genre management, and statistics. Media drafts and list navigation state survive Activity recreation.

Room access runs on the ViewModel executor. Quick progress writes use the current persisted value in a transaction, and metadata edits preserve progress recency. Schema version 7 retains the earlier migrations, adds normalized cover-position coordinates (v3), editable media types (v4), 1x–3x cover zoom (v5, default 1x for existing media), an optional character image (v6), and progress history (v7). Snapshot loading uses a fixed number of bulk queries instead of four queries per title. History is not loaded into the startup snapshot.

Media types are managed from the drawer or created inline in the media editor. Names are case-insensitively unique, with stable keys and a chapter/episode progress unit. Renames preserve associations; deleting an in-use type requires a replacement and preserves tracking, notes, covers, and genres. At least one type must remain. JSON backups include type definitions and cover positions and remain compatible with version-1 backups.

Detail cover taps open a full-screen, uncropped preview with pinch/pan, double-tap, and explicit zoom/fit controls. Original images decode only for explicit previews (up to 2560 pixels per edge) or one-time thumbnail creation. Full-screen originals are not retained in the decoded-memory cache after closing. If the original is unavailable, the preview falls back to the saved thumbnail and labels the reduced-quality fallback.

`CoverStore` saves an uncropped JPEG (78% quality, at most 768px on its longest edge) in app-private `files/cover_thumbnails`, keyed by source hash. Generation is serialized and compression runs off the UI thread; existing saved files bypass the generation queue. List/Detail thumbnails decode at bounded display sizes. Crop position and zoom use a matrix and never create extra image copies. Saving a cover waits for thumbnail preparation and warns if the source is unavailable; existing covers acquire their copy when loaded. Already-missing originals cannot be recovered without a previously saved copy. Copies survive cache clearing/source deletion but not app-data clearing or uninstall; JSON version-3 exports include saved thumbnails and zoom, and imports still accept earlier backups. Import file reading runs off the UI thread. Saved copies are retained in app files, not in a disposable image cache.

List cards use vertically centered 56×80dp covers beside the title, score/type, and genres. Genre tags and the +N indicator have matching, font-aware heights, with wrapping rather than clipping. List cards and Detail accents follow the first alphabetically sorted genre, with contrast-adjusted accents for dark colors. The red adaptive book/bookmark launcher icon uses small vector resources and includes a monochrome variant.

Detail typography distinguishes 18sp section headings, muted 11sp uppercase field labels, and 16sp reminder values (18sp for the main character). Genre color is reserved for small section markers and actions instead of coloring every heading. Reminder edit controls use an 18dp vector inside a 32dp visual surface while retaining 48dp touch targets. Expansion and personal-note editing use quiet text actions, with no empty expansion row for short values. Reminder editing and expansion have a dedicated UI regression test.

Detail title taps copy the complete title through Android's clipboard; holding the title opens the existing media editor and consumes the gesture without copying. The title retains a 48dp minimum touch target and labeled accessibility actions.

Changed character, description, story-memory, and personal-note dialogs ask **Discard / Keep** when dismissed with Cancel, system Back, or an outside tap. Keep invokes the normal validated save without needing the editor's Save button; Discard drops the unsaved draft while preserving previously saved data. Dismissing the decision itself resumes editing. Unchanged dialogs close directly. Character names remain required, and dialogs stay open until the write succeeds. The full media editor uses the same decision, with Keep saving all form fields including personal notes. No new runtime dependencies or database migration were required.

Characters support an optional device photo in Add/Edit character, with Change and Remove controls and a compact 64x88dp thumbnail. Tapping either the selected photo or the saved character photo opens the existing full-screen zoom/pan/fit viewer. The same app-private thumbnail store keeps a bounded offline copy; original decoding occurs only for explicit previews or first-time thumbnail preparation. Saving waits for the copy and retains the draft if the selected image cannot be read. Photo changes participate in Keep/Discard, and character form fields plus the photo selection survive Activity recreation. Removing a photo changes only the character association, not the original device file. JSON version-4 backups include character image references and saved thumbnails; older backups import with no character photo. The additive v5-to-v6 database migration preserves existing character names, roles, descriptions, and media associations. No runtime dependencies were added.

The character editor uses one charcoal dialog surface, brighter outlined input boxes with 16sp text, a small vector person placeholder, genre-accent photo controls, and a filled Save button. Its scroll content no longer forces pure black or covers the dialog's outline. These changes are local to Add/Edit character. The native input underline is removed so Material can draw the intended outline.

Progress history records future changes only; initial progress and existing data remain a baseline. Plus taps create reading updates, while decreases, manual entry, and progress edits in the media form create corrections. Unchanged values and metadata-only edits do not create entries. Progress and history are committed in the same Room transaction. Each entry stores before/after values, event time, previous/resulting reading timestamps, a unit snapshot, and a unique token. Undo verifies the latest token and current persisted state for that title, restores progress and its prior reading timestamp, and appends an Undo entry. A stale or repeated Undo is refused; Undo entries cannot themselves be undone. Changes to another title do not invalidate this title's latest action.

List and Detail display one reusable three-second Undo message, updated in place for rapid taps and identifying the title. It uses a charcoal surface with equal gutters, vertically centered text/action, and downward-swipe dismissal (including a swipe starting over Undo). Swiping dismisses feedback without changing progress. Statistics > Recently updated > a title's overflow menu offers Undo latest change, enabled only after loading its latest eligible record; the repository still rejects stale actions. History and Recently updated Undo remain available after notification expiry or app reopening. Detail's History action opens recycled text rows with indexed keyset paging (30 records per page). No new history is inferred for legacy imports. JSON version-5 backups preserve history and Undo records; inconsistent history is rejected within the import transaction. Export reads progress/history in one database transaction for consistency. The v6-to-v7 migration adds only the history table and indexes; deleting media cascades to its history.

New Media accepts HTTPS chapter URLs matched to enabled providers in Settings. The shipped Shinigami configuration accepts `shinigami.asia` and its subdomains. A link icon appears for a matching provider host, chapter path and ID format; only tapping it starts the two GET requests (chapter detail, then manga detail). The chapter you opened supplies fractional-capable progress, never the manga's latest chapter. The draft receives title, portrait cover (landscape fallback), Format, Genres and Reading tracking status. Description, alternate title, release year, authors/artists, community rating and source link are appended to Personal notes; the personal rating and Favorite choice stay untouched. Numeric release-status codes are not defined by the provided API contract, so the current release status is preserved with a visible review reminder; textual ongoing/completed values can be imported.

Imports stay editable and unsaved until Add media or Keep. Existing genres/types are matched without changing their colors; missing ones are draft selections and created atomically with the media on Save. Discard does not create taxonomy or media. Initial progress is a baseline, with no fabricated history. Draft fields and pending taxonomy survive recreation; an in-flight lookup is cancelled and can be retried. A late response cannot overwrite fields edited during lookup. Networking runs on a separate, lazily started executor with connection/read timeouts and a 1 MiB response cap; only explicitly configured API endpoints are requested, redirects are refused, and cover URLs are restricted to configured provider asset hosts. Routing is frozen for both requests when an import starts. No chapter images are fetched. Existing bounded thumbnail storage handles imported covers. No schema migration or runtime dependencies were added.

Settings > Import providers supports adding/editing/enabling/disabling/deleting multiple configurations. Each includes website host rules, HTTPS API base, chapter-link pattern, chapter and manga endpoint templates, allowed cover hosts, and UUID versus URL-safe ID validation. A local request preview checks extraction and shows URLs without sending traffic. Duplicate names and overlapping enabled host rules with the same link path are rejected; ambiguous links fail closed. Settings persist in app-private preferences; deleting all providers stays empty after reopening, and invalid saved configurations disable imports with a recovery message instead of silently falling back. Restoring defaults requires confirmation and replaces only provider settings. Provider drafts survive recreation, and Cancel/Back offers Keep/Discard for changed fields. The response schema remains the current chapter/manga JSON contract; arbitrary JSON mapping/authentication is not implemented. Collection JSON exports do not include provider settings. See `IMPORT_PROVIDERS.md` for the user-facing setup guide.

Other future feature proposals are documented in `FEATURE_BLUEPRINT.md`; only its Progress history + Undo feature is implemented.

## Build

On this workstation, Android Studio includes the required JDK:

```powershell
$env:JAVA_HOME = 'X:/Programs/Android/Android Studio/jbr'
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:testDebugUnitTest --console=plain
# Install in place and run instrumentation without uninstalling existing app data:
$adb = 'C:/Users/andel/AppData/Local/Android/Sdk/platform-tools/adb.exe'
& $adb install -r app/build/outputs/apk/debug/app-debug.apk
& $adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
& $adb shell am instrument -w com.watlis.app.test/androidx.test.runner.AndroidJUnitRunner
```

Verified on September 17, 2026 for dark Undo feedback and Shinigami link import:

- Debug APK, Android-test APK, local unit tests and lint passed. Lint reports zero errors and 42 pre-existing warnings. All 46 instrumentation tests passed on Pixel 7 / Android 15 with live Shinigami checks enabled (`-e liveShinigami true`). Ordinary offline suite runs skip the two explicitly opt-in live tests.
- Nine targeted tests also passed at 320dp width with font scale 1.3, including the live API/form checks and bounded offline cover-cache verification. Inspected the popup and imported form at both sizes; restored the emulator's original display and font settings afterward.
- Tests cover symmetric spacing and actual charcoal color (without Material inverse tint), the three-second timeout, downward dismissal starting on Undo, reusing the bar on rapid taps, expired-notification Undo from Recently updated after recreation, disabled baseline/already-undone actions, and stale-token rejection.
- Import coverage includes exact host/path/UUID validation, sequential chapter-to-manga requests, fractional opened chapter versus latest chapter, case-insensitive taxonomy reuse, atomic rollback/retry, no fabricated history, unsupported covers, malformed/mismatched/error responses, timeout retry, preserving manual edits against late results, draft recreation and cancellation. The live example fills Star-Embracing Swordmaster at chapter 81; its portrait thumbnail was saved and verified to stay within 768px.
- Installed in place without clearing existing collection data. Tests remove only their own saved fixtures. No new startup or RAM benchmark was performed; the import executor starts work only after the user's explicit tap.

Earlier verification on September 15, 2026 for Progress history + Undo:

- Debug APK, Android test APK, local unit tests, and lint tasks passed. Lint reports zero errors and the same 42 warnings as before this feature.
- All 34 instrumentation tests passed on Pixel 7 / Android 15. Eight new repository tests cover fractional/rapid updates, no-op changes, recency restoration, stale Undo at an identical progress value, metadata exclusion, transaction rollback on history-insert failure, bounded keyset pagination, unit snapshots, history backup validation/legacy import, persistent Undo after database reopening, and concurrent updates/Undo. Two new UI tests cover List/Detail/editor Undo, reopening History, the empty state, and scrolling through 85 entries in 30-record pages.
- Both history UI tests also passed at 320dp width with font scale 1.3. Visually inspected History at normal and enlarged text sizes; restored the emulator to its original size and font scale afterward.
- Upgraded and installed in place without clearing the user's collection. Existing migration coverage verifies that legacy progress and characters survive with an empty history table. No new runtime dependencies were introduced. Startup history loading is avoided by design; no new startup-time or memory benchmark was performed for this feature.

Earlier verification on September 15, 2026 for character-editor contrast and spacing:

- Debug/app-test APK builds, local unit tests, and lint tasks passed; lint reports zero errors and 42 warnings.
- Three targeted instrumentation tests passed: character form surfaces/field contrast/action sizing, character Keep/Discard/validation, and the full character-photo picker/rotation/preview/fallback/backup/removal flow.
- Visually inspected the normal-size character dialog, including the filled Save button. The full suite and enlarged-text layout were not rerun for this polish.

Earlier verification on September 14, 2026 for character images:

- Debug APK, Android test APK, local unit tests, and lint tasks passed. Lint reports zero errors and 42 warnings.
- All 23 instrumentation tests passed on Pixel 7 / Android 15. The new end-to-end test uses the real system document picker and verifies photo selection, persistable access, character draft recreation, Keep saving, original-resolution preview, zoom/fit, missing-original fallback, thumbnail backup restoration in an isolated database, and image-only Keep/Discard/removal while preserving descriptions. Migration and legacy-backup tests also cover the optional image field.
- APKs were installed in place without clearing the existing collection. Real-device and alternate photo-provider behavior remain outside this emulator verification.

Earlier verification on September 14, 2026 for the note-exit and title-gesture changes:

- Debug APK, Android test APK, local unit tests, and lint tasks passed. Lint still reports zero errors and 44 warnings.
- All 22 instrumentation tests passed on Pixel 7 / Android 15, including three new UI regressions for Keep/Discard, unchanged dismissal, system Back, outside-tap dismissal, resuming a draft, required character names, character descriptions/roles, full-form personal-note saving, title copying, and hold-to-edit without copying.
- Installed both APKs in place without uninstalling the existing app. Tests remove only their own temporary collection fixtures.

Earlier checks from September 10, 2026:

- Debug APK build and local unit test task passed.
- Lint: zero errors; 44 warnings remain, including dependency-version suggestions, unused starter resources, text localization, and accessibility warnings.
- All 19 instrumentation tests passed on Pixel 7 / Android 15. Coverage includes schema upgrades, CRUD/relations, rapid progress/recency, form recreation, filtering and genre navigation, reminder editing, genre row bounds/alignment, zoom persistence and validation, original-resolution preview, source-file deletion fallback, bounded saved thumbnails, and thumbnail backup restoration.
- Installed and launched the debug APK successfully.
- Visually inspected the corrected Home genre rows at normal size and at 320dp width with font scale 1.3. All three targeted cover/genre-layout tests also passed at the narrow size, including saved zoom and missing-original fallback. Prior checks covered Detail typography and the launcher icon. Restored emulator display settings afterward.
- One cold debug launch measured 0.95 seconds on this emulator with the current small collection; this is a smoke-check measurement, not a large-library or real-device benchmark. Debug APK is approximately 16 MB. No new runtime dependencies were added for these changes.

APK: `app/build/outputs/apk/debug/app-debug.apk`.

Remaining verification limits: exact prototype comparison, real-device photo-provider permissions, TalkBack, and offline cache behavior for remote covers have not been exhaustively tested. Uncached remote images require a connection; tracking data works locally.
