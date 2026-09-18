package com.watlis.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Small local settings; disk work is performed on the existing settings/database executor. */
final class ImportProviderStore {
    private final Context context;
    private final String preferencesName;
    private volatile List<ImportProvider> snapshot=Collections.singletonList(ImportProvider.shinigami());
    private volatile String loadError;
    ImportProviderStore(Context context) { this(context,"import_providers"); }
    ImportProviderStore(Context context,String preferencesName) { this.context=context.getApplicationContext(); this.preferencesName=preferencesName; }
    private SharedPreferences prefs() { return context.getSharedPreferences(preferencesName,Context.MODE_PRIVATE); }

    synchronized void load() {
        try {
            String raw=prefs().getString("providers",null);
            if(raw==null) { snapshot=Collections.singletonList(ImportProvider.shinigami()); loadError=null; return; }
            JSONArray array=new JSONArray(raw); List<ImportProvider> providers=new ArrayList<>();
            if(array.length()>40)throw new IllegalArgumentException("Too many providers");
            for(int i=0;i<array.length();i++) providers.add(ImportProvider.fromJson(array.getJSONObject(i)));
            validateList(providers); snapshot=providers; loadError=null;
        } catch (JSONException|RuntimeException error) {
            snapshot=Collections.emptyList();
            loadError="Saved provider settings could not be read. No provider will be contacted. Restore the default configuration to recover.";
        }
    }

    List<ImportProvider> all() {
        List<ImportProvider> copy=new ArrayList<>(); for(ImportProvider p:snapshot)copy.add(p.copy()); return copy;
    }
    String error() {return loadError;}
    ImportProvider matching(String link) {
        ImportProvider found=null;
        for(ImportProvider p:snapshot) if(p.chapterIdFor(link)!=null) {
            if(found!=null)return null; // Never silently send a link to an ambiguous provider.
            found=p;
        }
        return found==null ? null : found.copy();
    }

    synchronized void save(ImportProvider provider) {
        if(loadError!=null)throw new IllegalArgumentException("Restore the default configuration before editing unreadable settings.");
        provider.validate(); ImportProvider validated=provider.copy();
        List<ImportProvider> next=all(); boolean replaced=false;
        for(int i=0;i<next.size();i++)if(next.get(i).id.equals(validated.id)){next.set(i,validated);replaced=true;break;}
        if(!replaced)next.add(validated);
        commit(next);
    }
    synchronized void delete(String id) { List<ImportProvider> next=all(); next.removeIf(p->p.id.equals(id)); commit(next); }
    synchronized void reset() {commit(Collections.singletonList(ImportProvider.shinigami()));}

    private void commit(List<ImportProvider> providers) {
        validateList(providers);
        try {
            JSONArray array=new JSONArray();for(ImportProvider provider:providers)array.put(provider.toJson());
            if(!prefs().edit().putString("providers",array.toString()).commit())throw new IllegalStateException("Could not save provider settings. Try again.");
            snapshot=new ArrayList<>(providers);loadError=null;
        } catch(JSONException error){throw new IllegalArgumentException("Could not encode provider settings.",error);}
    }

    private static void validateList(List<ImportProvider> list) {
        if(list.size()>40)throw new IllegalArgumentException("Keep at most 40 providers.");
        for(int i=0;i<list.size();i++)for(int j=i+1;j<list.size();j++) {
            ImportProvider a=list.get(i),b=list.get(j);
            if(a.id.equals(b.id) || a.name.equalsIgnoreCase(b.name))throw new IllegalArgumentException("Provider names must be unique.");
            if(!a.enabled || !b.enabled || !a.linkPath.replaceAll("/$","").equals(b.linkPath.replaceAll("/$","")))continue;
            for(String left:a.hosts.split("\n"))for(String right:b.hosts.split("\n")) {
                String l=left.startsWith("*.")?"sample."+left.substring(2):left;
                String r=right.startsWith("*.")?"sample."+right.substring(2):right;
                if(ImportProvider.allowsHost(left,r)||ImportProvider.allowsHost(right,l))
                    throw new IllegalArgumentException("Enabled providers cannot share overlapping website hosts and the same chapter-link path.");
            }
        }
    }
}
