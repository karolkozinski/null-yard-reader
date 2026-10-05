package com.nullyard.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.graphics.pdf.PdfRenderer;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
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
    private static final String PREF_LIBRARY_TAB = "library_tab";

    private final ArrayList<Book> books = new ArrayList<>();
    private SharedPreferences prefs;
    private boolean readerOpen = false;
    private Book currentBook;
    private ReaderWebView currentWebView;
    private String currentRawHtml;
    private ViewGroup currentReaderRoot;
    private LinearLayout currentReaderHeader;
    private TextView currentReaderTitle;
    private TextView currentReaderAuthor;
    private TextView currentReaderProgress;
    private View currentReaderTopChrome;
    private View currentReaderBottomChrome;
    private boolean readerChromeVisible = false;
    private final Handler previewHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingPreview;
    private int previewGeneration = 0;
    private int currentPdfPage = 0;
    private PdfRenderer currentPdfRenderer;
    private ParcelFileDescriptor currentPdfDescriptor;
    private String currentLibraryType = "EPUB";
    private boolean currentBookFromOnline = false;

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
        currentLibraryType = prefs.getString(PREF_LIBRARY_TAB, "EPUB");
        showLibrary();
        handleViewIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleViewIntent(intent);
    }

    private void handleViewIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;

        Uri uri = intent.getData();
        String onlinePath = intent.getStringExtra("online_epub_path");
        if (onlinePath != null && !onlinePath.trim().isEmpty()) {
            uri = Uri.fromFile(new java.io.File(onlinePath));
        }
        if (uri == null) return;

        int grantFlags = intent.getFlags() & (
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        );
        if ((intent.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0
                && (grantFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
            try {
                getContentResolver().takePersistableUriPermission(
                        uri,
                        grantFlags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                );
            } catch (Exception ignored) {
            }
        }

        try {
            String name = displayName(uri);
            if (name == null || name.trim().isEmpty()) {
                name = uri.getLastPathSegment() == null ? "document" : uri.getLastPathSegment();
            }
            String mime = intent.getType();
            if (mime == null || mime.trim().isEmpty()) {
                mime = getContentResolver().getType(uri);
            }

            Book incoming = createBook(name, uri, mime);
            openBook(incoming);
        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "Nie udało się otworzyć pliku: " + e.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }
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
        currentReaderAuthor = null;
        currentReaderProgress = null;
        currentReaderTopChrome = null;
        currentReaderBottomChrome = null;
        readerChromeVisible = false;
        previewGeneration++;
        applyDarkSystemBars();
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
        title.setPadding(0, dp(2), 0, dp(12));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(0, 0, 0, dp(12));

        tabs.addView(libraryTabButton("EPUB", "EPUB"), libraryTabLayoutParams());
        tabs.addView(libraryTabButton("PDF", "PDF"), libraryTabLayoutParams());
        tabs.addView(libraryTabButton("Tekst", "MD"), libraryTabLayoutParams());
        tabs.addView(onlineTabButton(), libraryTabLayoutParams());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout library = new LinearLayout(this);
        library.setOrientation(LinearLayout.VERTICAL);
        library.setPadding(0, 0, 0, dp(16));

        List<Book> visibleBooks = booksOfType(currentLibraryType);
        if (visibleBooks.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(emptyLibraryMessage(currentLibraryType));
            empty.setTextColor(Color.rgb(150, 150, 150));
            empty.setGravity(Gravity.CENTER);
            empty.setTextSize(15);
            library.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(240)));
        } else {
            for (Book book : visibleBooks) addBookRow(library, book);
        }

        scroll.addView(library, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button add = new Button(this);
        add.setText(addButtonLabel(currentLibraryType));
        add.setAllCaps(false);
        add.setOnClickListener(v -> openPicker(currentLibraryType));

        root.addView(brand);
        root.addView(title);
        root.addView(tabs);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(add, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        root.requestApplyInsets();
    }

    private Button libraryTabButton(String label, String type) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setMinHeight(dp(46));
        button.setPadding(dp(12), dp(8), dp(12), dp(8));

        boolean selected = type.equals(currentLibraryType);
        button.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        button.setTextColor(selected ? Color.rgb(226, 242, 232) : Color.rgb(166, 176, 170));

        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(10));
        background.setColor(selected ? Color.rgb(36, 61, 47) : Color.rgb(30, 35, 32));
        background.setStroke(dp(1), selected ? Color.rgb(66, 104, 80) : Color.rgb(49, 58, 53));
        button.setBackground(background);

        button.setOnClickListener(v -> {
            if (!type.equals(currentLibraryType)) {
                currentLibraryType = type;
                prefs.edit().putString(PREF_LIBRARY_TAB, type).apply();
                showLibrary();
            }
        });
        return button;
    }

    private Button onlineTabButton() {
        Button button = new Button(this);
        button.setText("Online");
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setMinHeight(dp(46));
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        button.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        button.setTextColor(Color.rgb(166, 176, 170));

        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(10));
        background.setColor(Color.rgb(30, 35, 32));
        background.setStroke(dp(1), Color.rgb(49, 58, 53));
        button.setBackground(background);

        button.setOnClickListener(v ->
                startActivity(new Intent(this, WolneLekturyActivity.class)));
        return button;
    }

    private LinearLayout.LayoutParams libraryTabLayoutParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    private String addButtonLabel(String type) {
        if ("PDF".equals(type)) return "Dodaj PDF";
        if ("MD".equals(type)) return "Dodaj plik tekstowy";
        return "Dodaj EPUB";
    }

    private String emptyLibraryMessage(String type) {
        if ("PDF".equals(type)) return "Brak plików PDF";
        if ("MD".equals(type)) return "Brak plików MD/TXT";
        return "Brak książek EPUB";
    }

    private void addBookRow(LinearLayout parent, Book book) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackgroundColor(Color.rgb(22, 22, 22));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> openBook(book));
        row.setOnLongClickListener(v -> {
            confirmRemoveBook(book);
            return true;
        });

        if ("EPUB".equals(bookType(book))) {
            ImageView cover = new ImageView(this);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setImageBitmap(bookCoverBitmap(book));
            row.addView(cover, new LinearLayout.LayoutParams(dp(58), dp(86)));
        }

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding("EPUB".equals(bookType(book)) ? dp(14) : 0, 0, 0, 0);

        TextView name = new TextView(this);
        name.setText(book.displayTitle());
        name.setTextColor(Color.WHITE);
        name.setTextSize(17);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);

        TextView meta = new TextView(this);
        String metaText = book.author != null && !book.author.trim().isEmpty()
                ? book.author
                : bookType(book);
        meta.setText(metaText);
        meta.setTextColor(Color.rgb(135, 135, 135));
        meta.setTextSize(13);
        meta.setPadding(0, dp(4), 0, 0);

        info.addView(name);
        info.addView(meta);

        String progressText = libraryProgressText(book);
        if (progressText != null) {
            TextView progress = new TextView(this);
            progress.setText(progressText);
            progress.setTextColor(Color.rgb(118, 112, 101));
            progress.setTextSize(12);
            progress.setPadding(0, dp(6), 0, 0);
            info.addView(progress);
        }

        row.addView(info, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(48, 48, 48));
        parent.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
    }

    private String libraryProgressText(Book book) {
        int percent = prefs.getInt(progressKey(book), -1);
        if (percent < 0) return null;
        percent = Math.max(0, Math.min(100, percent));
        return "Postęp  " + percent + "%";
    }

    private Bitmap bookCoverBitmap(Book book) {
        if (book.coverData != null) {
            Bitmap decoded = BitmapFactory.decodeByteArray(book.coverData, 0, book.coverData.length);
            if (isUsableCover(decoded)) return decoded;
        }
        return generatedBookCover(book);
    }

    private boolean isUsableCover(Bitmap bitmap) {
        if (bitmap == null) return false;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width < 120 || height < 160) return false;
        float ratio = width / (float) height;
        return ratio >= 0.45f && ratio <= 0.90f;
    }

    private Bitmap generatedBookCover(Book book) {
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
        int index = Math.floorMod(book.displayTitle().hashCode(), backgrounds.length);
        canvas.drawColor(backgrounds[index]);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.rgb(226, 216, 190));

        paint.setTextSize(24);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        canvas.drawText("NULL YARD", 30, 48, paint);

        String initial = coverInitial(book.displayTitle());
        paint.setTextSize(170);
        paint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        float initialWidth = paint.measureText(initial);
        canvas.drawText(initial, (width - initialWidth) / 2.0f, 285, paint);

        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextSize(29);
        drawCoverText(canvas, paint, book.displayTitle(), 30, 390, width - 60, 2);

        if (book.author != null && !book.author.trim().isEmpty()) {
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
            paint.setTextSize(21);
            paint.setAlpha(190);
            drawCoverText(canvas, paint, book.author, 30, 485, width - 60, 1);
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
        String clean = title.trim();
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (Character.isLetterOrDigit(c)) return String.valueOf(Character.toUpperCase(c));
        }
        return "N";
    }

    private void drawCoverText(Canvas canvas, Paint paint, String text, float x, float y, float maxWidth, int maxLines) {
        if (text == null) return;
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

    private void openPicker(String type) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);

        if ("PDF".equals(type)) {
            intent.setType("application/pdf");
        } else if ("MD".equals(type)) {
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                    "text/plain",
                    "text/markdown",
                    "application/octet-stream"
            });
        } else {
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                    "application/epub+zip",
                    "application/octet-stream"
            });
        }

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

        Book imported = createBook(name, uri, mime);
        books.add(imported);
        currentLibraryType = bookType(imported);
        prefs.edit().putString(PREF_LIBRARY_TAB, currentLibraryType).apply();
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
        prefs.edit()
                .remove(positionKey(book))
                .remove(progressKey(book))
                .remove(pdfPageKey(book))
                .remove(pdfCountKey(book))
                .apply();

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
        prefs.edit().putInt(pdfCountKey(book), currentPdfRenderer.getPageCount()).apply();

        readerOpen = true;
        currentBook = book;
        currentWebView = null;
        readerChromeVisible = false;

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
            if (readerChromeVisible) {
                v.setPadding(
                        0,
                        insets.getSystemWindowInsetTop(),
                        0,
                        insets.getSystemWindowInsetBottom()
                );
            } else {
                v.setPadding(0, 0, 0, 0);
            }
            return insets;
        });

        TextView top = new TextView(this);
        currentReaderTitle = top;
        top.setText("‹  " + book.displayTitle());
        top.setSingleLine(true);
        top.setEllipsize(TextUtils.TruncateAt.END);
        top.setTextColor(Color.WHITE);
        top.setTextSize(16);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(18), dp(12), dp(18), dp(12));
        top.setBackgroundColor(Color.rgb(24, 24, 24));
        top.setOnClickListener(v -> showLibrary());
        currentReaderTopChrome = top;

        ZoomImageView image = new ZoomImageView(this);
        image.setBackgroundColor(Color.rgb(32, 32, 32));
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setPadding(dp(8), dp(8), dp(8), dp(8));
        image.setOnClickListener(v -> toggleReaderChrome());
        renderPdfPageInto(image);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(dp(10), dp(8), dp(10), dp(8));
        controls.setBackgroundColor(Color.rgb(24, 24, 24));
        currentReaderBottomChrome = controls;

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
        setReaderChromeVisible(readerChromeVisible);
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
        int count = Math.max(1, currentPdfRenderer.getPageCount());
        int percent = Math.round(((currentPdfPage + 1) * 100.0f) / count);
        prefs.edit()
                .putInt(pdfPageKey(currentBook), currentPdfPage)
                .putInt(pdfCountKey(currentBook), count)
                .putInt(progressKey(currentBook), Math.max(0, Math.min(100, percent)))
                .apply();
    }

    private String pdfPageKey(Book book) {
        return "pdf-page:" + book.uri.toString();
    }

    private String pdfCountKey(Book book) {
        return "pdf-count:" + book.uri.toString();
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
        readerChromeVisible = false;

        ThemeColors colors = currentThemeColors();

        FrameLayout root = new FrameLayout(this);
        currentReaderRoot = root;
        root.setBackgroundColor(colors.background);

        LinearLayout header = new LinearLayout(this);
        currentReaderHeader = header;
        currentReaderTopChrome = header;
        currentReaderBottomChrome = null;
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), 0, dp(6), 0);
        header.setBackgroundColor(colors.chrome);

        LinearLayout bookInfo = new LinearLayout(this);
        bookInfo.setOrientation(LinearLayout.VERTICAL);
        bookInfo.setGravity(Gravity.CENTER_VERTICAL);
        bookInfo.setPadding(dp(10), dp(8), dp(8), dp(8));
        bookInfo.setOnClickListener(v -> showLibrary());

        TextView top = new TextView(this);
        currentReaderTitle = top;
        top.setText("‹  " + book.displayTitle());
        top.setSingleLine(true);
        top.setEllipsize(TextUtils.TruncateAt.END);
        top.setTextColor(colors.foreground);
        top.setTextSize(16);

        bookInfo.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (book.author != null && !book.author.trim().isEmpty()) {
            TextView author = new TextView(this);
            currentReaderAuthor = author;
            author.setText("   " + book.author);
            author.setSingleLine(true);
            author.setEllipsize(TextUtils.TruncateAt.END);
            author.setTextColor(colors.foreground);
            author.setAlpha(0.72f);
            author.setTextSize(12);
            bookInfo.addView(author, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            currentReaderAuthor = null;
        }

        TextView progress = new TextView(this);
        currentReaderProgress = progress;
        progress.setTextColor(colors.foreground);
        progress.setAlpha(0.68f);
        progress.setTextSize(12);
        progress.setPadding(dp(3), 0, dp(8), 0);
        progress.setGravity(Gravity.CENTER_VERTICAL);

        Button restart = new Button(this);
        restart.setText("↑");
        restart.setContentDescription("Do początku");
        restart.setAllCaps(false);
        restart.setMinWidth(0);
        restart.setMinimumWidth(0);
        restart.setPadding(dp(12), 0, dp(12), 0);

        Button appearance = new Button(this);
        appearance.setText("Aa");
        appearance.setAllCaps(false);
        appearance.setMinWidth(0);
        appearance.setMinimumWidth(0);
        appearance.setPadding(dp(12), 0, dp(12), 0);
        appearance.setOnClickListener(v -> showReadingSettings(book));

        header.addView(bookInfo, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        header.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        header.addView(restart, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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

        final float[] down = new float[2];
        final boolean[] tapCandidate = new boolean[1];
        web.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = event.getX();
                    down[1] = event.getY();
                    tapCandidate[0] = true;
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    tapCandidate[0] = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(event.getX() - down[0]) > dp(12)
                            || Math.abs(event.getY() - down[1]) > dp(12)) {
                        tapCandidate[0] = false;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (tapCandidate[0]) {
                        v.post(this::toggleReaderChrome);
                    }
                    tapCandidate[0] = false;
                    break;
                case MotionEvent.ACTION_CANCEL:
                    tapCandidate[0] = false;
                    break;
            }
            return false;
        });

        web.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) ->
                updateReaderProgress());

        restart.setOnClickListener(v -> {
            web.scrollTo(0, 0);
            prefs.edit()
                    .putInt(positionKey(book), 0)
                    .putInt(progressKey(book), 0)
                    .apply();
            updateReaderProgress();
        });

        final int savedY = prefs.getInt(positionKey(book), 0);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                view.post(() -> {
                    if (savedY > 0) view.scrollTo(0, savedY);
                    updateReaderProgress();
                });
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleReaderLink(view, request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleReaderLink(view, Uri.parse(url));
            }
        });

        web.loadDataWithBaseURL(
                "https://local.nullyard.invalid/",
                applyReadingStyle(html),
                "text/html",
                "UTF-8",
                null
        );

        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams headerParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
        );
        root.addView(header, headerParams);
        header.setElevation(dp(6));

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            ViewGroup.LayoutParams rawParams = header.getLayoutParams();
            if (rawParams instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) rawParams;
                int topInset = insets.getSystemWindowInsetTop();
                if (params.topMargin != topInset) {
                    params.topMargin = topInset;
                    header.setLayoutParams(params);
                }
            }
            return insets;
        });

        setContentView(root);
        setReaderChromeVisible(false);
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

        String theme = prefs.getString(PREF_THEME, "compass");
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

        final int generation = ++previewGeneration;

        if (pendingPreview != null) {
            previewHandler.removeCallbacks(pendingPreview);
        }

        pendingPreview = () -> {
            if (generation != previewGeneration || currentWebView == null || currentRawHtml == null) return;

            final ReaderWebView targetWebView = currentWebView;
            final float progress = targetWebView.getScrollProgress();
            ThemeColors colors = currentThemeColors();

            if (currentReaderRoot != null) currentReaderRoot.setBackgroundColor(colors.background);
            if (currentReaderHeader != null) currentReaderHeader.setBackgroundColor(colors.chrome);
            if (currentReaderTitle != null) currentReaderTitle.setTextColor(colors.foreground);
            if (currentReaderAuthor != null) currentReaderAuthor.setTextColor(colors.foreground);
            if (currentReaderProgress != null) currentReaderProgress.setTextColor(colors.foreground);
            targetWebView.setBackgroundColor(colors.background);
            applyReaderSystemBars(colors);

            targetWebView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    if (generation != previewGeneration || view != currentWebView) return;
                    view.postDelayed(() -> {
                        if (generation == previewGeneration && view == currentWebView) {
                            targetWebView.scrollToProgress(progress);
                            updateReaderProgress();
                        }
                    }, 80);
                }
            });

            targetWebView.loadDataWithBaseURL(
                    "https://local.nullyard.invalid/",
                    applyReadingStyle(currentRawHtml),
                    "text/html",
                    "UTF-8",
                    null
            );
        };

        previewHandler.postDelayed(pendingPreview, 140);
    }

    private String applyReadingStyle(String html) {
        int fontSize = prefs.getInt(PREF_FONT_SIZE, 17);
        int lineHeight = prefs.getInt(PREF_LINE_HEIGHT, 160);
        int margin = prefs.getInt(PREF_MARGIN, 20);
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
        String theme = prefs.getString(PREF_THEME, "compass");
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

    private void updateReaderProgress() {
        if (currentWebView == null || currentReaderProgress == null) return;

        int contentHeight = currentWebView.contentHeightPx();
        int viewportHeight = Math.max(1, getResources().getDisplayMetrics().heightPixels);
        int maxScroll = Math.max(1, contentHeight - currentWebView.viewportHeightPx());
        int scrollY = Math.max(0, currentWebView.getScrollY());

        int totalPages = Math.max(1, (int) Math.ceil(contentHeight / (double) viewportHeight));
        int currentPage = Math.min(totalPages, Math.max(1, (scrollY / viewportHeight) + 1));
        int percent = Math.min(100, Math.max(0, Math.round(scrollY * 100.0f / maxScroll)));

        currentReaderProgress.setText(currentPage + " / " + totalPages + " · " + percent + "%");
        if (currentBook != null) {
            prefs.edit().putInt(progressKey(currentBook), percent).apply();
        }
    }

    private boolean handleReaderLink(WebView view, Uri uri) {
        if (uri == null) return true;

        String scheme = uri.getScheme();
        String host = uri.getHost();

        if ("https".equalsIgnoreCase(scheme) && "local.nullyard.invalid".equalsIgnoreCase(host)) {
            String path = uri.getPath();
            String fragment = uri.getFragment();
            if ((path == null || "/".equals(path)) && fragment != null && !fragment.isEmpty()) {
                return false;
            }
            return true;
        }

        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (Exception e) {
                Toast.makeText(this, "Nie udało się otworzyć linku", Toast.LENGTH_SHORT).show();
            }
            return true;
        }

        return true;
    }

    private void toggleReaderChrome() {
        if (!readerOpen) return;
        setReaderChromeVisible(!readerChromeVisible);
    }

    private void setReaderChromeVisible(boolean visible) {
        readerChromeVisible = visible;

        int visibility = visible ? View.VISIBLE : View.GONE;
        if (currentReaderTopChrome != null) currentReaderTopChrome.setVisibility(visibility);
        if (currentReaderBottomChrome != null) currentReaderBottomChrome.setVisibility(visibility);
        if (visible) updateReaderProgress();

        if (visible) {
            applyReaderSystemBars(currentThemeColors());
        } else {
            hideReaderSystemBars();
        }

        if (currentReaderRoot != null) currentReaderRoot.requestApplyInsets();
    }

    private void hideReaderSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        );
    }

    private void applyReaderSystemBars(ThemeColors colors) {
        Window window = getWindow();
        window.setStatusBarColor(colors.chrome);
        window.setNavigationBarColor(colors.background);

        int flags =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (isLightColor(colors.chrome)) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (isLightColor(colors.background)) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private void applyDarkSystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(18, 18, 18));
        window.setNavigationBarColor(Color.rgb(18, 18, 18));
        window.getDecorView().setSystemUiVisibility(0);
    }

    private boolean isLightColor(int color) {
        double luminance =
                (0.299 * Color.red(color))
                + (0.587 * Color.green(color))
                + (0.114 * Color.blue(color));
        return luminance >= 160.0;
    }

    private void handleBackNavigation() {
        if (readerOpen) {
            if (currentBookFromOnline) {
                currentBookFromOnline = false;
                finish();
                return;
            }
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
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && readerOpen && !readerChromeVisible) {
            hideReaderSystemBars();
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
                books.add(createBook(name, uri, mime));
            } catch (Exception ignored) {
                // Stale document permissions are ignored; a future library screen can expose cleanup.
            }
        }
        saveLibrary();
    }

    private Book createBook(String name, Uri uri, String mime) {
        String lower = name == null ? "" : name.toLowerCase();
        String normalizedMime = mime == null ? "" : mime.toLowerCase();
        if (lower.endsWith(".epub") || normalizedMime.equals("application/epub+zip")) {
            try {
                EpubMetadata metadata = EpubReader.readMetadata(this, uri, displayTitle(name));
                return new Book(name, uri, mime, metadata.title, metadata.author, metadata.coverData);
            } catch (Exception ignored) {
                // Broken or unusual EPUB metadata must never prevent the file from appearing.
            }
        }
        return new Book(name, uri, mime, displayTitle(name), null, null);
    }

    private void saveLibrary() {
        Set<String> uris = new HashSet<>();
        for (Book book : books) uris.add(book.uri.toString());
        prefs.edit().putStringSet(PREF_LIBRARY, uris).apply();
    }

    private void saveReadingPosition() {
        if (!readerOpen || currentBook == null || currentWebView == null) return;
        int percent = Math.round(currentWebView.getScrollProgress() * 100.0f);
        prefs.edit()
                .putInt(positionKey(currentBook), currentWebView.getScrollY())
                .putInt(progressKey(currentBook), Math.max(0, Math.min(100, percent)))
                .apply();
    }

    private String positionKey(Book book) {
        return "position:" + book.uri.toString();
    }

    private String progressKey(Book book) {
        return "progress:" + book.uri.toString();
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

        int contentHeightPx() {
            return Math.max(1, computeVerticalScrollRange());
        }

        int viewportHeightPx() {
            return Math.max(1, computeVerticalScrollExtent());
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
        private float downX;
        private float downY;
        private boolean moved = false;
        private boolean scaling = false;
        private boolean waitForFreshDown = false;

        ZoomImageView(android.content.Context context) {
            super(context);
            setScaleType(ScaleType.FIT_CENTER);
            setClickable(true);

            scaleDetector = new ScaleGestureDetector(
                    context,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override
                        public boolean onScaleBegin(ScaleGestureDetector detector) {
                            scaling = true;
                            moved = true;
                            waitForFreshDown = true;
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
                        @Override
                        public void onScaleEnd(ScaleGestureDetector detector) {
                            scaling = false;
                            waitForFreshDown = true;
                        }

                    }
            );
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            scaleDetector.onTouchEvent(event);

            int action = event.getActionMasked();

            if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
                waitForFreshDown = true;
                return true;
            }

            if (event.getPointerCount() != 1 || scaling || scaleDetector.isInProgress()) {
                return true;
            }

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    lastX = event.getX();
                    lastY = event.getY();
                    downX = lastX;
                    downY = lastY;
                    moved = false;
                    waitForFreshDown = false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (waitForFreshDown) return true;

                    if (Math.abs(event.getX() - downX) > 18.0f
                            || Math.abs(event.getY() - downY) > 18.0f) {
                        moved = true;
                    }

                    if (zoom > 1.0f) {
                        float dx = event.getX() - lastX;
                        float dy = event.getY() - lastY;

                        if (Math.abs(dx) < 60.0f && Math.abs(dy) < 60.0f) {
                            setTranslationX(getTranslationX() + dx);
                            setTranslationY(getTranslationY() + dy);
                            clampTranslation();
                        }
                    }

                    lastX = event.getX();
                    lastY = event.getY();
                    return true;

                case MotionEvent.ACTION_UP:
                    if (!waitForFreshDown && !moved && !scaling) {
                        performClick();
                    }
                    waitForFreshDown = false;
                    moved = false;
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    waitForFreshDown = false;
                    moved = false;
                    return true;
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
        final String title;
        final String author;
        final byte[] coverData;

        Book(String name, Uri uri, String mimeType, String title, String author, byte[] coverData) {
            this.name = name;
            this.uri = uri;
            this.mimeType = mimeType;
            this.title = title;
            this.author = author;
            this.coverData = coverData;
        }

        String displayTitle() {
            return title == null || title.trim().isEmpty() ? name : title;
        }

        @Override
        public String toString() {
            return displayTitle();
        }
    }

    private static class EpubMetadata {
        final String title;
        final String author;
        final byte[] coverData;

        EpubMetadata(String title, String author, byte[] coverData) {
            this.title = title;
            this.author = author;
            this.coverData = coverData;
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
        static EpubMetadata readMetadata(Activity activity, Uri uri, String fallbackTitle) throws Exception {
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
            return new EpubMetadata(
                    metadataTitle(opf, fallbackTitle),
                    metadataAuthor(opf),
                    coverData(opf, parent(opfPath), entries)
            );
        }

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
            Set<String> coverDocuments = coverDocumentPaths(opf, opfDir);
            List<String> spine = new ArrayList<>();
            NodeList refs = opf.getElementsByTagNameNS("*", "itemref");
            for (int i = 0; i < refs.getLength(); i++) {
                Element ref = (Element) refs.item(i);
                ManifestItem item = manifest.get(ref.getAttribute("idref"));
                if (item == null) continue;
                if (!isHtml(item.mediaType, item.href)) continue;

                String resolvedPath = resolve(opfDir, item.href);
                if (coverDocuments.contains(resolvedPath)) continue;
                spine.add(resolvedPath);
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
                body = rewriteElementAnchors(body, path);
                body = rewriteLinks(body, path);
                content.append("<section class=\"chapter\" id=\"")
                        .append(chapterAnchor(path))
                        .append("\">")
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

        private static byte[] coverData(Document opf, String opfDir, Map<String, byte[]> entries) {
            NodeList items = opf.getElementsByTagNameNS("*", "item");

            for (int i = 0; i < items.getLength(); i++) {
                Element item = (Element) items.item(i);
                String properties = item.getAttribute("properties");
                String mediaType = item.getAttribute("media-type");
                if (properties != null && properties.contains("cover-image") && isImageMediaType(mediaType)) {
                    byte[] bytes = entries.get(resolve(opfDir, item.getAttribute("href")));
                    if (bytes != null) return bytes;
                }
            }

            String coverId = null;
            NodeList metas = opf.getElementsByTagNameNS("*", "meta");
            for (int i = 0; i < metas.getLength(); i++) {
                Element meta = (Element) metas.item(i);
                if ("cover".equalsIgnoreCase(meta.getAttribute("name"))) {
                    coverId = meta.getAttribute("content");
                    break;
                }
            }

            if (coverId != null && !coverId.isEmpty()) {
                for (int i = 0; i < items.getLength(); i++) {
                    Element item = (Element) items.item(i);
                    if (coverId.equals(item.getAttribute("id")) && isImageMediaType(item.getAttribute("media-type"))) {
                        byte[] bytes = entries.get(resolve(opfDir, item.getAttribute("href")));
                        if (bytes != null) return bytes;
                    }
                }
            }

            for (int i = 0; i < items.getLength(); i++) {
                Element item = (Element) items.item(i);
                String id = item.getAttribute("id").toLowerCase();
                String href = item.getAttribute("href").toLowerCase();
                if ((id.contains("cover") || href.contains("cover"))
                        && isImageMediaType(item.getAttribute("media-type"))) {
                    byte[] bytes = entries.get(resolve(opfDir, item.getAttribute("href")));
                    if (bytes != null) return bytes;
                }
            }

            return null;
        }

        private static boolean isImageMediaType(String mediaType) {
            return mediaType != null && mediaType.toLowerCase().startsWith("image/");
        }

        private static Set<String> coverDocumentPaths(Document opf, String opfDir) {
            Set<String> paths = new HashSet<>();
            NodeList references = opf.getElementsByTagNameNS("*", "reference");
            for (int i = 0; i < references.getLength(); i++) {
                Element reference = (Element) references.item(i);
                if (!"cover".equalsIgnoreCase(reference.getAttribute("type"))) continue;

                String href = reference.getAttribute("href");
                if (href != null && !href.trim().isEmpty()) {
                    paths.add(resolve(opfDir, href));
                }
            }
            return paths;
        }

        private static String metadataTitle(Document opf, String fallback) {
            NodeList titles = opf.getElementsByTagNameNS("*", "title");
            if (titles.getLength() > 0) {
                String value = titles.item(0).getTextContent();
                if (value != null && !value.trim().isEmpty()) return value.trim();
            }
            return fallback;
        }

        private static String metadataAuthor(Document opf) {
            NodeList creators = opf.getElementsByTagNameNS("*", "creator");
            if (creators.getLength() > 0) {
                String value = creators.item(0).getTextContent();
                if (value != null && !value.trim().isEmpty()) return value.trim();
            }
            return null;
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
            html = inlineImageTags(html, chapterDir, entries, "img", "src");
            html = inlineImageTags(html, chapterDir, entries, "image", "href");
            html = inlineImageTags(html, chapterDir, entries, "image", "xlink:href");

            // Embedded objects are not part of the reader surface. Leaving them in WebView
            // commonly produces Android's generic broken-resource placeholder.
            html = html.replaceAll("(?is)<object\\b[^>]*>.*?</object>", "");
            html = html.replaceAll("(?is)<embed\\b[^>]*>", "");
            return html;
        }

        private static String inlineImageTags(
                String html,
                String chapterDir,
                Map<String, byte[]> entries,
                String tagName,
                String attributeName
        ) {
            java.util.regex.Pattern tagPattern = java.util.regex.Pattern.compile(
                    "(?is)<" + tagName + "\\b[^>]*>"
            );
            java.util.regex.Pattern refPattern = java.util.regex.Pattern.compile(
                    "(?i)(\\b" + java.util.regex.Pattern.quote(attributeName)
                            + "\\s*=\\s*[\"'])([^\"']+)([\"'])"
            );
            java.util.regex.Matcher tagMatcher = tagPattern.matcher(html);
            StringBuffer out = new StringBuffer();

            while (tagMatcher.find()) {
                String tag = tagMatcher.group();
                java.util.regex.Matcher refMatcher = refPattern.matcher(tag);
                if (!refMatcher.find()) {
                    tagMatcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(tag));
                    continue;
                }

                String src = refMatcher.group(2);
                String lower = src.toLowerCase();
                if (lower.startsWith("data:")) {
                    tagMatcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(tag));
                    continue;
                }

                if (lower.startsWith("http:") || lower.startsWith("https:")) {
                    tagMatcher.appendReplacement(out, "");
                    continue;
                }

                String path = resolve(chapterDir, src);
                byte[] image = entries.get(path);
                if (image == null) {
                    tagMatcher.appendReplacement(out, "");
                    continue;
                }

                String mime = imageMime(path);
                String data = "data:" + mime + ";base64," + Base64.encodeToString(image, Base64.NO_WRAP);
                String replaced = refMatcher.replaceFirst(java.util.regex.Matcher.quoteReplacement(
                        refMatcher.group(1) + data + refMatcher.group(3)
                ));
                tagMatcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(replaced));
            }

            tagMatcher.appendTail(out);
            return out.toString();
        }

        private static String rewriteElementAnchors(String html, String chapterPath) {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    "(?i)(\\b(?:id|name)\\s*=\\s*[\"'])([^\"']+)([\"'])"
            );
            java.util.regex.Matcher matcher = pattern.matcher(html);
            StringBuffer out = new StringBuffer();

            while (matcher.find()) {
                String original = matcher.group(2);
                String rewritten = anchorFor(chapterPath, original);
                matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(
                        matcher.group(1) + rewritten + matcher.group(3)
                ));
            }

            matcher.appendTail(out);
            return out.toString();
        }

        private static String rewriteLinks(String html, String chapterPath) {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    "(?i)(\\bhref\\s*=\\s*[\"'])([^\"']*)([\"'])"
            );
            java.util.regex.Matcher matcher = pattern.matcher(html);
            StringBuffer out = new StringBuffer();

            while (matcher.find()) {
                String href = matcher.group(2).trim();
                String lower = href.toLowerCase();

                if (lower.startsWith("http://") || lower.startsWith("https://")
                        || lower.startsWith("mailto:") || lower.startsWith("tel:")) {
                    matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(
                            matcher.group(1) + href + matcher.group(3)
                    ));
                    continue;
                }

                String rewritten;
                if (href.startsWith("#")) {
                    String fragment = href.length() > 1 ? href.substring(1) : "";
                    rewritten = fragment.isEmpty()
                            ? "#" + chapterAnchor(chapterPath)
                            : "#" + anchorFor(chapterPath, fragment);
                } else {
                    int hash = href.indexOf('#');
                    String targetRef = hash >= 0 ? href.substring(0, hash) : href;
                    String fragment = hash >= 0 ? href.substring(hash + 1) : "";
                    String targetPath = targetRef.isEmpty()
                            ? chapterPath
                            : resolve(parent(chapterPath), targetRef);

                    rewritten = fragment.isEmpty()
                            ? "#" + chapterAnchor(targetPath)
                            : "#" + anchorFor(targetPath, fragment);
                }

                matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(
                        matcher.group(1) + rewritten + matcher.group(3)
                ));
            }

            matcher.appendTail(out);
            return out.toString();
        }

        private static String chapterAnchor(String path) {
            return "ny-chapter-" + Integer.toHexString(path.hashCode());
        }

        private static String anchorFor(String path, String fragment) {
            String decoded = fragment == null ? "" : fragment;
            try {
                decoded = URLDecoder.decode(decoded, "UTF-8");
            } catch (Exception ignored) {
            }
            return chapterAnchor(path) + "-a-" + Integer.toHexString(decoded.hashCode());
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
