package com.nullyard.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;
    private int searchGeneration = 0;
    private String selectedProviderId = ALL_PROVIDERS;
    private LinearLayout results;
    private EditText search;
    private ProgressBar progress;
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        providers.add(new WolneLekturyProvider());
        providers.add(new WikisourceProvider());
        providers.add(new FbcProvider());
        if (!BuildConfig.PERSONAL_LIBRARY_URL.isEmpty()
                && !BuildConfig.PERSONAL_LIBRARY_TOKEN.isEmpty()) {
            providers.add(new PersonalLibraryProvider(
                    BuildConfig.PERSONAL_LIBRARY_URL,
                    BuildConfig.PERSONAL_LIBRARY_TOKEN
            ));
        }
        showCatalog();
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
        if (pendingSearch != null) searchHandler.removeCallbacks(pendingSearch);
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
                    scheduleSearch(search.getText() == null ? "" : search.getText().toString());
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
        search.setEnabled(true);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                scheduleSearch(s == null ? "" : s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        LinearLayout loading = new LinearLayout(this);
        loading.setGravity(Gravity.CENTER_VERTICAL);
        loading.setPadding(0, dp(12), 0, dp(8));

        progress = new ProgressBar(this);
        status = new TextView(this);
        status.setText("Wpisz tytuł lub autora");
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
        attribution.setText("Źródła: Wolne Lektury · Wikiźródła · FBC");
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

    private void scheduleSearch(String query) {
        if (pendingSearch != null) searchHandler.removeCallbacks(pendingSearch);

        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            searchGeneration++;
            catalog.clear();
            progress.setVisibility(View.GONE);
            status.setText("Wpisz tytuł lub autora");
            filter("");
            return;
        }

        final int generation = ++searchGeneration;
        pendingSearch = () -> searchProviders(trimmed, generation);
        searchHandler.postDelayed(pendingSearch, 350);
    }

    private void searchProviders(String query, int generation) {
        progress.setVisibility(View.VISIBLE);
        status.setText("Szukam…");

        executor.execute(() -> {
            ArrayList<OnlineProvider.Book> found = new ArrayList<>();
            ArrayList<String> errors = new ArrayList<>();

            for (OnlineProvider provider : providers) {
                if (!providerSelected(provider.id())) continue;
                try {
                    found.addAll(provider.search(query));
                } catch (Exception e) {
                    errors.add(provider.name());
                }
            }

            runOnUiThread(() -> {
                if (generation != searchGeneration) return;

                catalog.clear();
                catalog.addAll(found);
                progress.setVisibility(View.GONE);
                status.setText(errors.isEmpty()
                        ? "Znaleziono: " + catalog.size()
                        : "Znaleziono: " + catalog.size() + " · problem: " + String.join(", ", errors));
                filter(query);
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
        cover.setImageBitmap(generatedCover(entry.title, entry.author));
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
        source.setText("Pobrane · " + entry.provider + " · " + languageLabel(entry.language));
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
                        coverFile.getAbsolutePath(),
                        entry.language
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
        cover.setImageBitmap(generatedCover(book.title, book.author));
        loadCover(cover, book.coverUrl, book.providerId);

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
        source.setText(book.providerName + " · " + languageLabel(book.language) + " · " + book.format);
        source.setTextColor(Color.rgb(99, 145, 116));
        source.setTextSize(12);
        source.setPadding(0, dp(5), 0, 0);

        info.addView(title);
        info.addView(author);
        info.addView(source);

        Button action = new Button(this);
        action.setAllCaps(false);
        OnlineProvider provider = providerById(book.providerId);
        if (downloaded != null) {
            action.setText("Czytaj");
            action.setOnClickListener(v -> openDownloaded(downloaded));
        } else if (provider != null && !provider.canDownload(book)) {
            action.setText("Źródło");
            action.setOnClickListener(v -> openSource(book));
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

    private void openSource(OnlineProvider.Book book) {
        if (book.sourceUrl == null || book.sourceUrl.trim().isEmpty()) {
            Toast.makeText(this, "Brak linku do źródła", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(book.sourceUrl)));
        } catch (Exception e) {
            Toast.makeText(this, "Nie udało się otworzyć źródła", Toast.LENGTH_LONG).show();
        }
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
                download(epub, target, provider);

                String coverPath = "";
                if (!coverUrl.isEmpty()) {
                    File coverFile = new File(dir, safeFileName(book.title) + "-" + suffix + ".cover");
                    try {
                        download(coverUrl, coverFile, provider);
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
                        coverPath,
                        book.language
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

    private Bitmap generatedCover(String title, String author) {
        int width = 360;
        int height = 540;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        int[] backgrounds = new int[] {
                Color.rgb(31, 34, 36),
                Color.rgb(56, 61, 51),
                Color.rgb(36, 45, 56),
                Color.rgb(87, 75, 58)
        };
        String safeTitle = title == null ? "" : title.trim();
        int index = Math.floorMod(safeTitle.hashCode(), backgrounds.length);
        canvas.drawColor(backgrounds[index]);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.rgb(226, 216, 190));
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextSize(24);
        canvas.drawText("NULL YARD", 30, 48, paint);

        String initial = coverInitial(safeTitle);
        paint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        paint.setTextSize(170);
        float initialWidth = paint.measureText(initial);
        canvas.drawText(initial, (width - initialWidth) / 2.0f, 285, paint);

        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextSize(29);
        drawCoverText(canvas, paint, safeTitle, 30, 390, width - 60, 2);

        if (author != null && !author.trim().isEmpty()) {
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
            paint.setTextSize(21);
            paint.setAlpha(190);
            drawCoverText(canvas, paint, author.trim(), 30, 485, width - 60, 1);
        }

        paint.setAlpha(255);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2);
        paint.setColor(Color.argb(100, 226, 216, 190));
        canvas.drawRoundRect(new RectF(12, 12, width - 12, height - 12), 10, 10, paint);

        return bitmap;
    }

    private String coverInitial(String title) {
        if (title == null) return "N";
        for (int i = 0; i < title.length(); i++) {
            char c = title.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                return String.valueOf(Character.toUpperCase(c));
            }
        }
        return "N";
    }

    private void drawCoverText(
            Canvas canvas,
            Paint paint,
            String text,
            float x,
            float y,
            float maxWidth,
            int maxLines
    ) {
        if (text == null || text.trim().isEmpty()) return;
        String[] words = text.trim().split("\\s+");
        StringBuilder line = new StringBuilder();
        int lineCount = 0;
        float lineHeight = paint.getTextSize() * 1.18f;

        for (String word : words) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (paint.measureText(candidate) <= maxWidth) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }

            if (line.length() > 0) {
                canvas.drawText(line.toString(), x, y + lineCount * lineHeight, paint);
                lineCount++;
                if (lineCount >= maxLines) return;
            }
            line.setLength(0);
            line.append(word);
        }

        if (line.length() > 0 && lineCount < maxLines) {
            canvas.drawText(line.toString(), x, y + lineCount * lineHeight, paint);
        }
    }

    private ImageView coverView() {
        ImageView cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackgroundColor(Color.rgb(36, 36, 36));
        return cover;
    }

    private void loadCover(ImageView target, String address, String providerId) {
        if (address == null || address.trim().isEmpty()) return;
        String url = address.trim();
        OnlineProvider provider = providerById(providerId);

        Bitmap cached = coverCache.get(url);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }

        target.setTag(url);
        imageExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = open(url, provider);
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

    private void download(String address, File target, OnlineProvider provider) throws Exception {
        HttpURLConnection connection = open(address, provider);
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection open(String address, OnlineProvider provider) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(60000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "NullReader/0.3 (https://nullreader.nullyard.com)");
        connection.setRequestProperty("Accept", "application/json, application/epub+zip, */*");
        if (provider != null) provider.configureConnection(connection);
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
        if (selectedProviderId.equals(providerId)) return true;
        if (!ALL_PROVIDERS.equals(selectedProviderId)) return false;
        OnlineProvider provider = providerById(providerId);
        return provider != null && provider.includeInAllSearch();
    }

    private boolean providerNameSelected(String providerName) {
        if (ALL_PROVIDERS.equals(selectedProviderId)) return true;
        OnlineProvider provider = providerById(selectedProviderId);
        return provider != null && provider.name().equals(providerName);
    }

    private String languageLabel(String code) {
        if (code == null || code.trim().isEmpty()) return "—";
        String value = code.trim().toLowerCase(Locale.ROOT);
        if ("pl".equals(value)) return "PL";
        return value.toUpperCase(Locale.ROOT);
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
