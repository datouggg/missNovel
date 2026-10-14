package dev.novelinlay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

final class NovelShelf {
    record Entry(String id, String title, int page, boolean current) {
        @Override public String toString() {
            return (current ? "正在阅读 · " : "") + "《" + title + "》 · 第 " + (page + 1) + " 段";
        }
    }

    static boolean validId(String id) { return id != null && id.matches("[0-9a-f]{64}"); }

    static NovelBook read(Path directory, String id) throws IOException {
        if (!validId(id)) throw new IllegalArgumentException("书籍编号无效。");
        NovelBook book = NovelBook.read(directory.resolve(id + ".txt"));
        if (!book.id().equals(id)) throw new IOException("小说缓存已变化，请重新导入 TXT。");
        return book;
    }

    static List<Entry> list(Path directory, Map<String, String> titles, Map<String, Integer> positions,
                            String currentId, Runnable checkCanceled) throws IOException {
        if (!Files.exists(directory)) return List.of();
        var entries = new ArrayList<Entry>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.toList()) {
                checkCanceled.run();
                String name = path.getFileName().toString();
                if (!name.matches("[0-9a-f]{64}\\.txt") || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                String id = name.substring(0, 64);
                String title = titles.get(id);
                if (title == null || title.isBlank()) {
                    // Older versions saved only the active title; use a bounded text preview for other books.
                    String preview = "";
                    try (var input = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                        char[] text = new char[512];
                        int size = input.read(text);
                        if (size > 0) preview = new String(text, 0, size).strip().lines().findFirst().orElse("");
                    } catch (IOException ignored) { }
                    if (preview.codePointCount(0, preview.length()) > 40) preview = preview.substring(0, preview.offsetByCodePoints(0, 40)) + "…";
                    title = "旧缓存 " + id.substring(0, 6) + (preview.isBlank() ? "" : " · " + preview);
                }
                entries.add(new Entry(id, title, Math.max(0, positions.getOrDefault(id, 0)), id.equals(currentId)));
            }
        }
        entries.sort(Comparator.comparing(Entry::current).reversed().thenComparing(Entry::title).thenComparing(Entry::id));
        return List.copyOf(entries);
    }
}
