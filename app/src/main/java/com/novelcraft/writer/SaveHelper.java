package com.novelcraft.writer;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.novelcraft.writer.NovelService.Chapter;

public class SaveHelper {

    private static final String TAG = "SaveHelper";
    private static final String SUBDIR = "小说";
    private static final String LIB_DIR = "library";
    private static final String PREFS_IMPORTED = "imported_txt";

    public static String buildFileName(String title) {
        String base = TextUtils.isEmpty(title) ? "无题" : title;
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String safe = base.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        return safe + "_" + ts + ".txt";
    }

    /**
     * 保存小说到系统“下载”目录（通过 MediaStore / 旧版公共目录）。
     * 返回后可展示的保存位置摘要；API<29 未授权时抛出 SecurityException。
     */
    public static String saveNovel(Context context, String title, String content)
            throws IOException, SecurityException {
        if (TextUtils.isEmpty(content)) {
            return null;
        }
        String fileName = buildFileName(title);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIR);
            Uri uri = context.getContentResolver().insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("无法创建下载文件");
            }
            try (OutputStream os = context.getContentResolver().openOutputStream(uri)) {
                if (os == null) {
                    throw new IOException("无法打开输出流");
                }
                os.write(bytes);
                os.flush();
            }
            return "下载/小说/" + fileName;
        } else {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File sub = new File(dir, SUBDIR);
            if (!sub.exists() && !sub.mkdirs()) {
                throw new IOException("无法创建目录");
            }
            File file = new File(sub, fileName);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(bytes);
                fos.flush();
            }
            return "下载/小说/" + fileName;
        }
    }

    /** 复制到应用外部私有目录并返回文件，供分享/分享 Intent 使用。 */
    public static File makeShareFile(Context context, String title, String content) throws IOException {
        if (TextUtils.isEmpty(content)) {
            return null;
        }
        String fileName = buildFileName(title);
        File dir = context.getExternalFilesDir("share");
        if (dir == null) {
            dir = context.getCacheDir();
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建分享目录");
        }
        File file = new File(dir, fileName);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        }
        return file;
    }

    // ---------------- 书库（结构化） ----------------

    public static class NovelInfo {
        public final File dir;
        public final String title;
        public final int chapterCount;
        public final String genre;
        public final String style;
        public final int chapterLength;
        public final int totalChapters;

        public NovelInfo(File dir, String title, int chapterCount, String genre, String style,
                         int chapterLength, int totalChapters) {
            this.dir = dir;
            this.title = title;
            this.chapterCount = chapterCount;
            this.genre = genre;
            this.style = style;
            this.chapterLength = chapterLength;
            this.totalChapters = totalChapters;
        }
    }

    private static File libRoot(Context context) {
        File base = context.getExternalFilesDir(null);
        File dir = new File(base, LIB_DIR);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    public static File saveLibrary(Context context, String title, List<Chapter> chapters,
                                   String genre, String style, int chapterLength, int totalChapters,
                                   File targetDir) throws IOException {
        if (chapters == null || chapters.isEmpty()) {
            return targetDir;
        }
        File dir = targetDir;
        if (dir == null) {
            dir = new File(libRoot(context), sanitize(title) + "_" + System.currentTimeMillis());
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建书库目录");
        }
        try {
            JSONObject root = new JSONObject();
            root.put("title", title);
            root.put("created", System.currentTimeMillis());
            if (genre != null) root.put("genre", genre);
            if (style != null) root.put("style", style);
            root.put("chapterLength", chapterLength);
            root.put("totalChapters", totalChapters);
            JSONArray arr = new JSONArray();
            for (Chapter c : chapters) {
                JSONObject o = new JSONObject();
                o.put("title", c.title);
                o.put("content", c.content);
                arr.put(o);
            }
            root.put("chapters", arr);
            File f = new File(dir, "novel.json");
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (JSONException e) {
            throw new IOException("书库保存失败：" + e.getMessage());
        }
        return dir;
    }

    public static List<NovelInfo> listLibrary(Context context) {
        List<NovelInfo> out = new ArrayList<>();
        File[] dirs = libRoot(context).listFiles();
        if (dirs == null) return out;
        for (File d : dirs) {
            if (!d.isDirectory()) continue;
            File json = new File(d, "novel.json");
            JSONObject root = readJson(json);
            if (root == null) continue;
            String title = root.optString("title", d.getName());
            int count = root.optJSONArray("chapters") == null ? 0 : root.optJSONArray("chapters").length();
            String genre = root.optString("genre", "");
            String style = root.optString("style", "");
            int len = root.optInt("chapterLength", -1);
            int total = root.optInt("totalChapters", 0);
            out.add(new NovelInfo(d, title, count, genre, style, len, total));
        }
        out.sort((a, b) -> Long.compare(b.dir.lastModified(), a.dir.lastModified()));
        return out;
    }

    /** 读取某本小说的章节标题列表。 */
    public static List<String> chapterTitles(File dir) {
        List<String> titles = new ArrayList<>();
        JSONObject root = readJson(new File(dir, "novel.json"));
        if (root == null) return titles;
        JSONArray arr = root.optJSONArray("chapters");
        if (arr == null) return titles;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) {
                titles.add(o.optString("title", "第 " + (i + 1) + " 章"));
            } else {
                titles.add("第 " + (i + 1) + " 章");
            }
        }
        return titles;
    }

    /** 读取某章内容。index 从 0 开始。 */
    public static String chapterContent(File dir, int index) {
        JSONObject root = readJson(new File(dir, "novel.json"));
        if (root == null) return "";
        JSONArray arr = root.optJSONArray("chapters");
        if (arr == null || index < 0 || index >= arr.length()) return "";
        JSONObject o = arr.optJSONObject(index);
        return o == null ? "" : o.optString("content", "");
    }

    /** 把整本小说的章节拼成连续的正文文本，便于继续连载。 */
    public static String novelContent(File dir) {
        JSONObject root = readJson(new File(dir, "novel.json"));
        if (root == null) return "";
        JSONArray arr = root.optJSONArray("chapters");
        if (arr == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(o.optString("title", "第 " + (i + 1) + " 章"));
            sb.append("\n\n").append(o.optString("content", ""));
        }
        return sb.toString();
    }

    /** 将“下载/小说”里的 txt 导入到书库（仅导入一次），供旧作品显示。 */
    public static void importDownloads(Context context) {
        if (Build.VERSION.SDK_INT < 29) {
            return; // 旧版本公共目录读取需权限，跳过
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_IMPORTED, Context.MODE_PRIVATE);
        Set<String> done = new HashSet<>(prefs.getStringSet(PREFS_IMPORTED, new HashSet<>()));
        File lib = libRoot(context);
        int added = 0;

        Uri rootUri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL);
        Cursor c = null;
        try {
            c = context.getContentResolver().query(rootUri,
                    new String[]{MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.DATA,
                            MediaStore.Downloads.SIZE}, null, null, null);
            if (c == null) return;
            while (c.moveToNext()) {
                String name = c.getString(0);
                if (name == null || !name.endsWith(".txt")) continue;
                String data = c.getString(1);
                if (data == null || !data.replace("\\", "/").contains("/" + SUBDIR + "/")) continue;
                if (done.contains(name)) continue;
                String content = readText(new File(data));
                if (content == null || content.trim().isEmpty()) continue;
                String title = name.substring(0, name.length() - 4);
                title = title.replaceAll("_\\d{8}_\\d{6}$", "");
                List<Chapter> chapters = splitChapters(content);
                String folder = sanitize(title) + "_" + System.currentTimeMillis();
                File dir = new File(lib, folder);
                if (!dir.exists() && !dir.mkdirs()) continue;
                try {
                    JSONObject root = new JSONObject();
                    root.put("title", title);
                    root.put("created", System.currentTimeMillis());
                    JSONArray arr = new JSONArray();
                    for (Chapter ch : chapters) {
                        JSONObject o = new JSONObject();
                        o.put("title", ch.title);
                        o.put("content", ch.content);
                        arr.put(o);
                    }
                    root.put("chapters", arr);
                    File f = new File(dir, "novel.json");
                    try (FileOutputStream fos = new FileOutputStream(f)) {
                        fos.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
                    }
                    done.add(name);
                    added++;
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        if (added > 0) {
            prefs.edit().putStringSet(PREFS_IMPORTED, done).apply();
        }
    }

    /** 尽最大努力把整本 txt 拆成章节；无章节标记则视为单章。 */
    public static List<Chapter> splitChapters(String text) {
        List<Chapter> list = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return list;
        String body = text.replaceAll("\r\n", "\n").trim();
        String[] lines = body.split("\n");
        List<Integer> heads = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.matches("^第[\\s]*[0-9一二三四五六七八九十百]+[\\s]*章.*")) {
                heads.add(i);
            }
        }
        if (heads.isEmpty()) {
            list.add(new Chapter("正文", body));
            return list;
        }
        heads.add(lines.length);
        for (int i = 0; i < heads.size() - 1; i++) {
            int start = heads.get(i);
            int end = heads.get(i + 1);
            String title = lines[start].trim();
            StringBuilder sb = new StringBuilder();
            for (int j = start + 1; j < end; j++) {
                sb.append(lines[j]).append("\n");
            }
            String content = sb.toString().trim();
            if (i == heads.size() - 2 && content.isEmpty()) {
                break;
            }
            list.add(new Chapter(title, content));
        }
        return list;
    }

    private static JSONObject readJson(File f) {
        if (f == null || !f.exists()) return null;
        String s = readText(f);
        if (s == null) return null;
        try {
            return new JSONObject(s);
        } catch (JSONException e) {
            return null;
        }
    }

    /** 读取大纲文本（可能为空）。dir 为小说所在目录。 */
    public static String getOutline(File dir) {
        JSONObject root = dir == null ? null : readJson(new File(dir, "novel.json"));
        return root == null ? "" : root.optString("outline", "");
    }

    /** 读取硬性设定/人物卡（可能为空）。 */
    public static String getSettings(File dir) {
        JSONObject root = dir == null ? null : readJson(new File(dir, "novel.json"));
        return root == null ? "" : root.optString("settings", "");
    }

    /** 读取指定主角姓名（可能为空）。 */
    public static String getHeroName(File dir) {
        JSONObject root = dir == null ? null : readJson(new File(dir, "novel.json"));
        return root == null ? "" : root.optString("heroName", "");
    }

    /** 读取主要内容/梗概（可能为空）。 */
    public static String getMainContent(File dir) {
        JSONObject root = dir == null ? null : readJson(new File(dir, "novel.json"));
        return root == null ? "" : root.optString("mainContent", "");
    }

    /** 保存主要内容/梗概，不覆盖已写章节。返回所属目录。 */
    public static File saveMainContent(Context context, String title, File targetDir, String mainContent) throws IOException {
        File dir = targetDir;
        if (dir == null) {
            dir = new File(libRoot(context), sanitize(title) + "_" + System.currentTimeMillis());
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建书库目录");
        }
        File f = new File(dir, "novel.json");
        JSONObject root = readJson(f);
        if (root == null) root = new JSONObject();
        try {
            root.put("title", title);
            if (!root.has("created")) root.put("created", System.currentTimeMillis());
            root.put("mainContent", mainContent == null ? "" : mainContent);
            writeJson(f, root);
        } catch (JSONException e) {
            throw new IOException("主要内容保存失败：" + e.getMessage());
        }
        return dir;
    }

    /** 保存指定主角姓名，不覆盖已写章节。返回所属目录。 */
    public static File saveHeroName(Context context, String title, File targetDir, String heroName) throws IOException {
        File dir = targetDir;
        if (dir == null) {
            dir = new File(libRoot(context), sanitize(title) + "_" + System.currentTimeMillis());
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建书库目录");
        }
        File f = new File(dir, "novel.json");
        JSONObject root = readJson(f);
        if (root == null) root = new JSONObject();
        try {
            root.put("title", title);
            if (!root.has("created")) root.put("created", System.currentTimeMillis());
            if (heroName == null) heroName = "";
            root.put("heroName", heroName);
            writeJson(f, root);
        } catch (JSONException e) {
            throw new IOException("主角姓名保存失败：" + e.getMessage());
        }
        return dir;
    }

    /** 保存硬性设定/人物卡，不覆盖已写章节。返回所属目录。 */
    public static File saveSettings(Context context, String title, File targetDir, String settings) throws IOException {
        File dir = targetDir;
        if (dir == null) {
            dir = new File(libRoot(context), sanitize(title) + "_" + System.currentTimeMillis());
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建书库目录");
        }
        File f = new File(dir, "novel.json");
        JSONObject root = readJson(f);
        if (root == null) root = new JSONObject();
        try {
            root.put("title", title);
            if (!root.has("created")) root.put("created", System.currentTimeMillis());
            if (settings == null) settings = "";
            root.put("settings", settings);
            writeJson(f, root);
        } catch (JSONException e) {
            throw new IOException("设定保存失败：" + e.getMessage());
        }
        return dir;
    }

    /** 一次读取整本书的所有章节（避免重复解析。dir 为小说目录）。 */
    public static List<Chapter> loadChapters(File dir) {
        List<Chapter> out = new ArrayList<>();
        JSONObject root = dir == null ? null : readJson(new File(dir, "novel.json"));
        if (root == null) return out;
        JSONArray arr = root.optJSONArray("chapters");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) {
                out.add(new Chapter(o.optString("title", "第 " + (i + 1) + " 章"), o.optString("content", "")));
            } else {
                out.add(new Chapter("第 " + (i + 1) + " 章", ""));
            }
        }
        return out;
    }

    /** 用新内容替换某章（index 从 0 开始），标题不变；成功后返回 true。 */
    public static boolean updateChapter(File dir, int index, String newContent) {
        if (dir == null) return false;
        File f = new File(dir, "novel.json");
        JSONObject root = readJson(f);
        if (root == null) return false;
        JSONArray arr = root.optJSONArray("chapters");
        if (arr == null || index < 0 || index >= arr.length()) return false;
        JSONObject o = arr.optJSONObject(index);
        if (o == null) return false;
        try {
            o.put("content", newContent == null ? "" : newContent);
            writeJson(f, root);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 用新标题替换某章标题（index 从 0 开始）。 */
    public static boolean renameChapter(File dir, int index, String newTitle) {
        if (dir == null) return false;
        File f = new File(dir, "novel.json");
        JSONObject root = readJson(f);
        if (root == null) return false;
        JSONArray arr = root.optJSONArray("chapters");
        if (arr == null || index < 0 || index >= arr.length()) return false;
        JSONObject o = arr.optJSONObject(index);
        if (o == null) return false;
        try {
            o.put("title", newTitle == null ? "" : newTitle);
            writeJson(f, root);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 删除大纲与设定（保留已写章节）。 */
    public static boolean deleteOutline(File dir) {
        if (dir == null) return false;
        File f = new File(dir, "novel.json");
        JSONObject root = readJson(f);
        if (root == null) return false;
        root.remove("outline");
        root.remove("settings");
        root.remove("heroName");
        try {
            writeJson(f, root);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 从大纲中拆出“硬性设定”段与正文段。返回 [settings, body]。 */
    public static String[] splitOutline(String outline) {
        if (outline == null) outline = "";
        String settings = "";
        String body = outline;
        int m = outline.indexOf("【正文大纲】");
        if (m >= 0) {
            int s = outline.indexOf("【硬性设定】");
            if (s >= 0 && s < m) {
                settings = outline.substring(s, m).trim();
            } else {
                settings = outline.substring(0, m).trim();
            }
        }
        return new String[]{settings, body};
    }

    /** 保存大纲：若 targetDir 为空则新建一本书；保留已写章节，不覆盖它们。返回所属目录。 */
    public static File saveOutline(Context context, String title, File targetDir, String outline) throws IOException {
        char[] dummy = new char[0];
        File dir = targetDir;
        if (dir == null) {
            dir = new File(libRoot(context), sanitize(title) + "_" + System.currentTimeMillis());
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建书库目录");
        }
        File f = new File(dir, "novel.json");
        JSONObject root = readJson(f);
        if (root == null) root = new JSONObject();
        try {
            root.put("title", title);
            if (!root.has("created")) root.put("created", System.currentTimeMillis());
            if (outline == null) outline = "";
            root.put("outline", outline);
            writeJson(f, root);
        } catch (JSONException e) {
            throw new IOException("大纲保存失败：" + e.getMessage());
        }
        return dir;
    }

    /** 删除整本小说及其目录。 */
    public static void deleteNovel(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (f.isDirectory()) deleteNovel(f);
                else f.delete();
            }
        }
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }

    /** 删除某一章（index 从 0 开始）。 */
    public static boolean deleteChapter(File dir, int index) {
        File f = new File(dir, "novel.json");
        JSONObject root = readJson(f);
        if (root == null) return false;
        JSONArray arr = root.optJSONArray("chapters");
        if (arr == null || index < 0 || index >= arr.length()) return false;
        JSONArray next = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            if (i != index) next.put(arr.opt(i));
        }
        try {
            root.put("chapters", next);
            writeJson(f, root);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void writeJson(File f, JSONObject o) throws IOException, JSONException {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(o.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String readText(File f) {
        if (f == null || !f.exists() || !f.isFile()) return null;
        StringBuilder sb = new StringBuilder();
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            return null;
        }
        return sb.toString();
    }

    private static String sanitize(String s) {
        if (s == null) return "无题";
        String t = s.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        return t.length() > 24 ? t.substring(0, 24) : t;
    }
}
