package com.novelcraft.writer;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.novelcraft.writer.NovelService.Chapter;

public class ChatActivity extends android.app.Activity {

    private TextView tvChat;
    private EditText etInput;
    private Button btnSend, btnApply;

    private NovelService service;
    private String title;
    private File novelDir;
    private final StringBuilder transcript = new StringBuilder();
    private final List<String[]> turns = new ArrayList<>();
    private List<Chapter> chapters = new ArrayList<>();

    private int rewriteTarget = -1;
    private String lastReply = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        service = new NovelService(this);
        tvChat = findViewById(R.id.tvChat);
        etInput = findViewById(R.id.etInput);
        btnSend = findViewById(R.id.btnSend);
        btnApply = findViewById(R.id.btnApply);
        Button btnBack = findViewById(R.id.btnBack);
        Button btnPick = findViewById(R.id.btnPickChapter);

        title = getIntent().getStringExtra("title");
        String dir = getIntent().getStringExtra("dir");
        novelDir = dir == null ? null : new File(dir);
        if (novelDir != null) chapters = SaveHelper.loadChapters(novelDir);

        tvChat.setText(getString(R.string.chat_first_hint));
        btnApply.setVisibility(Button.GONE);
        btnBack.setOnClickListener(v -> finish());
        btnSend.setOnClickListener(v -> send());
        btnPick.setOnClickListener(v -> pickChapter());
        btnApply.setOnClickListener(v -> applyRewrite());
    }

    private void pickChapter() {
        if (chapters.isEmpty()) {
            toast(R.string.chat_empty);
            return;
        }
        String[] names = new String[chapters.size()];
        for (int i = 0; i < chapters.size(); i++) names[i] = chapters.get(i).title;
        new AlertDialog.Builder(this)
                .setTitle(R.string.chat_pick_hint)
                .setItems(names, (d, which) -> {
                    rewriteTarget = which;
                    etInput.setText("");
                    etInput.setHint(getString(R.string.chat_rewrite_hint, names[which]));
                    render("（你选择了改写「" + names[which] + "」，请在下方输入指令，AI 将输出整章新正文）");
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    private void send() {
        final String msg = etInput.getText().toString().trim();
        if (TextUtils.isEmpty(msg)) return;
        etInput.setText("");

        final int target = rewriteTarget;
        render("你：" + msg);
        btnSend.setEnabled(false);
        render("AI：" + getString(R.string.chat_sending));

        new Thread(() -> {
            final String context = buildContext(target);
            runOnUiThread(() -> {
                service.chat(title == null ? "" : title, context, new ArrayList<>(turns), msg,
                        new NovelService.ChatCallback() {
                            @Override
                            public void onReply(String reply) {
                                btnSend.setEnabled(true);
                                if (target >= 0) {
                                    lastReply = reply;
                                    turns.add(new String[]{"user", msg});
                                    turns.add(new String[]{"assistant", reply});
                                    render("AI：\n" + reply);
                                    render(getString(R.string.chat_apply_prompt));
                                    btnApply.setVisibility(Button.VISIBLE);
                                } else {
                                    turns.add(new String[]{"user", msg});
                                    turns.add(new String[]{"assistant", reply});
                                    render("AI：" + reply);
                                }
                            }

                            @Override
                            public void onError(String error) {
                                btnSend.setEnabled(true);
                                Toast.makeText(ChatActivity.this, error, Toast.LENGTH_LONG).show();
                            }
                        });
            });
        }).start();
    }

    private void applyRewrite() {
        if (novelDir == null || rewriteTarget < 0 || lastReply.isEmpty()) return;
        String cleaned = cleanReply(lastReply);
        if (SaveHelper.updateChapter(novelDir, rewriteTarget, cleaned)) {
            Chapter c = chapters.get(rewriteTarget);
            chapters.set(rewriteTarget, new Chapter(c == null ? "第 " + (rewriteTarget + 1) + " 章" : c.title, cleaned));
            toast(getString(R.string.chat_applied, c == null ? "第 " + (rewriteTarget + 1) + " 章" : c.title));
            rewriteTarget = -1;
            lastReply = "";
            btnApply.setVisibility(Button.GONE);
            setResult(RESULT_OK);
        } else {
            toast(R.string.chat_apply_failed);
        }
    }

    private String cleanReply(String r) {
        if (r == null) return "";
        String s = r.trim();
        s = s.replaceAll("(?s)^.*?第\\s*\\d+\\s*章.*?\n+", "");
        return s.trim();
    }

    private void render(String line) {
        transcript.append(line).append("\n\n");
        final TextView tv = tvChat;
        tv.setText(transcript.toString());
        tv.post(() -> tv.scrollTo(0, tv.getLayout() == null ? 0 : tv.getLayout().getLineTop(tv.getLineCount())));
    }

    private String buildContext(int target) {
        StringBuilder sb = new StringBuilder();
        if (target >= 0 && target < chapters.size()) {
            Chapter c = chapters.get(target);
            sb.append("作者要求**改写**以下章节，请只输出改写后的整章正文，不要任何解释或额外标题：\n");
            sb.append("目标章节：").append(c.title).append("\n");
            sb.append("当前正文：\n").append(c.content == null ? "" : clip(c.content, 4000)).append("\n");
            return sb.toString();
        }
        sb.append("书名：").append(title == null ? "" : title).append("\n");
        String heroName = novelDir == null ? "" : SaveHelper.getHeroName(novelDir);
        if (!TextUtils.isEmpty(heroName)) sb.append("主角：").append(heroName).append("\n");
        String outline = novelDir == null ? "" : SaveHelper.getOutline(novelDir);
        if (!TextUtils.isEmpty(outline)) sb.append("大纲：").append(clip(outline.replaceAll("\\s+", " "), 800)).append("\n");
        StringBuilder sum = new StringBuilder();
        int from = Math.max(0, chapters.size() - 3);
        for (int i = from; i < chapters.size(); i++) {
            Chapter c = chapters.get(i);
            sum.append(c.title).append("：").append(clip(c.content == null ? "" : c.content.replaceAll("\\s+", " "), 400)).append("\n");
        }
        if (sum.length() > 0) sb.append("最近正文：\n").append(sum);
        return sb.toString();
    }

    private String clip(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        service.shutdown();
    }
}
