package com.watlis.app;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.watlis.app.data.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MediaTypeInstrumentedTest {
    private void await(BooleanSupplier condition) throws Exception {
        long until=System.currentTimeMillis()+10000;
        while(System.currentTimeMillis()<until){if(condition.getAsBoolean())return;Thread.sleep(50);}
        assertTrue("Timed out waiting for media type change",condition.getAsBoolean());
    }
    private void waitText(String text) throws Exception {
        long until=System.currentTimeMillis()+10000;
        while(System.currentTimeMillis()<until) {
            try{onView(withText(text)).check(matches(isDisplayed()));return;}
            catch(androidx.test.espresso.NoMatchingViewException|AssertionError e){Thread.sleep(50);}
        }
        onView(withText(text)).check(matches(isDisplayed()));
    }
    @Test public void customTypeCanBeCreatedRenamedUsedAndSafelyDeleted() throws Exception {
        WatlisDatabase db=WatlisDatabase.get(InstrumentationRegistry.getInstrumentation().getTargetContext());
        String name="Serial "+UUID.randomUUID().toString().substring(0,8), renamed=name+" audio", title="Type test "+UUID.randomUUID();
        String[] key={null};
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            waitText("+ Add");
            onView(withContentDescription("Open navigation drawer")).perform(click());
            onView(withText("Media types")).perform(scrollTo(),click());
            onView(withText("+ New media type")).perform(scrollTo(),click());
            onView(withHint("Media type name")).perform(replaceText(name),closeSoftKeyboard());
            onView(withContentDescription("Progress unit")).perform(click());
            onData(equalTo("episodes")).inRoot(androidx.test.espresso.matcher.RootMatchers.isPlatformPopup()).perform(click());
            onView(withText("Save")).perform(click());
            await(() -> db.mediaTypeDao().findByName(name)!=null);
            key[0]=db.mediaTypeDao().findByName(name).key;
            onView(withContentDescription("Edit media type "+name)).perform(scrollTo(),click());
            onView(withHint("Media type name")).perform(replaceText(renamed),closeSoftKeyboard());
            onView(withText("Save")).perform(click());
            await(() -> db.mediaTypeDao().findByName(renamed)!=null);
            pressBack();
            onView(withText("+ Add")).perform(click());
            onView(withHint("Title")).perform(replaceText(title),closeSoftKeyboard());
            onView(withContentDescription("Media type")).perform(click());
            onData(equalTo(key[0])).inRoot(androidx.test.espresso.matcher.RootMatchers.isPlatformPopup()).perform(click());
            onView(withHint("Current episode")).perform(scrollTo(),replaceText("3.5"),closeSoftKeyboard());
            onView(allOf(withText("Add media"),isClickable())).perform(scrollTo(),click());
            waitText("Media details");
            onView(withContentDescription("Edit progress for "+title)).perform(scrollTo()).check(matches(withText("Episode 3.5")));
            pressBack();
            onView(withContentDescription("Open navigation drawer")).perform(click());
            onView(withText("Media types")).perform(scrollTo(),click());
            onView(withContentDescription("Actions for media type "+renamed)).perform(scrollTo(),click());
            onView(withText("Delete media type")).perform(click());
            onView(withText("Cancel")).perform(click());
            assertNotNull(db.mediaTypeDao().get(key[0]));
            onView(withContentDescription("Actions for media type "+renamed)).perform(scrollTo(),click());
            onView(withText("Delete media type")).perform(click());
            onView(withText("Move & delete")).perform(click());
            await(() -> db.mediaTypeDao().get(key[0])==null);
            for(MediaEntity m:db.mediaDao().getAll())if(m.title.equals(title)) {
                assertNotEquals(key[0],m.type);
                assertEquals(3.5,db.progressDao().get(m.id).currentProgress,0);
            }
        } finally {
            for(MediaEntity m:db.mediaDao().getAll())if(m.title.equals(title))db.mediaDao().delete(m);
            if(key[0]!=null)db.mediaTypeDao().delete(key[0]);
            MediaTypeEntity leftover=db.mediaTypeDao().findByName(name);
            if(leftover!=null)db.mediaTypeDao().delete(leftover.key);
        }
    }
}
