package com.watlis.app;

import android.app.UiAutomation;
import android.content.*;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.*;
import android.view.accessibility.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.watlis.app.data.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.function.BooleanSupplier;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class FloatingChapterUiTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
    private WatlisDatabase db;
    private MediaEntity media;
    private String originalOverlay;
    private Map<String, ?> originalPreferences;
    @Before public void setup() throws Exception {
        db = WatlisDatabase.get(context);
        originalOverlay = shell("appops get com.watlis.app SYSTEM_ALERT_WINDOW");
        originalPreferences = context.getSharedPreferences("floating_controls", Context.MODE_PRIVATE).getAll();
        shell("appops set com.watlis.app SYSTEM_ALERT_WINDOW allow");
        // Do not modify notification permission: skip the optional prompt for this UI fixture only.
        context.getSharedPreferences("floating_controls", Context.MODE_PRIVATE).edit().putBoolean("notificationAsked", true).commit();
        android.accessibilityservice.AccessibilityServiceInfo info = automation.getServiceInfo();
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(info);
        context.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Thread.sleep(350);
        media = new MediaEntity(); media.title = "Floating UI " + UUID.randomUUID();
        UserProgressEntity progress = new UserProgressEntity(); progress.currentProgress = 24.5;
        new WatlisRepository(db).saveMedia(media, progress, Collections.emptyList());
    }
    @After public void cleanup() throws Exception {
        context.stopService(new Intent(context, FloatingChapterService.class));
        await(() -> node("Increase floating progress") == null);
        db.mediaDao().delete(media);
        String mode = originalOverlay.contains(": allow") ? "allow" : originalOverlay.contains(": ignore") ? "ignore"
                : originalOverlay.contains(": deny") ? "deny" : "default";
        shell("appops set com.watlis.app SYSTEM_ALERT_WINDOW " + mode);
        SharedPreferences.Editor preferences = context.getSharedPreferences("floating_controls", Context.MODE_PRIVATE).edit().clear();
        for (Map.Entry<String, ?> entry : originalPreferences.entrySet()) {
            if (entry.getValue() instanceof Boolean) preferences.putBoolean(entry.getKey(), (Boolean) entry.getValue());
            if (entry.getValue() instanceof Float) preferences.putFloat(entry.getKey(), (Float) entry.getValue());
        }
        preferences.commit();
    }
    @Test public void listLaunchRapidTapsDraggingMiddleReturnAndDetailLaunch() throws Exception {
        MainActivity activity = launch();
        try {
            selectFixture();
            onView(withContentDescription("Actions for " + media.title)).perform(click());
            onView(withText("Floating chapter")).perform(click());
            await(() -> node("Increase floating progress") != null);
            await(() -> activity.getLifecycle().getCurrentState() == androidx.lifecycle.Lifecycle.State.CREATED);
            context.startActivity(new Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Thread.sleep(600); await(() -> node("Increase floating progress") != null);
            // The app is minimized. These are real input events over another app, not performClick().
            for (int i = 0; i < 8; i++) tap("Increase floating progress");
            tap("Decrease floating progress");
            await(() -> db.progressDao().get(media.id).currentProgress == 31.5);
            assertEquals(9, db.progressHistoryDao().forBackup(media.id).size());
            Rect before = bounds("Increase floating progress");
            drag(before.centerX(), before.centerY(), before.centerX() - 100, before.centerY() + 170);
            await(() -> Math.abs(bounds("Increase floating progress").centerY() - before.centerY()) > 50);
            assertEquals(31.5, db.progressDao().get(media.id).currentProgress, 0);
            screenshot("floating-chapter-overlay.png");
            tap("Open details for " + media.title + ". Chapter 31.5. Hold to close floating controls.");
            await(() -> node("Increase floating progress") == null);
            waitText("Your progress");
            onView(withContentDescription("Edit progress for " + media.title)).perform(scrollTo()).check(matches(withText("Chapter 31.5")));
            onView(withText("Floating chapter")).perform(scrollTo()); screenshot("floating-chapter-detail.png");
            onView(withText("Floating chapter")).perform(click());
            await(() -> node("Increase floating progress") != null);
            tap("Increase floating progress");
            await(() -> db.progressDao().get(media.id).currentProgress == 32.5);
            Rect center = bounds("Open details for " + media.title + ". Chapter 32.5. Hold to close floating controls.");
            long time = SystemClock.uptimeMillis(); inject(time, time, MotionEvent.ACTION_DOWN, center.centerX(), center.centerY());
            Thread.sleep(850); inject(time, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, center.centerX(), center.centerY());
            await(() -> node("Increase floating progress") == null);
            assertEquals(32.5, db.progressDao().get(media.id).currentProgress, 0);
            shell("am start -W -n com.watlis.app/.MainActivity -f 0x34000000");
            await(() -> activity.getLifecycle().getCurrentState() == androidx.lifecycle.Lifecycle.State.RESUMED);
            waitText("Chapter 32.5");
        } finally { InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::finish); }
    }
    @Test public void deniedPermissionDoesNotMinimizeOrChangeProgress() throws Exception {
        shell("appops set com.watlis.app SYSTEM_ALERT_WINDOW ignore");
        MainActivity activity = launch();
        try {
            selectFixture(); onView(withContentDescription("Actions for " + media.title)).perform(click());
            onView(withText("Floating chapter")).perform(click());
            onView(withText("Read with floating controls")).check(matches(isDisplayed()));
            onView(withText("Cancel")).perform(click());
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, activity.getLifecycle().getCurrentState());
            assertNull(node("Increase floating progress")); assertEquals(24.5, db.progressDao().get(media.id).currentProgress, 0);
        } finally { InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::finish); }
    }
    @Test public void deletingActiveTitleClosesOverlayWithoutRecreatingIt() throws Exception {
        MainActivity activity = launch();
        try {
            selectFixture(); onView(withContentDescription("Actions for " + media.title)).perform(click());
            onView(withText("Floating chapter")).perform(click()); await(() -> node("Increase floating progress") != null);
            db.mediaDao().delete(media); await(() -> node("Increase floating progress") == null);
            assertNull(db.progressDao().get(media.id));
        } finally { InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::finish); }
    }
    @Test public void grantingPermissionAndReturningStartsTheRequestedTitle() throws Exception {
        shell("appops set com.watlis.app SYSTEM_ALERT_WINDOW ignore");
        MainActivity activity = launch();
        try {
            selectFixture(); onView(withContentDescription("Actions for " + media.title)).perform(click());
            onView(withText("Floating chapter")).perform(click());
            onView(withText("Open settings")).perform(click());
            await(() -> activity.getLifecycle().getCurrentState() == androidx.lifecycle.Lifecycle.State.CREATED);
            shell("appops set com.watlis.app SYSTEM_ALERT_WINDOW allow");
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK));
            await(() -> node("Increase floating progress") != null);
            await(() -> activity.getLifecycle().getCurrentState() == androidx.lifecycle.Lifecycle.State.CREATED);
            assertNotNull(node("Open details for " + media.title + ". Chapter 24.5. Hold to close floating controls."));
            assertEquals(24.5, db.progressDao().get(media.id).currentProgress, 0);
        } finally { InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::finish); }
    }
    private MainActivity launch() {
        // Real task transitions + onNewIntent do not fit ActivityScenario's original-intent tracking.
        return (MainActivity) InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }
    private void selectFixture() throws Exception {
        waitText("+ Add"); onView(withContentDescription("Search your titles")).perform(replaceText(media.title), closeSoftKeyboard());
    }
    private void waitText(String text) throws Exception {
        await(() -> { try { onView(withText(text)).check(matches(isDisplayed())); return true; }
            catch (androidx.test.espresso.NoMatchingViewException | AssertionError error) { return false; } });
    }
    private void await(BooleanSupplier condition) throws Exception {
        long until = System.currentTimeMillis() + 12000;
        while (System.currentTimeMillis() < until) { if (condition.getAsBoolean()) return; Thread.sleep(80); }
        assertTrue("Timed out waiting for floating controls", condition.getAsBoolean());
    }
    private AccessibilityNodeInfo node(String description) {
        for (AccessibilityWindowInfo window : automation.getWindows()) {
            AccessibilityNodeInfo result = find(window.getRoot(), description); if (result != null) return result;
        }
        return null;
    }
    private AccessibilityNodeInfo find(AccessibilityNodeInfo root, String description) {
        if (root == null) return null;
        if (description.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())) return root;
        for (int i = 0; i < root.getChildCount(); i++) { AccessibilityNodeInfo result = find(root.getChild(i), description); if (result != null) return result; }
        return null;
    }
    private Rect bounds(String description) {
        AccessibilityNodeInfo found = node(description); assertNotNull(description, found);
        Rect bounds = new Rect(); found.getBoundsInScreen(bounds); return bounds;
    }
    private void tap(String description) {
        Rect rect = bounds(description); long time = SystemClock.uptimeMillis();
        inject(time, time, MotionEvent.ACTION_DOWN, rect.centerX(), rect.centerY());
        inject(time, time + 40, MotionEvent.ACTION_UP, rect.centerX(), rect.centerY());
    }
    private void drag(float x, float y, float endX, float endY) {
        long time = SystemClock.uptimeMillis(); inject(time, time, MotionEvent.ACTION_DOWN, x, y);
        for (int i = 1; i <= 12; i++) inject(time, time + i * 20, MotionEvent.ACTION_MOVE, x + (endX - x) * i / 12, y + (endY - y) * i / 12);
        inject(time, time + 260, MotionEvent.ACTION_UP, endX, endY);
    }
    private void inject(long down, long time, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, time, action, x, y, 0); event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        assertTrue(automation.injectInputEvent(event, true)); event.recycle();
    }
    private String shell(String command) throws Exception {
        try (java.io.InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))) {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    private void screenshot(String name) throws Exception {
        Thread.sleep(350); android.graphics.Bitmap bitmap = automation.takeScreenshot(); assertNotNull(bitmap);
        try (java.io.OutputStream output = new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null), name))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        } finally { bitmap.recycle(); }
    }
}
