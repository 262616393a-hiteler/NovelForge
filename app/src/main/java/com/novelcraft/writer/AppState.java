package com.novelcraft.writer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.novelcraft.writer.NovelService.Chapter;

public class AppState {

    public static String title = "";
    public static String genre = "";
    public static String style = "";
    public static String heroName = "";
    public static int chapterLength = -1;
    public static int totalChapters = 0;
    public static String content = "";
    public static String dir = "";
    public static final List<Chapter> chapters = new ArrayList<>();
    public static boolean pending = false;

    public static void put(String title, String genre, String style, String heroName,
                           int chapterLength, int totalChapters, String content, File dir,
                           List<Chapter> chapters) {
        AppState.title = title == null ? "" : title;
        AppState.genre = genre == null ? "" : genre;
        AppState.style = style == null ? "" : style;
        AppState.heroName = heroName == null ? "" : heroName;
        AppState.chapterLength = chapterLength;
        AppState.totalChapters = totalChapters;
        AppState.content = content == null ? "" : content;
        AppState.dir = dir == null ? "" : dir.getAbsolutePath();
        AppState.chapters.clear();
        if (chapters != null) AppState.chapters.addAll(chapters);
        AppState.pending = true;
    }

    public static void clear() {
        pending = false;
        title = "";
        genre = "";
        style = "";
        heroName = "";
        chapterLength = -1;
        totalChapters = 0;
        content = "";
        dir = "";
        chapters.clear();
    }
}
