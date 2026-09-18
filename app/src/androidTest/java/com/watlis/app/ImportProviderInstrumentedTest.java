package com.watlis.app;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ImportProviderInstrumentedTest {
    private Context context(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
    private ImportProvider custom() {
        ImportProvider p=new ImportProvider();p.name="Custom source";p.hosts="reader.example.test\n*.mirror.example.test";
        p.apiBase="https://api.example.test/v2";p.linkPath="/read/{id}.html";
        p.chapterEndpoint="chapters/{id}/metadata";p.mangaEndpoint="series/{id}";
        p.imageHosts="cdn.example.test\n*.images.example.test";p.uuidIds=false;p.validate();return p;
    }
    @Test public void configurableHostsPathsAndIdFormatsMatchPrecisely() {
        ImportProvider p=custom();assertEquals("https://api.example.test/v2/",p.apiBase);
        assertEquals("Ch-42",p.chapterIdFor("https://reader.example.test/read/Ch-42.html?q=abc#end"));
        assertEquals("42",p.chapterIdFor("https://a.mirror.example.test/read/42.html/"));
        for(String link:Arrays.asList("http://reader.example.test/read/42.html","https://reader.example.test.attacker.test/read/42.html",
                "https://mirror.example.test/read/42.html","https://user@reader.example.test/read/42.html",
                "https://reader.example.test/read/../42.html","https://reader.example.test/read/%2F42.html","https://reader.example.test/chapter/42"))assertNull(link,p.chapterIdFor(link));
        assertEquals("https://cdn.example.test/cover.jpg",p.safeImage("https://cdn.example.test/cover.jpg"));
        assertEquals("https://a.images.example.test/cover.jpg",p.safeImage("https://a.images.example.test/cover.jpg"));
        assertEquals("",p.safeImage("https://cdn.example.test.attacker.test/cover.jpg"));
        assertEquals("",p.safeImage("http://cdn.example.test/cover.jpg"));
        p.enabled=false;assertNull(p.chapterIdFor("https://reader.example.test/read/42.html"));
        p.enabled=true;p.uuidIds=true;assertNull(p.chapterIdFor("https://reader.example.test/read/42.html"));
    }

    @Test public void rejectsInvalidConfigurationBeforeAnyNetworkRequest() {
        List<Consumer<ImportProvider>> invalid=Arrays.asList(p->p.name="",p->p.hosts="https://reader.example.test",
                p->p.hosts="*example.test",p->p.hosts="127.0.0.1",p->p.hosts="a..example.test",p->p.hosts="-bad.example.test",
                p->p.apiBase="http://api.example.test/v2/",p->p.apiBase="https://user:pass@api.example.test/",
                p->p.apiBase="https://api.example.test/v2/?key=secret",p->p.apiBase="https://api.example.test/../v2/",
                p->p.chapterEndpoint="https://elsewhere.test/{id}",p->p.chapterEndpoint="/chapter/{id}",
                p->p.chapterEndpoint="../chapter/{id}",p->p.chapterEndpoint="chapter/%2e%2e/{id}",
                p->p.chapterEndpoint="chapter/{id}/{id}",p->p.mangaEndpoint="series/fixed",p->p.linkPath="chapter/{id}");
        for(Consumer<ImportProvider> mutation:invalid){ImportProvider p=custom();mutation.accept(p);try{p.validate();fail("Invalid provider accepted");}catch(IllegalArgumentException expected){}}
    }

    @Test public void savedProvidersPersistCrudAndDoNotResurrectAfterDeletion() {
        String prefs="provider-test-"+UUID.randomUUID();
        try {
            ImportProviderStore store=new ImportProviderStore(context(),prefs);store.load();assertEquals(1,store.all().size());
            ImportProvider p=custom();store.save(p);
            ImportProviderStore reopened=new ImportProviderStore(context(),prefs);reopened.load();assertEquals(2,reopened.all().size());
            assertEquals(p.id,reopened.matching("https://reader.example.test/read/42.html").id);
            p.apiBase="https://new-api.example.test/v3/";store.save(p);reopened.load();
            assertEquals(p.apiBase,reopened.matching("https://reader.example.test/read/42.html").apiBase);
            p.enabled=false;store.save(p);reopened.load();assertNull(reopened.matching("https://reader.example.test/read/42.html"));
            store.delete(p.id);store.delete(ImportProvider.shinigami().id);reopened.load();assertTrue(reopened.all().isEmpty());
            assertNull(reopened.matching(ShinigamiImporterInstrumentedTest.LINK));
            store.reset();reopened.load();assertEquals(1,reopened.all().size());assertNotNull(reopened.matching(ShinigamiImporterInstrumentedTest.LINK));
        } finally {context().deleteSharedPreferences(prefs);}
    }

    @Test public void duplicateRoutesAreRejectedAndUnreadableSettingsFailClosed() {
        String prefs="provider-test-"+UUID.randomUUID();
        try {
            ImportProviderStore store=new ImportProviderStore(context(),prefs);store.load();ImportProvider p=custom();store.save(p);
            ImportProvider duplicate=custom();duplicate.name="Another";duplicate.hosts="*.example.test";
            try{store.save(duplicate);fail();}catch(IllegalArgumentException expected){}
            assertEquals(2,store.all().size());
            duplicate.enabled=false;store.save(duplicate);assertEquals(3,store.all().size());
            // Copies returned to callers cannot mutate active routing.
            ImportProvider read=store.matching("https://reader.example.test/read/42.html");read.apiBase="https://wrong.example.test/";
            assertEquals(p.apiBase,store.matching("https://reader.example.test/read/42.html").apiBase);
            context().getSharedPreferences(prefs,Context.MODE_PRIVATE).edit().putString("providers","bad json").commit();
            store.load();assertNotNull(store.error());assertTrue(store.all().isEmpty());assertNull(store.matching(ShinigamiImporterInstrumentedTest.LINK));
            store.reset();assertNull(store.error());assertEquals(1,store.all().size());
        } finally {context().deleteSharedPreferences(prefs);}
    }

    @Test public void customRoutingUsesBothConfiguredEndpointsAndCoverHosts() throws Exception {
        ImportProvider p=custom();List<String> requests=new ArrayList<>();
        String chapter=ShinigamiImporterInstrumentedTest.chapter().replace(ShinigamiImporterInstrumentedTest.CHAPTER,"Ch-42").replace(ShinigamiImporterInstrumentedTest.MANGA,"series-17");
        String manga=ShinigamiImporterInstrumentedTest.manga("Custom title",null).replace(ShinigamiImporterInstrumentedTest.MANGA,"series-17").replace("assets.shngm.id","cdn.example.test");
        ShinigamiImporter.Result result=new ShinigamiImporter(url->{requests.add(url);p.apiBase="https://changed-during-request.example.test/";return requests.size()==1?chapter:manga;})
                .fetch("https://reader.example.test/read/Ch-42.html",p);
        assertEquals(Arrays.asList("https://api.example.test/v2/chapters/Ch-42/metadata","https://api.example.test/v2/series/series-17"),requests);
        assertEquals(81.5,result.progress,0);assertEquals("Custom title",result.title);assertTrue(result.cover.startsWith("https://cdn.example.test/"));
        assertTrue(result.notes.contains("Custom source community rating: 8.5/10"));
    }
}
