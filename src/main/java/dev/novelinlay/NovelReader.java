package dev.novelinlay;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.*;
import com.intellij.openapi.components.*;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import javax.swing.Timer;
import javax.swing.SwingUtilities;

@State(name = "NovelInlay", storages = @Storage(value = "novel-inlay.xml", roamingType = RoamingType.DISABLED))
public final class NovelReader implements PersistentStateComponent<NovelReader.ReaderState>, Disposable, EditorMouseListener {
    public static final class ReaderState {
        public String bookId = "";
        public String title = "";
        public boolean playing = true;
        public int intervalSeconds = 8;
        public boolean enabled = true;
        public int displayLines = 1;
        public Map<String, Integer> positions = new HashMap<>();
        public Map<String, Integer> offsets = new HashMap<>();
        public Map<String, String> titles = new HashMap<>();
    }

    private ReaderState state = new ReaderState();
    private NovelBook book;
    private Inlay<NovelRenderer> inlay;
    private final Timer timer = new Timer(250, e -> tick());
    private boolean started;
    private boolean loading;
    private volatile boolean disposed;
    private final ReadingClock clock = new ReadingClock();
    private String renderKey = "";

    public static NovelReader getInstance() {
        return ApplicationManager.getApplication().getService(NovelReader.class);
    }

    @Override public synchronized ReaderState getState() {
        ReaderState copy = new ReaderState();
        copy.bookId = state.bookId;
        copy.title = state.title;
        copy.playing = state.playing;
        copy.intervalSeconds = state.intervalSeconds;
        copy.enabled = state.enabled;
        copy.displayLines = state.displayLines;
        copy.positions = new HashMap<>(state.positions);
        copy.offsets = new HashMap<>(state.offsets);
        copy.titles = new HashMap<>(state.titles);
        return copy;
    }

    @Override public synchronized void loadState(ReaderState value) {
        if (!validInterval(value.intervalSeconds)) value.intervalSeconds = 8;
        if (!validDisplayLines(value.displayLines)) value.displayLines = 1;
        if (NovelShelf.validId(value.bookId) && !value.title.isBlank()) value.titles.putIfAbsent(value.bookId, value.title);
        state = value;
        resetClock();
        renderKey = "";
    }

    static Path cacheDirectory() { return PathManager.getConfigDir().resolve("novel-inlay/books"); }

    public void start(Project project) {
        if (started || disposed) return;
        startTimer();
        if (state.bookId.matches("[0-9a-f]{64}")) {
            load(cacheDirectory().resolve(state.bookId + ".txt"), state.title, project, state.bookId, null, false);
        }
    }

    public void importFile(Path path, Project project) {
        if (disposed || loading) return;
        if (!started) startTimer();
        load(path, path.getFileName().toString(), project, null, null, true);
    }

    public void importUrl(String url, Project project) {
        QishuxiaSource.downloadUri(url);
        if (disposed || loading) return;
        if (!started) startTimer();
        load(null, "", project, null, url, true);
    }

    public void openBook(NovelShelf.Entry entry, Project project) {
        if (!NovelShelf.validId(entry.id())) throw new IllegalArgumentException("书籍编号无效。");
        if (disposed || loading) return;
        if (!started) startTimer();
        load(cacheDirectory().resolve(entry.id() + ".txt"), entry.title(), project, entry.id(), null, true);
    }

    private void startTimer() {
        started = true;
        if (state.enabled) timer.start();
        EditorFactory.getInstance().getEventMulticaster().addEditorMouseListener(this, this);
    }

