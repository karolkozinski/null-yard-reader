package com.nullyard.reader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class WolneLekturyProvider implements OnlineProvider {
    private static final String API_BOOKS = "https://wolnelektury.pl/api/books/?format=json";

    @Override public String id() { return "wolne-lektury"; }
    @Override public String name() { return "Wolne Lektury"; }
    @Override public String attributionText() { return "Katalog i pliki: Wolne Lektury"; }
    @Override public String attributionUrl() { return "https://wolnelektury.pl/"; }

    @Override
    public List<Book> loadCatalog() throws Exception {
        JSONArray data = new JSONArray(getText(API_BOOKS));
        ArrayList<Book> result = new ArrayList<>();

        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null) continue;

            String title = item.optString("title", "").trim();
            String author = item.optString("author", "").trim();
            String href = absoluteUrl(item.optString("href", "").trim());
            String sourceUrl = absoluteUrl(item.optString("url", "").trim());
            String coverUrl = coverUrl(item.optString("cover", "").trim());

            if (title.isEmpty() || href.isEmpty()) continue;
            result.add(new Book(id(), name(), title, author, href, sourceUrl, coverUrl));
        }
        return result;
    }

    @Override
    public Download resolveDownload(Book book) throws Exception {
        JSONObject detail = new JSONObject(getText(withJsonFormat(book.detailUrl)));
        String epub = detail.optString("epub", "").trim();
        if (epub.isEmpty() || "null".equals(epub)) {
            throw new IllegalArgumentException("brak wersji EPUB");
        }

        String cover = book.coverUrl;
        if (cover == null || cover.isEmpty()) {
            cover = detail.optString("cover", "").trim();
        }

        return new Download(
                absoluteUrl(epub),
                coverUrl(cover)
        );
    }

    private String getText(String address) throws Exception {
        HttpURLConnection connection = open(address);
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

    private HttpURLConnection open(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "NullReader/0.3 (https://nullreader.nullyard.com)");
        connection.setRequestProperty("Accept", "application/json, application/epub+zip, */*");
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
        return connection;
    }

    private String withJsonFormat(String href) {
        if (href.contains("?")) return href + "&format=json";
        return href + "?format=json";
    }

    private String coverUrl(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        value = value.trim();
        if (value.startsWith("http://") || value.startsWith("https://")) return value;
        if (value.startsWith("/media/")) return "https://wolnelektury.pl" + value;
        if (value.startsWith("media/")) return "https://wolnelektury.pl/" + value;
        if (value.startsWith("/")) value = value.substring(1);
        return "https://wolnelektury.pl/media/" + value;
    }

    private String absoluteUrl(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        value = value.trim();
        if (value.startsWith("http://") || value.startsWith("https://")) return value;
        if (!value.startsWith("/")) value = "/" + value;
        return "https://wolnelektury.pl" + value;
    }
}
