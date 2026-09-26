package com.nullyard.reader;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Base64;
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
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

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

    private final ArrayList<Book> books = new ArrayList<>();
    private ArrayAdapter<Book> adapter;
    private SharedPreferences prefs;
    private boolean readerOpen = false;
    private Book currentBook;
    private WebView currentWebView;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(18, 18, 18));
        window.setNavigationBarColor(Color.rgb(18, 18, 18));
        window.getDecorView().setSystemUiVisibility(0);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        loadLibrary();
        showLibrary();
    }

    private void showLibrary() {
        if (readerOpen) saveReadingPosition();
        readerOpen = false;
        currentBook = null;
        currentWebView = null;

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
        title.setText("Reader");
        title.setTextColor(Color.WHITE);
        title.setTextSize(32);
        title.setPadding(0, dp(2), 0, dp(24));

        TextView empty = new TextView(this);
        empty.setText("Biblioteka jest pusta");
        empty.setTextColor(Color.rgb(150, 150, 150));
        empty.setGravity(Gravity.CENTER);

        ListView list = new ListView(this);
        list.setDividerHeight(dp(1));
        list.setBackgroundColor(Color.TRANSPARENT);

        adapter = new ArrayAdapter<Book>(this, android.R.layout.simple_list_item_2, android.R.id.text1, books) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                TextView line1 = row.findViewById(android.R.id.text1);
                TextView line2 = row.findViewById(android.R.id.text2);
                Book book = getItem(position);

                line1.setText(book.name);
                line1.setTextColor(Color.WHITE);
                line1.setTextSize(17);
                line2.setText(book.mimeType == null ? "EPUB / dokument" : book.mimeType);
                line2.setTextColor(Color.rgb(145, 145, 145));
                return row;
            }
        };

        list.setAdapter(adapter);
        list.setEmptyView(empty);
        list.setOnItemClickListener((parent, view, position, id) -> openBook(books.get(position)));

        Button add = new Button(this);
        add.setText("Dodaj książkę");
        add.setAllCaps(false);
        add.setOnClickListener(v -> openPicker());

        root.addView(brand);
        root.addView(title);
        root.addView(empty, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(add, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        root.requestApplyInsets();
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
        adapter.notifyDataSetChanged();
    }

    private void openBook(Book book) {
        if (!looksLikeEpub(book)) {
            Toast.makeText(this, "Na razie czytamy EPUB. PDF/TXT dojdą później.", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            String html = EpubReader.read(this, book.uri, book.name);
            showReader(book, html);
        } catch (Exception e) {
            Toast.makeText(this, "Nie udało się otworzyć EPUB: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private boolean looksLikeEpub(Book book) {
        if ("application/epub+zip".equals(book.mimeType)) return true;
        return book.name.toLowerCase().endsWith(".epub");
    }

    private void showReader(Book book, String html) {
        readerOpen = true;
        currentBook = book;

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
        top.setText("‹  " + book.name);
        top.setTextColor(Color.WHITE);
        top.setTextSize(16);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(18), dp(12), dp(18), dp(12));
        top.setBackgroundColor(Color.rgb(24, 24, 24));
        top.setOnClickListener(v -> showLibrary());

        WebView web = new WebView(this);
        currentWebView = web;
        web.setBackgroundColor(Color.rgb(18, 18, 18));

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
                html,
                "text/html",
                "UTF-8",
                null
        );

        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
        root.requestApplyInsets();
    }

    @Override
    public void onBackPressed() {
        if (readerOpen) {
            showLibrary();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        saveReadingPosition();
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
                    + "html,body{margin:0;padding:0;background:#121212;color:#e8e8e8;}"
                    + "body{font-family:serif;font-size:19px;line-height:1.65;padding:28px 22px 72px;}"
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
