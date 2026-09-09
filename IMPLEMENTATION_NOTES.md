# Watlis implementation and verification

The application remains Java in `com.watlis.app`, with native Android views, the existing ViewModel/repository classes, and Room. Covers load through Coil. No sample collection is seeded.

The current `tsugi-design-handoff/` folder is empty. The written handoff was recovered from the earlier session record; its black/lime tokens, drawer navigation, compact rows, filters, progress controls, form behavior, and story-memory organization informed this implementation. The original HTML prototype and design assets are unavailable for direct visual comparison.

Implemented controls include search and clear, multi-select filters with draft/reset/apply behavior, removable filters, sorting in both directions, Home and Detail progress updates, cover picking and preview, media editing, focused story/rating/status/notes editors, character management, genre management, and statistics. Media drafts and list navigation state survive Activity recreation.

Room access runs on the ViewModel executor. Quick progress writes use the current persisted value in a transaction, and metadata edits preserve progress recency. Schema version 5 retains the earlier migrations, adds normalized cover-position coordinates (v3), editable media types (v4), and 1x–3x cover zoom (v5, default 1x for existing media). Snapshot loading uses a fixed number of bulk queries instead of four queries per title.

Media types are managed from the drawer or created inline in the media editor. Names are case-insensitively unique, with stable keys and a chapter/episode progress unit. Renames preserve associations; deleting an in-use type requires a replacement and preserves tracking, notes, covers, and genres. At least one type must remain. JSON backups include type definitions and cover positions and remain compatible with version-1 backups.

Detail cover taps open a full-screen, uncropped preview with pinch/pan, double-tap, and explicit zoom/fit controls. Original images decode only for explicit previews (up to 2560 pixels per edge) or one-time thumbnail creation. Full-screen originals are not retained in the decoded-memory cache after closing. If the original is unavailable, the preview falls back to the saved thumbnail and labels the reduced-quality fallback.

`CoverStore` saves an uncropped JPEG (78% quality, at most 768px on its longest edge) in app-private `files/cover_thumbnails`, keyed by source hash. Generation is serialized and compression runs off the UI thread; existing saved files bypass the generation queue. List/Detail thumbnails decode at bounded display sizes. Crop position and zoom use a matrix and never create extra image copies. Saving a cover waits for thumbnail preparation and warns if the source is unavailable; existing covers acquire their copy when loaded. Already-missing originals cannot be recovered without a previously saved copy. Copies survive cache clearing/source deletion but not app-data clearing or uninstall; JSON version-3 exports include saved thumbnails and zoom, and imports still accept earlier backups. Import file reading runs off the UI thread. Saved copies are retained in app files, not in a disposable image cache.

List cards use vertically centered 56×80dp covers beside the title, score/type, and genres. Genre tags and the +N indicator have matching, font-aware heights, with wrapping rather than clipping. List cards and Detail accents follow the first alphabetically sorted genre, with contrast-adjusted accents for dark colors. The red adaptive book/bookmark launcher icon uses small vector resources and includes a monochrome variant.

Detail typography distinguishes 18sp section headings, muted 11sp uppercase field labels, and 16sp reminder values (18sp for the main character). Genre color is reserved for small section markers and actions instead of coloring every heading. Reminder edit controls use an 18dp vector inside a 32dp visual surface while retaining 48dp touch targets. Expansion and personal-note editing use quiet text actions, with no empty expansion row for short values. Reminder editing and expansion have a dedicated UI regression test.

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

Verified on September 10, 2026:

- Debug APK build and local unit test task passed.
- Lint: zero errors; 44 warnings remain, including dependency-version suggestions, unused starter resources, text localization, and accessibility warnings.
- All 19 instrumentation tests passed on Pixel 7 / Android 15. Coverage includes schema upgrades, CRUD/relations, rapid progress/recency, form recreation, filtering and genre navigation, reminder editing, genre row bounds/alignment, zoom persistence and validation, original-resolution preview, source-file deletion fallback, bounded saved thumbnails, and thumbnail backup restoration.
- Installed and launched the debug APK successfully.
- Visually inspected the corrected Home genre rows at normal size and at 320dp width with font scale 1.3. All three targeted cover/genre-layout tests also passed at the narrow size, including saved zoom and missing-original fallback. Prior checks covered Detail typography and the launcher icon. Restored emulator display settings afterward.
- One cold debug launch measured 0.95 seconds on this emulator with the current small collection; this is a smoke-check measurement, not a large-library or real-device benchmark. Debug APK is approximately 16 MB. No new runtime dependencies were added for these changes.

APK: `app/build/outputs/apk/debug/app-debug.apk`.

Remaining verification limits: exact prototype comparison, real-device photo-provider permissions, TalkBack, and offline cache behavior for remote covers have not been exhaustively tested. Uncached remote images require a connection; tracking data works locally.
