package com.watlis.app;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.watlis.app.data.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Collections;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ProgressHistoryUiTest {
    private WatlisDatabase db(){return WatlisDatabase.get(InstrumentationRegistry.getInstrumentation().getTargetContext());}
    private void await(BooleanSupplier condition) throws Exception {
        long until=System.currentTimeMillis()+10000;
        while(System.currentTimeMillis()<until){if(condition.getAsBoolean())return;Thread.sleep(75);}
        assertTrue("Timed out waiting for history UI",condition.getAsBoolean());
    }
    private void waitText(String text) throws Exception {
        await(() -> {
            try {onView(withText(text)).check(matches(isDisplayed()));return true;}
            catch(androidx.test.espresso.NoMatchingViewException|AssertionError error){return false;}
        });
    }
    @Test public void listDetailManualEditorAndReopenedHistorySupportUndo() throws Exception {
        WatlisRepository repo=new WatlisRepository(db());MediaEntity m=new MediaEntity();m.title="History UI "+UUID.randomUUID();
        UserProgressEntity p=new UserProgressEntity();p.currentProgress=24.5;repo.saveMedia(m,p,Collections.emptyList());
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            waitText("+ Add");
            onView(withContentDescription("Search your titles")).perform(replaceText(m.title),closeSoftKeyboard());
            onView(withContentDescription("Increase progress")).perform(click());
            await(() -> db().progressDao().get(m.id).currentProgress==25.5);
            onView(withText("Undo")).perform(click());await(() -> db().progressDao().get(m.id).currentProgress==24.5);
            onView(withContentDescription("Cover for "+m.title)).perform(click());
            onView(withContentDescription("Increase progress")).perform(scrollTo(),click(),click(),click());
            await(() -> db().progressDao().get(m.id).currentProgress==27.5);
            onView(withText("Undo")).perform(click());await(() -> db().progressDao().get(m.id).currentProgress==26.5);
            onView(withContentDescription("Edit progress for "+m.title)).perform(scrollTo(),click());
            onView(withHint("Chapter")).perform(replaceText("18.25"),closeSoftKeyboard());
            onView(withText("Update")).perform(click());await(() -> db().progressDao().get(m.id).currentProgress==18.25);
            assertEquals("correction",db().progressHistoryDao().latest(m.id).kind);
            scenario.recreate();
            await(() -> {
                try {onView(withContentDescription("Show progress history")).perform(scrollTo(),click());return true;}
                catch(androidx.test.espresso.NoMatchingViewException error){return false;}
            });
            waitText("Undo latest change");
            onView(withText("Chapter 26.5 → 18.25")).check(matches(isDisplayed()));
            android.graphics.Bitmap shot=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            if(shot!=null) {
                java.io.File file=new java.io.File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null),"progress-history-preview.png");
                try(java.io.OutputStream output=new java.io.FileOutputStream(file)){shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);}
                finally {shot.recycle();}
            }
            onView(withText("Undo latest change")).perform(click());
            await(() -> db().progressDao().get(m.id).currentProgress==26.5);
            onView(withText("Edit media")).perform(scrollTo(),click());
            onView(withHint("Current chapter")).perform(scrollTo(),replaceText("30.75"),closeSoftKeyboard());
            onView(withText("Save changes")).perform(scrollTo(),click());
            await(() -> db().progressDao().get(m.id).currentProgress==30.75);
            onView(withText("Undo")).perform(click());await(() -> db().progressDao().get(m.id).currentProgress==26.5);
            assertEquals(10,db().progressHistoryDao().forBackup(m.id).size());
        } finally {db().mediaDao().delete(m);}
    }

    @Test public void historyHasEmptyStateAndLoadsOlderRowsOnlyOnScroll() throws Exception {
        WatlisRepository repo=new WatlisRepository(db());MediaEntity m=new MediaEntity();m.title="History paging "+UUID.randomUUID();
        repo.saveMedia(m,new UserProgressEntity(),Collections.emptyList());
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            waitText("+ Add");onView(withContentDescription("Search your titles")).perform(replaceText(m.title),closeSoftKeyboard());
            onView(withContentDescription("Cover for "+m.title)).perform(click());
            onView(withContentDescription("Show progress history")).perform(scrollTo(),click());
            waitText("No progress changes yet. Existing progress is your starting point.");
            onView(withText("Close")).perform(click());
            for(int i=0;i<85;i++)repo.incrementProgress(m.id,1);
            onView(withContentDescription("Show progress history")).perform(scrollTo(),click());
            awaitRows(30);
            onView(withContentDescription("Progress history entries")).perform(scrollHistory());awaitRows(60);
            onView(withContentDescription("Progress history entries")).perform(scrollHistory());awaitRows(85);
            onView(withContentDescription("Progress history entries")).perform(scrollHistory());
            onView(withText("Chapter 0 → 1")).check(matches(isDisplayed()));
            onView(withText("Beginning of recorded history")).check(matches(isDisplayed()));
            onView(withText("Close")).perform(click());
        } finally {db().mediaDao().delete(m);}
    }
    private void awaitRows(int count) throws Exception {
        await(() -> {
            int[] rows={0};
            onView(withContentDescription("Progress history entries")).check((view,error) -> {
                if(error!=null)throw error;
                rows[0]=((androidx.recyclerview.widget.RecyclerView)view).getAdapter().getItemCount();
            });
            return rows[0]==count;
        });
    }

    @Test public void balancedNoticeExpiresAndRecentMenuCanUndoAfterReopening() throws Exception {
        WatlisRepository repo=new WatlisRepository(db());MediaEntity m=new MediaEntity();
        m.title="Recent Undo "+UUID.randomUUID();UserProgressEntity p=new UserProgressEntity();p.currentProgress=24.5;
        repo.saveMedia(m,p,Collections.emptyList());
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            waitText("+ Add");onView(withContentDescription("Search your titles")).perform(replaceText(m.title),closeSoftKeyboard());
            onView(withContentDescription("Increase progress")).perform(click());
            await(() -> db().progressDao().get(m.id).currentProgress==25.5);
            onView(withContentDescription("Progress updated. Swipe down to dismiss.")).check((view,error) -> {
                if(error!=null)throw error;
                android.widget.LinearLayout row=(android.widget.LinearLayout)view;
                assertEquals(row.getPaddingLeft(),row.getPaddingRight());
                assertEquals(row.getPaddingTop(),row.getPaddingBottom());
                android.view.View labels=row.getChildAt(0), action=row.getChildAt(1);
                assertTrue(Math.abs((labels.getTop()+labels.getBottom())-(action.getTop()+action.getBottom()))<=2);
                android.view.View bar=(android.view.View)row.getParent();
                assertNull("Theme must not tint the dark notification",bar.getBackgroundTintList());
                assertEquals(android.graphics.Color.parseColor("#101311"),((android.graphics.drawable.GradientDrawable)bar.getBackground()).getColor().getDefaultColor());
                android.view.ViewGroup.MarginLayoutParams margins=(android.view.ViewGroup.MarginLayoutParams)bar.getLayoutParams();
                assertEquals(margins.leftMargin,margins.rightMargin);
                assertTrue(action.getWidth()>=48*view.getResources().getDisplayMetrics().density);
                assertTrue(labels.getRight()<action.getLeft());
            });
            saveScreenshot("progress-undo-preview.png");
            // Still visible well before the timeout, then gone shortly after three seconds.
            onView(isRoot()).perform(pause(1000));
            onView(withText("Undo")).check(matches(isDisplayed()));
            onView(isRoot()).perform(pause(2400));
            onView(withContentDescription("Progress updated. Swipe down to dismiss.")).check(doesNotExist());
            assertEquals(25.5,db().progressDao().get(m.id).currentProgress,0);
            scenario.recreate();waitText("+ Add");openRecentMenu(m.title);
            awaitMenuUndoEnabled();
            saveScreenshot("recent-undo-preview.png");
            onView(withText("Undo latest change")).perform(click());
            await(() -> db().progressDao().get(m.id).currentProgress==24.5);
            onView(org.hamcrest.Matchers.allOf(withText("Statistics"),org.hamcrest.Matchers.not(isClickable()))).check(matches(isDisplayed()));
            onView(withContentDescription("More actions for "+m.title)).perform(scrollTo(),click());
            onView(withText("Undo latest change")).check(matches(isDescendantOfA(org.hamcrest.Matchers.allOf(isAssignableFrom(androidx.appcompat.view.menu.ListMenuItemView.class),org.hamcrest.Matchers.not(isEnabled())))));
            pressBack();
        } finally {db().mediaDao().delete(m);}
    }

    @Test public void downwardSwipeOverUndoDismissesWithoutChangingProgress() throws Exception {
        WatlisRepository repo=new WatlisRepository(db());MediaEntity m=new MediaEntity();m.title="Swipe Undo "+UUID.randomUUID();
        repo.saveMedia(m,new UserProgressEntity(),Collections.emptyList());
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            waitText("+ Add");onView(withContentDescription("Search your titles")).perform(replaceText(m.title),closeSoftKeyboard());
            onView(withContentDescription("Increase progress")).perform(click());
            await(() -> db().progressDao().get(m.id).currentProgress==1);
            onView(withText("Undo")).perform(swipeDown());
            // Wait for the dismiss animation, not the three-second timeout.
            onView(isRoot()).perform(pause(400));
            onView(withContentDescription("Progress updated. Swipe down to dismiss.")).check(doesNotExist());
            assertEquals(1,db().progressDao().get(m.id).currentProgress,0);
            assertEquals(1,db().progressHistoryDao().forBackup(m.id).size());
            // A new notification still works after the gesture dismissal.
            onView(withContentDescription("Increase progress")).perform(click());
            onView(withText("Undo")).perform(click());
            await(() -> db().progressDao().get(m.id).currentProgress==1);
        } finally {db().mediaDao().delete(m);}
    }

    @Test public void recentlyUpdatedRejectsStaleUndoAndDisablesBaseline() throws Exception {
        WatlisRepository repo=new WatlisRepository(db());MediaEntity m=new MediaEntity();m.title="Stale recent "+UUID.randomUUID();
        repo.saveMedia(m,new UserProgressEntity(),Collections.emptyList());
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            waitText("+ Add");openRecentMenu(m.title);
            onView(withText("Undo latest change")).check(matches(isDescendantOfA(org.hamcrest.Matchers.allOf(isAssignableFrom(androidx.appcompat.view.menu.ListMenuItemView.class),org.hamcrest.Matchers.not(isEnabled())))));pressBack();
            repo.incrementProgress(m.id,1);
            onView(withContentDescription("More actions for "+m.title)).perform(scrollTo(),click());
            awaitMenuUndoEnabled();
            repo.incrementProgress(m.id,1);
            onView(withText("Undo latest change")).perform(click());
            onView(isRoot()).perform(pause(400));
            assertEquals(2,db().progressDao().get(m.id).currentProgress,0);
            assertEquals(2,db().progressHistoryDao().forBackup(m.id).size());
        } finally {db().mediaDao().delete(m);}
    }

    private void openRecentMenu(String title) {
        onView(withContentDescription("Open navigation drawer")).perform(click());
        onView(withText("Statistics")).perform(click());
        onView(withContentDescription("More actions for "+title)).perform(scrollTo(),click());
    }

    private void awaitMenuUndoEnabled() throws Exception {
        await(() -> {try {
            onView(withText("Undo latest change")).check(matches(isDescendantOfA(org.hamcrest.Matchers.allOf(isAssignableFrom(androidx.appcompat.view.menu.ListMenuItemView.class),isEnabled()))));
            return true;
        } catch(AssertionError error){return false;}});
    }

    private void saveScreenshot(String name) throws Exception {
        android.graphics.Bitmap shot=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        if(shot!=null) {
            java.io.File file=new java.io.File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null),name);
            try(java.io.OutputStream output=new java.io.FileOutputStream(file)){shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);}
            finally {shot.recycle();}
        }
    }

    private androidx.test.espresso.ViewAction pause(long millis) {
        return new androidx.test.espresso.ViewAction() {
            public org.hamcrest.Matcher<android.view.View> getConstraints(){return isRoot();}
            public String getDescription(){return "Allow notification timeout to run";}
            public void perform(androidx.test.espresso.UiController ui,android.view.View view){ui.loopMainThreadForAtLeast(millis);}
        };
    }
    private androidx.test.espresso.ViewAction scrollHistory() {
        return new androidx.test.espresso.ViewAction() {
            public org.hamcrest.Matcher<android.view.View> getConstraints(){return isAssignableFrom(androidx.recyclerview.widget.RecyclerView.class);}
            public String getDescription(){return "Scroll history to the oldest loaded row";}
            public void perform(androidx.test.espresso.UiController ui,android.view.View view){view.scrollBy(0,100000);ui.loopMainThreadUntilIdle();}
        };
    }
}
