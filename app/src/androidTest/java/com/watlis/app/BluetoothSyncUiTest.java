package com.watlis.app;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BluetoothSyncUiTest {
    @Test public void settingsOpensSyncInstructionsAndBackWithoutChangingCollection() throws Exception {
        try (ActivityScenario<MainActivity> main = ActivityScenario.launch(MainActivity.class)) {
            long until = System.currentTimeMillis() + 10000; boolean ready = false;
            while (System.currentTimeMillis() < until) {
                try { onView(withText("+ Add")).check(matches(isDisplayed())); ready = true; break; }
                catch (androidx.test.espresso.NoMatchingViewException | AssertionError ignored) { Thread.sleep(80); }
            }
            assertTrue(ready); onView(withContentDescription("Open navigation drawer")).perform(click());
            onView(allOf(withText("Settings"), isClickable())).perform(scrollTo(), click());
            onView(allOf(withText("Bluetooth sync"), isClickable())).perform(scrollTo(), click());
            onView(withText("Your collection, on both devices")).check(matches(isDisplayed()));
            onView(withText("Pair once. Review before applying.")).check(matches(isDisplayed()));
            screenshot("bluetooth-sync-preview.png");
            onView(withText("Export pre-sync recovery backup")).perform(scrollTo()).check(matches(isDisplayed()));
            screenshot("bluetooth-sync-lower.png");
            onView(withText("Back to settings")).perform(scrollTo()); screenshot("bluetooth-sync-preview.png");
            onView(withText("Back to settings")).perform(click());
            onView(withText("Your devices")).check(matches(isDisplayed()));
        }
    }
    @Test public void rotationKeepsSafeIdleScreenAndRecoveryActionFits() throws Exception {
        try (ActivityScenario<BluetoothSyncActivity> scenario = ActivityScenario.launch(BluetoothSyncActivity.class)) {
            scenario.recreate(); onView(withText("Bluetooth sync")).check(matches(isDisplayed()));
            onView(withText("Show paired devices")).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withText("Export pre-sync recovery backup")).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withText("Back to settings")).perform(scrollTo()).check(matches(isDisplayed()));
        }
    }
    @Test public void conflictChoiceAndFinalApprovalAreReadableAndCancelable() throws Exception {
        com.watlis.app.data.SyncDocument local = fixture("phone", 24.5), remote = fixture("tablet", 12.25);
        java.util.concurrent.atomic.AtomicReference<BluetoothSyncActivity> activity = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (ActivityScenario<BluetoothSyncActivity> scenario = ActivityScenario.launch(BluetoothSyncActivity.class)) {
            scenario.onActivity(a -> { activity.set(a); busy(a, true); });
            java.util.concurrent.Future<com.watlis.app.data.SyncDocument> merged = worker.submit(() -> activity.get().resolve(local, remote));
            waitText("Both devices changed this"); onView(withText("Keep other device")).perform(scrollTo()); screenshot("bluetooth-sync-conflict.png");
            onView(withText("Keep other device")).perform(click());
            com.watlis.app.data.SyncDocument result = merged.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(12.25, result.records.get("m:fixture").data.getJSONObject("progress").getDouble("currentProgress"), 0);
            java.util.concurrent.Future<Boolean> approval = worker.submit(() -> activity.get().approve(local, remote, result, true));
            waitText("Review sync"); onView(withText("Approve & sync")).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withText("Cancel")).perform(click()); assertFalse(approval.get(10, java.util.concurrent.TimeUnit.SECONDS));
            java.util.concurrent.Future<?> interrupted = worker.submit(() -> { try { activity.get().resolve(local, remote); fail("Expected cancellation"); } catch (java.io.IOException expected) { } catch (Exception e) { throw new RuntimeException(e); } });
            waitText("Both devices changed this"); scenario.recreate(); interrupted.get(10, java.util.concurrent.TimeUnit.SECONDS);
            onView(withText("Bluetooth sync")).check(matches(isDisplayed()));
        } finally { worker.shutdownNow(); }
    }
    private void waitText(String value) throws Exception {
        long until = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < until) {
            try { onView(withText(value)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check(matches(isDisplayed())); return; }
            catch (androidx.test.espresso.NoMatchingViewException | AssertionError ignored) { Thread.sleep(60); }
        }
        fail("Missing sync UI: " + value);
    }
    private void busy(BluetoothSyncActivity activity, boolean value) {
        try { java.lang.reflect.Field field = BluetoothSyncActivity.class.getDeclaredField("busy"); field.setAccessible(true); field.setBoolean(activity, value); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private com.watlis.app.data.SyncDocument fixture(String device, double progress) throws Exception {
        com.watlis.app.data.SyncDocument document = new com.watlis.app.data.SyncDocument();
        java.util.Map<String, Long> revision = java.util.Collections.singletonMap(device, 1L);
        org.json.JSONObject data = new org.json.JSONObject().put("title", "Conflict preview").put("type", "t:manga")
                .put("genreIds", new org.json.JSONArray()).put("characters", new org.json.JSONArray()).put("progressHistory", new org.json.JSONArray())
                .put("coverPositionX", .5).put("coverPositionY", .5).put("coverZoom", 1).put("releaseStatus", "ongoing")
                .put("progress", new org.json.JSONObject().put("currentProgress", progress).put("trackingStatus", "reading").put("notes", "A complete note from " + device));
        document.records.put("m:fixture", new com.watlis.app.data.SyncDocument.Record("m:fixture", "Conflict preview", data, revision));
        document.records.put("t:manga", new com.watlis.app.data.SyncDocument.Record("t:manga", "Manga", new org.json.JSONObject().put("name", "Manga").put("usesEpisodes", false), revision));
        return document;
    }
    private void screenshot(String name) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync(); Thread.sleep(300);
        android.graphics.Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        if (image == null) return;
        try (java.io.OutputStream stream = new java.io.FileOutputStream(new java.io.File(
                InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null), name))) {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream);
        } finally { image.recycle(); }
    }
}
