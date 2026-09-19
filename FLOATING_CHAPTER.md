# Floating chapter controls

## How to use

1. Open a title's **Detail** page and tap **Floating chapter**, below the progress buttons. You can also select **Floating chapter** from that title's three-dot menu in the list.
2. On first use, allow Watlis to **display over other apps** in Android settings, then return to Watlis. Notifications are optional; allowing them gives you another Open/Close control.
3. Watlis minimizes after the floating control is ready. Open your usual reader app.

The floating control shows only **− | Chapter and number | +**. Episode-based media show **Episode** instead.

- **+ / −:** immediately change progress by one. Decimal progress is preserved (24.5 → 25.5), and progress never goes below zero.
- **Tap the middle:** return to this title's Detail page and close the floating control.
- **Drag:** move the control. Its position is remembered and kept within the screen edges.
- **Hold the middle:** close it without reopening Watlis. The ongoing notification also has a Close action.
- **Undo:** open Detail → History → Undo latest change, or use the existing Recently updated menu. Floating changes use the same history and stale-Undo protection as ordinary progress changes.

Only one title floats at a time. Choosing another title replaces the control. If you already have an editor open, a middle tap brings Watlis forward but keeps your draft; finish or leave the editor, then tap the floating chapter again.

## Safety and performance

- Taps are serialized on a worker thread and read the latest stored progress. Each progress change and its history entry commit in one database transaction.
- The display updates optimistically; a failed write refreshes the stored value and shows an error. Already accepted taps finish before a normal Close/middle action.
- The control watches changes to its selected record, including edits, deletion and restored data. A missing or replaced title closes the control instead of recreating it.
- No new database migration or runtime dependency. The floating service does not load covers, characters, the entire library, or old history. No network calls, polling loop, wake lock, boot receiver or automatic restart.
- Android keeps a foreground service only while floating controls are active. Opening Detail or closing the control removes the window and service. The app otherwise retains its existing startup path.

## Android limitations

Android's display-over-other-apps permission is required. Denying/canceling it leaves Watlis open and your progress unchanged. Permission revocation or Android stopping the app can close the overlay; committed progress remains saved. Security-sensitive apps/screens may deliberately hide overlays, and Watlis does not bypass that protection. Device manufacturers may add their own overlay restrictions.

The foreground service is declared as `specialUse`, with a manifest description of the user-started reading control. A Play Store release needs the matching foreground-service declaration/review. See Android's [overlay window documentation](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY), [overlay permission settings](https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_OVERLAY_PERMISSION), and [special-use foreground-service requirements](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use).

Real phone/tablet manufacturer behavior and physical-device battery/RAM use still need device testing; no performance benchmark is claimed.
