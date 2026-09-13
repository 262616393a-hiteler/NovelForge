package com.novelcraft.writer;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class NovelService {

    private static final String TAG = "NovelService";
    private static final String PREFS_NAME = "novel_settings";
    private static final String KEY_API_BASE_URL = "api_base_url";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_MODEL = "model";

    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    private static final String CHAT_COMPLETIONS_ENDPOINT = "/v1/chat/completions";
    private static final String DEFAULT_MODEL = "deepseek-chat";

    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int READ_TIMEOUT_MS = 120000;
    private static final int MAX_TOKENS_CAP = 8192;
    private static final int CONTEXT_CHARS = 7000;

    private final Context context;
    private final ExecutorService executorService;
    private final Handler mainHandler;
    private volatile java.util.concurrent.Future<?> lastFuture;

    private volatile int lastHttpCode = 0;
    private volatile String lastRawResponse = "";
    private final java.util.List<Chapter> lastChapters = new java.util.ArrayList<>();

    public interface NovelCallback {
        void onStatus(String status);
        void onContent(String fullText, boolean done);
        void onError(String error);
    }

    public interface TestCallback {
        void onSuccess();
        void onError(String error);
    }

    public static class Params {
        public String title = "";
        public String genre = "";
        public String style = "";
        public int chapterLength = 1000;
        public String chapterTitle = "";
        public String previousStory = "";
        public int batchCount = 1;
        public boolean isFirst = true;
        public int totalChapters = 0;
        public String outline = "";
        public String settings = "";
        public String heroName = "";
        public String mainContent = "";
        public int startChapterNumber = 0;
    }

    public static class Chapter {
        public final String title;
        public final String content;

        public Chapter(String title, String content) {
            this.title = title == null ? "" : title;
            this.content = content == null ? "" : content;
        }
    }

    public NovelService(Context context) {
        this.context = context.getApplicationContext();
        this.executorService = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void generate(Params params, NovelCallback callback) {
        lastFuture = executorService.submit(() -> {
            StringBuilder novel = new StringBuilder();
            if (params.previousStory != null) {
                novel.append(params.previousStory);
            }
            lastChapters.clear();
            int total = Math.max(1, params.batchCount);
            int chapterNumber = params.startChapterNumber > 0
                    ? params.startChapterNumber
                    : (params.isFirst ? 1 : countChapters(params.previousStory) + 1);
            try {
                int filled = 0;
                for (int i = 0; i < total; i++) {
                    int currentChapter = chapterNumber + i;
                    String status = (total > 1 ? ("连载 " + (i + 1) + "/" + total + " ｜ ") : "")
                            + "第 " + currentChapter + " 章";
                    postStatus(status, callback);
                    String storySoFar = novel.toString();
                    String chapterText = callDeepSeek(params, currentChapter, storySoFar);
                    if (chapterText == null || chapterText.trim().isEmpty()) {
                        Log.e(TAG, "第 " + currentChapter + " 章返回为空，停止本次生成以便重试");
                        postStatus(status + "（正文为空，已停止，请重试本章）", callback);
                        break;
                    }
                    filled++;
                    String heading = notEmpty(params.chapterTitle)
                            ? params.chapterTitle
                            : ("第 " + currentChapter + " 章");
                    lastChapters.add(new Chapter(heading, chapterText));
                    appendChapter(novel, currentChapter, chapterText, params.chapterTitle);
                    postContent(novel.toString(), false, callback);
                }
                postContent(novel.toString(), true, callback);
            } catch (Exception e) {
                Log.e(TAG, "AI 写作失败", e);
                postError(formatError(e), callback);
            }
        });
    }

    public void cancel() {
        java.util.concurrent.Future<?> f = lastFuture;
        if (f != null) f.cancel(true);
    }

    public void testConnection(TestCallback callback) {
        executorService.execute(() -> {
            try {
                callDeepSeekRaw("请只用两个词回答：正常", 64, 0.1);
                mainHandler.post(callback::onSuccess);
            } catch (Exception e) {
                Log.e(TAG, "测试连接失败", e);
                mainHandler.post(() -> callback.onError(formatError(e)));
            }
        });
    }

    // ---------- AI 策划：大纲 ----------
    public void generateOutline(Params params, NovelCallback callback) {
        executorService.execute(() -> {
            try {
                String outline = callDeepSeekRawWithMessages(
                        outlineSystemPrompt(params), outlineUserPrompt(params), 8192, 0.5);
                mainHandler.post(() -> callback.onContent(outline, true));
            } catch (Exception e) {
                Log.e(TAG, "大纲生成失败", e);
                mainHandler.post(() -> callback.onError(formatError(e)));
            }
        });
    }

    // ---------- AI 质量评审 ----------
    public void review(String title, String content, NovelCallback callback) {
        executorService.execute(() -> {
            try {
                String text = callDeepSeekRawWithMessages(
                        reviewSystemPrompt(), reviewUserPrompt(title, content), 8192, 0.3);
                mainHandler.post(() -> callback.onContent(text, true));
            } catch (Exception e) {
                Log.e(TAG, "评审失败", e);
                mainHandler.post(() -> callback.onError(formatError(e)));
            }
        });
    }

    private String outlineSystemPrompt(Params p) {
        String hero = notEmpty(p.heroName)
                ? "\n主角姓名必须严格为《" + p.heroName + "》，【硬性设定】与【正文大纲】都要用这个名字，不得更改或另起别名。\n"
                : "";
        return "你是一位资深华语网络小说策划与责任编辑。\n" +
                "根据用户提供的小说主题、题材、风格与预计章节数，设计一份结构严谨、节奏合理、有商业吸引力的章节目大纲。\n" +
                "重要：所有核心人物（尤其主角）姓名与设定必须固定，后续所有章节写作都将沿用，绝对不得改名或前后矛盾。\n" +
                hero +
                "若已有【已写正文】，请先整篇通读并概括已有剧情与设定，之后的大纲必须与其一致（人物姓名、时间线、世界观不要复发矛盾），并在【正文大纲】里【总结已有章节的前情 + 规划未写的后续章节】。\n" +
                "请严格按以下两个部分输出：\n" +
                "【硬性设定】\n" +
                "- 主角设定：姓名＋年龄＋性格＋身份/能力＋目标；\n" +
                "- 主要配角：姓名＋一句话人设＋作用（每人一行）；\n" +
                "- 世界观核心设定：一句话；\n" +
                "- 主线一句话。\n" +
                "【正文大纲】\n" +
                "- 三句话整体主线；\n" +
                "- 若已有正文：先给出【前情提要】（概括已写章节），再给出【后续章节】大纲；\n" +
                "- 后续每章“章节序号＋标题＋一句话剧情要点＋本章功能（铺垫/推进/高潮/转折/收束）”；\n" +
                "- 明确起承转合、伏笔回收、关键转折与大结局。\n" +
                "语言精炼，直接输出这两个段落，不要额外解释。";
    }

    private String outlineUserPrompt(Params p) {
        StringBuilder sb = new StringBuilder();
        sb.append("请为小说《").append(safe(p.title)).append("》设计章节大纲。\n");
        sb.append("题材：").append(safe(p.genre)).append("；写作风格：").append(notEmpty(p.style) ? safe(p.style) : "自然流畅").append("。\n");
        if (notEmpty(p.mainContent)) {
            sb.append("主要内容 / 故事梗概：\n").append(p.mainContent).append("\n");
        }
        sb.append("要求：先为所有核心人物确定并固定姓名与设定（写入【硬性设定】段），再规划各章剧情（写入【正文大纲】段）。\n");
        if (notEmpty(p.heroName)) {
            sb.append("主角姓名指定为：").append(p.heroName).append("，必须严格使用此名，不得更改。\n");
        }
        if (p.totalChapters > 0) {
            sb.append("预计共 ").append(p.totalChapters).append(" 章，请按此规划各章内容。\n");
        } else {
            sb.append("预计章节数未定，请给出约 12~20 章的整体大纲。\n");
        }
        if (notEmpty(p.previousStory)) {
            sb.append("以下是【已写正文】，请先整篇通读：概括已有剧情与人物（人名沿用、不与原文冲突），并在【正文大纲】中【总结已有内容 + 续写未来内容】。\n");
            sb.append("----已写正文----\n");
            sb.append(clip(p.previousStory, 24000));
            sb.append("\n----已写正文结束----\n");
        }
        return sb.toString();
    }

    // 评审大纲（单独的提示）
    public void reviewOutline(String title, String outline, NovelCallback callback) {
        executorService.execute(() -> {
            try {
                String text = callDeepSeekRawWithMessages(reviewOutlineSystemPrompt(),
                        "请评审以下小说《" + safe(title) + "》的大纲：\n\n————大纲————\n" + clip(outline, 12000)
                                + "\n————大纲结束————\n\n请按写作质量维度逐项打分并给出总分、优缺点与改进建议。",
                        8192, 0.3);
                mainHandler.post(() -> callback.onContent(text, true));
            } catch (Exception e) {
                Log.e(TAG, "大纲评审失败", e);
                mainHandler.post(() -> callback.onError(formatError(e)));
            }
        });
    }

    // 大纲 vs 已写正文：检查要点是否齐全、人物是否一致
    public void compare(String title, String outline, String content, NovelCallback callback) {
        executorService.execute(() -> {
            try {
                String text = callDeepSeekRawWithMessages(compareSystemPrompt(),
                        "小说《" + safe(title) + "》\n\n————大纲————\n" + clip(outline, 20000)
                                + "\n————大纲结束————\n\n————已写正文————\n" + clip(content, 26000)
                                + "\n————正文结束————\n\n请完成对比检查。",
                        8192, 0.3);
                mainHandler.post(() -> callback.onContent(text, true));
            } catch (Exception e) {
                Log.e(TAG, "对比检查失败", e);
                mainHandler.post(() -> callback.onError(formatError(e)));
            }
        });
    }

    // ---------- AI 依据评审意见自动修改某章 ----------
    public void reviseChapter(String title, String heroName, String mainContent, String outline,
                              String chapterTitle, String chapterContent, String review,
                              ChatCallback callback) {
        executorService.execute(() -> {
            try {
                String sys = "你是一位资深小说修订编辑。请根据评审意见，对给定章节进行修改与润色。\n" +
                        "要求：\n" +
                        "1. 保持人物姓名、身份、世界观与主线设定一致，不得改名或另起设定；\n" +
                        "2. 针对评审指出的问题做实质改进（结构、冲突、人物、文笔、逻辑等），保留原章节的关键情节；\n" +
                        "3. 直接输出修改后的整章正文，不要任何解释、评分或额外标题。";
                StringBuilder u = new StringBuilder();
                u.append("小说《").append(safe(title)).append("》\n");
                if (notEmpty(heroName)) u.append("主角：").append(heroName).append("\n");
                if (notEmpty(mainContent)) u.append("主要内容：").append(mainContent).append("\n");
                if (notEmpty(outline)) u.append("大纲（节选）：").append(clip(outline, 3000)).append("\n");
                u.append("\n章节：").append(safe(chapterTitle)).append("\n");
                u.append("评审意见：\n").append(clip(review, 4000)).append("\n");
                u.append("\n原章节内容：\n").append(clip(chapterContent, 6000)).append("\n");
                u.append("\n请输出修改后的整章正文。");
                String reply = callDeepSeekRawWithMessages(sys, u.toString(), 8192, 0.7);
                mainHandler.post(() -> callback.onReply(reply));
            } catch (Exception e) {
                Log.e(TAG, "自动修改失败", e);
                mainHandler.post(() -> callback.onError(formatError(e)));
            }
        });
    }

    private String reviewOutlineSystemPrompt() {
        return "你是一位专业小说编审。请针对给定的小说大纲（而非正文）进行评审：\n" +
                "1. 结构合理性：起承转合是否完整、章节安排与节奏是否合理、是否拖沓或跳跃；\n" +
                "2. 人物与设定：人物是否立体、姓名与设定是否固定清晰；\n" +
                "3. 冲突与吸引力：主线冲突、悬念、爽点是否充足；\n" +
                "4. 逻辑与一致性：设定是否自洽、前后是否一致；\n" +
                "5. 收官可行性：能否按计划在大纲末尾自然收束。\n" +
                "输出：逐项打分（每项标出得分/满分，可合计为 100）＋综评＋2~3 条可执行修改建议。直接输出。";
    }

    private String compareSystemPrompt() {
        return "你是专业小说审校。请先完整通读【已写正文】，再与【大纲】仔细逐项对照，检查：\n" +
                "1. 大纲中的每一章要点/关键节点是否在正文中覆盖到，哪些已达成、哪些缺失或因正文调整而偏离；\n" +
                "2. 人物姓名、身份、关系、能力与核心设定是否与大纲一致，有无前后矛盾或改名；\n" +
                "3. 主线与伏笔是否按大纲推进，有无遗漏或新冲突。\n" +
                "输出：\n" +
                "- 先给出“要点覆盖率”（X%）的概括；\n" +
                "- 分列【已覆盖要点】【缺失/偏离要点】【设定/人物一致性检查结果】；\n" +
                "- 最后给出 2~3 条建议（如何补齐遗漏、修正偏差）。\n" +
                "客观、直接输出。";
    }

    private String reviewSystemPrompt() {
        return "你是一位资深小说编审（责任编辑），擅长专业、客观、可执行的稿件评审。\n" +
                "请严格按照以下评分维度（满分合计 100 分）评审给定的小说正文：\n" +
                "1. 剧情与结构（20）：情节是否完整、推进是否合理、有无拖沓；\n" +
                "2. 人物塑造（15）：人物是否立体、行为是否符合性格；\n" +
                "3. 世界观/设定（10）：设定是否自洽、是否前后一致；\n" +
                "4. 冲突与吸引力（15）：有没有冲突、悬念、爽点/刺激点；\n" +
                "5. 文笔与表达（15）：语言自然度、画面感、节奏；\n" +
                "6. 逻辑与连贯性（10）：因果、时间线、人物行动是否合理；\n" +
                "7. 情绪感染力（5）：紧张、爽、悲、恐惧等情绪是否有效；\n" +
                "8. 创意与差异化（5）：是否有自己的特色；\n" +
                "9. 阅读体验（5）：开头、结尾、章节节奏、可读性。\n" +
                "输出要求：\n" +
                "1. 先逐项打分（每项标出“得分/满分”）；\n" +
                "2. 给出综合总分（满分 100）与等第评价；\n" +
                "3. 逐条简短点评优缺点；\n" +
                "4. 最后给出具体的 2~3 条可操作改进建议。\n" +
                "语言简洁、结构清晰，直接输出。";
    }

    private String reviewUserPrompt(String title, String content) {
        String slice = clip(content, 26000);
        return "请先完整通读以下小说正文，把握整体剧情、人物与设定后，再逐项评分。\n\n"
                + "小说《" + safe(title) + "》正文：\n\n"
                + "————正文————\n" + slice + "\n————正文结束————\n\n请按上述维度评分并给出总分与建议。";
    }

    private String clip(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }

    private String callDeepSeek(Params params, int chapterNumber, String storySoFar)
            throws IOException, JSONException {
        String systemPrompt = buildSystemPrompt(params);
        String userPrompt = buildUserPrompt(params, chapterNumber, storySoFar);
        int maxTokens = clampMaxTokens(params.chapterLength);
        return callDeepSeekRawWithMessages(systemPrompt, userPrompt, maxTokens, 0.9);
    }

    private String callDeepSeekRaw(String userMessage, int maxTokens, double temperature)
            throws IOException, JSONException {
        return callDeepSeekRawWithMessages(userMessage, userMessage, maxTokens, temperature);
    }

    private String callDeepSeekRawWithMessages(String system, String user, int maxTokens, double temperature)
            throws IOException, JSONException {
        JSONArray messages = new JSONArray();
        messages.put(message("system", system));
        messages.put(message("user", user));
        return postMessages(messages, maxTokens, temperature);
    }

    private String postMessages(JSONArray messages, int maxTokens, double temperature)
            throws IOException, JSONException {
        String apiKey = getApiKey();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("API Key 未设置，请先在设置中配置");
        }

        String normalizedBase = normalizeBaseUrl(getApiBaseUrl());
        String finalUrl = buildEndpoint(normalizedBase);
        Log.d(TAG, "[AI] finalUrl=" + finalUrl + " model=" + getModel());

        HttpURLConnection connection = null;
        try {
            URL url = new URL(finalUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Accept", "application/json");
            connection.setDoOutput(true);

            JSONObject body = new JSONObject();
            body.put("model", getModel());
            body.put("temperature", temperature);
            body.put("max_tokens", maxTokens);
            body.put("stream", false);
            body.put("messages", messages);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = connection.getResponseCode();
            String responseBody = readResponse(connection, code);
            lastHttpCode = code;
            lastRawResponse = responseBody == null ? "" : responseBody;
            Log.d(TAG, "[AI] HTTP " + code + " resp=" + responseBody);

            if (code >= 400) {
                throw new HttpException(code, responseBody);
            }
            return parseAiResponse(responseBody);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // ---------- AI 对话（修改建议 / 协商） ----------
    public interface ChatCallback {
        void onReply(String reply);
        void onError(String error);
    }

    public void chat(String title, String context, java.util.List<String[]> turns, String userMsg, ChatCallback callback) {
        executorService.execute(() -> {
            try {
                JSONArray messages = new JSONArray();
                messages.put(message("system", "你是一位经验丰富的小说编辑兼写作助手。你和作者一起打磨《" + safe(title) + "》。"
                        + "\n结合以下已写正文/大纲上下文，针对作者的指令给出专业、可执行的具体建议；若作者要求改写某段，请直接给出改写后的文字，并简要说明改动。\n"
                        + "上下文（节选）：\n" + clip(context, 6000)));
                if (turns != null) {
                    for (String[] t : turns) {
                        if (t != null && t.length == 2) {
                            messages.put(message(t[0], t[1]));
                        }
                    }
                }
                messages.put(message("user", userMsg));
                String reply = postMessages(messages, 4096, 0.7);
                mainHandler.post(() -> callback.onReply(reply));
            } catch (Exception e) {
                Log.e(TAG, "对话失败", e);
                mainHandler.post(() -> callback.onError(formatError(e)));
            }
        });
    }

    private JSONObject message(String role, String content) throws JSONException {
        JSONObject m = new JSONObject();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private String buildSystemPrompt(Params p) {
        String plan = p.totalChapters > 0
                ? "\n" + "6. 全书预计共 " + p.totalChapters + " 章，请整体规划剧情的起承转合，在最后一章（第 " + p.totalChapters + " 章）自然收束，交代主要人物结局，给读者一个有说服力的完整结局，不要戛然而止或草草断尾。"
                : "";
        String outlineBlock = notEmpty(p.outline)
                ? "\n" + "7. 本书大纲如下，写作必须严格遵循：\n" + p.outline
                : "";
        String hard = notEmpty(p.settings)
                ? "【硬性设定·必须遵守】\n" + p.settings
                + "\n以上姓名、身份与核心设定绝对禁止更改；写成正文时必须使用上述姓名，不得改名或另造新主角。\n"
                : "";
        String hero = notEmpty(p.heroName)
                ? "【主角姓名·必须遵守】主角姓名必须严格为《" + p.heroName + "》，正文、大纲与前文均不得改名，不得另起别名，主角必须是这个名字。\n"
                : "";
        String main = notEmpty(p.mainContent)
                ? "【主要内容·故事梗概】\n" + p.mainContent + "\n请围绕以上核心设定展开，保持主线一致。\n"
                : "";
        return "你是一位文笔精湛、想象力丰富的华语小说作家。\n" +
                "请创作符合《" + safe(p.title) + "》主题、「" + safe(p.genre) + "」题材的作品。\n" +
                hard +
                "写作要求：\n" +
                "1. 情节有张力、前后连贯，人物形象鲜明立体。\n" +
                "2. 语言生动流畅，善用对话、动作、环境与心理描写。\n" +
                "3. 严格遵循指定题材与写作风格" + (notEmpty(p.style) ? "（" + safe(p.style) + "）" : "") + "。\n" +
                "4. 尊重已出现的情节与设定，保持世界观一致。\n" +
                "5. 与大纲保持高度一致：已写成章节不可推翻人物姓名与关键设定；写作时潜意识对照大纲，确保每章覆盖大纲安排的要点。\n" +
                "6. 直接输出小说正文，不要添加任何解释、标题序号、前言或额外说明。"
                + hero + main + plan + outlineBlock;
    }

    private String buildUserPrompt(Params p, int chapterNumber, String storySoFar) {
        StringBuilder sb = new StringBuilder();
        String title = safe(p.title);
        String genre = safe(p.genre);
        String style = notEmpty(p.style) ? safe(p.style) : "自然流畅";
        boolean isFinal = p.totalChapters > 0 && chapterNumber >= p.totalChapters;

        if (p.isFirst && !notEmpty(storySoFar)) {
            sb.append("请创作小说《").append(title).append("》。\n");
            sb.append("题材：").append(genre).append("；写作风格：").append(style).append("。\n");
            sb.append("这是第一章（开篇）：交代故事背景，引入核心人物与主线冲突，埋下悬念，激发读者阅读兴趣。\n");
            sb.append("请严格按照本书大纲的第 1 章要点来写，并保持人物姓名与设定一致。\n");
        } else {
            sb.append("这是小说《").append(title).append("》的最近正文（结尾部分）：\n");
            sb.append("---- 前文结尾 ----\n");
            sb.append(truncate(storySoFar));
            sb.append("\n---- 前文结束 ----\n");
            sb.append("现在写到第 ").append(chapterNumber).append(" 章")
                    .append(p.totalChapters > 0 ? "（全书共 " + p.totalChapters + " 章）" : "")
                    .append("。请紧接这段前文继续写作：\n");
            sb.append("1. 严格承上启下：人物、场景、时间线、视角、世界观和文风必须与上文完全一致，从上一段的断点自然衔接，不要另起炉灶或跳跃；\n");
            sb.append("2. 不要复述或概括已写过的内容，直接推进新的剧情；\n");
            sb.append("3. 保持悬念与节奏，结尾可适当留钩子，便于继续往下读；\n");
            sb.append("4. 潜意识对照本书大纲：本章要覆盖大纲中对应的要点，已写章节与大纲不一致的地方不得新开矛盾；人物姓名与关键设定不得改变；\n");
            if (isFinal) {
                sb.append("5. 这是最后一章：请在收束本章的同时整体收尾，交代主要人物结局与主线结果，给出一个完整、有说服力的大结局；\n");
            } else {
                sb.append("5. 直接输出正文，不加解释。\n");
            }
        }

        if (notEmpty(p.chapterTitle)) {
            sb.append("本章标题：《").append(safe(p.chapterTitle)).append("》。\n");
        }
        sb.append("本章约 ").append(p.chapterLength).append(" 字，直接输出正文。");
        return sb.toString();
    }

    private String parseAiResponse(String responseBody) throws JSONException {
        if (responseBody == null || responseBody.trim().isEmpty()) {
            throw new JSONException("服务器返回为空");
        }
        JSONObject response;
        try {
            response = new JSONObject(responseBody);
        } catch (JSONException e) {
            String trim = responseBody.trim();
            if (trim.startsWith("<") || trim.toLowerCase().startsWith("<!doctype") || trim.toLowerCase().contains("<html")) {
                throw new JSONException("服务器返回的不是 JSON，而是网页（可能是网络 DNS 污染、运营拦截或代理返回 HTML）。原始响应开头：\"" + snippet(responseBody) + "\"");
            }
            throw new JSONException("无法解析响应 JSON（原始响应：\"" + snippet(responseBody) + "\"）");
        }
        JSONArray choices = response.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new JSONException("AI 返回内容为空（choices 缺失）");
        }
        JSONObject choice = choices.optJSONObject(0);
        if (choice == null) {
            throw new JSONException("AI 返回内容为空（choices[0] 缺失）");
        }
        JSONObject message = choice.optJSONObject("message");
        if (message == null) {
            throw new JSONException("AI 返回内容为空（message 缺失）");
        }
        String content = message.optString("content", "").trim();
        if (content.isEmpty()) {
            String reasoning = message.optString("reasoning_content", "").trim();
            if (!reasoning.isEmpty()) {
                throw new JSONException("模型把输出额度全花在了思考过程上，正文为空。请增大“单章字数”，或改用非推理模型（如 deepseek-chat）");
            }
            throw new JSONException("AI 返回内容为空（content 为空）。若多次出现，请增大“单章字数”或更换模型");
        }
        return content;
    }

    private String snippet(String s) {
        if (s == null) return "";
        String t = s.trim().replace("\n", " ");
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }

    private void appendChapter(StringBuilder novel, int chapterNumber, String chapterText, String fixedTitle) {
        if (novel.length() > 0) {
            novel.append("\n\n");
        }
        if (notEmpty(fixedTitle)) {
            novel.append(safe(fixedTitle));
        } else {
            novel.append("第 ").append(chapterNumber).append(" 章");
        }
        novel.append("\n\n").append(chapterText).append("\n");
    }

    private int clampMaxTokens(int chapterLength) {
        // 给足输出预算，避免推理模型把 token 全花在思考过程上导致正文为空
        int target = Math.max(chapterLength * 2, 4096);
        return Math.min(target, MAX_TOKENS_CAP);
    }

    private int countChapters(String story) {
        if (story == null || story.isEmpty()) return 0;
        int count = 0;
        int from = 0;
        while (from < story.length()) {
            int idx = story.indexOf("第", from);
            if (idx < 0) break;
            int dot = story.indexOf("章", idx);
            if (dot < 0) break;
            count++;
            from = dot + 1;
        }
        return count;
    }

    private String truncate(String s) {
        if (s == null) return "";
        if (s.length() <= CONTEXT_CHARS) return s;
        return "……".concat(s.substring(s.length() - CONTEXT_CHARS));
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) return DEFAULT_BASE_URL;
        String url = baseUrl.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    /** 根据各厂商的 Base URL 习惯，拼出 chat/completions 端点。 */
    private String buildEndpoint(String base) {
        String url = base;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        String lower = url.toLowerCase();
        if (lower.endsWith("/chat/completions")) return url;
        if (lower.endsWith("/compatible-mode")) return url + "/v1/chat/completions";
        if (lower.matches(".*/v\\d+$")) return url + "/chat/completions";
        return url + "/v1/chat/completions";
    }

    private String readResponse(HttpURLConnection connection, int code) throws IOException {
        java.io.InputStream is = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (is == null) return "";
        try (Scanner scanner = new Scanner(is, StandardCharsets.UTF_8.name()).useDelimiter("\\A")) {
            return scanner.hasNext() ? scanner.next() : "";
        }
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    private boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private void postStatus(String status, NovelCallback cb) {
        mainHandler.post(() -> cb.onStatus(status));
    }

    private void postContent(String fullText, boolean done, NovelCallback cb) {
        mainHandler.post(() -> cb.onContent(fullText, done));
    }

    private void postError(String e, NovelCallback cb) {
        mainHandler.post(() -> cb.onError(e));
    }

    private String formatError(Exception e) {
        if (e instanceof HttpException) {
            HttpException he = (HttpException) e;
            int statusCode = he.getStatusCode();
            String body = he.getMessage();
            String jsonError = extractJsonError(body);
            if (jsonError != null && !jsonError.isEmpty()) {
                return "HTTP " + statusCode + ": " + jsonError;
            }
            if (statusCode == 401) return "HTTP 401: API Key 无效或已过期，请检查设置。";
            if (statusCode == 402) return "HTTP 402: 配额/余额不足，请检查账户。";
            if (statusCode == 404) return "HTTP 404: 模型名或接口路径不存在。请检查“模型”是否填写正确（部分模型已下线），以及 Base URL 是否正确。";
            if (statusCode == 410) return "HTTP 410: 该模型已下线，请在“模型”里换一个可用的模型。";
            if (statusCode == 429) return "HTTP 429: 请求过于频繁，请稍后再试。";
            if (statusCode >= 500) return "HTTP " + statusCode + ": 服务器内部错误，请稍后重试。";
            return "HTTP " + statusCode + ": " + body;
        } else if (e instanceof IOException) {
            String m = e.getMessage();
            if (m != null && m.contains("timeout")) return "连接超时：请检查网络或稍后重试";
            if (m != null && m.contains("UnknownHost")) return "DNS 解析失败：无法连接 API 服务器";
            if (m != null && m.contains("Connection refused")) return "连接被拒绝：API 服务器不可达";
            return "网络错误：" + m;
        } else if (e instanceof JSONException) {
            return "JSON 解析错误：" + e.getMessage();
        } else if (e instanceof IllegalStateException) {
            return e.getMessage();
        }
        return "未知错误：" + e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    private String extractJsonError(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            JSONObject json = new JSONObject(body);
            if (json.has("error")) {
                JSONObject error = json.getJSONObject("error");
                if (error.has("message")) return error.getString("message");
            }
            if (json.has("message")) return json.getString("message");
            if (json.has("error_message")) return json.getString("error_message");
            if (json.has("detail")) return json.getString("detail");
            if (json.has("title")) return json.getString("title");
        } catch (JSONException ignored) {
        }
        return null;
    }

    public void saveSettings(String baseUrl, String apiKey, String model) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_API_BASE_URL, baseUrl)
                .putString(KEY_API_KEY, apiKey)
                .putString(KEY_MODEL, model)
                .apply();
    }

    public void saveProviderKey(String provider, String key) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString("key_" + provider, key == null ? "" : key).apply();
    }

    public String getProviderKey(String provider) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString("key_" + provider, "");
    }

    public void saveProvider(String provider) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString("provider", provider == null ? "" : provider).apply();
    }

    public String getProvider() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString("provider", "");
    }

    public String getApiBaseUrl() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_API_BASE_URL, DEFAULT_BASE_URL);
    }

    public String getApiKey() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_API_KEY, "");
    }

    public String getModel() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_MODEL, DEFAULT_MODEL);
    }

    public boolean hasValidApiKey() {
        String key = getApiKey();
        return key != null && !key.trim().isEmpty();
    }

    public int getLastHttpCode() {
        return lastHttpCode;
    }

    public String getLastRawResponse() {
        return lastRawResponse == null ? "" : lastRawResponse;
    }

    public java.util.List<Chapter> getLastChapters() {
        return lastChapters;
    }

    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static class HttpException extends RuntimeException {
        private final int statusCode;

        HttpException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        int getStatusCode() {
            return statusCode;
        }
    }
}
