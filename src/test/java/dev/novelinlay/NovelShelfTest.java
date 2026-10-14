package dev.novelinlay;

import java.nio.file.*;
import java.util.*;

public final class NovelShelfTest {
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("novel-shelf-test");
        try {
            NovelBookTest.check(NovelShelf.list(temp.resolve("missing"), Map.of(), Map.of(), "", () -> {}).isEmpty(), "new shelf empty");
            var first = NovelBook.fromText("第一章 旧书\n这是旧版缓存内容。");
            var second = NovelBook.fromText("第一章 新书\n这是第二本书。");
            first.cache(temp); second.cache(temp); second.cache(temp);
            Files.writeString(temp.resolve("unrelated.txt"), "not a book cache");
            Files.writeString(temp.resolve("import-temp.tmp"), "partial import");
            var titles = Map.of(second.id(), "第二本.txt");
            var shelf = NovelShelf.list(temp, titles, Map.of(first.id(), 2), second.id(), () -> {});
            NovelBookTest.check(shelf.size() == 2 && shelf.getFirst().id().equals(second.id())
                    && shelf.getFirst().title().equals("第二本.txt") && shelf.getFirst().current(), "deduped cache shelf, current first and title retained");
            NovelBookTest.check(shelf.get(1).title().contains("第一章 旧书") && shelf.get(1).page() == 2, "legacy preview and progress recovered");
            NovelBookTest.check(NovelShelf.read(temp, first.id()).id().equals(first.id()), "cached book switch reads original content");
            try { NovelShelf.read(temp, "../outside"); throw new AssertionError("unsafe ID accepted"); }
            catch (IllegalArgumentException expected) { }
            Files.writeString(temp.resolve(first.id() + ".txt"), "changed text");
            NovelBookTest.expectFailure(() -> NovelShelf.read(temp, first.id()));
            try {
                NovelShelf.list(temp, titles, Map.of(), "", () -> { throw new java.util.concurrent.CancellationException(); });
                throw new AssertionError("cancel ignored");
            } catch (java.util.concurrent.CancellationException expected) { }
            Files.delete(temp.resolve(second.id() + ".txt"));
            NovelBookTest.check(NovelShelf.list(temp, titles, Map.of(), "", () -> {}).size() == 1, "missing cache excluded despite saved title");
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("PASS: bookshelf discovery, deduplication, legacy names, progress, missing/corrupt cache, ID validation, cancellation");
    }
}
