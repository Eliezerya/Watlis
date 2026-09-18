package com.watlis.app.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Wire model and deterministic merge rules. No clocks on the wall, numeric row IDs, or network I/O. */
public final class SyncDocument {
    public static final int MAX_RECORDS = 10000;
    public final TreeMap<String, Record> records = new TreeMap<>();

    public static final class Record {
        public final String key, label;
        public final JSONObject data; // null is a durable deletion, not a missing record
        public final TreeMap<String, Long> clock;
        public Record(String key, String label, JSONObject data, Map<String, Long> clock) {
            this.key = key; this.label = label; this.data = data; this.clock = new TreeMap<>(clock);
        }
        public String hash() { return digest(canonical(data)); }
        public boolean deleted() { return data == null; }
        public Record resolved(Record other, JSONObject chosen, String actor) {
            TreeMap<String, Long> joined = join(clock, other.clock); tick(joined, actor);
            return new Record(key, chosen == null ? label : chosen.optString("title", chosen.optString("name", label)), chosen, joined);
        }
    }

    public JSONObject json() throws JSONException {
        JSONArray array = new JSONArray();
        for (Record r : records.values()) array.put(new JSONObject().put("key", r.key).put("label", r.label)
                .put("data", r.data == null ? JSONObject.NULL : r.data).put("clock", new JSONObject(r.clock)));
        return new JSONObject().put("protocol", 1).put("records", array);
    }

    public static SyncDocument parse(JSONObject json) throws JSONException {
        if (json.getInt("protocol") != 1) throw new IllegalArgumentException("Different sync version. Update Watlis on both devices.");
        JSONArray array = json.getJSONArray("records");
        if (array.length() > MAX_RECORDS) throw new IllegalArgumentException("Collection exceeds the sync record limit.");
        SyncDocument doc = new SyncDocument();
        for (int i = 0; i < array.length(); i++) {
            JSONObject value = array.getJSONObject(i);
            String key = value.getString("key"), label = value.getString("label");
            if (!(key.startsWith("m:") || key.startsWith("g:") || key.startsWith("t:")) || key.length() > 1024 || label.length() > 10000)
                throw new IllegalArgumentException("Invalid sync identity.");
            JSONObject data = value.isNull("data") ? null : value.getJSONObject("data");
            if (data != null) {
                if (key.startsWith("m:")) {
                    if (data.getString("title").trim().isEmpty()) throw new IllegalArgumentException("A synced title is empty.");
                    WatlisRepository.validateProgress(data.getJSONObject("progress").getDouble("currentProgress"));
                } else if (!key.equals(catalogKey(key.substring(0, 1), data.getString("name"))))
                    throw new IllegalArgumentException("Invalid category identity.");
            }
            Record old = doc.records.put(key, new Record(key, label, data, readClock(value.getJSONObject("clock"))));
            if (old != null) throw new IllegalArgumentException("Repeated identity in sync data.");
        }
        return doc;
    }

