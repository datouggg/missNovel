package dev.novelinlay;

import java.io.IOException;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

public final class NovelBookTest {
    public static void main(String[] args) throws Exception {
        ReadingClock clock = new ReadingClock();
        long now = 0;
        check(!clock.tick(now, true, 8), "first frame never advances");
        for (int i = 0; i < 31; i++) check(!clock.tick(now += 250_000_000L, true, 8), "wait full eight seconds");
        check(clock.tick(now += 250_000_000L, true, 8), "advance at eight seconds");
        for (int i = 0; i < 100; i++) check(!clock.tick(now += 250_000_000L, false, 8), "unfocused/paused never advances");
        check(!clock.tick(now += 100_000_000_000L, true, 8), "refocus does not count idle time");
        check(!clock.tick(now += 100_000_000_000L, true, 8), "suspend does not skip pages");
        clock.reset();
        check(!clock.tick(now, true, 8), "manual turn resets clock");
        for (int i = 0; i < 16; i++) check(!clock.tick(now += 250_000_000L, true, 8), "half interval");
        check(!clock.tick(now += 50_000_000_000L, false, 8), "pause freezes interval");
        check(!clock.tick(now += 50_000_000_000L, true, 8), "resume preserves interval");
        for (int i = 0; i < 15; i++) check(!clock.tick(now += 250_000_000L, true, 8), "remaining interval");
        check(clock.tick(now += 250_000_000L, true, 8), "resume advances after remaining reading time");
        for (int seconds : new int[]{1, 2, 30, 3600}) {
            clock.reset();
            check(!clock.tick(now, true, seconds), "custom interval starts fresh");
            for (int i = 1; i < seconds * 4; i++) {
                check(!clock.tick(now += 250_000_000L, true, seconds), "custom interval does not advance early");
            }
            check(clock.tick(now += 250_000_000L, true, seconds), "custom interval deadline " + seconds);
        }
        String story = "他推开那扇门，屋里只剩一盏灯。\n桌上的信封写着他的名字。";
        var layoutPages = List.of("第一章", "天地🌌玄黄宇宙洪荒".repeat(4), "短段", "尾声");
        for (int width : new int[]{1, 7, 20, 100}) {
            for (int count : new int[]{1, 2, 5, 10}) {
                var position = new ReadingWindow.Position(0, 0);
                var previous = position;
                StringBuilder read = new StringBuilder();
                int windows = 0;
                while (position.page() < layoutPages.size()) {
                    var window = ReadingWindow.layout(layoutPages, position, count, width,
                            value -> value.codePointCount(0, value.length()));
                    check(window.lines().size() <= count, "body line limit");
                    check(window.previous().equals(previous), "previous window boundary");
                    window.lines().forEach(read::append);
                    previous = position;
                    position = window.next();
                    check(++windows < 1000, "pagination always advances");
                }
                check(read.toString().equals(String.join("", layoutPages)), "multi-line paging loses no text or surrogate pairs");
            }
        }
        var partial = new ReadingWindow.Position(1, 2);
        var resized = ReadingWindow.layout(layoutPages, partial, 5, 4, String::length);
        check(String.join("", resized.lines()).startsWith(layoutPages.get(1).substring(2, 7)), "resizing resumes at exact offset");
        check(NovelBook.decode(story.getBytes(StandardCharsets.UTF_8)).equals(story), "UTF-8");
        check(NovelBook.decode(("\ufeff" + story).getBytes(StandardCharsets.UTF_8)).equals(story), "UTF-8 BOM");
        check(NovelBook.decode(story.getBytes(StandardCharsets.UTF_16)).equals(story), "UTF-16 BE BOM");
        check(NovelBook.decode(("\ufeff" + story).getBytes(StandardCharsets.UTF_16LE)).equals(story), "UTF-16 LE BOM");
        check(NovelBook.decode(story.getBytes(Charset.forName("GB18030"))).equals(story), "GB18030");
        check(NovelBook.decode(story.getBytes(Charset.forName("GBK"))).equals(story), "GBK");
        expectFailure(() -> NovelBook.fromText(" \n\t　\r\n"));
        expectFailure(() -> NovelBook.fromText("binary\u0000data"));
        expectFailure(() -> NovelBook.decode(new byte[]{(byte) 0xff}));
        String unicode = "星🌌".repeat(1000);
        NovelBook book = NovelBook.fromText(unicode);
        check(String.join("", book.pages()).equals(unicode), "no text lost at page boundaries");
        for (String page : book.pages()) {
            check(page.codePointCount(0, page.length()) <= 48, "bounded pages");
            check(!Character.isHighSurrogate(page.charAt(page.length() - 1)), "no split surrogate");
        }
        check(book.clamp(-1) == 0 && book.clamp(Integer.MAX_VALUE) == book.pages().size() - 1, "progress bounds");
        check(NovelBook.fromText(story.replace("\n", "\r\n")).id().equals(NovelBook.fromText(story).id()), "stable identity");
        String chapterText = "前面的介绍\n　第一章：起点　\n" + "星🌌".repeat(60)
                + "\n第 １２３ 章 重逢\n正文\n正文 第两百零一回 归来\nCHAPTER 4: Home\n尾声\n结束";
        var chapterBook = NovelBook.fromText(chapterText);
        check(chapterBook.chapters().stream().map(NovelBook.Chapter::title).toList().equals(
                List.of("第一章：起点", "第 １２３ 章 重逢", "正文 第两百零一回 归来", "CHAPTER 4: Home", "尾声")),
                "Chinese/Arabic/fullwidth/English headings and epilogue");
        check(chapterBook.chapters().get(0).page() == 1 && chapterBook.chapters().get(1).page() == 5,
                "chapter offsets count wrapped Unicode paragraphs and preface");
        for (var chapter : chapterBook.chapters()) {
            check(chapterBook.pages().get(chapter.page()).equals(chapter.title()), "jump lands on heading");
        }
        check(NovelBook.fromText("他说第一章很精彩。\nChapterhouse\n第一章" + "文".repeat(81)).chapters().isEmpty(),
                "ordinary paragraphs and long lines are not chapter headings");
        check(NovelBook.fromText(story).chapters().isEmpty(), "unstructured TXT still works");
        check(NovelBook.fromText("第一章 起点\n正文\n第一章 起点\n正文").chapters().size() == 2,
                "duplicate chapter names retain separate locations");
        Path dir = Files.createTempDirectory("novel-inlay-test-");
        try {
            Path original = dir.resolve("source.txt");
            Files.writeString(original, story);
            byte[] before = Files.readAllBytes(original);
            NovelBook imported = NovelBook.read(original);
            imported.cache(dir.resolve("books"));
            imported.cache(dir.resolve("books"));
            check(Arrays.equals(before, Files.readAllBytes(original)), "import leaves source intact");
            check(NovelBook.read(dir.resolve("books/" + imported.id() + ".txt")).equals(imported), "cache restart roundtrip");
            Path large = dir.resolve("large.txt");
            try (var file = new java.io.RandomAccessFile(large.toFile(), "rw")) { file.setLength(NovelBook.MAX_BYTES + 1L); }
            expectFailure(() -> NovelBook.read(large));
            expectFailure(() -> NovelBook.read(dir.resolve("missing.txt")));
        } finally {
            try (var files = Files.walk(dir)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
        System.out.println("PASS: timer eligibility/pause/resume/suspend, encoding, segmentation, Unicode, progress bounds, cache roundtrip, source integrity, invalid/oversize files");
    }
    static void check(boolean condition, String name) { if (!condition) throw new AssertionError(name); }
    static void expectFailure(Checked action) throws Exception {
        try { action.run(); throw new AssertionError("Expected IOException"); } catch (IOException expected) { }
    }
    interface Checked { void run() throws Exception; }
}
