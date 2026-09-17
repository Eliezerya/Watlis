package com.watlis.app;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.watlis.app.data.*;
import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ShinigamiImportUiTest {
    private static final String IMPORT="Fill from Shinigami chapter link";
    private WatlisDatabase db(){return WatlisDatabase.get(InstrumentationRegistry.getInstrumentation().getTargetContext());}
    private void await(BooleanSupplier ready) throws Exception {
        long end=System.currentTimeMillis()+12000;
        while(System.currentTimeMillis()<end){if(ready.getAsBoolean())return;Thread.sleep(75);}
        assertTrue("Import UI did not become ready",ready.getAsBoolean());
    }
    private void waitText(String text) throws Exception {
        await(()->{try{onView(withText(text)).check(matches(isDisplayed()));return true;}
            catch(androidx.test.espresso.NoMatchingViewException|AssertionError error){return false;}});
    }
    private String manga(String title,String genre,String format) throws Exception {
        JSONObject value=new JSONObject(ShinigamiImporterInstrumentedTest.manga(title,genre));
        JSONObject data=value.getJSONObject("data");data.remove("cover_portrait_url");data.remove("cover_image_url");
        data.getJSONObject("taxonomy").put("Genre",new JSONArray().put(new JSONObject().put("name",genre)));
        data.getJSONObject("taxonomy").put("Format",new JSONArray().put(new JSONObject().put("name",format)));
        return value.toString();
    }

    @Test public void importIsOnlyDraftUntilSaveAndSurvivesRotation() throws Exception {
        String title="Import UI "+UUID.randomUUID(),genre="Genre "+UUID.randomUUID(),format="Format "+UUID.randomUUID();
        String response=manga(title,genre,format);
        int count=db().mediaDao().getAll().size();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            waitText("+ Add");onView(withText("+ Add")).perform(click());
            scenario.onActivity(activity->activity.chapterImporterFactory=()->new ShinigamiImporter(url->url.contains("chapter/detail")?ShinigamiImporterInstrumentedTest.chapter():response));
            onView(withHint("Personal notes")).perform(scrollTo(),replaceText("My own note"),closeSoftKeyboard());
            onView(withHint("Title")).perform(scrollTo(),replaceText("Ordinary title"),closeSoftKeyboard());
            onView(withContentDescription(IMPORT)).check(matches(not(isDisplayed())));
            onView(withHint("Title")).perform(replaceText(ShinigamiImporterInstrumentedTest.LINK),closeSoftKeyboard());
            onView(withContentDescription(IMPORT)).perform(click());waitText(title);
            assertEquals(count,db().mediaDao().getAll().size());assertNull(db().genreDao().findByName(genre));assertNull(db().mediaTypeDao().findByName(format));
            onView(withHint("Title")).check(matches(withText(title)));
            onView(withHint("Current chapter")).check(matches(withText("81.5")));
            onView(withHint("Personal notes")).check(matches(withText(allOf(containsString("My own note"),containsString("8.5/10"),containsString("Synopsis")))));
            onView(withText(genre+" (new)")).check(matches(isChecked()));
            scenario.recreate();waitText(title);
            onView(withText(genre+" (new)")).check(matches(isChecked()));
            onView(withHint("Current chapter")).check(matches(withText("81.5")));
            onView(allOf(withText("Add media"),isClickable())).perform(scrollTo(),click());
            await(()->db().mediaDao().getAll().stream().anyMatch(m->m.title.equals(title)));
            MediaEntity saved=db().mediaDao().getAll().stream().filter(m->m.title.equals(title)).findFirst().get();
            assertEquals(81.5,db().progressDao().get(saved.id).currentProgress,0);
            assertNull(db().progressDao().get(saved.id).rating);assertEquals("reading",db().progressDao().get(saved.id).trackingStatus);
            assertEquals(genre,db().genreDao().forMedia(saved.id).get(0).name);
            assertEquals(format,db().mediaTypeDao().get(saved.type).name);
            assertTrue(db().progressHistoryDao().forBackup(saved.id).isEmpty());
        } finally {
            for(MediaEntity media:db().mediaDao().getAll())if(media.title.equals(title))db().mediaDao().delete(media);
            GenreEntity added=db().genreDao().findByName(genre);if(added!=null)db().genreDao().delete(added.id);
            MediaTypeEntity addedType=db().mediaTypeDao().findByName(format);if(addedType!=null)db().mediaTypeDao().delete(addedType.key);
        }
    }

    @Test public void timeoutAndLateResultKeepUserEditsAndRotationCancelsLookup() throws Exception {
        String response=manga("Do not overwrite","Unused genre","Manhwa");
        int count=db().mediaDao().getAll().size();
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            waitText("+ Add");onView(withText("+ Add")).perform(click());
            scenario.onActivity(activity->activity.chapterImporterFactory=()->new ShinigamiImporter(url->{throw new java.net.SocketTimeoutException();}));
            onView(withHint("Title")).perform(replaceText(ShinigamiImporterInstrumentedTest.LINK),closeSoftKeyboard());
            onView(withContentDescription(IMPORT)).perform(click());
            waitText("The service timed out. Draft kept. Tap the link icon to retry.");
            onView(withHint("Title")).check(matches(withText(ShinigamiImporterInstrumentedTest.LINK)));
            scenario.onActivity(activity->activity.chapterImporterFactory=()->new ShinigamiImporter(url->{
                started.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new java.io.IOException("Test timeout");}
                catch(InterruptedException error){Thread.currentThread().interrupt();throw new java.io.IOException("Cancelled",error);}
                return url.contains("chapter/detail")?ShinigamiImporterInstrumentedTest.chapter():response;
            }));
            onView(withContentDescription(IMPORT)).perform(click());assertTrue(started.await(3,TimeUnit.SECONDS));
            onView(withHint("Title")).perform(replaceText("My manual title"),closeSoftKeyboard());release.countDown();
            waitText("Your draft changed during lookup. Tap the link icon again to import.");
            onView(withHint("Title")).check(matches(withText("My manual title")));
            CountDownLatch blocked=new CountDownLatch(1),requested=new CountDownLatch(1);
            scenario.onActivity(activity->activity.chapterImporterFactory=()->new ShinigamiImporter(url->{
                requested.countDown();try{blocked.await(10,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();throw new java.io.IOException("Cancelled");}
                return response;
            }));
            onView(withHint("Title")).perform(replaceText(ShinigamiImporterInstrumentedTest.LINK),closeSoftKeyboard());
            onView(withContentDescription(IMPORT)).perform(click());assertTrue(requested.await(3,TimeUnit.SECONDS));
            scenario.recreate();
            onView(withHint("Title")).check(matches(withText(ShinigamiImporterInstrumentedTest.LINK)));
            onView(withContentDescription(IMPORT)).check(matches(isDisplayed()));
            assertEquals(count,db().mediaDao().getAll().size());assertNull(db().genreDao().findByName("Unused genre"));
        } finally {release.countDown();}
    }

    @Test public void liveExampleFillsNewMediaWithoutSaving() throws Exception {
        org.junit.Assume.assumeTrue("Opt-in live network smoke test", "true".equals(InstrumentationRegistry.getArguments().getString("liveShinigami")));
        int count=db().mediaDao().getAll().size();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            waitText("+ Add");onView(withText("+ Add")).perform(click());
            onView(withHint("Title")).perform(replaceText(ShinigamiImporterInstrumentedTest.LINK),closeSoftKeyboard());
            onView(withContentDescription(IMPORT)).perform(click());
            waitText("Star-Embracing Swordmaster");
            onView(withHint("Current chapter")).check(matches(withText("81")));
            onView(withHint("Image URL")).check(matches(withText(startsWith("https://assets.shngm.id/"))));
            onView(withHint("Personal notes")).check(matches(withText(allOf(containsString("Vlad"),containsString("8.5/10")))));
            assertEquals(count,db().mediaDao().getAll().size());
            android.graphics.Bitmap shot=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            if(shot!=null) {
                java.io.File file=new java.io.File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null),"chapter-import-preview.png");
                try(java.io.OutputStream out=new java.io.FileOutputStream(file)){shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
                finally {shot.recycle();}
            }
        }
    }
}
