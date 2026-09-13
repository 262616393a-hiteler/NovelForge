package com.novelcraft.writer;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import com.novelcraft.writer.NovelService.NovelCallback;
import com.novelcraft.writer.NovelService.Params;

import java.io.File;

public class OutlineActivity extends android.app.Activity {

    private EditText etOutline;
    private Button btnBack, btnSave, btnRegen, btnReview, btnCompare, btnDelete;

    private NovelService service;
    private File novelDir;
    private String title, genre, style;
    private int totalChapters;
    private String heroName;
    private boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_outline);

        service = new NovelService(this);
        etOutline = findViewById(R.id.etOutline);
        btnBack = findViewById(R.id.btnBack);
        btnSave = findViewById(R.id.btnSave);
        btnRegen = findViewById(R.id.btnRegen);
        btnReview = findViewById(R.id.btnReview);
        btnCompare = findViewById(R.id.btnCompare);
        btnDelete = findViewById(R.id.btnDelete);

        title = getIntent().getStringExtra("title");
        genre = getIntent().getStringExtra("genre");
        style = getIntent().getStringExtra("style");
        totalChapters = getIntent().getIntExtra("totalChapters", 0);
        heroName = getIntent().getStringExtra("heroName");
        String dirPath = getIntent().getStringExtra("dir");
        novelDir = dirPath == null ? null : new File(dirPath);

        String initial = getIntent().getStringExtra("outline");
        if (initial == null && novelDir != null) {
            initial = SaveHelper.getOutline(novelDir);
        }
        etOutline.setText(initial == null ? "" : initial);

        btnBack.setOnClickListener(v -> finish());
        btnSave.setOnClickListener(v -> saveOutline());
        btnRegen.setOnClickListener(v -> regenerate());
        btnReview.setOnClickListener(v -> reviewOutline());
        btnCompare.setOnClickListener(v -> compare());
        btnDelete.setOnClickListener(v -> confirmDelete());
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.btn_delete_outline)
                .setMessage(R.string.delete_outline_confirm)
                .setPositiveButton(R.string.btn_delete, (d, w) -> {
                    if (novelDir != null) SaveHelper.deleteOutline(novelDir);
                    etOutline.setText("");
                    toast(R.string.outline_deleted);
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    private Params buildParams() {
        Params p = new Params();
        p.title = title == null ? "" : title;
        p.genre = genre == null ? "" : genre;
        p.style = style == null ? "" : style;
        p.totalChapters = totalChapters;
        p.heroName = heroName == null ? "" : heroName;
        return p;
    }

    private void saveOutline() {
        String text = etOutline.getText().toString().trim();
        if (text.isEmpty()) {
            toast(R.string.outline_empty);
            return;
        }
        try {
            novelDir = SaveHelper.saveOutline(this, title, novelDir, text);
            toast(R.string.outline_saved);
        } catch (Exception e) {
            toast(getString(R.string.saved_error, e.getMessage()));
        }
    }

    private void regenerate() {
        if (title == null || title.isEmpty()) {
            toast(R.string.error_title_empty);
            return;
        }
        if (!service.hasValidApiKey()) {
            toast(R.string.error_api_key);
            return;
        }
        setBusy(true);
        toast(R.string.toast_outlining);
        service.generateOutline(buildParams(), new NovelCallback() {
            @Override
            public void onStatus(String status) {
            }

            @Override
            public void onContent(String fullText, boolean done) {
                setBusy(false);
                if (done) {
                    etOutline.setText(fullText);
                    saveOutline();
                    String[] parts = SaveHelper.splitOutline(fullText);
                    if (parts[0] != null && !parts[0].isEmpty()) {
                        try {
                            novelDir = SaveHelper.saveSettings(OutlineActivity.this, title, novelDir, parts[0]);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }

            @Override
            public void onError(String error) {
                setBusy(false);
                toast(error);
            }
        });
    }

    private void reviewOutline() {
        String text = etOutline.getText().toString().trim();
        if (text.isEmpty()) {
            toast(R.string.outline_empty);
            return;
        }
        if (!service.hasValidApiKey()) {
            toast(R.string.error_api_key);
            return;
        }
        setBusy(true);
        toast(R.string.toast_reviewing);
        service.reviewOutline(title == null ? "" : title, text, new NovelCallback() {
            @Override
            public void onStatus(String status) {
            }

            @Override
            public void onContent(String fullText, boolean done) {
                setBusy(false);
                if (done) showResult(getString(R.string.outline_title), fullText);
            }

            @Override
            public void onError(String error) {
                setBusy(false);
                toast(error);
            }
        });
    }

    private void compare() {
        String outline = etOutline.getText().toString().trim();
        if (outline.isEmpty()) {
            toast(R.string.outline_empty);
            return;
        }
        String content = novelDir != null ? SaveHelper.novelContent(novelDir) : "";
        if (content.isEmpty()) {
            toast(R.string.review_empty);
            return;
        }
        if (!service.hasValidApiKey()) {
            toast(R.string.error_api_key);
            return;
        }
        setBusy(true);
        toast(R.string.toast_reviewing);
        service.compare(title == null ? "" : title, outline, content, new NovelCallback() {
            @Override
            public void onStatus(String status) {
            }

            @Override
            public void onContent(String fullText, boolean done) {
                setBusy(false);
                if (done) showResult(getString(R.string.btn_compare), fullText);
            }

            @Override
            public void onError(String error) {
                setBusy(false);
                toast(error);
            }
        });
    }

    private void showResult(String title, String text) {
        Intent it = new Intent(this, ChapterContentActivity.class);
        it.putExtra("title", title);
        it.putExtra("content", text);
        startActivity(it);
    }

    private void setBusy(boolean b) {
        busy = b;
        btnRegen.setEnabled(!b);
        btnReview.setEnabled(!b);
        btnCompare.setEnabled(!b);
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        service.shutdown();
    }
}
