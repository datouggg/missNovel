package dev.novelinlay;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

record ReadingWindow(List<String> lines, Position next, Position previous) {
    record Position(int page, int offset) {}

    static ReadingWindow layout(List<String> pages, Position start, int count, int width,
                                ToIntFunction<String> measure) {
        if (count < 1 || count > 10 || width < 1) throw new IllegalArgumentException("Invalid layout limits");
        List<String> lines = new ArrayList<>();
        int page = start.page(), offset = start.offset();
        while (page < pages.size() && lines.size() < count) {
            String text = pages.get(page);
            int end = lineEnd(text, offset, width, measure);
            lines.add(text.substring(offset, end));
            offset = end;
            if (offset == text.length()) { page++; offset = 0; }
        }
        Position next = new Position(page, offset);
        page = start.page();
        offset = start.offset();
        int remaining = count;
        while (remaining > 0 && (page > 0 || offset > 0)) {
            if (offset == 0) { page--; offset = pages.get(page).length(); }
            String prefix = pages.get(page).substring(0, offset);
            List<Integer> starts = new ArrayList<>();
            for (int i = 0; i < prefix.length();) {
                starts.add(i);
                i = lineEnd(prefix, i, width, measure);
            }
            int taken = Math.min(remaining, starts.size());
            offset = starts.get(starts.size() - taken);
            remaining -= taken;
        }
        return new ReadingWindow(List.copyOf(lines), next, new Position(page, offset));
    }

    private static int lineEnd(String text, int start, int width, ToIntFunction<String> measure) {
        int end = start;
        while (end < text.length()) {
            int candidate = end + Character.charCount(text.codePointAt(end));
            if (end > start && measure.applyAsInt(text.substring(start, candidate)) > width) break;
            end = candidate;
        }
        return end;
    }
}
