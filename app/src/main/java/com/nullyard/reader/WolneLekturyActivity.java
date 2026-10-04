package com.nullyard.reader;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WolneLekturyActivity extends Activity {
    private static final String API_BOOKS = "https://wolnelektury.pl/api/books/?format=json";
    private static final String SOURCE = "Wolne Lektury";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ArrayList<CatalogBook> catalog = new ArrayList<>();
    private LinearLayout results;
    private EditText search;
    private ProgressBar progress;
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        showCatalog();
        loadCatalog();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void showCatalog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(18, 18, 18));
        root.setPadding(dp(20), dp(18), dp(20), dp(16));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(dp(20), insets.getSystemWindowInsetTop() + dp(18),
                    dp(20), insets.getSystemWindowInsetBottom() + dp(16));
            return insets;
        });

        TextView back = new TextView(this);
        back.setText("‹  Biblioteka");
        back.setTextColor(Color.rgb(190, 205, 196));
        back.setTextSize(15);
        back.setPadding(0, 0, 0, dp(8));
        back.setOnClickListener(v -> finish());

        TextView title = new TextView(this);
        title.setText("Wolne Lektury");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);

        TextView intro = new TextView(this);
        intro.setText("Darmowe książki z wolnelektury.pl\\nPoniżej pokazujemy przykładowe 60 pozycji. Wyszukiwarka przeszukuje cały katalog.");
        intro.setTextColor(Color.rgb(145, 145, 145));
        intro.setTextSize(13);
        intro.setPadding(0, dp(3), 0, dp(14));

        search = new EditText(this);
        search.setHint("Tytuł lub autor");
        search.setSingleLine(true);
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(Color.rgb(125, 125, 125));
        search.setBackgroundColor(Color.rgb(30, 35, 32));
        search.setPadding(dp(14), dp(10), dp(14), dp(10));
        search.setEnabled(false);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                filter(s == null ? "" : s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        LinearLayout loading = new LinearLayout(this);
        loading.setGravity(Gravity.CENTER_VERTICAL);
        loading.setPadding(0, dp(12), 0, dp(8));

        progress = new ProgressBar(this);
        status = new TextView(this);
        status.setText("Pobieram katalog…");
        status.setTextColor(Color.rgb(145, 145, 145));
        status.setTextSize(13);
        status.setPadding(dp(10), 0, 0, 0);
        loading.addView(progress, new LinearLayout.LayoutParams(dp(28), dp(28)));
        loading.addView(status);

        ScrollView scroll = new ScrollView(this);
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(results, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView attribution = new TextView(this);
        attribution.setText("Katalog i pliki: Wolne Lektury");
        attribution.setTextColor(Color.rgb(105, 125, 113));
        attribution.setTextSize(12);
        attribution.setGravity(Gravity.CENTER);
        attribution.setPadding(0, dp(10), 0, 0);
        attribution.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://wolnelektury.pl/")));
            } catch (Exception ignored) {}
        });

        root.addView(back);
        root.addView(title);
        root.addView(intro);
        root.addView(search);
        root.addView(loading);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(attribution);
        setContentView(root);
        root.requestApplyInsets();
    }

    private void loadCatalog() {
        executor.execute(() -> {
            try {
                JSONArray data = new JSONArray(getText(API_BOOKS));
                ArrayList<CatalogBook> loaded = new ArrayList<>();
                for (int i = 0; i < data.length(); i++) {
                    JSONObject item = data.optJSONObject(i);
                    if (item == null) continue;
                    String title = item.optString("title", "").trim();
                    String author = item.optString("author", "").trim();
                    String href = item.optString("href", "").trim();
                    String sourceUrl = item.optString("url", "").trim();
                    if (title.isEmpty() || href.isEmpty()) continue;
                    loaded.add(new CatalogBook(title, author, href, sourceUrl));
                }
                runOnUiThread(() -> {
                    catalog.clear();
                    catalog.addAll(loaded);
                    progress.setVisibility(View.GONE);
                    status.setText("Katalog: " + catalog.size() + " utworów");
                    search.setEnabled(true);
                    filter("");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    status.setText("Nie udało się pobrać katalogu");
                    Toast.makeText(this, "Wolne Lektury: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void filter(String query) {
        results.removeAllViews();
        if (catalog.isEmpty()) return;

        String needle = normalize(query);
        int shown = 0;
        for (CatalogBook book : catalog) {
            if (!needle.isEmpty()) {
                String haystack = normalize(book.title + " " + book.author);
                if (!haystack.contains(needle)) continue;
            }
            addResult(book);
            shown++;
            if (shown >= 60) break;
        }

        if (shown == 0) {
            TextView empty = new TextView(this);
            empty.setText("Brak wyników");
            empty.setTextColor(Color.rgb(145, 145, 145));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(60), 0, 0);
            results.addView(empty);
        }
    }

    private void addResult(CatalogBook book) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.setBackgroundColor(Color.rgb(22, 22, 22));
        row.setOnClickListener(v -> openBook(book));

        TextView title = new TextView(this);
        title.setText(book.title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);

        TextView author = new TextView(this);
        author.setText(book.author.isEmpty() ? SOURCE : book.author);
        author.setTextColor(Color.rgb(150, 150, 150));
        author.setTextSize(13);
        author.setPadding(0, dp(3), 0, 0);

        TextView source = new TextView(this);
        source.setText("Wolne Lektury  ·  EPUB");
        source.setTextColor(Color.rgb(99, 145, 116));
        source.setTextSize(12);
        source.setPadding(0, dp(5), 0, 0);

        row.addView(title);
        row.addView(author);
        row.addView(source);
        results.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(48, 48, 48));
        results.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
    }

    private void openBook(CatalogBook book) {
        search.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        status.setText("Pobieram „" + book.title + "”…");

        executor.execute(() -> {
            try {
                JSONObject detail = new JSONObject(getText(withJsonFormat(book.href)));
                String epub = detail.optString("epub", "").trim();
                if (epub.isEmpty() || "null".equals(epub)) {
                    throw new IllegalArgumentException("brak wersji EPUB");
                }
                epub = absoluteUrl(epub);

                File dir = new File(getCacheDir(), "wolne-lektury");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IllegalStateException("nie można utworzyć cache");
                }
                String fileName = safeFileName(book.title) + ".epub";
                File target = new File(dir, fileName);
                download(epub, target);

                Intent intent = new Intent(this, MainActivity.class);
                intent.setAction(Intent.ACTION_VIEW);
                intent.putExtra("online_epub_path", target.getAbsolutePath());
                intent.putExtra("online_epub_title", book.title);
                intent.putExtra("online_source_url", book.sourceUrl);
                startActivity(intent);
                runOnUiThread(() -> {
                    search.setEnabled(true);
                    progress.setVisibility(View.GONE);
                    status.setText("Katalog: " + catalog.size() + " utworów");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    search.setEnabled(true);
                    progress.setVisibility(View.GONE);
                    status.setText("Katalog: " + catalog.size() + " utworów");
                    Toast.makeText(this, "Nie udało się otworzyć EPUB: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
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

    private void download(String address, File target) throws Exception {
        HttpURLConnection connection = open(address);
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
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

    private String absoluteUrl(String value) {
        if (value.startsWith("http://") || value.startsWith("https://")) return value;
        if (!value.startsWith("/")) value = "/" + value;
        return "https://wolnelektury.pl" + value;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private String safeFileName(String value) {
        String clean = value.replaceAll("[^\\p{L}\\p{N}._ -]+", "_").trim();
        if (clean.isEmpty()) clean = "wolne-lektury";
        return clean.length() > 80 ? clean.substring(0, 80) : clean;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static class CatalogBook {
        final String title;
        final String author;
        final String href;
        final String sourceUrl;

        CatalogBook(String title, String author, String href, String sourceUrl) {
            this.title = title;
            this.author = author;
            this.href = href;
            this.sourceUrl = sourceUrl;
        }
    }
}
