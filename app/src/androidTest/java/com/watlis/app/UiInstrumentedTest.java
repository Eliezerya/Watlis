package com.watlis.app;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.watlis.app.data.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.function.BooleanSupplier;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class UiInstrumentedTest {
    private WatlisDatabase db(){return WatlisDatabase.get(InstrumentationRegistry.getInstrumentation().getTargetContext());}
    private void await(BooleanSupplier condition) throws Exception {
        long until=System.currentTimeMillis()+10000;
        while(System.currentTimeMillis()<until){if(condition.getAsBoolean())return;Thread.sleep(50);}
        assertTrue("Timed out waiting for persisted change",condition.getAsBoolean());
    }
    private MediaEntity find(String title){for(MediaEntity m:db().mediaDao().getAll())if(m.title.equals(title))return m;return null;}
    private void waitHome() throws Exception {
        // Initial Room load completes asynchronously; Espresso waits for the view below.
        long until=System.currentTimeMillis()+10000;
        while(System.currentTimeMillis()<until) {
            try{onView(withText("+ Add")).check(matches(isDisplayed()));return;}
            catch(androidx.test.espresso.NoMatchingViewException e){Thread.sleep(50);}
        }
        onView(withText("+ Add")).check(matches(isDisplayed()));
    }
    @Test public void formRotationProgressStoryAndDrawerPersist() throws Exception {
        String title="Watlis UI test "+UUID.randomUUID();
        long[] id={0};
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            waitHome();
            onView(withText("+ Add")).perform(click());
            onView(withHint("Title")).perform(replaceText(title),closeSoftKeyboard());
            onView(withHint("Current chapter")).perform(scrollTo(),replaceText("12.5"),closeSoftKeyboard());
            scenario.recreate();
            long until=System.currentTimeMillis()+10000;
            while(System.currentTimeMillis()<until) {
                try{onView(withHint("Title")).check(matches(withText(title)));break;}
                catch(androidx.test.espresso.NoMatchingViewException e){Thread.sleep(50);}
            }
            onView(withHint("Title")).check(matches(withText(title)));
            onView(withHint("Current chapter")).check(matches(withText("12.5")));
            onView(allOf(withText("Add media"),isClickable())).perform(scrollTo(),click());
            await(() -> find(title)!=null);
            id[0]=find(title).id;
            onView(withContentDescription("Increase progress")).perform(scrollTo(),click(),click(),click());
            await(() -> db().progressDao().get(id[0]).currentProgress==15.5);
            onView(withText("+ Add story reminder")).perform(scrollTo(),click());
            onView(withHint("Story reminder")).perform(replaceText("The journey reached the northern gate."),closeSoftKeyboard());
            onView(withText("Save")).perform(click());
            await(() -> db().storyDao().get(id[0])!=null);
            assertEquals("The journey reached the northern gate.",db().storyDao().get(id[0]).storySummary);
            pressBack();
            onView(withContentDescription("Search your titles")).perform(replaceText(title),closeSoftKeyboard());
            onView(withText("Chapter 15.5")).check(matches(isDisplayed()));
            onView(withContentDescription("Increase progress")).perform(click(),click());
            await(() -> db().progressDao().get(id[0]).currentProgress==17.5);
            onView(withContentDescription("Open navigation drawer")).perform(click());
            onView(withText("Statistics")).perform(click());
            onView(withText("Total media")).check(matches(isDisplayed()));
        } finally {
            MediaEntity created=find(title);if(created!=null)db().mediaDao().delete(created);
        }
    }
    @Test public void detailReminderHierarchyCompactEditingAndExpansionWork() throws Exception {
        String title="Watlis detail test "+UUID.randomUUID();
        WatlisRepository repo=new WatlisRepository(db());
        MediaEntity m=new MediaEntity();m.title=title;
        repo.saveMedia(m,new UserProgressEntity(),Collections.emptyList());
        StoryMemoryEntity story=new StoryMemoryEntity();story.mediaId=m.id;story.mainCharacterName="Test reader";
        story.storySummary="First reminder line\nSecond reminder line\nThird reminder line\nFourth reminder line\nFifth reminder line\nFinal reminder line";
        repo.saveStory(story);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            waitHome();
            onView(withContentDescription("Search your titles")).perform(replaceText(title),closeSoftKeyboard());
            onView(withContentDescription("Cover for "+title)).perform(click());
            onView(withContentDescription("Edit main character")).perform(scrollTo()).check((view,error) -> {
                if(error!=null)throw error;
                float density=view.getResources().getDisplayMetrics().density;
                assertTrue(view instanceof android.widget.ImageButton);
                assertTrue("Compact icon keeps a 48dp tap target",view.getWidth()>=48*density-1);
                assertTrue("Pencil artwork stays compact",view.getWidth()-view.getPaddingLeft()-view.getPaddingRight()<=19*density);
            }).perform(click());
            onView(withHint("Main character")).perform(replaceText("Updated reader"),closeSoftKeyboard());
            onView(withText("Save")).perform(click());
            await(() -> "Updated reader".equals(db().storyDao().get(m.id).mainCharacterName));
            onView(withText("Updated reader")).perform(scrollTo()).check((view,error) -> {
                if(error!=null)throw error;
                android.widget.TextView body=(android.widget.TextView)view;
                android.view.ViewGroup column=(android.view.ViewGroup)body.getParent();
                android.widget.TextView label=(android.widget.TextView)column.getChildAt(0);
                assertTrue(body.getTextSize()>label.getTextSize());
                assertNotEquals(body.getCurrentTextColor(),label.getCurrentTextColor());
            });
            onView(withContentDescription("Show more story reminder")).perform(scrollTo(),click());
            onView(withText(story.storySummary)).check((view,error) -> {
                if(error!=null)throw error;
                assertEquals(Integer.MAX_VALUE,((android.widget.TextView)view).getMaxLines());
            });
            onView(withContentDescription("Show less story reminder")).perform(scrollTo(),click());
            onView(withText(story.storySummary)).check((view,error) -> {
                if(error!=null)throw error;
                assertEquals(4,((android.widget.TextView)view).getMaxLines());
            });
            assertEquals(story.storySummary,db().storyDao().get(m.id).storySummary);
        } finally {db().mediaDao().delete(m);}
    }
    @Test public void invalidProgressKeepsDialogOpenAndDraftBackPrompts() throws Exception {
        String title="Watlis validation "+UUID.randomUUID();
        WatlisRepository repo=new WatlisRepository(db());
        MediaEntity m=new MediaEntity();m.title=title;
        UserProgressEntity p=new UserProgressEntity();p.currentProgress=7.5;
        repo.saveMedia(m,p,Collections.emptyList());
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            waitHome();
            onView(withContentDescription("Search your titles")).perform(replaceText(title),closeSoftKeyboard());
            onView(withText("Chapter 7.5")).perform(click());
            onView(withHint("Chapter")).perform(replaceText(""),closeSoftKeyboard());
            onView(withText("Update")).perform(click());
            onView(withText("Update progress")).check(matches(isDisplayed()));
            assertEquals(7.5,db().progressDao().get(m.id).currentProgress,0);
            onView(withText("Cancel")).perform(click());
            onView(withText("+ Add")).perform(click());
            onView(withHint("Title")).perform(replaceText("Unsaved"),closeSoftKeyboard());
            pressBack();
            onView(withText("Discard changes?")).check(matches(isDisplayed()));
            onView(withText("Keep editing")).perform(click());
            onView(withHint("Title")).check(matches(withText("Unsaved")));
            pressBack();onView(withText("Discard")).perform(click());
            onView(withText("My media")).check(matches(isDisplayed()));
        } finally {db().mediaDao().delete(m);}
    }

    @Test public void detailGenreOpensMatchingListAndClearsPreviousSearchAndFavorites() throws Exception {
        String prefix="Genre navigation "+UUID.randomUUID();
        WatlisRepository repo=new WatlisRepository(db());
        GenreEntity genre=new GenreEntity();genre.name=prefix;genre.id=repo.addGenre(genre);
        List<MediaEntity> fixtures=new ArrayList<>();
        try {
            for(int i=0;i<3;i++) {
                MediaEntity m=new MediaEntity();m.title=prefix+" "+i;m.isFavorite=i==0;
                repo.saveMedia(m,new UserProgressEntity(),i<2?Collections.singletonList(genre.id):Collections.emptyList());
                fixtures.add(m);
            }
            try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
                waitHome();
                onView(withContentDescription("Open navigation drawer")).perform(click());
                onView(withText(startsWith("Favorites  ·"))).perform(scrollTo(),click());
                onView(withContentDescription("Search your titles")).perform(replaceText(fixtures.get(0).title),closeSoftKeyboard());
                onView(withContentDescription("Cover for "+fixtures.get(0).title)).perform(click());
                onView(withContentDescription("Show titles in "+genre.name)).perform(scrollTo(),click());
                onView(withText("My media")).check(matches(isDisplayed()));
                onView(withContentDescription("Search your titles")).check(matches(withText("")));
                onView(withText("Filter · 1")).check(matches(isDisplayed()));
                onView(withText("2 titles")).check(matches(isDisplayed()));
                onView(withContentDescription("Cover for "+fixtures.get(1).title)).perform(scrollTo()).check(matches(isDisplayed()));
                onView(withContentDescription("Cover for "+fixtures.get(2).title)).check(doesNotExist());
                scenario.recreate();
                waitHome();
                onView(withText("Filter · 1")).check(matches(isDisplayed()));
                onView(withText("2 titles")).check(matches(isDisplayed()));
            }
        } finally {
            for(MediaEntity m:fixtures)db().mediaDao().delete(m);
            db().genreDao().delete(genre.id);
        }
    }
    @Test public void listGenresAndOverflowBadgeAreNotClippedAndCoverAlignsWithIdentity() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,6);
        WatlisRepository repo=new WatlisRepository(db());
        MediaEntity m=new MediaEntity();m.title="Layout check "+suffix;
        List<Long> genreIds=new ArrayList<>();
        try {
            for(String name:new String[]{"Action ","Adventure ","Drama ","School "}) {
                GenreEntity g=new GenreEntity();g.name=name+suffix;g.id=repo.addGenre(g);genreIds.add(g.id);
            }
            repo.saveMedia(m,new UserProgressEntity(),genreIds);
            try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
                waitHome();
                onView(withContentDescription("Search your titles")).perform(replaceText(m.title),closeSoftKeyboard());
                onView(withContentDescription("Genres for "+m.title)).perform(scrollTo()).check((view,error) -> {
                    if(error!=null)throw error;
                    android.view.ViewGroup group=(android.view.ViewGroup)view;
                    assertEquals(3,group.getChildCount());
                    for(int i=0;i<group.getChildCount();i++) {
                        android.widget.TextView tag=(android.widget.TextView)group.getChildAt(i);
                        assertTrue("Genre must stay inside its row",tag.getBottom()<=group.getHeight());
                        assertTrue(tag.getRight()<=group.getWidth());
                        assertEquals(group.getChildAt(0).getHeight(),tag.getHeight());
                        assertTrue(tag.getHeight()>=tag.getLineHeight()+tag.getPaddingTop()+tag.getPaddingBottom());
                    }
                });
                onView(withText("+2")).check(matches(isDisplayed()));
                onView(withContentDescription("Cover for "+m.title)).check((view,error) -> {
                    if(error!=null)throw error;
                    android.view.ViewGroup upper=(android.view.ViewGroup)view.getParent();
                    android.view.View identity=upper.getChildAt(1);
                    assertEquals(view.getTop()+view.getHeight()/2f,identity.getTop()+identity.getHeight()/2f,1);
                });
            }
        } finally {
            if(m.id!=0)db().mediaDao().delete(m);
            for(Long id:genreIds)db().genreDao().delete(id);
        }
    }
    @Test public void filtersCombineGroupsAndCancelDiscardsDraft() throws Exception {
        String prefix="Watlis filters "+UUID.randomUUID();
        WatlisRepository repo=new WatlisRepository(db());
        List<MediaEntity> fixtures=new ArrayList<>();
        GenreEntity first=new GenreEntity();first.name=prefix+" fantasy";first.id=repo.addGenre(first);
        GenreEntity second=new GenreEntity();second.name=prefix+" action";second.id=repo.addGenre(second);
        try {
            String[] types={"manga","anime","manhwa"};
            for(int i=0;i<3;i++) {
                MediaEntity m=new MediaEntity();m.title=prefix+" "+i;m.type=types[i];
                UserProgressEntity p=new UserProgressEntity();p.trackingStatus="reading";
                repo.saveMedia(m,p,Collections.singletonList(i==1?second.id:first.id));fixtures.add(m);
            }
            try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
                waitHome();
                onView(withContentDescription("Search your titles")).perform(replaceText(prefix),closeSoftKeyboard());
                onView(withText("3 titles")).check(matches(isDisplayed()));
                onView(withText("Filter")).perform(click());
                onView(withText("Manga")).perform(click());
                onView(withText("Anime")).perform(click());
                onView(withText("Cancel")).perform(scrollTo(),click());
                onView(withText("3 titles")).check(matches(isDisplayed()));
                onView(withText("Filter")).perform(click());
                onView(withText("Manga")).perform(click());
                onView(withText("Anime")).perform(click());
                onView(withText("Reading / watching")).perform(click());
                onView(withText(first.name)).perform(scrollTo(),click());
                onView(withText(second.name)).perform(scrollTo(),click());
                onView(withText("Show 2 titles")).perform(scrollTo(),click());
                onView(withText("2 titles")).check(matches(isDisplayed()));
                onView(withText("Clear all")).perform(click());
                onView(withText("3 titles")).check(matches(isDisplayed()));
            }
        } finally {
            for(MediaEntity m:fixtures)db().mediaDao().delete(m);
            db().genreDao().delete(first.id);db().genreDao().delete(second.id);
        }
    }
}
