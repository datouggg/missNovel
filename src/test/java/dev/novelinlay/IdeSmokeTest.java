package dev.novelinlay;

import com.intellij.openapi.application.*;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.util.xmlb.XmlSerializer;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Runs only in the isolated test plugin assembled by smoke-test.py. */
public final class IdeSmokeTest implements ApplicationStarter {
    @Override public String getCommandName() { return "novel-inlay-smoke"; }
    @Override public boolean isHeadless() { return !Boolean.getBoolean("novel.test.gui"); }
    @Override public int getRequiredModality() { return NOT_IN_EDT; }

    @Override public void main(List<String> args) {
        try {
            boolean restore = args.contains("restore");
            Application app = ApplicationManager.getApplication();
            NovelReader reader = NovelReader.getInstance();
            app.invokeAndWait(() -> {
                if (restore) reader.start(null);
                else reader.importFile(Path.of(System.getProperty("novel.test.source")), null);
            });
            long deadline = System.nanoTime() + 30_000_000_000L;
            boolean[] loaded = {false};
            while (!loaded[0] && System.nanoTime() < deadline) {
                Thread.sleep(100);
                app.invokeAndWait(() -> loaded[0] = reader.hasBook());
            }
            require(loaded[0], "TXT background import / startup restore");
            if (Boolean.getBoolean("novel.test.gui")) {
                guiTest(app, reader);
                System.out.println("IDE_SMOKE_PASS gui");
                System.exit(0);
            }
            if (!restore) shelfSwitchTest(app, reader);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            app.invokeAndWait(() -> {
                Editor editor = null;
                try {
                    require(ActionManager.getInstance().getAction("NovelInlay.Import") != null, "registered menu actions");
                    require(ActionManager.getInstance().getAction("NovelInlay.Search") != null, "search and download action registered");
                    require(ActionManager.getInstance().getAction("NovelInlay.Bookshelf") != null, "bookshelf menu registered");
                    var shelfState = reader.getState();
                    require(shelfState.titles.containsValue("shelf-second.txt") && shelfState.titles.size() == 2,
                            "book titles survive multi-book switching and restart");
                    require(NovelShelf.list(NovelReader.cacheDirectory(), shelfState.titles, shelfState.positions,
                            shelfState.bookId, () -> {}).size() == 2, "two cached books discoverable in IDE");
                    var searchDialog = new ReaderActions.SearchDialog(null);
                    try {
                        require(searchDialog.doValidate() != null, "search dialog rejects both fields empty");
                        searchDialog.author.setText("耳根");
                        require(searchDialog.doValidate() == null, "search dialog accepts author alone");
                        searchDialog.title.setText("我欲封天");
                        require(searchDialog.doValidate() == null, "search dialog accepts title plus author");
                        searchDialog.author.setText("🌌");
                        require(searchDialog.doValidate() != null, "search dialog rejects unsupported author encoding");
                    } finally { searchDialog.close(com.intellij.openapi.ui.DialogWrapper.CANCEL_EXIT_CODE); }
                    require(ActionManager.getInstance().getAction("NovelInlay.Interval") != null, "interval menu registered");
                    require(ActionManager.getInstance().getAction("NovelInlay.Chapters") != null, "chapter menu registered");
                    var novel = reader.currentBook();
                    require(novel.chapters().size() == 2, "import indexes sample chapters");
                    if (restore) require(reader.position().toString().equals(Files.readString(PathManager.getConfigDir().resolve("expected-position.txt"))),
                            "actual IDE restart restores exact reading position");
                    if (restore) require(!reader.isEnabled() && reader.getDisplayLines() == 5, "restart keeps display closed and custom line count");
                    reader.setEnabled(true);
                    reader.setDisplayLines(1);
                    if (restore) require(reader.getIntervalSeconds() == 2 && !reader.isPlaying(), "restart restores custom interval and manual mode");
                    var legacy = XmlSerializer.deserialize(new org.jdom.Element("ReaderState"), NovelReader.ReaderState.class);
                    require(legacy.intervalSeconds == 8 && legacy.enabled && legacy.displayLines == 1 && legacy.offsets.isEmpty(), "old settings remain compatible");
                    require(ActionManager.getInstance().getAction("NovelInlay.Visibility") != null
                            && ActionManager.getInstance().getAction("NovelInlay.DisplayLines") != null, "display actions registered");
                    try { reader.setDisplayLines(0); throw new AssertionError("accepted zero lines"); }
                    catch (IllegalArgumentException expected) { }
                    try { reader.setDisplayLines(11); throw new AssertionError("accepted too many lines"); }
                    catch (IllegalArgumentException expected) { }
                    reader.setIntervalSeconds(1);
                    reader.setIntervalSeconds(3600);
                    reader.setIntervalSeconds(2);
                    try { reader.setIntervalSeconds(0); throw new AssertionError("accepted zero interval"); }
                    catch (IllegalArgumentException expected) { }
                    try { reader.setIntervalSeconds(3601); throw new AssertionError("accepted excessive interval"); }
                    catch (IllegalArgumentException expected) { }
                    require(reader.getIntervalSeconds() == 2, "invalid interval preserves previous value");
                    Document document = EditorFactory.getInstance().createDocument("fun main() {\n    println(\"hello\")\n}\n");
                    String original = document.getText();
                    long stamp = document.getModificationStamp();
                    editor = EditorFactory.getInstance().createEditor(document, null, EditorKind.MAIN_EDITOR);
                    editor.getComponent().setSize(900, 600);
                    editor.getComponent().doLayout();
                    var render = NovelReader.class.getDeclaredMethod("render", Editor.class);
                    render.setAccessible(true);
                    editor.getCaretModel().moveToOffset(15);
                    render.invoke(reader, editor);
                    var inlays = editor.getInlayModel().getBlockElementsInRange(0, document.getTextLength());
                    require(inlays.size() == 1 && inlays.getFirst().getOffset() == 15, "real block inlay at caret");
                    require(inlays.getFirst().getHeightInPixels() > 0, "renderer size");
                    editor.getCaretModel().moveToOffset(0);
                    render.invoke(reader, editor);
                    inlays = editor.getInlayModel().getBlockElementsInRange(0, document.getTextLength());
                    require(inlays.size() == 1 && inlays.getFirst().getOffset() == 0, "caret follow replaces old inlay");
                    reader.turn(1000000);
                    require(!reader.isPlaying(), "stop at end");
                    reader.turn(-1000000);
                    require(reader.page() == 0, "previous boundary");
                    render.invoke(reader, editor);
                    var block = editor.getInlayModel().getBlockElementsInRange(0, document.getTextLength()).getFirst();
                    var outside = press(editor, null, java.awt.event.MouseEvent.BUTTON1, 1);
                    reader.mousePressed(outside);
                    require(reader.page() == 0 && !outside.isConsumed(), "code clicks pass through");
                    var right = press(editor, block, java.awt.event.MouseEvent.BUTTON3, 1);
                    reader.mousePressed(right);
                    require(reader.page() == 0 && !right.isConsumed(), "right click keeps context menu");
                    var firstNext = ((NovelRenderer) block.getRenderer()).window().next();
                    var left = press(editor, block, java.awt.event.MouseEvent.BUTTON1, 1);
                    reader.mousePressed(left);
                    require(reader.position().equals(firstNext) && left.isConsumed() && !reader.isPlaying(), "novel click advances once in manual mode");
                    block = editor.getInlayModel().getBlockElementsInRange(0, document.getTextLength()).getFirst();
                    reader.mousePressed(press(editor, block, java.awt.event.MouseEvent.BUTTON1, 2));
                    require(reader.position().equals(firstNext), "double click second press does not skip another page");
                    var secondNext = ((NovelRenderer) block.getRenderer()).window().next();
                    reader.mousePressed(press(editor, block, java.awt.event.MouseEvent.BUTTON1, 1));
                    require(reader.position().equals(secondNext) && editor.getCaretModel().getOffset() == 0, "next click advances without moving caret");
                    reader.selectChapter("another-book", 1);
                    reader.selectChapter(novel.id(), -1);
                    reader.selectChapter(novel.id(), 2);
                    require(reader.position().equals(secondNext), "stale book and invalid chapter selections leave progress intact");
                    reader.selectChapter(novel.id(), 1);
                    require(reader.page() == novel.chapters().get(1).page() && !reader.isPlaying(),
                            "chapter selection lands on heading and preserves manual mode");
                    reader.turn(1);
                    var beforeResize = reader.position();
                    reader.setDisplayLines(5);
                    require(reader.position().equals(beforeResize), "changing lines preserves exact position");
                    render.invoke(reader, editor);
                    block = editor.getInlayModel().getBlockElementsInRange(0, document.getTextLength()).getFirst();
                    var fiveLines = (NovelRenderer) block.getRenderer();
                    require(fiveLines.window().lines().size() == 5, "renderer displays five body lines");
                    int fiveHeight = block.getHeightInPixels();
                    reader.setDisplayLines(1);
                    block = editor.getInlayModel().getBlockElementsInRange(0, document.getTextLength()).getFirst();
                    require(((NovelRenderer) block.getRenderer()).window().lines().size() == 1
                            && block.getHeightInPixels() < fiveHeight, "one-line setting immediately shrinks inlay");
                    reader.setDisplayLines(5);
                    reader.setEnabled(false);
                    require(editor.getInlayModel().getBlockElementsInRange(0, document.getTextLength()).isEmpty(), "close immediately removes text");
                    render.invoke(reader, editor);
                    var tick = NovelReader.class.getDeclaredMethod("tick");
                    tick.setAccessible(true);
                    tick.invoke(reader);
                    require(editor.getInlayModel().getBlockElementsInRange(0, document.getTextLength()).isEmpty()
                            && reader.position().equals(beforeResize), "closed display stays hidden without advancing");
                    reader.setEnabled(true);
                    render.invoke(reader, editor);
                    require(editor.getInlayModel().getBlockElementsInRange(0, document.getTextLength()).size() == 1
                            && reader.position().equals(beforeResize), "reopen restores same reading position");
                    reader.setEnabled(false);
                    Files.writeString(PathManager.getConfigDir().resolve("expected-position.txt"), reader.position().toString());
                    var state = reader.getState();
                    var restored = XmlSerializer.deserialize(XmlSerializer.serialize(state), NovelReader.ReaderState.class);
                    require(restored.positions.equals(state.positions) && restored.bookId.equals(state.bookId) && restored.titles.equals(state.titles), "progress XML roundtrip");
                    require(restored.intervalSeconds == 2 && !restored.playing && !restored.enabled && restored.displayLines == 5
                            && restored.offsets.equals(state.offsets), "display, offsets, mode and interval XML roundtrip");
                    render.invoke(reader, editor);
                    require(document.getText().equals(original) && document.getModificationStamp() == stamp, "no source text or modification-stamp changes");
                    reader.dispose();
                    require(editor.getInlayModel().getBlockElementsInRange(0, document.getTextLength()).isEmpty(), "dispose removes inlay");
                } catch (Throwable e) { failure.set(e); }
                finally { if (editor != null) EditorFactory.getInstance().releaseEditor(editor); }
            });
            if (failure.get() != null) throw new AssertionError(failure.get());
            com.intellij.configurationStore.StoreUtil.saveSettings(app, true);
            Path settings = PathManager.getConfigDir().resolve("options/novel-inlay.xml");
            long saveDeadline = System.nanoTime() + 15_000_000_000L;
            while (!Files.exists(settings) && System.nanoTime() < saveDeadline) Thread.sleep(100);
            require(Files.exists(settings), "IDE wrote persistent state to disk");
            System.out.println("IDE_SMOKE_PASS " + (restore ? "restart" : "import"));
            System.exit(0);
        } catch (Throwable error) {
            error.printStackTrace();
            System.exit(1);
        }
    }

