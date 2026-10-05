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

final class WikisourceProvider implements OnlineProvider {
    private static final String API = "https://pl.wikisource.org/w/api.php";
    private static final String EXPORT = "https://ws-export.wmcloud.org/";

    @Override public String id() { return "wikisource-pl"; }
    @Override public String name() { return "Wikiźródła"; }
    @Override public String attributionText() { return "Źródło: polskie Wikiźródła"; }
    @Override public String attributionUrl() { return "https://pl.wikisource.org/"; }

    @Override
    public List<Book> search(String query) throws Exception {
        String needle = query == null ? "" : query.trim();
        if (needle.isEmpty()) return new ArrayList<>();

        String address = API
                + "?action=query"
                + "&generator=search"
                + "&gsrnamespace=0"
                + "&gsrlimit=40"
                + "&prop=pageimages%7Cinfo"
                + "&piprop=thumbnail"
                + "&pithumbsize=220"
                + "&inprop=url"
                + "&format=json"
                + "&formatversion=2"
                + "&gsrsearch=" + enc(needle);

        JSONObject data = new JSONObject(getText(address));
        JSONArray pages = data.optJSONObject("query") == null
                ? null
                : data.optJSONObject("query").optJSONArray("pages");

        ArrayList<Book> result = new ArrayList<>();
        if (pages == null) return result;

        for (int i = 0; i < pages.length(); i++) {
            JSONObject page = pages.optJSONObject(i);
            if (page == null) continue;

            String title = page.optString("title", "").trim();
            String sourceUrl = page.optString("fullurl", "").trim();
            String coverUrl = "";
            JSONObject thumbnail = page.optJSONObject("thumbnail");
            if (thumbnail != null) coverUrl = thumbnail.optString("source", "").trim();

            if (title.isEmpty() || sourceUrl.isEmpty()) continue;
            result.add(new Book(
                    id(),
                    name(),
                    title,
                    "",
                    "pl",
                    title,
                    sourceUrl,
                    coverUrl
            ));
        }
        return result;
    }

    @Override
    public Download resolveDownload(Book book) throws Exception {
        String epub = EXPORT
                + "?format=epub"
                + "&lang=pl"
                + "&page=" + enc(book.detailUrl);
        return new Download(epub, book.coverUrl);
    }

    private String getText(String address) throws Exception {
        HttpURLConnection connection = open(address, "application/json,*/*");
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

    private HttpURLConnection open(String address, String accept) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "NullReader/0.3 (https://nullreader.nullyard.com)");
        connection.setRequestProperty("Accept", accept);
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
        return connection;
    }

    private String enc(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }
}
