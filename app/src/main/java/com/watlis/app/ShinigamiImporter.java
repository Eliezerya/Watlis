package com.watlis.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Explicit, two-request metadata lookup. Never downloads chapter pages or writes the collection. */
final class ShinigamiImporter {
    private ImportProvider provider = ImportProvider.shinigami();
    private volatile HttpURLConnection connection;
    private volatile boolean cancelled;

    interface Transport { String get(String url) throws IOException; }
    private final Transport transport;
    ShinigamiImporter() { transport = this::request; }
    ShinigamiImporter(Transport transport) { this.transport = transport; }

    static final class Result {
        String title, cover, format, notes, releaseStatus;
        double progress;
        final ArrayList<String> genres = new ArrayList<>();
    }

    static String chapterId(String text) {
        return ImportProvider.shinigami().chapterIdFor(text);
    }

    Result fetch(String link) throws IOException {
        return fetch(link, ImportProvider.shinigami());
    }

    Result fetch(String link, ImportProvider configured) throws IOException {
        provider = configured.copy(); // Freeze routing for both requests, even if settings change meanwhile.
        String id = provider.chapterIdFor(link);
        if (id == null) throw new IOException("This chapter link does not match the selected provider settings.");
        try {
            JSONObject chapter = data(transport.get(provider.endpoint(true, id)));
            if (!sameId(id, chapter.getString("chapter_id"))) throw new IOException("The chapter response did not match your link.");
            String mangaId = chapter.getString("manga_id");
            if (!provider.validId(mangaId)) throw new IOException("The chapter response has an invalid manga ID.");
            double progress = chapter.getDouble("chapter_number");
            if (!Double.isFinite(progress) || progress < 0) throw new IOException("The chapter number is invalid.");
            if (cancelled) throw new IOException("Import cancelled.");
            JSONObject manga = data(transport.get(provider.endpoint(false, mangaId)));
            if (!sameId(mangaId, manga.getString("manga_id"))) throw new IOException("The manga response did not match the chapter.");
            Result result = new Result();
            result.title = clean(manga, "title");
            if (result.title.isEmpty()) throw new IOException("No title was returned. Your draft was not changed.");
            result.progress = progress; // Never use latest_chapter_number for personal progress.
            result.cover = provider.safeImage(clean(manga, "cover_portrait_url"));
            if (result.cover.isEmpty()) result.cover = provider.safeImage(clean(manga, "cover_image_url"));
            JSONObject taxonomy = manga.optJSONObject("taxonomy");
            List<String> formats = names(taxonomy, "Format");
            result.format = formats.isEmpty() ? "" : formats.get(0);
            result.genres.addAll(names(taxonomy, "Genre"));
            // Numeric API status codes are not documented in the supplied contract: do not guess.
            String status = clean(manga, "status").toLowerCase(Locale.ROOT);
            if (status.equals("ongoing") || status.equals("completed")) result.releaseStatus = status;
            StringBuilder notes = new StringBuilder();
            append(notes, "", clean(manga, "description"));
            append(notes, "Alternative title", clean(manga, "alternative_title"));
            append(notes, "Release year", clean(manga, "release_year"));
            append(notes, "Author", String.join(", ", names(taxonomy, "Author")));
            append(notes, "Artist", String.join(", ", names(taxonomy, "Artist")));
            double score = manga.optDouble("user_rate", Double.NaN);
            if (Double.isFinite(score) && score >= 0 && score <= 10)
                append(notes, provider.name + " community rating", java.math.BigDecimal.valueOf(score).stripTrailingZeros().toPlainString() + "/10");
            append(notes, "Source", link.trim());
            result.notes = notes.toString();
            return result;
        } catch (JSONException error) { throw new IOException("The service returned incomplete or invalid metadata. Your draft was not changed.", error); }
    }

    private static JSONObject data(String json) throws JSONException, IOException {
        JSONObject response = new JSONObject(json);
        if (response.getInt("retcode") != 0) throw new IOException("The service could not find this chapter or manga. Check the link and retry.");
        return response.getJSONObject("data");
    }

    private static String clean(JSONObject object, String key) {
        return object.isNull(key) ? "" : object.optString(key, "").trim();
    }

    private static List<String> names(JSONObject taxonomy, String key) {
        ArrayList<String> result = new ArrayList<>();
        JSONArray values = taxonomy == null ? null : taxonomy.optJSONArray(key);
        if (values != null) for (int i=0; i<values.length() && result.size()<100; i++) {
            JSONObject item = values.optJSONObject(i);
            String name = item == null ? "" : clean(item, "name");
            boolean duplicate = false;
            for (String existing : result) if (existing.equalsIgnoreCase(name)) duplicate = true;
            if (!name.isEmpty() && name.length() <= 200 && !duplicate) result.add(name);
        }
        return result;
    }

    private boolean sameId(String expected, String actual) {
        return provider.uuidIds ? expected.equalsIgnoreCase(actual) : expected.equals(actual);
    }

    private static void append(StringBuilder notes, String label, String value) {
        if (value.isEmpty()) return;
        if (notes.length()>0) notes.append("\n\n");
        if (!label.isEmpty()) notes.append(label).append(": ");
        notes.append(value);
    }

    void cancel() {
        cancelled = true;
        HttpURLConnection active = connection;
        if (active != null) active.disconnect();
    }

    private String request(String address) throws IOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new IOException("Import cancelled.");
        HttpURLConnection request = (HttpURLConnection) new URL(address).openConnection();
        connection = request;
        try {
            request.setConnectTimeout(8000); request.setReadTimeout(8000);
            request.setInstanceFollowRedirects(false);
            request.setRequestMethod("GET");
            request.setRequestProperty("Accept", "application/json");
            request.setRequestProperty("User-Agent", "Watlis/1.0");
            if (cancelled) throw new IOException("Import cancelled.");
            int status = request.getResponseCode();
            if (status != 200) throw new IOException(provider.name + " returned HTTP " + status + ". Please try again later.");
            if (request.getContentLengthLong() > 1024*1024) throw new IOException("The metadata response is too large.");
            try (InputStream input = request.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (cancelled || Thread.currentThread().isInterrupted()) throw new IOException("Import cancelled.");
                    if (output.size()+count > 1024*1024) throw new IOException("The metadata response is too large.");
                    output.write(buffer, 0, count);
                }
                return output.toString(StandardCharsets.UTF_8.name());
            }
        } finally { request.disconnect(); if (connection == request) connection = null; }
    }
}
