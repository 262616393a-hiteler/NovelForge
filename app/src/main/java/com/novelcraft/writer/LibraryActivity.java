package com.novelcraft.writer;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import com.novelcraft.writer.SaveHelper.NovelInfo;

public class LibraryActivity extends android.app.Activity {

    public static final int RESULT_NEW = 99;

    private ListView listView;
    private TextView tvEmpty, tvHeading;
    private Button btnBack, btnAction;

    private List<NovelInfo> novels = new ArrayList<>();
    private List<String> chapterTitles = new ArrayList<>();
    private List<String> displayTitles = new ArrayList<>();
    private NovelInfo current;
    private boolean chaptersMode = false;
    private boolean hasOutline = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_library);
        listView = findViewById(R.id.listView);
        tvEmpty = findViewById(R.id.tvEmpty);
        tvHeading = findViewById(R.id.tvHeading);
        btnBack = findViewById(R.id.btnBack);
        btnAction = findViewById(R.id.btnAction);

        setupBottomNav();

        btnBack.setOnClickListener(v -> {
            if (chaptersMode) {
                chaptersMode = false;
                refresh();
            } else {
                finish();
            }
        });

        btnAction.setOnClickListener(v -> {
            if (chaptersMode && current != null) {
                continueWriting(current);
            } else {
                setResult(RESULT_NEW);
                finish();
            }
        });

        listView.setOnItemClickListener((AdapterView<?> parent, View view, int position, long id) -> {
            if (chaptersMode) {
                if (hasOutline && position == 0) {
                    openOutline();
                    return;
                }
                int idx = position - (hasOutline ? 1 : 0);
                String content = SaveHelper.chapterContent(current.dir, idx);
                String title = chapterTitles.get(idx);
                Intent it = new Intent(this, ChapterContentActivity.class);
                it.putExtra("title", title);
                it.putExtra("content", content);
                startActivity(it);
            } else {
                current = novels.get(position);
                hasOutline = !SaveHelper.getOutline(current.dir).isEmpty();
                chapterTitles = SaveHelper.chapterTitles(current.dir);
                chaptersMode = true;
                refresh();
            }
        });

        listView.setOnItemLongClickListener((AdapterView<?> parent, View view, int position, long id) -> {
            if (chaptersMode) {
                int idx = position - (hasOutline ? 1 : 0);
                if (hasOutline && position == 0) {
                    return false;
                }
                if (idx < 0 || idx >= chapterTitles.size()) return false;
                String title = chapterTitles.get(idx);
                new AlertDialog.Builder(this)
                        .setTitle(R.string.btn_delete)
                        .setMessage(getString(R.string.delete_confirm_chapter, title))
                        .setPositiveButton(R.string.btn_delete, (d, w) -> {
                            SaveHelper.deleteChapter(current.dir, idx);
                            chapterTitles = SaveHelper.chapterTitles(current.dir);
                            refresh();
                        })
                        .setNegativeButton(R.string.btn_cancel, null)
                        .show();
            } else {
                if (position < 0 || position >= novels.size()) return false;
                NovelInfo n = novels.get(position);
                new AlertDialog.Builder(this)
                        .setTitle(R.string.btn_delete)
                        .setMessage(getString(R.string.delete_confirm_novel, "《" + n.title + "》"))
                        .setPositiveButton(R.string.btn_delete, (d, w) -> {
                            SaveHelper.deleteNovel(n.dir);
                            novels = SaveHelper.listLibrary(this);
                            refresh();
                        })
                        .setNegativeButton(R.string.btn_cancel, null)
                        .show();
            }
            return true;
        });
    }

    private void setupBottomNav() {
        setTabActive(R.id.navLibrary);
        findViewById(R.id.navHome).setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        findViewById(R.id.navFactory).setOnClickListener(v -> goMain("factory"));
        findViewById(R.id.navQuality).setOnClickListener(v -> goMain("quality"));
        findViewById(R.id.navSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
    }

    private void goMain(String action) {
        Intent it = new Intent(this, MainActivity.class);
        it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (action != null) it.putExtra("action", action);
        startActivity(it);
    }

    private void setTabActive(int id) {
        int[] tabs = {R.id.navHome, R.id.navLibrary, R.id.navFactory, R.id.navQuality, R.id.navSettings};
        for (int t : tabs) {
            Button b = findViewById(t);
            if (b == null) continue;
            boolean active = t == id;
            b.setTextColor(active ? getColor(R.color.primary_color) : getColor(R.color.secondary_text));
            b.setTypeface(null, active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    private void continueWriting(NovelInfo novel) {
        String content = SaveHelper.novelContent(novel.dir);
        if (content == null || content.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.library_empty, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Intent data = new Intent();
        data.putExtra("title", novel.title);
        data.putExtra("content", content);
        data.putExtra("genre", novel.genre);
        data.putExtra("style", novel.style);
        data.putExtra("chapterLength", novel.chapterLength);
        data.putExtra("totalChapters", novel.totalChapters);
        data.putExtra("dir", novel.dir.getAbsolutePath());
        setResult(RESULT_OK, data);
        finish();
    }

    private void openOutline() {
        if (current == null) return;
        Intent it = new Intent(this, OutlineActivity.class);
        it.putExtra("title", current.title);
        it.putExtra("genre", current.genre);
        it.putExtra("style", current.style);
        it.putExtra("totalChapters", current.totalChapters);
        it.putExtra("dir", current.dir.getAbsolutePath());
        startActivity(it);
    }

    @Override
    protected void onResume() {
        super.onResume();
        new Thread(() -> {
            SaveHelper.importDownloads(this);
            final List<NovelInfo> result = SaveHelper.listLibrary(this);
            runOnUiThread(() -> {
                novels = result;
                chaptersMode = false;
                refresh();
            });
        }).start();
    }

    private void refresh() {
        if (chaptersMode && current != null) {
            tvHeading.setText(current.title);
            btnAction.setText(R.string.btn_continue_write);
            displayTitles.clear();
            if (hasOutline) {
                displayTitles.add("《大纲》");
            }
            displayTitles.addAll(chapterTitles);
            ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, displayTitles);
            listView.setAdapter(a);
            boolean empty = displayTitles.isEmpty();
            tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
            listView.setVisibility(empty ? View.GONE : View.VISIBLE);
        } else {
            tvHeading.setText(R.string.library_title);
            btnAction.setText(R.string.btn_new);
            List<String> items = new ArrayList<>();
            for (NovelInfo n : novels) {
                items.add("《" + n.title + "》  ·  共 " + n.chapterCount + " 章");
            }
            ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, items);
            listView.setAdapter(a);
            boolean empty = novels.isEmpty();
            tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
            listView.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
    }
}
