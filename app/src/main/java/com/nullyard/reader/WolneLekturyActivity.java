package com.nullyard.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WolneLekturyActivity extends Activity {
    private static final String ALL_PROVIDERS = "all";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService imageExecutor = Executors.newFixedThreadPool(3);
    private final Map<String, Bitmap> coverCache = new HashMap<>();
    private final ArrayList<OnlineProvider> providers = new ArrayList<>();
    private final ArrayList<OnlineProvider.Book> catalog = new ArrayList<>();
    private String selectedProviderId = ALL_PROVIDERS;
    private LinearLayout results;
    private EditText search;
    private ProgressBar progress;
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        providers.add(new WolneLekturyProvider());
        showCatalog();
        loadCatalog();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (results != null && search != null) {
            filter(search.getText() == null ? "" : search.getText().toString());
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        imageExecutor.shutdownNow();
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
        title.setText("Online");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);

        TextView intro = new TextView(this);
        intro.setText("Wyszukuj i pobieraj książki z wybranych źródeł");
        intro.setTextColor(Color.rgb(145, 145, 145));
        intro.setTextSize(13);
        intro.setPadding(0, dp(3), 0, dp(10));

        Spinner providerSelector = new Spinner(this);
        ArrayList<String> providerLabels = new ArrayList<>();
        providerLabels.add("Wszystkie źródła");
        for (OnlineProvider provider : providers) providerLabels.add(provider.name());
        ArrayAdapter<String> providerAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                providerLabels
        );
        providerSelector.setAdapter(providerAdapter);
        providerSelector.setPadding(0, 0, 0, dp(10));
        providerSelector.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                selectedProviderId = position == 0
                        ? ALL_PROVIDERS
                        : providers.get(position - 1).id();
                if (results != null && search != null) {
                    filter(search.getText() == null ? "" : search.getText().toString());
                }
            }

            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

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
        attribution.setText("Źródła: Wolne Lektury");
        attribution.setTextColor(Color.rgb(105, 125, 113));
        attribution.setTextSize(12);
        attribution.setGravity(Gravity.CENTER);
        attribution.setPadding(0, dp(10), 0, 0);
        attribution.setOnClickListener(v -> {
            try {
                if (!providers.isEmpty()) {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(providers.get(0).attributionUrl())));
                }
            } catch (Exception ignored) {}
        });

        root.addView(back);
        root.addView(title);
        root.addView(intro);
        root.addView(providerSelector);
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
            ArrayList<OnlineProvider.Book> loaded = new ArrayList<>();
            ArrayList<String> errors = new ArrayList<>();

            for (OnlineProvider provider : providers) {
                try {
                    loaded.addAll(provider.loadCatalog());
                } catch (Exception e) {
                    errors.add(provider.name());
                }
            }

            runOnUiThread(() -> {
                catalog.clear();
                catalog.addAll(loaded);
                progress.setVisibility(View.GONE);

                if (catalog.isEmpty() && !errors.isEmpty()) {
                    status.setText("Nie udało się pobrać katalogu");
                    Toast.makeText(
                            this,
                            "Nie udało się pobrać: " + String.join(", ", errors),
                            Toast.LENGTH_LONG
                    ).show();
                    return;
                }

                status.setText("Katalog: " + catalog.size() + " utworów");
                search.setEnabled(true);
                filter("");
            });
        });
    }

    private void filter(String query) {
        results.removeAllViews();
        String needle = normalize(query);

        addDownloadedSection(needle);

        if (needle.isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText("Wpisz tytuł lub autora, aby przeszukać cały katalog.");
            hint.setTextColor(Color.rgb(145, 145, 145));
            hint.setGravity(Gravity.CENTER);
            hint.setTextSize(14);
            hint.setPadding(dp(12), dp(36), dp(12), dp(36));
            results.addView(hint);
            return;
        }

        if (catalog.isEmpty()) return;

        int shown = 0;
        for (OnlineProvider.Book book : catalog) {
            if (!providerSelected(book.providerId)) continue;
            String haystack = normalize(book.title + " " + book.author);
            if (!haystack.contains(needle)) continue;
            addResult(book);
            shown++;
            if (shown >= 100) break;
        }

        if (shown == 0) {
            TextView empty = new TextView(this);
            empty.setText("Brak wyników");
            empty.setTextColor(Color.rgb(145, 145, 145));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(40), 0, dp(20));
            results.addView(empty);
        }
    }

    private void addDownloadedSection(String needle) {
        List<OnlineLibrary.Entry> entries = OnlineLibrary.load(this);
        ArrayList<OnlineLibrary.Entry> visible = new ArrayList<>();
        for (OnlineLibrary.Entry entry : entries) {
            if (!providerNameSelected(entry.provider)) continue;
            if (!needle.isEmpty()) {
                String haystack = normalize(entry.title + " " + entry.author);
                if (!haystack.contains(needle)) continue;
            }
            visible.add(entry);
        }

        if (visible.isEmpty()) return;

        TextView heading = new TextView(this);
        heading.setText("Pobrane");
        heading.setTextColor(Color.rgb(190, 205, 196));
        heading.setTextSize(14);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setPadding(0, dp(8), 0, dp(6));
        results.addView(heading);

        for (OnlineLibrary.Entry entry : visible) addDownloadedRow(entry);

        View gap = new View(this);
        results.addView(gap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(16)));
    }

    private void addDownloadedRow(OnlineLibrary.Entry entry) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        row.setBackgroundColor(Color.rgb(22, 22, 22));
        row.setOnLongClickListener(v -> {
            confirmRemove(entry);
            return true;
        });

        ImageView cover = coverView();
        if (!entry.coverPath.isEmpty()) {
            Bitmap bitmap = BitmapFactory.decodeFile(entry.coverPath);
            if (bitmap != null) {
                cover.setImageBitmap(bitmap);
            } else {
                ensureStoredCover(entry, cover);
            }
        } else {
            ensureStoredCover(entry, cover);
        }

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(12), 0, dp(8), 0);

        TextView title = new TextView(this);
        title.setText(entry.title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setMaxLines(2);

        TextView author = new TextView(this);
        author.setText(entry.author.isEmpty() ? entry.provider : entry.author);
        author.setTextColor(Color.rgb(150, 150, 150));
        author.setTextSize(13);
        author.setPadding(0, dp(3), 0, 0);

        TextView source = new TextView(this);
        source.setText("Pobrane · " + entry.provider);
        source.setTextColor(Color.rgb(99, 145, 116));
        source.setTextSize(12);
        source.setPadding(0, dp(5), 0, 0);

        int percent = readingProgress(entry);
        TextView reading = new TextView(this);
        reading.setText(percent >= 0 ? "Przeczytano " + percent + "%" : "Nie rozpoczęto");
        reading.setTextColor(Color.rgb(118, 112, 101));
        reading.setTextSize(12);
        reading.setPadding(0, dp(5), 0, 0);

        info.addView(title);
        info.addView(author);
        info.addView(source);
        info.addView(reading);

        Button read = new Button(this);
        read.setText("Czytaj");
        read.setAllCaps(false);
        read.setOnClickListener(v -> openDownloaded(entry));

        row.addView(cover, new LinearLayout.LayoutParams(dp(56), dp(82)));
        row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(read);

        results.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(48, 48, 48));
        results.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
    }

    private int readingProgress(OnlineLibrary.Entry entry) {
        String uri = Uri.fromFile(new File(entry.path)).toString();
        return getSharedPreferences("reader_state", MODE_PRIVATE)
                .getInt("progress:" + uri, -1);
    }

    private void confirmRemove(OnlineLibrary.Entry entry) {
        new AlertDialog.Builder(this)
                .setTitle("Usuń pobraną książkę?")
                .setMessage("„" + entry.title + "” zostanie usunięta z pamięci Null Readera.")
                .setNegativeButton("Anuluj", null)
                .setPositiveButton("Usuń", (dialog, which) -> {
                    try {
                        OnlineLibrary.remove(this, entry);
                        filter(search.getText() == null ? "" : search.getText().toString());
                        Toast.makeText(this, "Usunięto książkę", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(this, "Nie udało się usunąć książki: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private void ensureStoredCover(OnlineLibrary.Entry entry, ImageView target) {
        imageExecutor.execute(() -> {
            try {
                File epub = new File(entry.path);
                if (!epub.isFile()) return;

                MainActivity.EpubMetadata metadata = MainActivity.EpubReader.readMetadata(
                        this,
                        Uri.fromFile(epub),
                        entry.title
                );
                if (metadata.coverData == null || metadata.coverData.length == 0) return;

                File coverFile = new File(epub.getParentFile(), epub.getName() + ".cover");
                try (FileOutputStream output = new FileOutputStream(coverFile)) {
                    output.write(metadata.coverData);
                }

                OnlineLibrary.Entry updated = new OnlineLibrary.Entry(
                        entry.provider,
                        entry.title,
                        entry.author,
                        entry.sourceUrl,
                        entry.path,
                        entry.coverUrl,
                        coverFile.getAbsolutePath()
                );
                OnlineLibrary.upsert(this, updated);

                Bitmap bitmap = BitmapFactory.decodeByteArray(
                        metadata.coverData, 0, metadata.coverData.length);
                if (bitmap == null) return;

                runOnUiThread(() -> target.setImageBitmap(bitmap));
            } catch (Exception ignored) {
            }
        });
    }

    private void openDownloaded(OnlineLibrary.Entry entry) {
        File file = new File(entry.path);
        if (!file.isFile()) {
            Toast.makeText(this, "Plik nie jest już dostępny", Toast.LENGTH_SHORT).show();
            filter(search.getText() == null ? "" : search.getText().toString());
            return;
        }

        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra("online_epub_path", file.getAbsolutePath());
        intent.putExtra("online_epub_title", entry.title);
        intent.putExtra("online_source_url", entry.sourceUrl);
        startActivity(intent);
    }

    private void addResult(OnlineProvider.Book book) {
        OnlineLibrary.Entry downloaded = findDownloaded(book);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        row.setBackgroundColor(Color.rgb(22, 22, 22));

        ImageView cover = coverView();
        loadCover(cover, book.coverUrl);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(12), 0, dp(8), 0);

        TextView title = new TextView(this);
        title.setText(book.title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setMaxLines(2);

        TextView author = new TextView(this);
        author.setText(book.author.isEmpty() ? book.providerName : book.author);
        author.setTextColor(Color.rgb(150, 150, 150));
        author.setTextSize(13);
        author.setPadding(0, dp(3), 0, 0);

        TextView source = new TextView(this);
        source.setText(book.providerName + " · EPUB");
        source.setTextColor(Color.rgb(99, 145, 116));
        source.setTextSize(12);
        source.setPadding(0, dp(5), 0, 0);

        info.addView(title);
        info.addView(author);
        info.addView(source);

        Button action = new Button(this);
        action.setAllCaps(false);
        if (downloaded != null) {
            action.setText("Czytaj");
            action.setOnClickListener(v -> openDownloaded(downloaded));
        } else {
            action.setText("Pobierz");
            action.setOnClickListener(v -> downloadBook(book, action));
        }

        row.addView(cover, new LinearLayout.LayoutParams(dp(56), dp(82)));
        row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(action);

        results.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(48, 48, 48));
        results.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
    }

    private OnlineLibrary.Entry findDownloaded(OnlineProvider.Book book) {
        for (OnlineLibrary.Entry entry : OnlineLibrary.load(this)) {
            if (book.providerName.equals(entry.provider) && !book.sourceUrl.isEmpty()
                    && book.sourceUrl.equals(entry.sourceUrl)) {
                return entry;
            }
        }
        return null;
    }

    private void downloadBook(OnlineProvider.Book book, Button action) {
        action.setEnabled(false);
        action.setText("Pobieranie…");
        progress.setVisibility(View.VISIBLE);
        status.setText("Pobieram „" + book.title + "”…");

        executor.execute(() -> {
            try {
                OnlineProvider provider = providerById(book.providerId);
                if (provider == null) throw new IllegalStateException("nieznane źródło");

                OnlineProvider.Download resolved = provider.resolveDownload(book);
                String epub = resolved.epubUrl;
                String coverUrl = resolved.coverUrl == null ? "" : resolved.coverUrl;

                File dir = OnlineLibrary.providerDirectory(this, provider.id());
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IllegalStateException("nie można utworzyć biblioteki Online");
                }

                String stableId = !book.sourceUrl.isEmpty() ? book.sourceUrl : book.detailUrl;
                String suffix = Integer.toHexString(stableId.hashCode());
                File target = new File(dir, safeFileName(book.title) + "-" + suffix + ".epub");
                download(epub, target);

                String coverPath = "";
                if (!coverUrl.isEmpty()) {
                    File coverFile = new File(dir, safeFileName(book.title) + "-" + suffix + ".cover");
                    try {
                        download(coverUrl, coverFile);
                        coverPath = coverFile.getAbsolutePath();
                    } catch (Exception ignored) {
                    }
                }

                OnlineLibrary.upsert(this, new OnlineLibrary.Entry(
                        provider.name(),
                        book.title,
                        book.author,
                        book.sourceUrl,
                        target.getAbsolutePath(),
                        coverUrl,
                        coverPath
                ));

                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    status.setText("Pobrano „" + book.title + "”");
                    filter(search.getText() == null ? "" : search.getText().toString());
                    Toast.makeText(this, "Książka pobrana", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    action.setEnabled(true);
                    action.setText("Pobierz");
                    progress.setVisibility(View.GONE);
                    status.setText("Katalog: " + catalog.size() + " utworów");
                    Toast.makeText(this, "Nie udało się pobrać EPUB: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private ImageView coverView() {
        ImageView cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackgroundColor(Color.rgb(36, 36, 36));
        return cover;
    }

    private void loadCover(ImageView target, String address) {
        if (address == null || address.trim().isEmpty()) return;
        String url = address.trim();

        Bitmap cached = coverCache.get(url);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }

        target.setTag(url);
        imageExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = open(url);
                Bitmap bitmap;
                try (InputStream input = new BufferedInputStream(connection.getInputStream())) {
                    bitmap = BitmapFactory.decodeStream(input);
                }
                if (bitmap == null) return;
                synchronized (coverCache) {
                    coverCache.put(url, bitmap);
                }
                runOnUiThread(() -> {
                    if (url.equals(target.getTag())) target.setImageBitmap(bitmap);
                });
            } catch (Exception ignored) {
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
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

    private OnlineProvider providerById(String id) {
        for (OnlineProvider provider : providers) {
            if (provider.id().equals(id)) return provider;
        }
        return null;
    }

    private boolean providerSelected(String providerId) {
        return ALL_PROVIDERS.equals(selectedProviderId) || selectedProviderId.equals(providerId);
    }

    private boolean providerNameSelected(String providerName) {
        if (ALL_PROVIDERS.equals(selectedProviderId)) return true;
        OnlineProvider provider = providerById(selectedProviderId);
        return provider != null && provider.name().equals(providerName);
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

}
