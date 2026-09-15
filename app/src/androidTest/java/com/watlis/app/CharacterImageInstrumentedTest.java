package com.watlis.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.room.Room;
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
public class CharacterImageInstrumentedTest {
    private void await(BooleanSupplier condition) throws Exception {
        long until=System.currentTimeMillis()+15000;
        while(System.currentTimeMillis()<until){if(condition.getAsBoolean())return;Thread.sleep(75);}
        assertTrue("Timed out waiting for character image",condition.getAsBoolean());
    }
    private void waitText(String text) throws Exception {
        // Wait for the external picker to yield focus before asking Espresso to select an app root.
        android.app.UiAutomation ui=InstrumentationRegistry.getInstrumentation().getUiAutomation();
        await(() -> findNode(ui.getRootInActiveWindow(),text)!=null);
        androidx.test.espresso.ViewInteraction target=onView(withText(text));
        if (!text.equals("+ Add")) target=target.inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog());
        target.check(matches(isDisplayed()));
    }
    private AccessibilityNodeInfo findNode(AccessibilityNodeInfo node,String text) {
        if(node==null)return null;
        if(text.contentEquals(node.getText()==null?"":node.getText()) ||
                (node.getContentDescription()!=null && (text.contentEquals(node.getContentDescription()) ||
                        node.getContentDescription().toString().startsWith(text+", "))))return node;
        // Prefer the drawer (drawn last), not the identically named toolbar behind it.
        for(int i=node.getChildCount()-1;i>=0;i--) {
            AccessibilityNodeInfo found=findNode(node.getChild(i),text);if(found!=null)return found;
        }
        return null;
    }
    private void pickDocument(String name) throws Exception {
        clickSystemNode("Show roots");
        clickSystemNode("Recent");
        clickSystemNode(name);
    }
    private void clickSystemNode(String name) throws Exception {
        android.app.UiAutomation ui=InstrumentationRegistry.getInstrumentation().getUiAutomation();
        await(() -> findNode(ui.getRootInActiveWindow(),name)!=null);
        ui.waitForIdle(200,5000);
        AccessibilityNodeInfo node=findNode(ui.getRootInActiveWindow(),name);
        assertNotNull(node);
        android.graphics.Rect bounds=new android.graphics.Rect();node.getBoundsInScreen(bounds);
        // The external picker handles pointer timing itself; use a real shell tap at the matched node.
        try(android.os.ParcelFileDescriptor tap=ui.executeShellCommand("input tap "+bounds.centerX()+" "+bounds.centerY());
            java.io.FileInputStream output=new java.io.FileInputStream(tap.getFileDescriptor())) {
            output.readAllBytes();
        }
        ui.waitForIdle(300,5000);
    }

    @Test public void chooseKeepRestorePreviewFallbackBackupAndRemoveCharacterImage() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        WatlisDatabase db=WatlisDatabase.get(context);
        WatlisRepository repo=new WatlisRepository(db);
        String suffix=UUID.randomUUID().toString();
        String fileName="watlis-character-"+suffix+".png";
        android.content.ContentValues values=new android.content.ContentValues();
        values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,fileName);
        values.put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/png");
        values.put(android.provider.MediaStore.Images.Media.IS_PENDING,1);
        Uri uri=context.getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
        assertNotNull(uri);
        MediaEntity m=new MediaEntity();m.title="Character image test "+suffix;
        String[] chosen={null};boolean[] deleted={false};
        try {
            Bitmap bitmap=Bitmap.createBitmap(900,1800,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.RED);
            try(java.io.OutputStream stream=context.getContentResolver().openOutputStream(uri)) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,stream));
            } finally {bitmap.recycle();}
            values.clear();values.put(android.provider.MediaStore.Images.Media.IS_PENDING,0);
            context.getContentResolver().update(uri,values,null,null);
            repo.saveMedia(m,new UserProgressEntity(),Collections.emptyList());
            try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
                waitText("+ Add");
                onView(withContentDescription("Search your titles")).perform(replaceText(m.title),closeSoftKeyboard());
                onView(withContentDescription("Cover for "+m.title)).perform(click());
                onView(withText("+ Add character")).perform(scrollTo(),click());
                onView(withHint("Name")).perform(scrollTo(),replaceText("Photo guardian"),closeSoftKeyboard());
                onView(withHint("Description")).perform(scrollTo(),replaceText("A remembered friend"),closeSoftKeyboard());
                onView(withText("Add character image")).perform(scrollTo(),click());
                pickDocument(fileName); // Real system picker and persistable URI permission.
                waitText("Change character image");
                scenario.recreate();waitText("Change character image");
                onView(withHint("Name")).check(matches(withText("Photo guardian")));
                onView(withHint("Description")).check(matches(withText("A remembered friend")));
                onView(withText("Cancel")).perform(click());onView(withText("Keep")).perform(click());
                await(() -> db.storyDao().characters(m.id).size()==1);
                CharacterEntity c=db.storyDao().characters(m.id).get(0);chosen[0]=c.image;
                assertNotNull(c.image);assertTrue(CoverStore.get(context).fileFor(c.image).isFile());
                onView(withContentDescription("Preview character image for Photo guardian")).perform(scrollTo(),click());
                waitText("Pinch to zoom · Double-tap to fit");
                onView(withContentDescription("Full character image")).check((view,error) -> {
                    if(error!=null)throw error;
                    assertTrue(((CoverPreviewView)view).getDrawable().getIntrinsicHeight()>768);
                });
                onView(withText("Zoom in")).perform(click());onView(withText("Fit image")).perform(click());
                onView(withContentDescription("Close character image preview")).perform(click());
                context.getContentResolver().delete(uri,null,null);deleted[0]=true;
                scenario.recreate();
                await(() -> {
                    try {onView(withContentDescription("Preview character image for Photo guardian")).perform(scrollTo(),click());return true;}
                    catch(androidx.test.espresso.NoMatchingViewException e){return false;}
                });
                waitText("Original unavailable · Saved character preview");
                onView(withContentDescription("Close character image preview")).perform(click());
                // Round trip only an isolated database; never import over the user's collection.
                WatlisDatabase isolated=Room.inMemoryDatabaseBuilder(context,WatlisDatabase.class).build();
                try {
                    WatlisRepository backups=new WatlisRepository(isolated,CoverStore.get(context));
                    MediaEntity fixture=new MediaEntity();fixture.title="Backup fixture";
                    backups.saveMedia(fixture,new UserProgressEntity(),Collections.emptyList());
                    CharacterEntity character=new CharacterEntity();character.mediaId=fixture.id;character.name=c.name;character.image=c.image;
                    backups.saveCharacter(character);
                    org.json.JSONObject json=new org.json.JSONObject(backups.exportToJson());
                    org.json.JSONObject item=json.getJSONArray("media").getJSONObject(0).getJSONArray("characters").getJSONObject(0);
                    assertFalse(item.getString("imageThumbnail").isEmpty());
                    String restoredSource="content://watlis-test/restored/"+suffix;
                    item.put("image",restoredSource);
                    try {
                        backups.importFromJson(json.toString());
                        assertEquals(restoredSource,backups.characters(fixture.id).get(0).image);
                        assertTrue(CoverStore.get(context).fileFor(restoredSource).isFile());
                    } finally {CoverStore.get(context).fileFor(restoredSource).delete();}
                } finally {isolated.close();}
                onView(withContentDescription("Character options for Photo guardian")).perform(scrollTo(),click());
                onView(withText("Edit")).perform(click());
                onView(withText("Remove image")).perform(scrollTo(),click());
                pressBack();onView(withText("Discard")).perform(click());
                assertEquals(chosen[0],db.storyDao().characters(m.id).get(0).image);
                onView(withContentDescription("Character options for Photo guardian")).perform(scrollTo(),click());
                onView(withText("Edit")).perform(click());
                onView(withText("Remove image")).perform(scrollTo(),click());
                onView(withText("Cancel")).perform(click());onView(withText("Keep")).perform(click());
                await(() -> db.storyDao().characters(m.id).get(0).image==null);
                assertEquals("A remembered friend",db.storyDao().characters(m.id).get(0).description);
            }
        } finally {
            if(m.id!=0)db.mediaDao().delete(m);
            if(!deleted[0])context.getContentResolver().delete(uri,null,null);
            if(chosen[0]!=null) {
                CoverStore.get(context).fileFor(chosen[0]).delete();
                try {context.getContentResolver().releasePersistableUriPermission(Uri.parse(chosen[0]),android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);}
                catch(SecurityException ignored) {}
            }
        }
    }
}
