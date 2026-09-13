package com.novelcraft.writer;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class ChapterContentActivity extends android.app.Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chapter_content);

        TextView tvTitle = findViewById(R.id.tvTitle);
        TextView tvContent = findViewById(R.id.tvContent);
        Button btnCopy = findViewById(R.id.btnCopy);
        Button btnBack = findViewById(R.id.btnBack);
        Button btnExport = findViewById(R.id.btnExport);

        String title = getIntent().getStringExtra("title");
        String content = getIntent().getStringExtra("content");
        if (title == null) title = "";
        if (content == null) content = "";
        final String chapterTitle = title;
        final String chapterBody = content;

        tvTitle.setText(chapterTitle);
        tvContent.setText(chapterBody.isEmpty() ? getString(R.string.chapter_empty) : chapterBody);

        btnBack.setOnClickListener(v -> finish());

        btnExport.setOnClickListener(v -> {
            if (chapterBody.isEmpty()) {
                Toast.makeText(this, R.string.share_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                String where = SaveHelper.saveNovel(this, chapterTitle, chapterBody);
                Toast.makeText(this, getString(R.string.export_toast, where), Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, getString(R.string.saved_error, e.getMessage()), Toast.LENGTH_LONG).show();
            }
        });

        btnCopy.setOnClickListener(v -> {
            if (chapterBody.isEmpty()) {
                Toast.makeText(this, R.string.share_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("chapter", chapterTitle + "\n\n" + chapterBody));
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        });
    }
}
