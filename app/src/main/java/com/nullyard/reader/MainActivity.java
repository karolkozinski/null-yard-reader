package com.nullyard.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.graphics.pdf.PdfRenderer;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

public class MainActivity extends Activity {
    private static final int PICK_BOOK = 1001;
    private static final String PREFS = "reader_state";
    private static final String PREF_LIBRARY = "library_uris";
    private static final String PREF_THEME = "reading_theme";
    private static final String PREF_FONT_SIZE = "font_size";
    private static final String PREF_LINE_HEIGHT = "line_height";
    private static final String PREF_MARGIN = "reader_margin";

    private final ArrayList<Book> books = new ArrayList<>();
    private SharedPreferences prefs;
    private boolean readerOpen = false;
    private Book currentBook;
    private ReaderWebView currentWebView;
    private String currentRawHtml;
    private LinearLayout currentReaderRoot;
    private LinearLayout currentReaderHeader;
    private TextView currentReaderTitle;
    private int currentPdfPage = 0;
    private PdfRenderer currentPdfRenderer;
    private ParcelFileDescriptor currentPdfDescriptor;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(18, 18, 18));
        window.setNavigationBarColor(Color.rgb(18, 18, 18));
        window.getDecorView().setSystemUiVisibility(0);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    () -> handleBackNavigation()
            );
        }

        loadLibrary();
        showLibrary();
    }

    private void showLibrary() {
        if (readerOpen) saveReadingPosition();
        readerOpen = false;
        currentBook = null;
        currentWebView = null;
        currentRawHtml = null;
        currentReaderRoot = null;
        currentReaderHeader = null;
        currentReaderTitle = null;
        closePdf();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(18, 18, 18));

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(
                    dp(20),
                    insets.getSystemWindowInsetTop() + dp(18),
                    dp(20),
                    insets.getSystemWindowInsetBottom() + dp(16)
            );
            return insets;
        });

        TextView brand = new TextView(this);
        brand.setText("NULL YARD");
        brand.setTextColor(Color.rgb(190, 190, 190));
        brand.setTextSize(13);

        TextView title = new TextView(this);
        title.setText("Null Reader");
        title.setTextColor(Color.WHITE);
        title.setTextSize(32);
        title.setPadding(0, dp(2), 0, dp(18));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout library = new LinearLayout(this);
        library.setOrientation(LinearLayout.VERTICAL);
        library.setPadding(0, 0, 0, dp(16));

        if (books.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Biblioteka jest pusta");
            empty.setTextColor(Color.rgb(150, 150, 150));
            empty.setGravity(Gravity.CENTER);
            library.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(240)));
        } else {
            addSection(library, "EPUB", booksOfType("EPUB"));
            addSection(library, "PDF", booksOfType("PDF"));
            addSection(library, "MD", booksOfType("MD"));
        }

        scroll.addView(library, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button add = new Button(this);
        add.setText("Dodaj książkę");
        add.setAllCaps(false);
        add.setOnClickListener(v -> openPicker());

        root.addView(brand);
        root.addView(title);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(add, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        root.requestApplyInsets();
    }

    private void addSection(LinearLayout parent, String label, List<Book> sectionBooks) {
        if (sectionBooks.isEmpty()) return;

        TextView header = new TextView(this);
        header.setText(label + "  " + sectionBooks.size());
        header.setTextColor(Color.rgb(185, 185, 185));
        header.setTextSize(13);
        header.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.setPadding(0, dp(14), 0, dp(6));
        parent.addView(header);

        for (Book book : sectionBooks) addBookRow(parent, book);
    }

    private void addBookRow(LinearLayout parent, Book book) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackgroundColor(Color.rgb(22, 22, 22));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> openBook(book));
        row.setOnLongClickListener(v -> {
            confirmRemoveBook(book);
            return true;
        });

        TextView name = new TextView(this);
        name.setText(displayTitle(book.name));
        name.setTextColor(Color.WHITE);
        name.setTextSize(17);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);

        TextView meta = new TextView(this);
        meta.setText(bookType(book));
        meta.setTextColor(Color.rgb(135, 135, 135));
        meta.setTextSize(13);
        meta.setPadding(0, dp(3), 0, 0);

        row.addView(name);
        row.addView(meta);
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(48, 48, 48));
        parent.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
    }

    private List<Book> booksOfType(String type) {
        List<Book> result = new ArrayList<>();
        for (Book book : books) {
            if (type.equals(bookType(book))) result.add(book);
        }
        return result;
    }

    private String bookType(Book book) {
        String name = book.name.toLowerCase();
        String mime = book.mimeType == null ? "" : book.mimeType.toLowerCase();
        if (name.endsWith(".epub") || mime.equals("application/epub+zip")) return "EPUB";
        if (name.endsWith(".pdf") || mime.equals("application/pdf")) return "PDF";
        return "MD";
    }

    private String displayTitle(String name) {
        String lower = name.toLowerCase();
        for (String suffix : new String[] { ".epub", ".pdf", ".markdown", ".md", ".txt" }) {
            if (lower.endsWith(suffix)) return name.substring(0, name.length() - suffix.length());
        }
        return name;
    }

    private void openPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "application/epub+zip",
                "application/pdf",
                "text/plain",
                "text/markdown",
                "application/octet-stream"
        });
        startActivityForResult(intent, PICK_BOOK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_BOOK || resultCode != RESULT_OK || data == null) return;

        Uri uri = data.getData();
        if (uri == null) return;

        int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Exception ignored) {
        }

        String name = displayName(uri);
        String mime = getContentResolver().getType(uri);

        for (Book book : books) {
            if (book.uri.equals(uri)) return;
        }

        books.add(new Book(name, uri, mime));
        saveLibrary();
        showLibrary();
    }

    private void confirmRemoveBook(Book book) {
        new AlertDialog.Builder(this)
                .setTitle("Usuń z biblioteki?")
                .setMessage("" + book.name + "\n\nPlik źródłowy pozostanie bez zmian.")
                .setNegativeButton("Anuluj", null)
                .setPositiveButton("Usuń", (dialog, which) -> removeBook(book))
                .show();
    }

    private void removeBook(Book book) {
        books.remove(book);
        prefs.edit().remove(positionKey(book)).remove(pdfPageKey(book)).apply();

        try {
            getContentResolver().releasePersistableUriPermission(
                    book.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
        } catch (Exception ignored) {
            // The document may not have a persistable permission; removing from our library still works.
        }

        saveLibrary();
        Toast.makeText(this, "Usunięto z biblioteki", Toast.LENGTH_SHORT).show();
        showLibrary();
    }

    private void openBook(Book book) {
        String type = bookType(book);

        try {
            if ("EPUB".equals(type)) {
                showReader(book, EpubReader.read(this, book.uri, book.name));
                return;
            }

            if ("MD".equals(type)) {
                showReader(book, TextReader.read(this, book.uri, book.name));
                return;
            }

            if ("PDF".equals(type)) {
                showPdfReader(book);
                return;
            }
        } catch (Exception e) {
            Toast.makeText(this, "Nie udało się otworzyć pliku: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showPdfReader(Book book) throws Exception {
        closePdf();

        currentPdfDescriptor = getContentResolver().openFileDescriptor(book.uri, "r");
        if (currentPdfDescriptor == null) throw new IllegalArgumentException("brak dostępu do PDF");

        currentPdfRenderer = new PdfRenderer(currentPdfDescriptor);
        if (currentPdfRenderer.getPageCount() == 0) {
            throw new IllegalArgumentException("PDF nie zawiera stron");
        }

        readerOpen = true;
        currentBook = book;
        currentWebView = null;

        int savedPage = prefs.getInt(pdfPageKey(book), 0);
        currentPdfPage = Math.max(0, Math.min(savedPage, currentPdfRenderer.getPageCount() - 1));

        renderPdfScreen(book);
    }

    private void renderPdfScreen(Book book) {
        if (currentPdfRenderer == null) return;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(18, 18, 18));

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(
                    0,
                    insets.getSystemWindowInsetTop(),
                    0,
                    insets.getSystemWindowInsetBottom()
            );
            return insets;
        });

        TextView top = new TextView(this);
        currentReaderTitle = top;
        top.setText("‹  " + displayTitle(book.name));
        top.setSingleLine(true);
        top.setEllipsize(TextUtils.TruncateAt.END);
        top.setTextColor(Color.WHITE);
        top.setTextSize(16);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(18), dp(12), dp(18), dp(12));
        top.setBackgroundColor(Color.rgb(24, 24, 24));
        top.setOnClickListener(v -> showLibrary());

        ZoomImageView image = new ZoomImageView(this);
        image.setBackgroundColor(Color.rgb(32, 32, 32));
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setPadding(dp(8), dp(8), dp(8), dp(8));
        renderPdfPageInto(image);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(dp(10), dp(8), dp(10), dp(8));
        controls.setBackgroundColor(Color.rgb(24, 24, 24));

        Button previous = new Button(this);
        previous.setText("‹");
        previous.setEnabled(currentPdfPage > 0);
        previous.setOnClickListener(v -> {
            savePdfPage();
            if (currentPdfPage > 0) {
                currentPdfPage--;
                renderPdfScreen(book);
            }
        });

        TextView page = new TextView(this);
        page.setText((currentPdfPage + 1) + " / " + currentPdfRenderer.getPageCount());
        page.setTextColor(Color.WHITE);
        page.setTextSize(14);
        page.setGravity(Gravity.CENTER);

        Button next = new Button(this);
        next.setText("›");
        next.setEnabled(currentPdfPage < currentPdfRenderer.getPageCount() - 1);
        next.setOnClickListener(v -> {
            savePdfPage();
            if (currentPdfPage < currentPdfRenderer.getPageCount() - 1) {
                currentPdfPage++;
                renderPdfScreen(book);
            }
        });

        controls.addView(previous, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        controls.addView(page, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2));
        controls.addView(next, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(image, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(controls, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        root.requestApplyInsets();
    }

    private void renderPdfPageInto(ImageView image) {
        PdfRenderer.Page page = null;
        try {
            page = currentPdfRenderer.openPage(currentPdfPage);

            int targetWidth = Math.max(
                    getResources().getDisplayMetrics().widthPixels - dp(16),
                    dp(320)
            );
            float scale = (float) targetWidth / (float) page.getWidth();
            int targetHeight = Math.max(1, Math.round(page.getHeight() * scale));

            Bitmap bitmap = Bitmap.createBitmap(
                    targetWidth,
                    targetHeight,
                    Bitmap.Config.ARGB_8888
            );
            bitmap.eraseColor(Color.WHITE);

            page.render(
                    bitmap,
                    null,
                    null,
                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
            );
            image.setImageBitmap(bitmap);
        } finally {
            if (page != null) page.close();
        }
    }

    private void savePdfPage() {
        if (currentBook == null || currentPdfRenderer == null) return;
        prefs.edit().putInt(pdfPageKey(currentBook), currentPdfPage).apply();
    }

    private String pdfPageKey(Book book) {
        return "pdf-page:" + book.uri.toString();
    }

    private void closePdf() {
        savePdfPage();

        if (currentPdfRenderer != null) {
            try {
                currentPdfRenderer.close();
            } catch (Exception ignored) {
            }
            currentPdfRenderer = null;
        }

        if (currentPdfDescriptor != null) {
            try {
                currentPdfDescriptor.close();
            } catch (Exception ignored) {
            }
            currentPdfDescriptor = null;
        }
    }

    private void showReader(Book book, String html) {
        readerOpen = true;
        currentBook = book;
        currentRawHtml = html;

        ThemeColors colors = currentThemeColors();

        LinearLayout root = new LinearLayout(this);
        currentReaderRoot = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(colors.background);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(
                    0,
                    insets.getSystemWindowInsetTop(),
                    0,
                    insets.getSystemWindowInsetBottom()
            );
            return insets;
        });

        LinearLayout header = new LinearLayout(this);
        currentReaderHeader = header;
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), 0, dp(6), 0);
        header.setBackgroundColor(colors.chrome);

        TextView top = new TextView(this);
        top.setText("‹  " + displayTitle(book.name));
        top.setSingleLine(true);
        top.setEllipsize(TextUtils.TruncateAt.END);
        top.setTextColor(colors.foreground);
        top.setTextSize(16);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(10), dp(12), dp(8), dp(12));
        top.setOnClickListener(v -> showLibrary());

        Button appearance = new Button(this);
        appearance.setText("Aa");
        appearance.setAllCaps(false);
        appearance.setMinWidth(0);
        appearance.setMinimumWidth(0);
        appearance.setPadding(dp(12), 0, dp(12), 0);
        appearance.setOnClickListener(v -> showReadingSettings(book));

        header.addView(top, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        header.addView(appearance, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ReaderWebView web = new ReaderWebView(this);
        currentWebView = web;
        web.setBackgroundColor(colors.background);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(100);

        final int savedY = prefs.getInt(positionKey(book), 0);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (savedY > 0) view.post(() -> view.scrollTo(0, savedY));
            }
        });

        web.loadDataWithBaseURL(
                "https://local.nullyard.invalid/",
                applyReadingStyle(html),
                "text/html",
                "UTF-8",
                null
        );

        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
        root.requestApplyInsets();
    }

    private void showReadingSettings(Book book) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(4));

        TextView profileLabel = new TextView(this);
        profileLabel.setText("Profil");
        profileLabel.setTextSize(14);
        profileLabel.setPadding(0, dp(6), 0, dp(4));
        box.addView(profileLabel);

        RadioGroup profiles = new RadioGroup(this);
        profiles.setOrientation(RadioGroup.HORIZONTAL);

        RadioButton dark = new RadioButton(this);
        dark.setText("Ciemny");
        dark.setId(View.generateViewId());

        RadioButton light = new RadioButton(this);
        light.setText("Jasny");
        light.setId(View.generateViewId());

        RadioButton compass = new RadioButton(this);
        compass.setText("Kompas");
        compass.setId(View.generateViewId());

        profiles.addView(dark, new RadioGroup.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        profiles.addView(light, new RadioGroup.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        profiles.addView(compass, new RadioGroup.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        String theme = prefs.getString(PREF_THEME, "dark");
        if ("light".equals(theme)) light.setChecked(true);
        else if ("compass".equals(theme)) compass.setChecked(true);
        else dark.setChecked(true);

        profiles.setOnCheckedChangeListener((group, checkedId) -> {
            String selected = checkedId == light.getId()
                    ? "light"
                    : checkedId == compass.getId() ? "compass" : "dark";
            prefs.edit().putString(PREF_THEME, selected).apply();
            previewReadingStyle();
        });

        box.addView(profiles);

        Runnable preview = this::previewReadingStyle;

        box.addView(settingRow(
                "Rozmiar tekstu",
                PREF_FONT_SIZE,
                19,
                14,
                32,
                1,
                "",
                preview
        ));

        box.addView(settingRow(
                "Interlinia",
                PREF_LINE_HEIGHT,
                165,
                120,
                220,
                5,
                "%",
                preview
        ));

        box.addView(settingRow(
                "Margines",
                PREF_MARGIN,
                22,
                8,
                48,
                2,
                " dp",
                preview
        ));

        new AlertDialog.Builder(this)
                .setTitle("Czytanie")
                .setView(box)
                .setPositiveButton("Gotowe", null)
                .show();
    }

    private View settingRow(
            String label,
            String key,
            int defaultValue,
            int min,
            int max,
            int step,
            String suffix,
            Runnable onChange
    ) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, 0);

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextSize(14);

        Button minus = new Button(this);
        minus.setText("−");

        TextView value = new TextView(this);
        value.setGravity(Gravity.CENTER);
        value.setTextSize(14);

        Button plus = new Button(this);
        plus.setText("+");

        Runnable refresh = () -> value.setText(
                prefs.getInt(key, defaultValue) + suffix
        );
        refresh.run();

        minus.setOnClickListener(v -> {
            int current = prefs.getInt(key, defaultValue);
            int updated = Math.max(min, current - step);
            if (updated != current) {
                prefs.edit().putInt(key, updated).apply();
                refresh.run();
                onChange.run();
            }
        });

        plus.setOnClickListener(v -> {
            int current = prefs.getInt(key, defaultValue);
            int updated = Math.min(max, current + step);
            if (updated != current) {
                prefs.edit().putInt(key, updated).apply();
                refresh.run();
                onChange.run();
            }
        });

        row.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(minus);
        row.addView(value, new LinearLayout.LayoutParams(dp(70), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(plus);
        return row;
    }

    private void previewReadingStyle() {
        if (currentWebView == null || currentRawHtml == null) return;

        final float progress = currentWebView.getScrollProgress();
        ThemeColors colors = currentThemeColors();

        if (currentReaderRoot != null) currentReaderRoot.setBackgroundColor(colors.background);
        if (currentReaderHeader != null) currentReaderHeader.setBackgroundColor(colors.chrome);
        if (currentReaderTitle != null) currentReaderTitle.setTextColor(colors.foreground);
        currentWebView.setBackgroundColor(colors.background);

        currentWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                view.postDelayed(() -> {
                    if (currentWebView != null) {
                        currentWebView.scrollToProgress(progress);
                    }
                }, 120);
            }
        });

        currentWebView.loadDataWithBaseURL(
                "https://local.nullyard.invalid/",
                applyReadingStyle(currentRawHtml),
                "text/html",
                "UTF-8",
                null
        );
    }

    private String applyReadingStyle(String html) {
        int fontSize = prefs.getInt(PREF_FONT_SIZE, 19);
        int lineHeight = prefs.getInt(PREF_LINE_HEIGHT, 165);
        int margin = prefs.getInt(PREF_MARGIN, 22);
        ThemeColors colors = currentThemeColors();

        String css = "<style id=\"null-reader-user-style\">"
                + "html,body{background:" + colors.backgroundCss + "!important;"
                + "color:" + colors.foregroundCss + "!important;}"
                + "body{font-size:" + fontSize + "px!important;"
                + "line-height:" + (lineHeight / 100.0f) + "!important;"
                + "padding-left:" + margin + "px!important;"
                + "padding-right:" + margin + "px!important;}"
                + "h1,h2,h3,h4,h5,h6{color:" + colors.foregroundCss + "!important;}"
                + "a{color:" + colors.linkCss + "!important;}"
                + "pre,code{background:" + colors.codeCss + "!important;}"
                + "blockquote{color:" + colors.secondaryCss + "!important;}"
                + "</style>";

        int headEnd = html.toLowerCase().indexOf("</head>");
        if (headEnd >= 0) {
            return html.substring(0, headEnd) + css + html.substring(headEnd);
        }
        return css + html;
    }

    private ThemeColors currentThemeColors() {
        String theme = prefs.getString(PREF_THEME, "dark");
        if ("light".equals(theme)) {
            return new ThemeColors(
                    Color.rgb(247, 247, 244),
                    Color.rgb(255, 255, 252),
                    Color.rgb(28, 28, 28),
                    "#f7f7f4",
                    "#1c1c1c",
                    "#4b5f8a",
                    "#e9e9e5",
                    "#555555"
            );
        }

        if ("compass".equals(theme)) {
            return new ThemeColors(
                    Color.rgb(227, 216, 190),
                    Color.rgb(214, 201, 172),
                    Color.rgb(45, 38, 30),
                    "#e3d8be",
                    "#2d261e",
                    "#6a4d2f",
                    "#d1c3a3",
                    "#655848"
            );
        }

        return new ThemeColors(
                Color.rgb(18, 18, 18),
                Color.rgb(24, 24, 24),
                Color.rgb(232, 232, 232),
                "#121212",
                "#e8e8e8",
                "#cfcfcf",
                "#202124",
                "#cccccc"
        );
    }

    private void handleBackNavigation() {
        if (readerOpen) {
            showLibrary();
        } else {
            moveTaskToBack(true);
        }
    }

    @Override
    public void onBackPressed() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            handleBackNavigation();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        saveReadingPosition();
        savePdfPage();
        super.onPause();
    }

    private void loadLibrary() {
        Set<String> uris = new HashSet<>(prefs.getStringSet(PREF_LIBRARY, new HashSet<>()));

        for (UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
            if (permission.isReadPermission()) uris.add(permission.getUri().toString());
        }

        books.clear();
        for (String value : uris) {
            try {
                Uri uri = Uri.parse(value);
                String name = displayName(uri);
                String mime = getContentResolver().getType(uri);
                books.add(new Book(name, uri, mime));
            } catch (Exception ignored) {
                // Stale document permissions are ignored; a future library screen can expose cleanup.
            }
        }
        saveLibrary();
    }

    private void saveLibrary() {
        Set<String> uris = new HashSet<>();
        for (Book book : books) uris.add(book.uri.toString());
        prefs.edit().putStringSet(PREF_LIBRARY, uris).apply();
    }

    private void saveReadingPosition() {
        if (!readerOpen || currentBook == null || currentWebView == null) return;
        prefs.edit().putInt(positionKey(currentBook), currentWebView.getScrollY()).apply();
    }

    private String positionKey(Book book) {
        return "position:" + book.uri.toString();
    }

    private String displayName(Uri uri) {
        Cursor cursor = getContentResolver().query(
                uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null);
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) return cursor.getString(index);
                }
            } finally {
                cursor.close();
            }
        }
        String fallback = uri.getLastPathSegment();
        return fallback != null ? fallback : "Bez tytułu";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static class ReaderWebView extends WebView {
        ReaderWebView(android.content.Context context) {
            super(context);
        }

        float getScrollProgress() {
            int range = computeVerticalScrollRange() - computeVerticalScrollExtent();
            if (range <= 0) return 0.0f;
            return Math.max(0.0f, Math.min(1.0f, getScrollY() / (float) range));
        }

        void scrollToProgress(float progress) {
            int range = computeVerticalScrollRange() - computeVerticalScrollExtent();
            if (range <= 0) {
                scrollTo(0, 0);
                return;
            }
            int target = Math.round(Math.max(0.0f, Math.min(1.0f, progress)) * range);
            scrollTo(0, target);
        }
    }

    private static class ThemeColors {
        final int background;
        final int chrome;
        final int foreground;
        final String backgroundCss;
        final String foregroundCss;
        final String linkCss;
        final String codeCss;
        final String secondaryCss;

        ThemeColors(
                int background,
                int chrome,
                int foreground,
                String backgroundCss,
                String foregroundCss,
                String linkCss,
                String codeCss,
                String secondaryCss
        ) {
            this.background = background;
            this.chrome = chrome;
            this.foreground = foreground;
            this.backgroundCss = backgroundCss;
            this.foregroundCss = foregroundCss;
            this.linkCss = linkCss;
            this.codeCss = codeCss;
            this.secondaryCss = secondaryCss;
        }
    }

    private static class ZoomImageView extends ImageView {
        private final ScaleGestureDetector scaleDetector;
        private float zoom = 1.0f;
        private float lastX;
        private float lastY;

        ZoomImageView(android.content.Context context) {
            super(context);
            setScaleType(ScaleType.FIT_CENTER);
            setClickable(true);

            scaleDetector = new ScaleGestureDetector(
                    context,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override
                        public boolean onScaleBegin(ScaleGestureDetector detector) {
                            return true;
                        }

                        @Override
                        public boolean onScale(ScaleGestureDetector detector) {
                            zoom *= detector.getScaleFactor();
                            zoom = Math.max(1.0f, Math.min(4.0f, zoom));

                            setScaleX(zoom);
                            setScaleY(zoom);

                            if (zoom <= 1.001f) {
                                zoom = 1.0f;
                                setScaleX(1.0f);
                                setScaleY(1.0f);
                                setTranslationX(0.0f);
                                setTranslationY(0.0f);
                            } else {
                                clampTranslation();
                            }
                            return true;
                        }
                    }
            );
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            scaleDetector.onTouchEvent(event);

            if (event.getPointerCount() == 1 && !scaleDetector.isInProgress()) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        lastX = event.getX();
                        lastY = event.getY();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        if (zoom > 1.0f) {
                            float dx = event.getX() - lastX;
                            float dy = event.getY() - lastY;

                            setTranslationX(getTranslationX() + dx);
                            setTranslationY(getTranslationY() + dy);
                            clampTranslation();
                        }

                        lastX = event.getX();
                        lastY = event.getY();
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        performClick();
                        return true;
                }
            }

            return true;
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }

        @Override
        public void setImageBitmap(Bitmap bm) {
            super.setImageBitmap(bm);
            resetZoom();
        }

        private void resetZoom() {
            zoom = 1.0f;
            setScaleX(1.0f);
            setScaleY(1.0f);
            setTranslationX(0.0f);
            setTranslationY(0.0f);
        }

        private void clampTranslation() {
            float maxX = Math.max(0.0f, getWidth() * (zoom - 1.0f) / 2.0f);
            float maxY = Math.max(0.0f, getHeight() * (zoom - 1.0f) / 2.0f);

            setTranslationX(Math.max(-maxX, Math.min(maxX, getTranslationX())));
            setTranslationY(Math.max(-maxY, Math.min(maxY, getTranslationY())));
        }
    }

    private static class Book {
        final String name;
        final Uri uri;
        final String mimeType;

        Book(String name, Uri uri, String mimeType) {
            this.name = name;
            this.uri = uri;
            this.mimeType = mimeType;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static class TextReader {
        static String read(Activity activity, Uri uri, String fallbackTitle) throws Exception {
            String text;
            try (InputStream input = activity.getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IllegalArgumentException("brak dostępu do pliku");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
                text = new String(out.toByteArray(), StandardCharsets.UTF_8);
            }

            boolean markdown = fallbackTitle.toLowerCase().endsWith(".md")
                    || fallbackTitle.toLowerCase().endsWith(".markdown");
            String body = markdown ? markdownToHtml(text) : plainTextToHtml(text);

            return "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                    + "<style>"
                    + "html,body{margin:0;padding:0;background:#121212;color:#e8e8e8;max-width:100%;overflow-x:hidden;}"
                    + "*{box-sizing:border-box;}"
                    + "body{font-family:serif;font-size:19px;line-height:1.65;padding:28px 28px 72px;max-width:100%;overflow-wrap:anywhere;}"
                    + "h1,h2,h3,h4,h5,h6{font-family:sans-serif;line-height:1.25;color:#fff;margin:1.5em 0 .7em;}"
                    + "h1{font-size:1.8em}h2{font-size:1.5em}h3{font-size:1.25em}"
                    + "p{margin:0 0 1em;}ul,ol{padding-left:1.5em;margin:0 0 1em;}li{margin:.25em 0;}"
                    + "blockquote{border-left:3px solid #555;padding:.2em 0 .2em 1em;margin:1em 0;color:#ccc;}"
                    + "code{font-family:monospace;background:#202124;padding:.12em .3em;border-radius:3px;}"
                    + "pre{font-family:monospace;background:#202124;padding:1em;max-width:100%;overflow-x:auto;white-space:pre-wrap;word-break:break-word;}"
                    + "a{color:#cfcfcf;}hr{border:0;border-top:1px solid #333;margin:2em 0;}"
                    + "</style><title>" + escapeHtml(titleWithoutExtension(fallbackTitle))
                    + "</title></head><body>" + body + "</body></html>";
        }

        private static String plainTextToHtml(String text) {
            String escaped = escapeHtml(text).replace("\r\n", "\n").replace("\r", "\n");
            String[] blocks = escaped.split("\n\\s*\n");
            StringBuilder html = new StringBuilder();
            for (String block : blocks) {
                if (block.trim().isEmpty()) continue;
                html.append("<p>").append(block.trim().replace("\n", "<br>")).append("</p>");
            }
            return html.toString();
        }

        private static String markdownToHtml(String text) {
            String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
            String[] lines = normalized.split("\n", -1);
            StringBuilder html = new StringBuilder();
            StringBuilder paragraph = new StringBuilder();
            boolean inCode = false;
            boolean inList = false;

            for (String raw : lines) {
                String trimmed = raw.trim();

                if (trimmed.startsWith("```")) {
                    flushParagraph(html, paragraph);
                    if (inList) { html.append("</ul>"); inList = false; }
                    if (!inCode) { html.append("<pre><code>"); inCode = true; }
                    else { html.append("</code></pre>"); inCode = false; }
                    continue;
                }

                if (inCode) {
                    html.append(escapeHtml(raw)).append("\n");
                    continue;
                }

                if (trimmed.isEmpty()) {
                    flushParagraph(html, paragraph);
                    if (inList) { html.append("</ul>"); inList = false; }
                    continue;
                }

                if (trimmed.matches("^#{1,6}\\s+.*")) {
                    flushParagraph(html, paragraph);
                    if (inList) { html.append("</ul>"); inList = false; }
                    int level = 0;
                    while (level < trimmed.length() && trimmed.charAt(level) == '#') level++;
                    html.append("<h").append(level).append(">")
                            .append(inlineMarkdown(trimmed.substring(level).trim()))
                            .append("</h").append(level).append(">");
                    continue;
                }

                if (trimmed.matches("^[-*+]\\s+.*")) {
                    flushParagraph(html, paragraph);
                    if (!inList) { html.append("<ul>"); inList = true; }
                    html.append("<li>")
                            .append(inlineMarkdown(trimmed.replaceFirst("^[-*+]\\s+", "")))
                            .append("</li>");
                    continue;
                }

                if (trimmed.startsWith(">")) {
                    flushParagraph(html, paragraph);
                    html.append("<blockquote>")
                            .append(inlineMarkdown(trimmed.substring(1).trim()))
                            .append("</blockquote>");
                    continue;
                }

                if (paragraph.length() > 0) paragraph.append(' ');
                paragraph.append(trimmed);
            }

            flushParagraph(html, paragraph);
            if (inList) html.append("</ul>");
            if (inCode) html.append("</code></pre>");
            return html.toString();
        }

        private static void flushParagraph(StringBuilder html, StringBuilder paragraph) {
            if (paragraph.length() == 0) return;
            html.append("<p>").append(inlineMarkdown(paragraph.toString())).append("</p>");
            paragraph.setLength(0);
        }

        private static String inlineMarkdown(String text) {
            String value = escapeHtml(text);
            value = value.replaceAll("`([^`]+)`", "<code>$1</code>");
            value = value.replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
            value = value.replaceAll("__([^_]+)__", "<strong>$1</strong>");
            value = value.replaceAll("(?<!\\*)\\*([^*]+)\\*(?!\\*)", "<em>$1</em>");
            return value;
        }

        private static String escapeHtml(String text) {
            return text.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;");
        }

        private static String titleWithoutExtension(String name) {
            String lower = name.toLowerCase();
            for (String suffix : new String[] { ".markdown", ".epub", ".pdf", ".md", ".txt" }) {
                if (lower.endsWith(suffix)) return name.substring(0, name.length() - suffix.length());
            }
            return name;
        }
    }

    private static class EpubReader {
        static String read(Activity activity, Uri uri, String fallbackTitle) throws Exception {
            Map<String, byte[]> entries = unzip(activity.getContentResolver().openInputStream(uri));

            byte[] containerBytes = entries.get("META-INF/container.xml");
            if (containerBytes == null) throw new IllegalArgumentException("brak META-INF/container.xml");

            Document container = parseXml(containerBytes);
            NodeList roots = container.getElementsByTagNameNS("*", "rootfile");
            if (roots.getLength() == 0) throw new IllegalArgumentException("brak pliku OPF");

            String opfPath = ((Element) roots.item(0)).getAttribute("full-path");
            byte[] opfBytes = entries.get(opfPath);
            if (opfBytes == null) throw new IllegalArgumentException("nie znaleziono " + opfPath);

            Document opf = parseXml(opfBytes);
            Map<String, ManifestItem> manifest = new HashMap<>();

            NodeList items = opf.getElementsByTagNameNS("*", "item");
            for (int i = 0; i < items.getLength(); i++) {
                Element item = (Element) items.item(i);
                manifest.put(
                        item.getAttribute("id"),
                        new ManifestItem(
                                item.getAttribute("href"),
                                item.getAttribute("media-type")
                        )
                );
            }

            String opfDir = parent(opfPath);
            List<String> spine = new ArrayList<>();
            NodeList refs = opf.getElementsByTagNameNS("*", "itemref");
            for (int i = 0; i < refs.getLength(); i++) {
                Element ref = (Element) refs.item(i);
                ManifestItem item = manifest.get(ref.getAttribute("idref"));
                if (item == null) continue;
                if (!isHtml(item.mediaType, item.href)) continue;
                spine.add(resolve(opfDir, item.href));
            }

            if (spine.isEmpty()) throw new IllegalArgumentException("EPUB nie ma czytelnego spine");

            String title = metadataTitle(opf, fallbackTitle);
            StringBuilder content = new StringBuilder();

            for (String path : spine) {
                byte[] chapterBytes = entries.get(path);
                if (chapterBytes == null) continue;

                String chapter = new String(chapterBytes, StandardCharsets.UTF_8);
                String body = bodyOf(chapter);
                body = removeScripts(body);
                body = inlineImages(body, parent(path), entries);
                content.append("<section class=\"chapter\">")
                        .append(body)
                        .append("</section>");
            }

            if (content.length() == 0) throw new IllegalArgumentException("nie udało się odczytać rozdziałów");

            return "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                    + "<style>"
                    + "html,body{margin:0;padding:0;background:#121212;color:#e8e8e8;max-width:100%;overflow-x:hidden;}"
                    + "*{box-sizing:border-box;}"
                    + "body{font-family:serif;font-size:19px;line-height:1.65;padding:28px 28px 72px;max-width:100%;overflow-wrap:anywhere;}"
                    + "h1,h2,h3,h4{font-family:sans-serif;line-height:1.25;color:#fff;margin-top:1.6em;}"
                    + "p{margin:0 0 1em;} img{max-width:100%;height:auto;display:block;margin:1.5em auto;}"
                    + "a{color:#cfcfcf;} .chapter+ .chapter{margin-top:3em;padding-top:2em;border-top:1px solid #333;}"
                    + "blockquote{border-left:3px solid #555;padding-left:1em;color:#ccc;}"
                    + "</style><title>" + escape(title) + "</title></head><body>"
                    + content
                    + "</body></html>";
        }

        private static Map<String, byte[]> unzip(InputStream input) throws Exception {
            if (input == null) throw new IllegalArgumentException("brak dostępu do pliku");

            Map<String, byte[]> files = new LinkedHashMap<>();
            try (ZipInputStream zip = new ZipInputStream(input)) {
                ZipEntry entry;
                byte[] buffer = new byte[16 * 1024];

                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;

                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    files.put(entry.getName(), out.toByteArray());
                }
            }
            return files;
        }

        private static Document parseXml(byte[] data) throws Exception {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setExpandEntityReferences(false);

            safeSetFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            safeSetFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
            safeSetFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            safeSetFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(data));
        }

        private static void safeSetFeature(DocumentBuilderFactory factory, String feature, boolean value) {
            try {
                factory.setFeature(feature, value);
            } catch (Exception ignored) {
                // Android XML parsers differ by API level; unsupported hardening flags are skipped.
            }
        }

        private static String metadataTitle(Document opf, String fallback) {
            NodeList titles = opf.getElementsByTagNameNS("*", "title");
            if (titles.getLength() > 0) {
                String value = titles.item(0).getTextContent();
                if (value != null && !value.trim().isEmpty()) return value.trim();
            }
            return fallback;
        }

        private static boolean isHtml(String mediaType, String href) {
            if ("application/xhtml+xml".equals(mediaType) || "text/html".equals(mediaType)) return true;
            String lower = href.toLowerCase();
            return lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm");
        }

        private static String bodyOf(String html) {
            java.util.regex.Matcher match = java.util.regex.Pattern
                    .compile("(?is)<body[^>]*>(.*?)</body>")
                    .matcher(html);
            return match.find() ? match.group(1) : html;
        }

        private static String removeScripts(String html) {
            return html.replaceAll("(?is)<script[^>]*>.*?</script>", "");
        }

        private static String inlineImages(String html, String chapterDir, Map<String, byte[]> entries) {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    "(?i)(<img\\b[^>]*?\\bsrc\\s*=\\s*[\"'])([^\"']+)([\"'])"
            );
            java.util.regex.Matcher matcher = pattern.matcher(html);
            StringBuffer out = new StringBuffer();

            while (matcher.find()) {
                String src = matcher.group(2);
                if (src.startsWith("data:") || src.startsWith("http:") || src.startsWith("https:")) {
                    continue;
                }

                String path = resolve(chapterDir, src);
                byte[] image = entries.get(path);
                if (image == null) continue;

                String mime = imageMime(path);
                String data = "data:" + mime + ";base64," + Base64.encodeToString(image, Base64.NO_WRAP);
                matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(
                        matcher.group(1) + data + matcher.group(3)
                ));
            }
            matcher.appendTail(out);
            return out.toString();
        }

        private static String imageMime(String path) {
            String lower = path.toLowerCase();
            if (lower.endsWith(".png")) return "image/png";
            if (lower.endsWith(".gif")) return "image/gif";
            if (lower.endsWith(".webp")) return "image/webp";
            if (lower.endsWith(".svg")) return "image/svg+xml";
            return "image/jpeg";
        }

        private static String resolve(String baseDir, String href) {
            try {
                href = URLDecoder.decode(href, "UTF-8");
            } catch (Exception ignored) {
            }

            int hash = href.indexOf('#');
            if (hash >= 0) href = href.substring(0, hash);

            String joined = href.startsWith("/") ? href.substring(1) : baseDir + href;
            ArrayDeque<String> parts = new ArrayDeque<>();

            for (String part : joined.split("/")) {
                if (part.isEmpty() || ".".equals(part)) continue;
                if ("..".equals(part)) {
                    if (!parts.isEmpty()) parts.removeLast();
                } else {
                    parts.addLast(part);
                }
            }
            return String.join("/", parts);
        }

        private static String parent(String path) {
            int slash = path.lastIndexOf('/');
            return slash >= 0 ? path.substring(0, slash + 1) : "";
        }

        private static String escape(String text) {
            return text.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;");
        }

        private static class ManifestItem {
            final String href;
            final String mediaType;

            ManifestItem(String href, String mediaType) {
                this.href = href;
                this.mediaType = mediaType;
            }
        }
    }
}
