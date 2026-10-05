package com.nullyard.reader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class PersonalLibraryProvider implements OnlineProvider {
    private final String baseUrl;
    private final String token;

    PersonalLibraryProvider(String baseUrl, String token) {
        this.baseUrl = trimSlash(baseUrl);
        this.token = token == null ? "" : token.trim();
    }

    @Override public String id() { return "personal-library"; }
    @Override public String name() { return "Moja biblioteka"; }
    @Override public String attributionText() { return "Prywatna biblioteka"; }
    @Override public String attributionUrl() { return baseUrl; }

    @Override
    public boolean includeInAllSearch() {
        return false;
    }

    @Override
    public void configureConnection(HttpURLConnection connection) {
        connection.setRequestProperty("Authorization", "Bearer " + token);
    }

    @Override
    public List<Book> search(String query) throws Exception {
        String needle = query == null ? "" : query.trim();
        if (needle.isEmpty()) return new ArrayList<>();

        String address = baseUrl + "/api/search?q="
                + URLEncoder.encode(needle, "UTF-8")
                + "&limit=100";

        JSONObject data = new JSONObject(getText(address));
        JSONArray items = data.optJSONArray("results");
        ArrayList<Book> result = new ArrayList<>();
        if (items == null) return result;

        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;

            long id = item.optLong("id", -1);
            String title = item.optString("title", "").trim();
            String author = item.optString("author", "").trim();
            String language = item.optString("language", "").trim();

            if (id < 0 || title.isEmpty()) continue;

            String fileUrl = baseUrl + "/api/books/" + id + "/file";
            String coverUrl = baseUrl + "/api/books/" + id + "/cover";

            result.add(new Book(
                    id(),
                    name(),
                    title,
                    author,
                    language,
                    "EPUB",
                    Long.toString(id),
                    fileUrl,
                    coverUrl
            ));
        }

        return result;
    }

    @Override
    public Download resolveDownload(Book book) {
        return new Download(book.sourceUrl, book.coverUrl);
    }

    private String getText(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "NullReader/0.3 (https://nullreader.nullyard.com)");
        connection.setRequestProperty("Accept", "application/json");
        configureConnection(connection);

        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            connection.disconnect();
            throw new IllegalStateException("HTTP " + code);
        }

        try (InputStream input = new BufferedInputStream(connection.getInputStream())) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private static String trimSlash(String value) {
        if (value == null) return "";
        value = value.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }
}
