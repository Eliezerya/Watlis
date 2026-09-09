package com.watlis.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;
import android.widget.SeekBar;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.watlis.app.data.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Collections;
import java.util.UUID;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;
import static org.hamcrest.Matchers.*;

@RunWith(AndroidJUnit4.class)
public class CoverInstrumentedTest {
    @Test public void matrixCropMovesOnlyTheVisibleRegionAndPreviewFitsWholeImage() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
            Bitmap bitmap=Bitmap.createBitmap(100,300,Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.BLUE);
            BitmapDrawable drawable=new BitmapDrawable(context.getResources(),bitmap);
            drawable.setTargetDensity(bitmap.getDensity());
            PositionedCoverView crop=new PositionedCoverView(context);
            crop.setImageDrawable(drawable);crop.layout(0,0,100,144);
            RectF top=new RectF(0,0,100,300);
            crop.setCoverPosition(0.5f,0);crop.getImageMatrix().mapRect(top);
            assertEquals(0,top.top,0.01f);
            RectF bottom=new RectF(0,0,100,300);
            crop.setCoverPosition(0.5f,1);crop.getImageMatrix().mapRect(bottom);
            assertEquals(144,bottom.bottom,0.01f);
            crop.setCoverZoom(2);
            RectF zoomed=new RectF(0,0,100,300);crop.getImageMatrix().mapRect(zoomed);
            assertEquals(200,zoomed.width(),0.01f);
            assertEquals(144,zoomed.bottom,0.01f);
            crop.setCoverZoom(Float.NaN);assertEquals(1,crop.getCoverZoom(),0);
            crop.setCoverZoom(10);assertEquals(3,crop.getCoverZoom(),0);
            assertSame(drawable,crop.getDrawable());
            CoverPreviewView full=new CoverPreviewView(context);
            full.setImageDrawable(drawable);full.layout(0,0,300,300);
            RectF bounds=new RectF(0,0,100,300);full.getImageMatrix().mapRect(bounds);
            assertTrue(bounds.left>=0&&bounds.top>=0&&bounds.right<=300&&bounds.bottom<=300);
            full.zoomIn();assertTrue(full.getZoom()>1);
            full.resetZoom();assertEquals(1,full.getZoom(),0);
            full.setImageDrawable(null);crop.setImageDrawable(null);
            bitmap.recycle();
        });
    }

    @Test public void positionDraftRestoresAndDetailOpensUncroppedPreview() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        WatlisDatabase db=WatlisDatabase.get(context);
        WatlisRepository repo=new WatlisRepository(db);
        // A generated, local test image; removed along with this test's title.
        android.content.ContentValues values=new android.content.ContentValues();
        values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,"watlis-cover-test-"+UUID.randomUUID()+".png");
        values.put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/png");
        values.put(android.provider.MediaStore.Images.Media.IS_PENDING,1);
        android.net.Uri uri=context.getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
        assertNotNull(uri);
        MediaEntity m=new MediaEntity();m.title="Cover test "+UUID.randomUUID();m.coverImage=uri.toString();
        boolean sourceDeleted=false;
        try {
            Bitmap bitmap=Bitmap.createBitmap(800,2400,Bitmap.Config.ARGB_8888);
            Canvas canvas=new Canvas(bitmap);Paint paint=new Paint();
            paint.setColor(Color.RED);canvas.drawRect(0,0,800,800,paint);
            paint.setColor(Color.GREEN);canvas.drawRect(0,800,800,1600,paint);
            paint.setColor(Color.BLUE);canvas.drawRect(0,1600,800,2400,paint);
            try(java.io.OutputStream stream=context.getContentResolver().openOutputStream(uri)){bitmap.compress(Bitmap.CompressFormat.PNG,100,stream);}
            bitmap.recycle();values.clear();values.put(android.provider.MediaStore.Images.Media.IS_PENDING,0);
            context.getContentResolver().update(uri,values,null,null);
            repo.saveMedia(m,new UserProgressEntity(),Collections.emptyList());
            try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
                waitForText("+ Add");
                onView(withContentDescription("Search your titles")).perform(replaceText(m.title),closeSoftKeyboard());
                onView(allOf(withText(m.title),not(isAssignableFrom(android.widget.EditText.class)))).perform(click());
                onView(withContentDescription("Preview cover for "+m.title)).perform(click());
                waitForText("Pinch to zoom · Double-tap to fit");
                onView(withContentDescription("Full cover image")).check(matches(isDisplayed()));
                onView(withContentDescription("Full cover image")).check((view,error) -> {
                    if(error!=null)throw error;
                    assertTrue("Full preview uses the original resolution",((CoverPreviewView)view).getDrawable().getIntrinsicHeight()>768);
                });
                onView(withText("Zoom in")).perform(click());
                onView(withText("Fit image")).perform(click());
                onView(withContentDescription("Close cover preview")).perform(click());
                onView(withText("Edit media")).perform(scrollTo(),click());
                onView(withText("Adjust cover position")).perform(scrollTo(),click());
                onView(withContentDescription("Vertical cover position")).perform(setPosition(90));
                onView(withContentDescription("Cover zoom")).perform(scrollTo(),setPosition(75));
                onView(withText("Zoom · 175%")).perform(scrollTo()).check(matches(isDisplayed()));
                onView(withText("Use position")).perform(scrollTo(),click());
                scenario.recreate();
                waitForText("Edit media");
                onView(withText("Position · 50% across · 90% down")).perform(scrollTo()).check(matches(isDisplayed()));
                onView(withText("Save changes")).perform(scrollTo(),click());
                long until=System.currentTimeMillis()+10000;
                while(db.mediaDao().getById(m.id).coverPositionY!=0.9f&&System.currentTimeMillis()<until)Thread.sleep(50);
                assertEquals(0.9f,db.mediaDao().getById(m.id).coverPositionY,0);
                assertEquals(1.75f,db.mediaDao().getById(m.id).coverZoom,0);
                pressBack();
                onView(withContentDescription("Cover for "+m.title)).check((view,error) -> {
                    if(error!=null)throw error;
                    assertEquals(0.9f,((PositionedCoverView)view).getCoverPositionY(),0);
                    assertEquals(1.75f,((PositionedCoverView)view).getCoverZoom(),0);
                });
                java.io.File thumbnail=CoverStore.get(context).fileFor(m.coverImage);
                assertTrue(thumbnail.isFile());
                android.graphics.BitmapFactory.Options bounds=new android.graphics.BitmapFactory.Options();
                bounds.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeFile(thumbnail.getPath(),bounds);
                assertTrue(bounds.outWidth<=768&&bounds.outHeight<=768);
                assertTrue(thumbnail.length()<1024*1024);
                // Delete only our generated source, then verify a fresh screen and preview retain the saved cover.
                context.getContentResolver().delete(uri,null,null);
                sourceDeleted=true;
                scenario.recreate();waitForText("+ Add");
                onView(withContentDescription("Cover for "+m.title)).perform(click());
                onView(withContentDescription("Preview cover for "+m.title)).perform(click());
                waitForText("Original unavailable · Saved cover preview");
                onView(withContentDescription("Full cover image")).check((view,error) -> {
                    if(error!=null)throw error;
                    assertTrue(((CoverPreviewView)view).getDrawable() instanceof BitmapDrawable);
                });
                onView(withContentDescription("Close cover preview")).perform(click());
                String encoded=CoverStore.get(context).exportThumbnail(m.coverImage);
                assertNotNull(encoded);
                assertTrue(thumbnail.delete());
                CoverStore.get(context).importThumbnail(m.coverImage,encoded);
                assertTrue(thumbnail.isFile());
            }
        } finally {
            if(m.id!=0)db.mediaDao().delete(m);
            if(!sourceDeleted)context.getContentResolver().delete(uri,null,null);
            CoverStore.get(context).fileFor(m.coverImage).delete();
        }
    }

    private void waitForText(String text) throws Exception {
        long until=System.currentTimeMillis()+10000;
        while(System.currentTimeMillis()<until) {
            try {onView(withText(text)).check(matches(isDisplayed()));return;}
            catch(androidx.test.espresso.NoMatchingViewException|AssertionError e){Thread.sleep(50);}
        }
        onView(withText(text)).check(matches(isDisplayed()));
    }

    private androidx.test.espresso.ViewAction setPosition(int value) {
        return new androidx.test.espresso.ViewAction() {
            public org.hamcrest.Matcher<View> getConstraints(){return isAssignableFrom(SeekBar.class);}
            public String getDescription(){return "Set cover position";}
            public void perform(androidx.test.espresso.UiController ui,View view){((SeekBar)view).setProgress(value);ui.loopMainThreadUntilIdle();}
        };
    }
}
