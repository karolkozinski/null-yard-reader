package com.nullyard.reader;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class OnlineLibrary {
    private static final String ROOT = "online-library";
    private static final String INDEX = "index.json";

    static final class Entry {
        final String provider;
        final String title;
        final String author;
        final String sourceUrl;
        final String path;
        final String coverUrl;
        final String coverPath;
        final String language;

        Entry(String provider, String title, String author, String sourceUrl, String path,
              String coverUrl, String coverPath, String language) {
            this.provider = provider;
            this.title = title;
            this.author = author;
            this.sourceUrl = sourceUrl;
            this.path = path;
            this.coverUrl = coverUrl;
            this.coverPath = coverPath;
            this.language = language;
        }

        JSONObject toJson() throws Exception {
            JSONObject object = new JSONObject();
            object.put("provider", provider);
            object.put("title", title);
            object.put("author", author);
            object.put("sourceUrl", sourceUrl);
            object.put("path", path);
            object.put("coverUrl", coverUrl);
            object.put("coverPath", coverPath);
            object.put("language", language);
            return object;
        }

        static Entry fromJson(JSONObject object) {
            return new Entry(
                    object.optString("provider", ""),
                    object.optString("title", ""),
                    object.optString("author", ""),
                    object.optString("sourceUrl", ""),
                    object.optString("path", ""),
                    object.optString("coverUrl", ""),
                    object.optString("coverPath", ""),
                    object.optString("language", "pl")
            );
        }
    }

    static File providerDirectory(Context context, String provider) {
        return new File(new File(context.getFilesDir(), ROOT), safe(provider));
    }

    static synchronized List<Entry> load(Context context) {
        ArrayList<Entry> result = new ArrayList<>();
        File index = new File(new File(context.getFilesDir(), ROOT), INDEX);
        if (!index.isFile()) return result;

        try (FileInputStream input = new FileInputStream(index)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);

            JSONArray array = new JSONArray(new String(out.toByteArray(), StandardCharsets.UTF_8));
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                Entry entry = Entry.fromJson(object);
                if (entry.path.isEmpty()) continue;
                File file = new File(entry.path);
                if (file.isFile()) result.add(entry);
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    static synchronized void remove(Context context, Entry entry) throws Exception {
        List<Entry> entries = load(context);
        ArrayList<Entry> updated = new ArrayList<>();

        for (Entry existing : entries) {
            if (!sameBook(existing, entry)) updated.add(existing);
        }

        File epub = new File(entry.path);
        if (epub.isFile()) epub.delete();

        if (entry.coverPath != null && !entry.coverPath.isEmpty()) {
            File cover = new File(entry.coverPath);
            if (cover.isFile()) cover.delete();
        }

        save(context, updated);
    }

    static synchronized void upsert(Context context, Entry entry) throws Exception {
        List<Entry> entries = load(context);
        ArrayList<Entry> updated = new ArrayList<>();
        boolean replaced = false;

        for (Entry existing : entries) {
            if (sameBook(existing, entry)) {
                if (!replaced) {
                    updated.add(entry);
                    replaced = true;
                }
            } else {
                updated.add(existing);
            }
        }
        if (!replaced) updated.add(entry);
        save(context, updated);
    }

    private static boolean sameBook(Entry left, Entry right) {
        if (!left.sourceUrl.isEmpty() && !right.sourceUrl.isEmpty()) {
            return left.provider.equals(right.provider) && left.sourceUrl.equals(right.sourceUrl);
        }
        return left.provider.equals(right.provider)
                && left.title.equalsIgnoreCase(right.title)
                && left.author.equalsIgnoreCase(right.author);
    }

    private static void save(Context context, List<Entry> entries) throws Exception {
        File root = new File(context.getFilesDir(), ROOT);
        if (!root.exists() && !root.mkdirs()) {
            throw new IllegalStateException("nie można utworzyć biblioteki Online");
        }

        JSONArray array = new JSONArray();
        for (Entry entry : entries) array.put(entry.toJson());

        File target = new File(root, INDEX);
        File temporary = new File(root, INDEX + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(array.toString(2).getBytes(StandardCharsets.UTF_8));
        }

        if (target.exists() && !target.delete()) {
            throw new IllegalStateException("nie można zaktualizować indeksu Online");
        }
        if (!temporary.renameTo(target)) {
            throw new IllegalStateException("nie można zapisać indeksu Online");
        }
    }

    private static String safe(String value) {
        String clean = value == null ? "" : value.toLowerCase().replaceAll("[^a-z0-9._-]+", "-");
        clean = clean.replaceAll("^-+|-+$", "");
        return clean.isEmpty() ? "provider" : clean;
    }

    private OnlineLibrary() {}
}