    public String fingerprint() throws JSONException { return digest(canonical(json())); }
    public static String catalogKey(String kind, String name) { return kind + ":" + name.trim().toLowerCase(Locale.ROOT); }
    public static TreeMap<String, Long> readClock(JSONObject object) throws JSONException {
        if (object.length() == 0 || object.length() > 128) throw new IllegalArgumentException("Invalid sync revision.");
        TreeMap<String, Long> clock = new TreeMap<>();
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String actor = keys.next(); Object raw = object.get(actor);
            if (actor.isEmpty() || actor.length() > 80 || !(raw instanceof Number)) throw new IllegalArgumentException("Invalid sync device identity.");
            long n = object.getLong(actor);
            if (n < 1 || n >= Long.MAX_VALUE || ((Number) raw).doubleValue() != (double) n) throw new IllegalArgumentException("Invalid sync counter.");
            clock.put(actor, n);
        }
        return clock;
    }
    public static void tick(Map<String, Long> clock, String actor) {
        long n = clock.getOrDefault(actor, 0L);
        if (n >= Long.MAX_VALUE - 1 || !clock.containsKey(actor) && clock.size() >= 128)
            throw new IllegalArgumentException("Sync revision limit reached. Export a backup before continuing.");
        clock.put(actor, n + 1);
    }
    public static TreeMap<String, Long> join(Map<String, Long> a, Map<String, Long> b) {
        TreeMap<String, Long> result = new TreeMap<>(a);
        for (Map.Entry<String, Long> entry : b.entrySet()) result.merge(entry.getKey(), entry.getValue(), Math::max);
        return result;
    }
    public static boolean dominates(Map<String, Long> a, Map<String, Long> b) {
        for (Map.Entry<String, Long> entry : b.entrySet()) if (a.getOrDefault(entry.getKey(), 0L) < entry.getValue()) return false;
        return true;
    }
    public static String canonical(Object value) {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value; TreeSet<String> keys = new TreeSet<>(); object.keys().forEachRemaining(keys::add);
            StringBuilder text = new StringBuilder("{");
            for (String key : keys) { if (text.length() > 1) text.append(','); text.append(JSONObject.quote(key)).append(':').append(canonical(object.opt(key))); }
            return text.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value; StringBuilder text = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) { if (i > 0) text.append(','); text.append(canonical(array.opt(i))); }
            return text.append(']').toString();
        }
        if (value instanceof Number) {
            try { return JSONObject.numberToString((Number) value); } catch (JSONException e) { throw new IllegalArgumentException(e); }
        }
        return value instanceof Boolean ? value.toString() : JSONObject.quote(value.toString());
    }
    public static String digest(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            final char[] hex = "0123456789abcdef".toCharArray();
            for (byte b : bytes) result.append(hex[(b & 255) >>> 4]).append(hex[b & 15]);
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static JSONObject copy(JSONObject object) throws JSONException { return new JSONObject(object.toString()); }

    public static final class Conflict {
        public final Record here, there;
        public final boolean duplicate;
        Conflict(Record here, Record there, boolean duplicate) { this.here = here; this.there = there; this.duplicate = duplicate; }
        public String title() { return duplicate ? "Possible duplicate" : here.deleted() || there.deleted() ? "Deletion to review" : "Both devices changed this"; }
    }

    public static final class Plan {
        public final SyncDocument here, there, result = new SyncDocument();
        public final List<Conflict> conflicts = new ArrayList<>();
        public final List<String> notices = new ArrayList<>();
        private final String actor;
        private int unresolved;
        public Plan(SyncDocument here, SyncDocument there, String actor) {
            this.here = here; this.there = there; this.actor = actor;
            TreeSet<String> keys = new TreeSet<>(here.records.keySet()); keys.addAll(there.records.keySet());
            for (String key : keys) {
                Record a = here.records.get(key), b = there.records.get(key);
                if (a == null || b == null) { result.records.put(key, a == null ? b : a); continue; }
                if (a.hash().equals(b.hash())) { result.records.put(key, new Record(key, a.label, a.data, join(a.clock, b.clock))); continue; }
                boolean newer = dominates(a.clock, b.clock), older = dominates(b.clock, a.clock);
                if (newer && older) throw new IllegalArgumentException("Different content has the same sync revision. No data was changed.");
                if (a.deleted() != b.deleted() || !newer && !older) conflicts.add(new Conflict(a, b, false));
                else result.records.put(key, newer ? a : b);
            }
            unresolved = conflicts.size();
        }
        /** choose: 0=this device, 1=other device, 2=keep two distinct titles (duplicates only). */
        public void choose(Conflict conflict, int choice) {
            if (!conflicts.contains(conflict) || choice < 0 || choice > (conflict.duplicate ? 2 : 1)) throw new IllegalArgumentException("Invalid conflict choice.");
            Record a = conflict.here, b = conflict.there;
            if (conflict.duplicate) {
                if (choice != 2) {
                    Record kept = a.resolved(b, choice == 0 ? a.data : b.data, actor);
                    result.records.put(a.key, kept);
                    result.records.put(b.key, b.resolved(a, null, actor));
                }
            } else result.records.put(a.key, a.resolved(b, choice == 0 ? a.data : b.data, actor));
            conflicts.remove(conflict); unresolved--;
        }
        /** Called after revision conflicts are resolved. Pair one possible duplicate at a time. */
        public Conflict nextDuplicate(java.util.Set<String> keptSeparate) {
            List<Record> media = new ArrayList<>();
            for (Record r : result.records.values()) if (r.key.startsWith("m:") && !r.deleted()) media.add(r);
            for (int i = 0; i < media.size(); i++) for (int j = i + 1; j < media.size(); j++) {
                Record a = media.get(i), b = media.get(j);
                if (keptSeparate.contains(a.key + b.key) || !identity(a).equals(identity(b))) continue;
                // Titles already coexisting on either device are intentional, not new duplicates.
                if (live(here, a.key) && live(here, b.key) || live(there, a.key) && live(there, b.key)) continue;
                Conflict conflict = live(here, a.key) ? new Conflict(a, b, true) : new Conflict(b, a, true);
                conflicts.add(conflict); unresolved++; return conflict;
            }
            return null;
        }
        public SyncDocument finish() throws JSONException {
            if (unresolved != 0) throw new IllegalStateException("Review every conflict first.");
            // A category deletion cannot invalidate a retained title. Keep the referenced definition explicitly.
            TreeSet<String> needed = new TreeSet<>();
            for (Record r : result.records.values()) if (r.key.startsWith("m:") && !r.deleted()) {
                needed.add(r.data.getString("type")); JSONArray genres = r.data.getJSONArray("genreIds");
                for (int i = 0; i < genres.length(); i++) needed.add(genres.getString(i));
            }
            for (String key : needed) {
                Record r = result.records.get(key);
                if (r == null) throw new IllegalArgumentException("A title references a missing category.");
                if (r.deleted()) {
                    Record source = live(here, key) ? here.records.get(key) : there.records.get(key);
                    if (source == null || source.deleted()) throw new IllegalArgumentException("A title references a missing category.");
                    result.records.put(key, r.resolved(source, source.data, actor));
                    notices.add("Kept " + source.label + " because a retained title uses it.");
                }
            }
            return result;
        }
        private static boolean live(SyncDocument d, String key) { return d.records.containsKey(key) && !d.records.get(key).deleted(); }
        private static String identity(Record r) {
            return r.data.optString("title").trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT) + "\n" + r.data.optString("type");
        }
    }
}
