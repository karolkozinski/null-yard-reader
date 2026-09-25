package com.nullyard.reader;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;

public class MainActivity extends Activity {
    private static final int PICK_BOOK = 1001;
    private final ArrayList<String> books = new ArrayList<>();
    private ArrayAdapter<String> adapter;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 56, 40, 40);
        root.setBackgroundColor(Color.rgb(18, 18, 18));

        TextView brand = new TextView(this);
        brand.setText("NULL YARD");
        brand.setTextColor(Color.LTGRAY);
        brand.setTextSize(14);

        TextView title = new TextView(this);
        title.setText("Reader");
        title.setTextColor(Color.WHITE);
        title.setTextSize(34);
        title.setPadding(0, 0, 0, 28);

        TextView empty = new TextView(this);
        empty.setText("Biblioteka jest pusta");
        empty.setTextColor(Color.GRAY);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, 24, 0, 24);

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, books) {
            @Override
            public android.view.View getView(int position, android.view.View convertView, ViewGroup parent) {
                TextView v = (TextView) super.getView(position, convertView, parent);
                v.setTextColor(Color.WHITE);
                return v;
            }
        };
        list.setAdapter(adapter);
        list.setEmptyView(empty);

        Button add = new Button(this);
        add.setText("Dodaj książkę");
        add.setOnClickListener(v -> openPicker());

        root.addView(brand);
        root.addView(title);
        root.addView(empty, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(add);

        setContentView(root);
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
        if (!books.contains(name)) {
            books.add(name);
            adapter.notifyDataSetChanged();
        }
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
}