    private static void shelfSwitchTest(Application app, NovelReader reader) throws Exception {
        var first = reader.getState();
        String firstId = first.bookId;
        first.positions.put(first.bookId, 1);
        first.offsets.put(first.bookId, 3);
        app.invokeAndWait(() -> reader.loadState(first));
        Path secondPath = PathManager.getConfigDir().resolve("shelf-second.txt");
        Files.writeString(secondPath, "第一章 第二本测试小说\n" + "这是第二本原创测试小说正文。".repeat(40));
        app.invokeAndWait(() -> reader.importFile(secondPath, null));
        awaitBook(app, reader);
        Files.delete(secondPath);
        var second = reader.getState();
        String secondId = second.bookId;
        second.positions.put(second.bookId, 1);
        second.offsets.put(second.bookId, 2);
        app.invokeAndWait(() -> reader.loadState(second));
        var entries = NovelShelf.list(NovelReader.cacheDirectory(), second.titles, second.positions, second.bookId, () -> {});
        var firstEntry = entries.stream().filter(e -> e.id().equals(firstId)).findFirst().orElseThrow();
        var secondEntry = entries.stream().filter(e -> e.id().equals(secondId)).findFirst().orElseThrow();
        app.invokeAndWait(() -> reader.openBook(firstEntry, null));
        awaitBook(app, reader);
        app.invokeAndWait(() -> require(reader.position().equals(new ReadingWindow.Position(1, 3)), "switch A-B-A restores exact A offset"));
        app.invokeAndWait(() -> { reader.setEnabled(false); reader.openBook(secondEntry, null); });
        awaitBook(app, reader);
        app.invokeAndWait(() -> require(reader.isEnabled() && reader.position().equals(new ReadingWindow.Position(1, 2)),
                "switch B restores its own offset, opens display and needs no source TXT"));
        app.invokeAndWait(() -> reader.openBook(firstEntry, null));
        awaitBook(app, reader);
    }

