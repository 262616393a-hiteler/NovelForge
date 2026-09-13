package com.novelcraft.writer;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.TypedArray;
import android.os.Build;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.FileProvider;

import com.novelcraft.writer.NovelService.NovelCallback;
import com.novelcraft.writer.NovelService.Chapter;
import com.novelcraft.writer.NovelService.Params;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends android.app.Activity {

    private EditText etTitle, etStyle, etChapterTitle, etTotalChapters, etHeroName, etMainContent;
    private Spinner spinnerGenre, spinnerChapterLen;
    private Button btnStart, btnContinue, btnBatch, btnSave, btnCopy, btnShare, btnClear, btnOutline, btnReview, btnReview2, btnChat, btnSort, btnStop;
    private ProgressBar progressBar, progressCard;
    private TextView tvProgress, tvStatus, tvChapterHeader, tvCardTitle, tvCardGenre, tvCardProgress, tvCardScore;
    private ListView chapterList;
    private ScrollView outerScroll;

    private NovelService service;
    private final StringBuilder novel = new StringBuilder();
    private final List<Chapter> allChapters = new ArrayList<>();
    private File currentLibDir;
    private String outlineText = "";
    private String settingsText = "";
    private android.content.SharedPreferences editorPrefs;
    private boolean busy = false;
    private int aiScore = 0;

    private static final int REQ_PERMISSION_STORAGE = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        service = new NovelService(this);
        editorPrefs = getSharedPreferences("editor_prefs", MODE_PRIVATE);
        bindViews();
        setupSpinners();
        restoreEditor();
        setupActions();
        setupBottomNav();
        updateStatus();
        handleIntentAction(getIntent());

        if (!service.hasValidApiKey()) {
            Toast.makeText(this, R.string.error_api_key, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntentAction(intent);
    }

    private void handleIntentAction(Intent intent) {
        if (intent == null) return;
        String action = intent.getStringExtra("action");
        if (action == null) return;
        intent.removeExtra("action");
        if ("factory".equals(action)) {
            scrollToWorkbench();
        } else if ("quality".equals(action)) {
            reviewCurrent();
        }
    }

    private void bindViews() {
        etTitle = findViewById(R.id.etTitle);
        etStyle = findViewById(R.id.etStyle);
        etChapterTitle = findViewById(R.id.etChapterTitle);
        etTotalChapters = findViewById(R.id.etTotalChapters);
        etHeroName = findViewById(R.id.etHeroName);
        etMainContent = findViewById(R.id.etMainContent);
        spinnerGenre = findViewById(R.id.spinnerGenre);
        spinnerChapterLen = findViewById(R.id.spinnerChapterLen);
        btnStart = findViewById(R.id.btnStart);
        btnContinue = findViewById(R.id.btnContinue);
        btnBatch = findViewById(R.id.btnBatch);
        btnSave = findViewById(R.id.btnSave);
        btnCopy = findViewById(R.id.btnCopy);
        btnShare = findViewById(R.id.btnShare);
        btnClear = findViewById(R.id.btnClear);
        btnOutline = findViewById(R.id.btnOutline);
        btnReview = findViewById(R.id.btnReview);
        btnChat = findViewById(R.id.btnChat);
        btnSort = findViewById(R.id.btnSort);
        btnStop = findViewById(R.id.btnStop);
        progressBar = findViewById(R.id.progressBar);
        tvProgress = findViewById(R.id.tvProgress);
        tvStatus = findViewById(R.id.tvStatus);
        tvChapterHeader = findViewById(R.id.tvChapterHeader);
        chapterList = findViewById(R.id.chapterList);
        outerScroll = findViewById(R.id.outerScroll);
        tvCardTitle = findViewById(R.id.tvCardTitle);
        tvCardGenre = findViewById(R.id.tvCardGenre);
        tvCardProgress = findViewById(R.id.tvCardProgress);
        progressCard = findViewById(R.id.progressCard);
        tvCardScore = findViewById(R.id.tvCardScore);
        btnReview2 = findViewById(R.id.btnReview2);
        chapterList.setOnItemClickListener((parent, view, position, id) -> openChapter(position));
        btnSort.setOnClickListener(v -> sortChapters());
        btnChat.setOnClickListener(v -> openChat());
        btnStop.setOnClickListener(v -> stopGeneration());
        btnReview2.setOnClickListener(v -> reviewCurrent());
        findViewById(R.id.btnEditNovel).setOnClickListener(v -> toggleSettingsPanel());
        findViewById(R.id.btnToggleSettings).setOnClickListener(v -> toggleSettingsPanel());
    }

    private boolean settingsOpen = false;

    private void toggleSettingsPanel() {
        settingsOpen = !settingsOpen;
        View panel = findViewById(R.id.settingsPanel);
        if (panel != null) panel.setVisibility(settingsOpen ? View.VISIBLE : View.GONE);
        Button b = findViewById(R.id.btnToggleSettings);
        if (b != null) b.setText(settingsOpen ? R.string.btn_creation_settings_hide : R.string.btn_creation_settings);
        if (settingsOpen && outerScroll != null) {
            View panelV = findViewById(R.id.settingsPanel);
            outerScroll.post(() -> outerScroll.scrollTo(0, panelV.getTop()));
        }
    }

    private void scrollToWorkbench() {
        View wb = findViewById(R.id.tvWorkbenchTitle);
        if (wb != null && outerScroll != null) {
            outerScroll.post(() -> outerScroll.scrollTo(0, wb.getTop()));
        }
    }

    private static final int REQ_LIBRARY = 200;
    private static final int REQ_REVIEW = 201;
    private static final int REQ_CHAT = 202;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_LIBRARY) {
            if (resultCode == RESULT_OK && data != null) {
                String title = data.getStringExtra("title");
                String content = data.getStringExtra("content");
                String dir = data.getStringExtra("dir");
                currentLibDir = dir == null ? null : new File(dir);
                if (title != null && content != null) {
                    loadNovel(title, content,
                            data.getStringExtra("genre"),
                            data.getStringExtra("style"),
                            data.getIntExtra("chapterLength", -1),
                            data.getIntExtra("totalChapters", 0));
                }
            } else if (resultCode == LibraryActivity.RESULT_NEW) {
                newNovel();
            }
        } else if (requestCode == REQ_REVIEW && resultCode == RESULT_OK && data != null) {
            if (allChapters.isEmpty()) {
                doReview(novelTitle(), novel.toString(), null, false);
                return;
            }
            int[] indices = data.getIntArrayExtra("indices");
            boolean autoRevise = data.getBooleanExtra("autoRevise", false);
            StringBuilder sb = new StringBuilder();
            String prefix = novelTitle();
            if (indices != null) {
                for (int idx : indices) {
                    if (idx < 0 || idx >= allChapters.size()) continue;
                    Chapter c = allChapters.get(idx);
                    sb.append(c.title).append("\n\n").append(c.content).append("\n\n");
                }
            }
            String content = sb.toString().trim();
            if (content.isEmpty()) {
                content = novel.toString();
            }
            doReview(prefix, content, indices, autoRevise);
        } else if (requestCode == REQ_CHAT) {
            if (currentLibDir != null) {
                allChapters.clear();
                allChapters.addAll(SaveHelper.loadChapters(currentLibDir));
                refreshChapterList();
                updateStatus();
            }
        }
    }

    private void loadNovel(String title, String content, String genre, String style,
                           int chapterLength, int totalChapters) {
        etTitle.setText(title);
        etTitle.setSelection(etTitle.getText().length());
        applySettings(genre, style, chapterLength, totalChapters);
        etHeroName.setText(currentLibDir == null ? "" : SaveHelper.getHeroName(currentLibDir));
        novel.setLength(0);
        novel.append(content);
        allChapters.clear();
        if (currentLibDir != null) {
            allChapters.addAll(SaveHelper.loadChapters(currentLibDir));
            outlineText = SaveHelper.getOutline(currentLibDir);
            settingsText = SaveHelper.getSettings(currentLibDir);
            etHeroName.setText(SaveHelper.getHeroName(currentLibDir));
            etMainContent.setText(SaveHelper.getMainContent(currentLibDir));
        } else {
            allChapters.addAll(SaveHelper.splitChapters(content));
            outlineText = "";
            settingsText = "";
        }
        refreshChapterList();
        updateStatus();
        toast("已载入《" + title + "》，点“续写下一章”继续创作");
    }

    private void newNovel() {
        currentLibDir = null;
        allChapters.clear();
        novel.setLength(0);
        outlineText = "";
        settingsText = "";
        etTitle.setText("");
        etChapterTitle.setText("");
        etTotalChapters.setText("");
        etHeroName.setText("");
        etMainContent.setText("");
        refreshChapterList();
        updateStatus();
    }

    private void setupBottomNav() {
        setTabActive(R.id.navHome);
        findViewById(R.id.navLibrary).setOnClickListener(v ->
                startActivityForResult(new Intent(this, LibraryActivity.class), REQ_LIBRARY));
        findViewById(R.id.navFactory).setOnClickListener(v -> scrollToWorkbench());
        findViewById(R.id.navQuality).setOnClickListener(v -> reviewCurrent());
        findViewById(R.id.navSettings).setOnClickListener(v -> openSettings());
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

    private void setupSpinners() {
        spinnerGenre.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                split(getString(R.string.genres))));
        List<String> lens = new ArrayList<>();
        for (String s : split(getString(R.string.chapter_lengths))) {
            lens.add(s + " 字");
        }
        spinnerChapterLen.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, lens));
    }

    private void restoreEditor() {
        try {
            selectSpinnerValue(spinnerGenre, editorPrefs.getString("genre", ""));
            etStyle.setText(editorPrefs.getString("style", ""));
            int len = editorPrefs.getInt("chapterLength", -1);
            if (len > 0) {
                String target = len + " 字";
                for (int i = 0; i < spinnerChapterLen.getAdapter().getCount(); i++) {
                    if (target.equals(spinnerChapterLen.getAdapter().getItem(i))) {
                        spinnerChapterLen.setSelection(i);
                        break;
                    }
                }
            }
            int tot = editorPrefs.getInt("totalChapters", 0);
            if (tot > 0) etTotalChapters.setText(String.valueOf(tot));
            etHeroName.setText(editorPrefs.getString("heroName", ""));
            etMainContent.setText(editorPrefs.getString("mainContent", ""));
            aiScore = editorPrefs.getInt("aiScore", 0);
        } catch (Exception ignored) {
        }
    }

    private void saveEditor() {
        try {
            editorPrefs.edit()
                    .putString("genre", selectedGenre())
                    .putString("style", selectedStyle())
                    .putInt("chapterLength", readChapterLength())
                    .putInt("totalChapters", readTotalChapters())
                    .putString("heroName", etHeroName.getText().toString().trim())
                    .putString("mainContent", etMainContent.getText().toString().trim())
                    .apply();
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveEditor();
    }

    private void setupActions() {
        btnStart.setOnClickListener(v -> startChapter(true, 1));
        btnContinue.setOnClickListener(v -> startChapter(false, 1));
        btnBatch.setOnClickListener(v -> showBatchDialog());
        btnSave.setOnClickListener(v -> saveNovel());
        btnCopy.setOnClickListener(v -> copyNovel());
        btnShare.setOnClickListener(v -> shareNovel());
        btnClear.setOnClickListener(v -> clearNovel());
        btnOutline.setOnClickListener(v -> generateOutline());
        btnReview.setOnClickListener(v -> reviewCurrent());
    }

    private void generateOutline() {
        String title = etTitle.getText().toString().trim();
        if (TextUtils.isEmpty(title)) {
            toast(R.string.error_title_empty);
            return;
        }
        if (!service.hasValidApiKey()) {
            toast(R.string.error_api_key);
            return;
        }
        if (currentLibDir != null && !SaveHelper.getOutline(currentLibDir).isEmpty()) {
            openOutlineEditor();
            return;
        }
        Params p = new Params();
        p.title = title;
        p.genre = (String) spinnerGenre.getSelectedItem();
        p.style = etStyle.getText().toString().trim();
        p.totalChapters = readTotalChapters();
        p.heroName = etHeroName.getText().toString().trim();
        p.mainContent = etMainContent.getText().toString().trim();
        p.previousStory = novel.toString();
        setBusy(true);
        tvProgress.setText(R.string.toast_outlining);
        service.generateOutline(p, new NovelCallback() {
            @Override
            public void onStatus(String status) {
            }

            @Override
            public void onContent(String fullText, boolean done) {
                setBusy(false);
                if (done) {
                    tvProgress.setText(R.string.progress_done);
                    saveOutlineToStory(fullText);
                    openOutlineEditor();
                }
            }

            @Override
            public void onError(String error) {
                setBusy(false);
                tvProgress.setText(getString(R.string.progress_failed, error));
                showErrorDialog(error);
            }
        });
    }

    private void saveOutlineToStory(String outline) {
        outlineText = outline;
        String[] parts = SaveHelper.splitOutline(outline);
        settingsText = (parts[0] == null) ? "" : parts[0];
        try {
            currentLibDir = SaveHelper.saveOutline(this, novelTitle(), currentLibDir, outline);
            if (!settingsText.isEmpty()) {
                currentLibDir = SaveHelper.saveSettings(this, novelTitle(), currentLibDir, settingsText);
            }
            String n = etHeroName.getText().toString().trim();
            if (!TextUtils.isEmpty(n)) {
                currentLibDir = SaveHelper.saveHeroName(this, novelTitle(), currentLibDir, n);
            }
            String mc = etMainContent.getText().toString().trim();
            if (!TextUtils.isEmpty(mc)) {
                currentLibDir = SaveHelper.saveMainContent(this, novelTitle(), currentLibDir, mc);
            }
        } catch (Exception ignored) {
        }
    }

    private void openOutlineEditor() {
        Intent it = new Intent(this, OutlineActivity.class);
        it.putExtra("title", etTitle.getText().toString().trim());
        it.putExtra("genre", selectedGenre());
        it.putExtra("style", selectedStyle());
        it.putExtra("totalChapters", readTotalChapters());
        it.putExtra("heroName", etHeroName.getText().toString().trim());
        if (currentLibDir != null) {
            it.putExtra("dir", currentLibDir.getAbsolutePath());
        }
        startActivity(it);
    }

    private void reviewCurrent() {
        if (novel.length() == 0) {
            toast(R.string.review_empty);
            return;
        }
        if (!service.hasValidApiKey()) {
            toast(R.string.error_api_key);
            return;
        }
        if (allChapters.isEmpty()) {
            doReview(novelTitle(), novel.toString());
            return;
        }
        String[] titles = new String[allChapters.size()];
        for (int i = 0; i < allChapters.size(); i++) {
            titles[i] = allChapters.get(i).title;
        }
        Intent it = new Intent(this, ChapterSelectActivity.class);
        it.putExtra("titles", titles);
        startActivityForResult(it, REQ_REVIEW);
    }

    private int[] pendingIndices;
    private boolean pendingAutoRevise = false;
    private int revisePos = 0;
    private String pendingReviewText = "";

    private void doReview(String title, String content) {
        doReview(title, content, null, false);
    }

    private void doReview(String title, String content, int[] indices, boolean autoRevise) {
        setBusy(true);
        tvProgress.setText(R.string.toast_reviewing);
        service.review(title, content, new NovelCallback() {
            @Override
            public void onStatus(String status) {
            }

            @Override
            public void onContent(String fullText, boolean done) {
                if (done) {
                    int s = parseScore(fullText);
                    if (s > 0) {
                        aiScore = s;
                        if (editorPrefs != null) editorPrefs.edit().putInt("aiScore", s).apply();
                        updateStatus();
                    }
                    showTextResult(getString(R.string.review_title), fullText);
                    if (autoRevise && indices != null && indices.length > 0) {
                        startAutoRevise(fullText, indices);
                    } else {
                        setBusy(false);
                        tvProgress.setText(R.string.progress_done);
                    }
                }
            }

            @Override
            public void onError(String error) {
                setBusy(false);
                tvProgress.setText(getString(R.string.progress_failed, error));
                showErrorDialog(error);
            }
        });
    }

    private void startAutoRevise(String review, int[] indices) {
        pendingIndices = indices;
        pendingReviewText = review;
        revisePos = 0;
        tvProgress.setText(getString(R.string.auto_revise_working, 0, indices.length));
        reviseNext();
    }

    private void reviseNext() {
        if (pendingIndices == null || revisePos >= pendingIndices.length) {
            setBusy(false);
            tvProgress.setText(R.string.progress_done);
            toast(getString(R.string.auto_revise_done, revisePos));
            if (currentLibDir != null) {
                allChapters.clear();
                allChapters.addAll(SaveHelper.loadChapters(currentLibDir));
                refreshChapterList();
            }
            return;
        }
        final int idx = pendingIndices[revisePos];
        if (idx < 0 || idx >= allChapters.size()) {
            revisePos++;
            reviseNext();
            return;
        }
        final Chapter c = allChapters.get(idx);
        tvProgress.setText(getString(R.string.auto_revise_working, revisePos + 1, pendingIndices.length));
        service.reviseChapter(novelTitle(),
                etHeroName.getText().toString().trim(),
                etMainContent.getText().toString().trim(),
                outlineText, c.title, c.content, pendingReviewText,
                new NovelService.ChatCallback() {
                    @Override
                    public void onReply(String reply) {
                        String cleaned = reply == null ? "" : reply.trim();
                        if (!cleaned.isEmpty()) {
                            allChapters.set(idx, new Chapter(c.title, cleaned));
                            if (currentLibDir != null) {
                                SaveHelper.updateChapter(currentLibDir, idx, cleaned);
                            }
                        }
                        revisePos++;
                        reviseNext();
                    }

                    @Override
                    public void onError(String error) {
                        setBusy(false);
                        tvProgress.setText(getString(R.string.auto_revise_failed, error));
                        toast(getString(R.string.auto_revise_failed, error));
                    }
                });
    }

    private void showTextResult(String title, String text) {
        Intent it = new Intent(this, ChapterContentActivity.class);
        it.putExtra("title", title);
        it.putExtra("content", text);
        startActivity(it);
    }

    private void startChapter(boolean first, int batchCount) {
        String title = etTitle.getText().toString().trim();
        if (TextUtils.isEmpty(title)) {
            toast(R.string.error_title_empty);
            return;
        }
        if (!service.hasValidApiKey()) {
            toast(R.string.error_api_key);
            openSettings();
            return;
        }
        if (first) {
            currentLibDir = null;
            allChapters.clear();
        }
        Params p = new Params();
        p.title = title;
        p.genre = (String) spinnerGenre.getSelectedItem();
        p.style = etStyle.getText().toString().trim();
        p.chapterLength = readChapterLength();
        p.totalChapters = readTotalChapters();
        p.chapterTitle = etChapterTitle.getText().toString().trim();
        p.isFirst = first;
        p.batchCount = batchCount;
        p.outline = outlineText;
        p.settings = settingsText;
        p.heroName = etHeroName.getText().toString().trim();
        p.mainContent = etMainContent.getText().toString().trim();
        p.startChapterNumber = first ? 1 : nextChapterNumber();
        if (!first) {
            p.previousStory = boundStory(novel.toString());
        }

        setBusy(true);
        service.generate(p, new NovelCallback() {
            @Override
            public void onStatus(String status) {
                tvProgress.setText(status);
            }

            @Override
            public void onContent(String fullText, boolean done) {
                novel.setLength(0);
                novel.append(fullText);
                if (done && novel.length() > 0) {
                    allChapters.addAll(service.getLastChapters());
                    updateStatus();
                    finishProgress(true);
                    autoSaveToLibrary();
                } else {
                    updateStatus();
                }
                if (done) {
                    refreshChapterList();
                    sortChapters(true);
                }
            }

            @Override
            public void onError(String error) {
                finishProgress(false);
                tvProgress.setText(getString(R.string.progress_failed, error));
                toastLong(error);
                showErrorDialog(error);
            }
        });
    }

    private void refreshChapterList() {
        if (chapterList == null) return;
        List<String> titles = new ArrayList<>();
        for (Chapter c : allChapters) {
            titles.add(c.title);
        }
        chapterList.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, titles));
        tvChapterHeader.setText(getString(R.string.chapter_list_title) + "  ·  " + allChapters.size() + " 章");
    }

    private String boundStory(String story) {
        if (story == null) return "";
        int max = 14000;
        return story.length() > max ? "……" + story.substring(story.length() - max) : story;
    }

    private int chapterNum(String t) {
        if (t == null) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("第\\s*(\\d+)\\s*章").matcher(t);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** 下一章编号 = 最小的缺失章号（可自动补回丢失的章节），没有缺失则取最大章号 + 1。 */
    private int nextChapterNumber() {
        java.util.Set<Integer> used = new java.util.HashSet<>();
        int max = 0;
        for (Chapter c : allChapters) {
            int n = chapterNum(c.title);
            if (n > 0) {
                used.add(n);
                if (n > max) max = n;
            }
        }
        if (used.isEmpty()) return allChapters.size() + 1;
        for (int n = 1; n <= max + 1; n++) {
            if (!used.contains(n)) return n;
        }
        return max + 1;
    }

    private void sortChapters() {
        sortChapters(false);
    }

    private void sortChapters(boolean quiet) {
        allChapters.sort((a, b) -> {
            int na = chapterNum(a.title), nb = chapterNum(b.title);
            if (na >= 0 && nb >= 0) return Integer.compare(na, nb);
            return a.title.compareTo(b.title);
        });
        refreshChapterList();
        if (!quiet) toast(R.string.chap_sort_done);
    }

    private void openChapter(int position) {
        if (position < 0 || position >= allChapters.size()) return;
        Chapter c = allChapters.get(position);
        showTextResult(c.title, c.content);
    }

    private void openChat() {
        Intent it = new Intent(this, ChatActivity.class);
        it.putExtra("title", etTitle.getText().toString().trim());
        if (currentLibDir != null) {
            it.putExtra("dir", currentLibDir.getAbsolutePath());
        }
        startActivityForResult(it, REQ_CHAT);
    }

    private void showErrorDialog(String error) {
        String raw = service.getLastRawResponse();
        if (raw.length() > 1200) {
            raw = raw.substring(0, 1200) + "…";
        }
        String detail = error + "\n\n[HTTP " + service.getLastHttpCode() + "]\n原始响应：\n" + raw;
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.dialog_error_title)
                .setMessage(detail)
                .setPositiveButton(R.string.btn_copy, (d, w) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("err", detail));
                    toast(R.string.copied);
                })
                .setNegativeButton(R.string.btn_close, null)
                .show();
    }

    private void showBatchDialog() {
        String[] counts = {"3 章", "5 章", "10 章"};
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.btn_batch)
                .setItems(counts, (dialog, which) -> {
                    int n = 3 + which * 2 + ((which == 2) ? 5 : 0);
                    startChapter(novel.length() == 0, n);
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    private void finishProgress(boolean success) {
        setBusy(false);
        progressBar.setVisibility(ProgressBar.GONE);
        if (success) {
            tvProgress.setText(R.string.progress_done);
        } else {
            tvProgress.setText(R.string.progress_waiting);
        }
    }

    private void setBusy(boolean b) {
        busy = b;
        btnStart.setEnabled(!b);
        btnContinue.setEnabled(!b);
        btnBatch.setEnabled(!b);
        progressBar.setVisibility(b ? ProgressBar.VISIBLE : ProgressBar.GONE);
        btnStop.setVisibility(b ? View.VISIBLE : View.GONE);
    }

    private void stopGeneration() {
        if (service != null) service.cancel();
        setBusy(false);
        tvProgress.setText(R.string.progress_waiting);
        toast(R.string.toast_stopped);
    }

    private void updateStatus() {
        if (tvCardTitle != null) {
            String title = etTitle == null ? "" : etTitle.getText().toString().trim();
            tvCardTitle.setText(title.isEmpty() ? getString(R.string.novel_untitled) : title);
        }
        if (tvCardGenre != null) {
            String genre = selectedGenre();
            String style = selectedStyle();
            String g = genre == null || genre.isEmpty() ? getString(R.string.novel_no_genre) : genre;
            tvCardGenre.setText(style == null || style.isEmpty() ? g : (g + "  ·  " + style));
        }
        int done = allChapters.size();
        int total = readTotalChapters();
        if (tvCardProgress != null) {
            tvCardProgress.setText(getString(R.string.card_chapters, done,
                    total > 0 ? getString(R.string.card_total_suffix, total) : ""));
        }
        if (progressCard != null) {
            if (total > 0) {
                progressCard.setVisibility(View.VISIBLE);
                progressCard.setMax(total);
                progressCard.setProgress(Math.min(done, total));
            } else {
                progressCard.setVisibility(View.GONE);
            }
        }
        if (tvCardScore != null) {
            tvCardScore.setText(aiScore > 0 ? getString(R.string.card_score, aiScore) : getString(R.string.card_score_none));
        }
        int len = novel.length();
        tvStatus.setText(len > 0 ? getString(R.string.word_count, len) : "");
    }

    private int parseScore(String review) {
        if (review == null) return 0;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?:综合)?总分[^0-9]{0,8}(\\d{1,3})").matcher(review);
            if (m.find()) {
                int v = Integer.parseInt(m.group(1));
                if (v >= 0 && v <= 100) return v;
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private int readChapterLength() {
        String sel = (String) spinnerChapterLen.getSelectedItem();
        try {
            String num = sel.replace(" 字", "").trim();
            return Integer.parseInt(num);
        } catch (Exception e) {
            return 1000;
        }
    }

    private void saveNovel() {
        if (novel.length() == 0) {
            toast(R.string.nothing_to_save);
            return;
        }
        if (Build.VERSION.SDK_INT < 29 && !hasStoragePermission()) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERMISSION_STORAGE);
            return;
        }
        doSave();
    }

    private void doSave() {
        final String t = novelTitle();
        final String text = novel.toString();
        final List<Chapter> chs = new ArrayList<>(allChapters);
        final int len = readChapterLength();
        final int total = readTotalChapters();
        final String g = selectedGenre();
        final String s = selectedStyle();
        new Thread(() -> {
            try {
                String where = SaveHelper.saveNovel(this, t, text);
                File d = SaveHelper.saveLibrary(this, t, chs, g, s, len, total, currentLibDir);
                currentLibDir = d;
                String hn = etHeroName.getText().toString().trim();
                if (!TextUtils.isEmpty(hn)) currentLibDir = SaveHelper.saveHeroName(this, t, currentLibDir, hn);
                String mc = etMainContent.getText().toString().trim();
                if (!TextUtils.isEmpty(mc)) currentLibDir = SaveHelper.saveMainContent(this, t, currentLibDir, mc);
                final String w = where;
                runOnUiThread(() -> toast(getString(R.string.saved_to) + "\n" + w));
            } catch (Exception e) {
                final String msg = e.getMessage();
                runOnUiThread(() -> toast(getString(R.string.saved_error, msg)));
            }
        }).start();
    }

    private void autoSaveToLibrary() {
        if (allChapters.isEmpty()) return;
        final String t = novelTitle();
        final int len = readChapterLength();
        final int total = readTotalChapters();
        final String g = selectedGenre();
        final String s = selectedStyle();
        final List<Chapter> chs = new ArrayList<>(allChapters);
        new Thread(() -> {
            try {
                File d = SaveHelper.saveLibrary(this, t, chs, g, s, len, total, currentLibDir);
                currentLibDir = d;
            } catch (Exception ignored) {
            }
        }).start();
    }

    private String formatChapters(List<Chapter> chapters) {
        StringBuilder sb = new StringBuilder();
        for (Chapter c : chapters) {
            if (sb.length() > 0) {
                sb.append("\n\n════════════════════\n\n");
            }
            sb.append(c.title).append("\n\n").append(c.content);
        }
        return sb.toString();
    }

    private int readTotalChapters() {
        try {
            int v = Integer.parseInt(etTotalChapters.getText().toString().trim());
            return Math.max(0, v);
        } catch (Exception e) {
            return 0;
        }
    }

    private String selectedGenre() {
        Object g = spinnerGenre.getSelectedItem();
        return g == null ? "" : g.toString();
    }

    private String selectedStyle() {
        return etStyle.getText().toString().trim();
    }

    private void applySettings(String genre, String style, int chapterLength, int totalChapters) {
        selectSpinnerValue(spinnerGenre, genre);
        etStyle.setText(style == null ? "" : style);
        if (chapterLength > 0) {
            String target = chapterLength + " 字";
            for (int i = 0; i < spinnerChapterLen.getAdapter().getCount(); i++) {
                String item = (String) spinnerChapterLen.getAdapter().getItem(i);
                if (target.equals(item)) {
                    spinnerChapterLen.setSelection(i);
                    break;
                }
            }
        }
        etTotalChapters.setText(totalChapters > 0 ? String.valueOf(totalChapters) : "");
    }

    private void selectSpinnerValue(Spinner spinner, String value) {
        if (value == null) return;
        for (int i = 0; i < spinner.getAdapter().getCount(); i++) {
            if (value.equals(spinner.getAdapter().getItem(i))) {
                spinner.setSelection(i);
                return;
            }
        }
    }

    private void copyNovel() {
        if (novel.length() == 0) {
            toast(R.string.nothing_to_copy);
            return;
        }
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("novel", novel.toString()));
        toast(R.string.copied);
    }

    private void shareNovel() {
        if (novel.length() == 0) {
            toast(R.string.share_empty);
            return;
        }
        try {
            File f = SaveHelper.makeShareFile(this, novelTitle(), novel.toString());
            if (f == null) {
                toast(R.string.share_empty);
                return;
            }
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_STREAM,
                    FileProvider.getUriForFile(this, getApplicationContext().getPackageName() + ".fileprovider", f));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.btn_share)));
        } catch (Exception e) {
            toast(getString(R.string.saved_error, e.getMessage()));
        }
    }

    private void clearNovel() {
        if (novel.length() == 0) {
            toast(R.string.nothing_to_save);
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setMessage(R.string.clear_confirm)
                .setPositiveButton(R.string.btn_ok, (d, w) -> {
                    novel.setLength(0);
                    allChapters.clear();
                    refreshChapterList();
                    updateStatus();
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    private String novelTitle() {
        String t = etTitle.getText().toString().trim();
        return TextUtils.isEmpty(t) ? "无题" : t;
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void toastLong(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private List<String> split(String src) {
        List<String> list = new ArrayList<>();
        if (src != null) {
            for (String s : src.split("\\|")) {
                if (!s.isEmpty()) list.add(s);
            }
        }
        return list;
    }

    private boolean hasStoragePermission() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSION_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                doSave();
            } else {
                toast(R.string.storage_denied);
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            openSettings();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        service.shutdown();
    }
}
