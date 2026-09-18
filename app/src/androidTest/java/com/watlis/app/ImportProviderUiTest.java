package com.watlis.app;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.function.BooleanSupplier;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ImportProviderUiTest {
    private ImportProviderStore store(){ImportProviderStore store=new ImportProviderStore(InstrumentationRegistry.getInstrumentation().getTargetContext());store.load();return store;}
    private void await(BooleanSupplier ready) throws Exception {
        long end=System.currentTimeMillis()+12000;
        while(System.currentTimeMillis()<end){if(ready.getAsBoolean())return;Thread.sleep(75);}assertTrue("Provider UI timeout",ready.getAsBoolean());
    }
    private void waitText(String text) throws Exception {
        await(()->{try{onView(allOf(withText(text),isDisplayed())).check(matches(isDisplayed()));return true;}
            catch(androidx.test.espresso.NoMatchingViewException|AssertionError error){return false;}});
    }
    private void set(String hint,String value){onView(withHint(hint)).perform(scrollTo(),replaceText(value),closeSoftKeyboard());}
    private void settings() {
        onView(withContentDescription("Open navigation drawer")).perform(click());
        onView(allOf(withText("Settings"),isClickable())).perform(scrollTo(),click());
    }
    private void cleanup(String name){ImportProviderStore s=store();for(ImportProvider p:s.all())if(p.name.equals(name))s.delete(p.id);}
    private void waitProvider(String name) throws Exception {
        await(()->store().all().stream().anyMatch(p->p.name.equals(name)));
        await(()->{try{onView(withContentDescription("Edit provider "+name)).check(matches(isDisplayed()));return true;}
            catch(androidx.test.espresso.NoMatchingViewException|AssertionError error){return false;}});
    }

    @Test public void addPreviewRotateEditDisableEnableImportAndDeleteProvider() throws Exception {
        String suffix=UUID.randomUUID().toString(),name="Provider "+suffix,host="reader-"+suffix+".example.test";
        String link="https://"+host+"/read/"+ShinigamiImporterInstrumentedTest.CHAPTER;
        List<String> requests=Collections.synchronizedList(new ArrayList<>());
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            waitText("+ Add");settings();onView(withText("+ Add provider")).perform(click());
            set("Provider name",name);set("Website hosts",host);set("API base URL","https://api.example.test/v2/");
            set("Chapter link path","/read/{id}");set("Chapter endpoint path","chapters/{id}/metadata");set("Manga endpoint path","series/{id}");
            set("Cover image hosts","");set("Test chapter URL (optional)",link);
            onView(withText("Preview requests")).perform(scrollTo(),click());
            onView(withText(containsString("GET https://api.example.test/v2/chapters/"))).check(matches(isDisplayed()));
            onView(withText("Close")).perform(click());
            scenario.recreate();
            onView(withHint("Provider name")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(closeSoftKeyboard()).check(matches(withText(name)));
            onView(withHint("Website hosts")).check(matches(withText(host)));
            onView(withHint("Manga endpoint path")).check(matches(withText("series/{id}")));
            android.graphics.Bitmap editorShot=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            if(editorShot!=null){try(java.io.OutputStream output=new java.io.FileOutputStream(new java.io.File(
                    InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null),"provider-editor-preview.png"))){editorShot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);}finally{editorShot.recycle();}}
            onView(withText("Save provider")).perform(click());
            waitProvider(name);
            ImportProvider saved=store().all().stream().filter(p->p.name.equals(name)).findFirst().get();
            scenario.recreate();waitProvider(name);onView(withContentDescription("Edit provider "+name)).perform(scrollTo(),click());
            set("API base URL","https://moved-api.example.test/v3/");onView(withText("Save provider")).perform(click());waitProvider(name);
            onView(withContentDescription("Provider actions for "+name)).perform(scrollTo(),click());onView(withText("Disable provider")).perform(click());
            await(()->store().matching(link)==null);
            onView(withContentDescription("Open navigation drawer")).perform(click());onView(withText(startsWith("My media"))).perform(click());
            waitText("+ Add");onView(withText("+ Add")).perform(click());set("Title",link);
            onView(withContentDescription("Fill from chapter link")).check(matches(not(isDisplayed())));
            onView(withContentDescription("Back")).perform(click());onView(withText("Discard")).perform(click());waitText("+ Add");settings();
            onView(withContentDescription("Provider actions for "+name)).perform(scrollTo(),click());onView(withText("Enable provider")).perform(click());
            await(()->store().matching(link)!=null);
            onView(withContentDescription("Open navigation drawer")).perform(click());onView(withText(startsWith("My media"))).perform(click());
            waitText("+ Add");onView(withText("+ Add")).perform(click());
            scenario.onActivity(a->a.chapterImporterFactory=()->new ShinigamiImporter(url->{requests.add(url);return requests.size()==1?
                    ShinigamiImporterInstrumentedTest.chapter():ShinigamiImporterInstrumentedTest.manga("Configured import result",null);}));
            set("Title",link);onView(withContentDescription("Fill from chapter link")).perform(click());waitText("Configured import result");
            assertEquals(Arrays.asList("https://moved-api.example.test/v3/chapters/"+ShinigamiImporterInstrumentedTest.CHAPTER+"/metadata",
                    "https://moved-api.example.test/v3/series/"+ShinigamiImporterInstrumentedTest.MANGA),requests);
            onView(withHint("Current chapter")).check(matches(withText("81.5")));onView(withHint("Image URL")).check(matches(withText("")));
            onView(withContentDescription("Back")).perform(click());onView(withText("Discard")).perform(click());waitText("+ Add");settings();
            onView(withContentDescription("Provider actions for "+name)).perform(scrollTo(),click());onView(withText("Delete provider")).perform(click());
            onView(withText("Delete")).perform(click());await(()->store().all().stream().noneMatch(p->p.id.equals(saved.id)));
        } finally {cleanup(name);}
    }

    @Test public void invalidSettingsStayUnsavedAndDiscardPreservesConfiguration() throws Exception {
        String name="Validation "+UUID.randomUUID();int initial=store().all().size();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            waitText("+ Add");settings();onView(withText("+ Add provider")).perform(click());
            set("Provider name",name);set("Website hosts","reader.example.test");set("API base URL","http://api.example.test/");
            onView(withText("Save provider")).perform(click());
            assertEquals(initial,store().all().size());
            onView(withText("API base must be an HTTPS URL without credentials, query, fragment or dot segments.")).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withText("Cancel")).perform(click());onView(withText("Discard")).perform(click());
            waitText("+ Add provider");assertEquals(initial,store().all().size());
            // Capture the final settings screen without changing the user's configured providers.
            android.graphics.Bitmap image=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            if(image!=null){try(java.io.OutputStream output=new java.io.FileOutputStream(new java.io.File(
                    InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null),"provider-settings-preview.png"))){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);}finally{image.recycle();}}
        } finally {cleanup(name);}
    }

}