    private void load(Path path, String title, Project project, String cachedId, String remoteUrl, boolean reveal) {
        boolean importing = cachedId == null;
        loading = true;
        resetClock();
        new Task.Backgroundable(project, remoteUrl != null ? "下载 TXT 小说" : importing ? "导入小说" : "恢复小说进度", true) {
            NovelBook loaded;
            String loadedTitle = title;
            @Override public void run(ProgressIndicator indicator) {
                try {
                    if (cachedId != null) loaded = NovelShelf.read(cacheDirectory(), cachedId);
                    else if (remoteUrl == null) loaded = NovelBook.read(path);
                    else {
                        var download = QishuxiaSource.download(remoteUrl, indicator::checkCanceled);
                        loaded = download.book();
                        loadedTitle = download.title();
                    }
                    indicator.checkCanceled();
                    if (importing) loaded.cache(cacheDirectory());
                } catch (IOException e) { throw new IllegalArgumentException(e.getMessage(), e); }
            }
            @Override public void onSuccess() {
                if (disposed) return;
                synchronized (NovelReader.this) {
                    book = loaded;
                    state.bookId = loaded.id();
                    state.title = loadedTitle;
                    state.titles.put(loaded.id(), loadedTitle);
                    state.positions.put(loaded.id(), loaded.clamp(state.positions.getOrDefault(loaded.id(), 0)));
                }
                loading = false;
                if (reveal) setEnabled(true);
                renderKey = "";
                resetClock();
            }
            @Override public void onThrowable(Throwable error) {
                loading = false;
                if (!disposed) Messages.showErrorDialog(project,
                        "无法读取小说，原有进度已保留。\n" + error.getMessage(), "Novel Inlay");
            }
            @Override public void onCancel() { loading = false; resetClock(); }
        }.queue();
    }

    public boolean isLoading() { return loading; }
    public boolean hasBook() { return book != null && !loading; }
    NovelBook currentBook() { return hasBook() ? book : null; }
    public boolean isPlaying() { return state.playing; }
    public int getIntervalSeconds() { return state.intervalSeconds; }
    public boolean isEnabled() { return state.enabled; }
    public int getDisplayLines() { return state.displayLines; }

    public synchronized void setEnabled(boolean enabled) {
        state.enabled = enabled;
        resetClock();
        if (!enabled) { timer.stop(); clearInlay(); }
        else if (started && !disposed) timer.start();
    }

    static boolean validDisplayLines(int lines) { return lines >= 1 && lines <= 10; }

    public synchronized void setDisplayLines(int lines) {
        if (!validDisplayLines(lines)) throw new IllegalArgumentException("显示行数必须为 1–10 的整数。");
        state.displayLines = lines;
        resetClock();
        if (inlay != null && inlay.isValid()) render(inlay.getEditor());
    }

    static boolean validInterval(int seconds) { return seconds >= 1 && seconds <= 3600; }

    public synchronized void setIntervalSeconds(int seconds) {
        if (!validInterval(seconds)) throw new IllegalArgumentException("翻段间隔必须为 1–3600 秒。");
        state.intervalSeconds = seconds;
        resetClock();
        renderKey = "";
    }

    @Override public void mousePressed(EditorMouseEvent event) {
        var mouse = event.getMouseEvent();
        if (disposed || !state.enabled || !hasBook() || inlay == null || event.getInlay() != inlay
                || event.isConsumed() || !SwingUtilities.isLeftMouseButton(mouse)
                || mouse.isPopupTrigger() || mouse.isControlDown() || mouse.getClickCount() != 1) return;
        // Consume before the editor moves the caret underneath this block inlay.
        event.consume();
        turn(1);
        render(event.getEditor());
    }

    public synchronized void toggle() {
        state.playing = !state.playing;
        resetClock();
        renderKey = "";
    }

    public synchronized void turn(int delta) {
        if (!hasBook()) return;
        if (Math.abs(delta) == 1 && inlay != null && inlay.isValid()) {
            render(inlay.getEditor());
            ReadingWindow window = inlay.getRenderer().window();
            var target = delta > 0 ? window.next() : window.previous();
            if (target.page() == book.pages().size()) {
                state.playing = false;
                resetClock();
                renderKey = "";
            } else goToPosition(target);
            return;
        }
        goToPage(book.clamp(page() + delta));
        if (page() == book.pages().size() - 1) state.playing = false;
    }

