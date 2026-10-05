package com.nullyard.reader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class FbcProvider implements OnlineProvider {
    private static final String API = "https://fbc.pionier.net.pl/graphql";

    private static final String SEARCH_QUERY =
            "query($r:RequestInput){components(requestInput:$r){searchResults{" +
            "totalResultsCount queryString searchResults{" +
            "Title:attributeValues(rdfName:\"Title\") " +
            "Creator:attributeValues(rdfName:\"Creator\") " +
            "Language:attributeValues(rdfName:\"Language\") " +
            "Format:attributeValues(rdfName:\"Format\") " +
            "elementId publicationId " +
            "extra{id fbc_url isAccessGranted}" +
            "}}}}";

    @Override public String id() { return "fbc"; }
    @Override public String name() { return "FBC"; }
    @Override public String attributionText() { return "Źródło: Federacja Bibliotek Cyfrowych"; }
    @Override public String attributionUrl() { return "https://fbc.pionier.net.pl/"; }

    @Override
    public List<Book> search(String query) throws Exception {
        String needle = query == null ? "" : query.trim();
        if (needle.isEmpty()) return new ArrayList<>();

        JSONObject variables = new JSONObject();
        JSONObject request = new JSONObject();
        request.put("page", "results");
        request.put("language", "pl");

        JSONArray params = new JSONArray();
        params.put(param("action", "SimpleSearchAction"));
        params.put(param("type", "-6"));
        params.put(param("p", "0"));
        params.put(param("q", needle));
        request.put("params", params);
        variables.put("r", request);

        JSONObject body = new JSONObject();
        body.put("query", SEARCH_QUERY);
        body.put("variables", variables);

        JSONObject response = postJson(body);
        JSONObject data = response.optJSONObject("data");
        JSONObject components = data == null ? null : data.optJSONObject("components");
        JSONObject searchResults = components == null ? null : components.optJSONObject("searchResults");
        JSONArray items = searchResults == null ? null : searchResults.optJSONArray("searchResults");

        ArrayList<Book> result = new ArrayList<>();
        if (items == null) return result;

        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;

            String title = first(item.optJSONArray("Title"));
            String author = first(item.optJSONArray("Creator"));
            String language = languageCode(first(item.optJSONArray("Language")));
            String format = first(item.optJSONArray("Format"));

            JSONObject extra = item.optJSONObject("extra");
            String sourceUrl = extra == null ? "" : extra.optString("fbc_url", "").trim();
            String publicationId = item.optString("publicationId", "").trim();

            title = clean(title);
            author = clean(author);
            format = clean(format);

            if (title.isEmpty() || sourceUrl.isEmpty()) continue;
            if (format.isEmpty()) format = "ŹRÓDŁO";

            result.add(new Book(
                    id(),
                    name(),
                    title,
                    author,
                    language,
                    format,
                    publicationId,
                    sourceUrl,
                    ""
            ));

            if (result.size() >= 40) break;
        }

        return result;
    }

    @Override
    public boolean canDownload(Book book) {
        return false;
    }

    @Override
    public Download resolveDownload(Book book) {
        throw new UnsupportedOperationException("FBC udostępnia rekord źródłowy");
    }

    private JSONObject param(String name, String value) throws Exception {
        JSONObject param = new JSONObject();
        param.put("name", name);
        param.put("value", value);
        return param;
    }

    private JSONObject postJson(JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(API).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(30000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("User-Agent", "NullReader/0.3 (https://nullreader.nullyard.com)");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");

        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(payload);
        }

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
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            connection.disconnect();
        }
    }

    private String first(JSONArray values) {
        if (values == null || values.length() == 0) return "";
        return values.optString(0, "");
    }

    private String clean(String value) {
        if (value == null) return "";
        return value.replaceAll("<[^>]+>", "").replace("&nbsp;", " ").trim();
    }

    private String languageCode(String value) {
        String v = clean(value).toLowerCase();
        if (v.isEmpty()) return "";
        if (v.equals("polski") || v.equals("pol") || v.equals("pl")) return "pl";
        if (v.equals("angielski") || v.equals("eng") || v.equals("en")) return "en";
        if (v.length() == 2) return v;
        return "";
    }
}
