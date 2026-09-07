# Watlis implementation and verification

The application remains Java in `com.watlis.app`, with native Android views, the existing ViewModel/repository classes, and Room. Covers load through Coil. No sample collection is seeded.

The current `tsugi-design-handoff/` folder is empty. The written handoff was recovered from the earlier session record; its black/lime tokens, drawer navigation, compact rows, filters, progress controls, form behavior, and story-memory organization informed this implementation. The original HTML prototype and design assets are unavailable for direct visual comparison.

Implemented controls include search and clear, multi-select filters with draft/reset/apply behavior, removable filters, sorting in both directions, Home and Detail progress updates, cover picking and preview, media editing, focused story/rating/status/notes editors, character management, genre management, and statistics. Media drafts and list navigation state survive Activity recreation.

Room access runs on the ViewModel executor. Quick progress writes use the current persisted value in a transaction, and metadata edits preserve progress recency. Schema version 2 adds genre uniqueness and foreign-key indices, with a tested migration retaining existing media and consolidating duplicate genre associations.

## Build

On this workstation, Android Studio includes the required JDK:

```powershell
$env:JAVA_HOME = 'X:/Programs/Android/Android Studio/jbr'
./gradlew.bat :app:assembleDebug :app:lintDebug :app:testDebugUnitTest :app:connectedDebugAndroidTest --console=plain
```

Verified on September 7, 2026:

- Debug APK build and local unit test task passed.
- Lint: zero errors; 35 warnings remain, including dependency-version suggestions, unused starter resources, and text localization warnings.
- Nine instrumentation tests passed on the Pixel 7 / Android 15 emulator. These cover database migration, cascade deletion, case-insensitive genre uniqueness, decimal/rapid progress updates, recency preservation, rating averages, invalid input, form recreation, story editing, drawer navigation, and filter combinations.
- Installed and launched the debug APK successfully.
- Visually inspected Home at the normal display size and at 320dp width with font scale 1.3; restored emulator display settings afterward.

APK: `app/build/outputs/apk/debug/app-debug.apk`.

Remaining verification limits: exact prototype comparison, real-device photo-provider permissions, TalkBack, and offline cache behavior for remote covers have not been exhaustively tested. Uncached remote images require a connection; tracking data works locally.