    public synchronized void selectChapter(String bookId, int chapterIndex) {
        if (!hasBook() || !book.id().equals(bookId)
                || chapterIndex < 0 || chapterIndex >= book.chapters().size()) return;
        goToPage(book.chapters().get(chapterIndex).page());
    }

    private void goToPage(int next) {
        goToPosition(new ReadingWindow.Position(next, 0));
    }

    private void goToPosition(ReadingWindow.Position next) {
        state.positions.put(book.id(), next.page());
        state.offsets.put(book.id(), next.offset());
        resetClock();
        renderKey = "";
    }

    int page() { return book == null ? 0 : book.clamp(state.positions.getOrDefault(book.id(), 0)); }

    ReadingWindow.Position position() {
        if (book == null) return new ReadingWindow.Position(0, 0);
        String text = book.pages().get(page());
        int offset = Math.max(0, Math.min(state.offsets.getOrDefault(book.id(), 0), text.length() - 1));
        if (offset > 0 && Character.isLowSurrogate(text.charAt(offset))) offset--;
        return new ReadingWindow.Position(page(), offset);
    }

    private void resetClock() { clock.reset(); }

    private void tick() {
        if (disposed) return;
        if (!state.enabled) { clearInlay(); resetClock(); return; }
        long now = System.nanoTime();
        if (inlay != null && (!inlay.isValid() || inlay.getEditor().isDisposed()
                || !inlay.getEditor().getContentComponent().isShowing())) clearInlay();
        if (!hasBook() || !ApplicationManager.getApplication().isActive()) { clock.tick(now, false, state.intervalSeconds); return; }
        Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        Editor editor = null;
        for (Editor candidate : EditorFactory.getInstance().getAllEditors()) {
            if (!candidate.isDisposed() && candidate.getEditorKind() == EditorKind.MAIN_EDITOR
                    && candidate.getContentComponent() == focus) {
                editor = candidate;
                break;
            }
        }
        if (editor == null) { clock.tick(now, false, state.intervalSeconds); return; }
        if (inlay != null && inlay.getEditor() != editor) { clearInlay(); resetClock(); }
        render(editor);
        boolean eligible = state.playing && inlay != null && inlay.isValid() && inlay.getBounds() != null
                && editor.getScrollingModel().getVisibleArea().contains(inlay.getBounds());
        if (clock.tick(now, eligible, state.intervalSeconds)) { turn(1); render(editor); }
    }

    private void render(Editor editor) {
        if (!state.enabled || !hasBook()) { clearInlay(); return; }
        int offset = editor.getCaretModel().getOffset();
        var visible = editor.getScrollingModel().getVisibleArea();
        var attributes = editor.getColorsScheme().getAttributes(DefaultLanguageHighlighterColors.LINE_COMMENT);
        String key = offset + ":" + page() + ":" + state.playing + ":" + visible.width + ":" + visible.x
                + ":" + position().offset() + ":" + state.displayLines
                + ":" + editor.getColorsScheme().getEditorFontSize2D() + ":" + attributes;
        if (inlay != null && inlay.isValid() && inlay.getOffset() == offset && key.equals(renderKey)) return;
        clearInlay();
        String status = (page() + 1) + "/" + book.pages().size() + " · "
                + (state.playing ? "自动 · " + state.intervalSeconds + "秒" : "手动 · 点击下一页");
        inlay = editor.getInlayModel().addBlockElement(offset, false, true, 0,
                new NovelRenderer(editor, book, position(), state.displayLines, status));
        renderKey = key;
    }

    private void clearInlay() {
        if (inlay != null) { inlay.dispose(); inlay = null; }
        renderKey = "";
    }

    @Override public void dispose() {
        disposed = true;
        timer.stop();
        if (ApplicationManager.getApplication().isDispatchThread()) clearInlay();
        else ApplicationManager.getApplication().invokeLater(this::clearInlay, ModalityState.any());
    }
}