    private static void awaitBook(Application app, NovelReader reader) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        boolean[] ready = {false};
        while (!ready[0] && System.nanoTime() < deadline) {
            Thread.sleep(100);
            app.invokeAndWait(() -> ready[0] = reader.hasBook());
        }
        require(ready[0], "background shelf switch completes");
    }

    private static void guiTest(Application app, NovelReader reader) throws Exception {
        javax.swing.JFrame[] frame = new javax.swing.JFrame[1];
        Editor[] editor = new Editor[1];
        javax.swing.JTextField[] focusButton = new javax.swing.JTextField[1];
        String source = "package demo\n\nclass Recorder {\n    fun startRecording() {\n        prepareAudio()\n        recorder.start()\n    }\n}\n";
        app.invokeAndWait(() -> {
            reader.turn(-1000000);
            reader.setIntervalSeconds(8);
            reader.setEnabled(true);
            reader.setDisplayLines(1);
            if (!reader.isPlaying()) reader.toggle();
            editor[0] = EditorFactory.getInstance().createEditor(
                    EditorFactory.getInstance().createDocument(source), null, EditorKind.MAIN_EDITOR);
            frame[0] = new javax.swing.JFrame("Novel Inlay — isolated Android Studio editor test");
            frame[0].add(editor[0].getComponent());
            focusButton[0] = new javax.swing.JTextField("Focus outside editor (test)");
            frame[0].add(focusButton[0], java.awt.BorderLayout.SOUTH);
            frame[0].setSize(920, 540);
            frame[0].setLocationRelativeTo(null);
            frame[0].setVisible(true);
            java.awt.Desktop.getDesktop().requestForeground(true);
            frame[0].toFront();
            editor[0].getCaretModel().moveToOffset(source.indexOf("prepareAudio"));
            editor[0].getContentComponent().requestFocusInWindow();
        });
        Thread.sleep(2000);
        app.invokeAndWait(() -> {
            require(app.isActive(), "GUI application active");
            require(editor[0].getContentComponent().hasFocus(), "editor has focus");
            require(!editor[0].getInlayModel().getBlockElementsInRange(0, source.length()).isEmpty(), "timer creates visible inlay");
            editor[0].getCaretModel().moveToOffset(source.indexOf("recorder.start"));
        });
        Thread.sleep(1000);
        app.invokeAndWait(() -> {
            var inlays = editor[0].getInlayModel().getBlockElementsInRange(0, source.length());
            require(inlays.size() == 1 && inlays.getFirst().getOffset() == source.indexOf("recorder.start"), "timer follows moved caret");
            require(editor[0].getScrollingModel().getVisibleArea().contains(inlays.getFirst().getBounds()), "whole inlay visible");
            var component = editor[0].getComponent();
            var image = new java.awt.image.BufferedImage(component.getWidth(), component.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            component.paint(graphics);
            graphics.dispose();
            try { javax.imageio.ImageIO.write(image, "png", Path.of(System.getProperty("novel.test.preview")).toFile()); }
            catch (java.io.IOException e) { throw new RuntimeException(e); }
        });
        long deadline = System.nanoTime() + 30_000_000_000L;
        boolean[] advanced = {false};
        while (!advanced[0] && System.nanoTime() < deadline) {
            Thread.sleep(200);
            app.invokeAndWait(() -> advanced[0] = reader.page() == 1);
        }
        app.invokeAndWait(() -> {
            require(reader.page() == 1, "real timer advances after 8 focused seconds (active="
                    + app.isActive() + ", focus=" + editor[0].getContentComponent().hasFocus() + ", page=" + reader.page() + ")");
            reader.toggle();
        });
        Thread.sleep(8500);
        app.invokeAndWait(() -> {
            require(reader.page() == 1, "pause stops automatic advance");
            reader.toggle();
            focusButton[0].requestFocusInWindow();
        });
        Thread.sleep(500);
        app.invokeAndWait(() -> require(focusButton[0].hasFocus(), "test focus transfer succeeded"));
        Thread.sleep(8500);
        app.invokeAndWait(() -> {
            require(focusButton[0].hasFocus(), "test focus moved outside editor");
            require(reader.page() == 1, "no progress while editor unfocused (page=" + reader.page() + ")");
            require(editor[0].getDocument().getText().equals(source), "GUI session leaves code intact");
            reader.dispose();
            EditorFactory.getInstance().releaseEditor(editor[0]);
            frame[0].dispose();
        });
    }
    private static com.intellij.openapi.editor.event.EditorMouseEvent press(Editor editor, Inlay<?> inlay, int button, int count) {
        var mouse = new java.awt.event.MouseEvent(editor.getContentComponent(), java.awt.event.MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), 0, 10, 10, count, button == 3, button);
        return new com.intellij.openapi.editor.event.EditorMouseEvent(editor, mouse,
                com.intellij.openapi.editor.event.EditorMouseEventArea.EDITING_AREA,
                editor.getCaretModel().getOffset(), editor.getCaretModel().getLogicalPosition(),
                editor.getCaretModel().getVisualPosition(), false, null, inlay, null);
    }
    private static void require(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        System.out.println("PASS: " + label);
    }
}
