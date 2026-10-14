package dev.novelinlay;

import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.markup.TextAttributes;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

final class NovelRenderer implements EditorCustomElementRenderer {
    private final List<String> lines = new ArrayList<>();
    private final Font font;
    private final Color color;
    private final int width;
    private final int lineHeight;
    private final int ascent;
    private final int left;
    private final ReadingWindow window;

    NovelRenderer(Editor editor, NovelBook book, ReadingWindow.Position start, int lineCount, String status) {
        String text = String.join("", book.pages().subList(start.page(), Math.min(book.pages().size(), start.page() + lineCount)));
        var scheme = editor.getColorsScheme();
        var attributes = scheme.getAttributes(DefaultLanguageHighlighterColors.LINE_COMMENT);
        int style = attributes == null ? Font.PLAIN : attributes.getFontType();
        Font preferred = scheme.getFont(EditorFontType.forJavaStyle(style));
        font = preferred.canDisplayUpTo(text + status) < 0 ? preferred
                : new Font(Font.MONOSPACED, style, preferred.getSize()).deriveFont(preferred.getSize2D());
        color = attributes != null && attributes.getForegroundColor() != null
                ? attributes.getForegroundColor() : scheme.getDefaultForeground();
        FontMetrics metrics = editor.getContentComponent().getFontMetrics(font);
        lineHeight = Math.max(editor.getLineHeight(), metrics.getHeight());
        ascent = metrics.getAscent();
        var visible = editor.getScrollingModel().getVisibleArea();
        left = visible.x + 8;
        int available = Math.max(40, visible.width - 32 - metrics.stringWidth("// "));
        window = ReadingWindow.layout(book.pages(), start, lineCount, available, metrics::stringWidth);
        for (String line : window.lines()) lines.add("// " + line);
        lines.add("// " + status + (window.next().page() == book.pages().size() ? " · 末页" : ""));
        width = Math.max(1, left + lines.stream().mapToInt(metrics::stringWidth).max().orElse(1) + 8);
    }

    ReadingWindow window() { return window; }

    @Override public int calcWidthInPixels(Inlay inlay) { return width; }
    @Override public int calcHeightInPixels(Inlay inlay) { return lines.size() * lineHeight + 4; }

    @Override public void paint(Inlay inlay, Graphics graphics, Rectangle region, TextAttributes ignored) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setFont(font);
            g.setColor(color);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int y = region.y + ascent + 2;
            for (String line : lines) {
                g.drawString(line, region.x + left, y);
                y += lineHeight;
            }
        } finally { g.dispose(); }
    }

    @Override public String getContextMenuGroupId(Inlay inlay) { return "NovelInlay.Menu"; }
}
