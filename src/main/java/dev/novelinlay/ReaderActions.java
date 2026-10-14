package dev.novelinlay;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.fileChooser.*;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.progress.*;
import java.util.List;
import java.io.IOException;
import com.intellij.openapi.ui.InputValidator;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBTextField;
import javax.swing.*;
import java.awt.GridLayout;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import java.nio.file.Path;

public final class ReaderActions {
    public static final class Search extends DumbAwareAction {
        private boolean searching;
        @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        @Override public void update(AnActionEvent e) {
            e.getPresentation().setEnabled(!searching && !NovelReader.getInstance().isLoading());
        }
        @Override public void actionPerformed(AnActionEvent e) {
            if (searching || NovelReader.getInstance().isLoading()) return;
            var project = e.getProject();
            var dialog = new SearchDialog(project);
            if (!dialog.showAndGet()) return;
            String title = dialog.title.getText().strip(), author = dialog.author.getText().strip();
            searching = true;
            new Task.Backgroundable(project, "搜索小说：" + (title.isEmpty() ? author : title), true) {
                List<QishuxiaSource.SearchResult> results;
                @Override public void run(ProgressIndicator indicator) {
                    try { results = QishuxiaSource.search(title, author, indicator::checkCanceled); }
                    catch (IOException error) { throw new IllegalArgumentException(error.getMessage(), error); }
                }
                @Override public void onFinished() { searching = false; }
                @Override public void onSuccess() {
                    if (project != null && project.isDisposed()) return;
                    if (results.isEmpty()) {
                        Messages.showInfoMessage(project, "没有找到同时符合条件的小说。请检查作者完整笔名，或清空书名仅按作者搜索。", "搜索小说");
                        return;
                    }
                    JBPopupFactory.getInstance().createPopupChooserBuilder(results)
                            .setTitle("找到 " + results.size() + " 本 · 选择后下载（可输入文字筛选）")
                            .setNamerForFiltering(QishuxiaSource.SearchResult::toString)
                            .setItemChosenCallback(result -> {
                                if (project != null && project.isDisposed()) return;
                                var reader = NovelReader.getInstance();
                                if (reader.isLoading()) {
                                    Messages.showInfoMessage(project, "已有小说正在导入，请完成后再搜索下载。", "搜索小说");
                                } else reader.importUrl(result.downloadUrl().toString(), project);
                            }).createPopup().showInFocusCenter();
                }
                @Override public void onThrowable(Throwable error) {
                    if (project == null || !project.isDisposed()) {
                        Messages.showErrorDialog(project, "搜索失败，原有小说和进度未改变。\n" + error.getMessage(), "搜索小说");
                    }
                }
            }.queue();
        }
    }

    static final class SearchDialog extends DialogWrapper {
        final JBTextField title = new JBTextField(28);
        final JBTextField author = new JBTextField(28);
        SearchDialog(Project project) {
            super(project);
            setTitle("搜索并下载小说（奇书网）");
            setOKButtonText("搜索");
            init();
        }
        @Override protected JComponent createCenterPanel() {
            var panel = new JPanel(new GridLayout(0, 1, 0, 6));
            var titleLabel = new JLabel("书名（可填关键词）");
            titleLabel.setLabelFor(title);
            panel.add(titleLabel);
            panel.add(title);
            var authorLabel = new JLabel("作者（可选，请填完整笔名）");
            authorLabel.setLabelFor(author);
            panel.add(authorLabel);
            panel.add(author);
            panel.add(new JLabel("至少填写一项，每项最多 30 字；作者按全名匹配。"));
            return panel;
        }
        @Override public JComponent getPreferredFocusedComponent() { return title; }
        @Override protected ValidationInfo doValidate() {
            try { QishuxiaSource.validateSearch(title.getText(), author.getText()); return null; }
            catch (IllegalArgumentException error) { return new ValidationInfo(error.getMessage()); }
        }
    }

    public static final class Bookshelf extends DumbAwareAction {
        private boolean scanning;
        @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        @Override public void update(AnActionEvent e) {
            e.getPresentation().setEnabled(!scanning && !NovelReader.getInstance().isLoading());
        }
        @Override public void actionPerformed(AnActionEvent e) {
            var reader = NovelReader.getInstance();
            if (scanning || reader.isLoading()) return;
            var project = e.getProject();
            var state = reader.getState();
            scanning = true;
            new Task.Backgroundable(project, "加载书架", true) {
                List<NovelShelf.Entry> entries;
                @Override public void run(ProgressIndicator indicator) {
                    try {
                        entries = NovelShelf.list(NovelReader.cacheDirectory(), state.titles, state.positions,
                                state.bookId, indicator::checkCanceled);
                    } catch (IOException error) { throw new IllegalArgumentException(error.getMessage(), error); }
                }
                @Override public void onFinished() { scanning = false; }
                @Override public void onSuccess() {
                    if (project != null && project.isDisposed()) return;
                    if (entries.isEmpty()) {
                        Messages.showInfoMessage(project, "书架还是空的，请先导入本地 TXT 或搜索下载小说。", "我的书架");
                        return;
                    }
                    JBPopupFactory.getInstance().createPopupChooserBuilder(entries)
                            .setTitle("我的书架 · " + entries.size() + " 本（输入书名筛选）")
                            .setNamerForFiltering(NovelShelf.Entry::title)
                            .setItemChosenCallback(entry -> {
                                if (project != null && project.isDisposed()) return;
                                if (reader.isLoading()) Messages.showInfoMessage(project, "已有小说正在导入，请稍后再切换。", "我的书架");
                                else reader.openBook(entry, project);
                            }).createPopup().showInFocusCenter();
                }
                @Override public void onThrowable(Throwable error) {
                    if (project == null || !project.isDisposed()) Messages.showErrorDialog(project,
                            "无法加载书架，原有小说和进度未改变。\n" + error.getMessage(), "我的书架");
                }
            }.queue();
        }
    }

    public static final class Import extends DumbAwareAction {
        @Override public void actionPerformed(AnActionEvent e) {
            var descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("txt");
            descriptor.setTitle("导入本地 TXT 小说");
            FileChooser.chooseFile(descriptor, e.getProject(), null, file ->
                    NovelReader.getInstance().importFile(Path.of(file.getPath()), e.getProject()));
        }
    }

    public abstract static class ReadingAction extends DumbAwareAction {
        @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        @Override public void update(AnActionEvent e) {
            var reader = NovelReader.getInstance();
            e.getPresentation().setEnabled(reader.hasBook() && reader.isEnabled());
        }
    }

    public static final class Visibility extends DumbAwareAction {
        @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        @Override public void actionPerformed(AnActionEvent e) {
            var reader = NovelReader.getInstance();
            reader.setEnabled(!reader.isEnabled());
        }
        @Override public void update(AnActionEvent e) {
            var reader = NovelReader.getInstance();
            e.getPresentation().setEnabled(reader.hasBook());
            e.getPresentation().setText(reader.isEnabled() ? "关闭小说显示" : "开启小说显示");
        }
    }

    public static final class DisplayLines extends DumbAwareAction {
        @Override public void actionPerformed(AnActionEvent e) {
            var reader = NovelReader.getInstance();
            String value = Messages.showInputDialog(e.getProject(), "一次显示几行小说正文？请输入 1–10 的整数。\n另有一行进度提示。",
                    "设置显示行数", Messages.getQuestionIcon(), Integer.toString(reader.getDisplayLines()), new InputValidator() {
                        @Override public boolean checkInput(String input) {
                            String value = input.trim();
                            return value.matches("[0-9]{1,2}") && NovelReader.validDisplayLines(Integer.parseInt(value));
                        }
                        @Override public boolean canClose(String input) { return checkInput(input); }
                    });
            if (value != null) reader.setDisplayLines(Integer.parseInt(value.trim()));
        }
    }

    public static final class Toggle extends ReadingAction {
        @Override public void actionPerformed(AnActionEvent e) { NovelReader.getInstance().toggle(); }
        @Override public void update(AnActionEvent e) {
            super.update(e);
            e.getPresentation().setText(NovelReader.getInstance().isPlaying() ? "切换为手动翻段（点击小说）" : "开启自动翻段");
        }
    }

    public static final class Interval extends DumbAwareAction {
        @Override public void actionPerformed(AnActionEvent e) {
            var reader = NovelReader.getInstance();
            String value = Messages.showInputDialog(e.getProject(), "每隔多少秒显示下一段？请输入 1–3600 的整数。",
                    "设置翻段间隔", Messages.getQuestionIcon(), Integer.toString(reader.getIntervalSeconds()), new InputValidator() {
                        @Override public boolean checkInput(String input) {
                            String value = input.trim();
                            return value.matches("[0-9]{1,4}") && NovelReader.validInterval(Integer.parseInt(value));
                        }
                        @Override public boolean canClose(String input) { return checkInput(input); }
                    });
            if (value != null) reader.setIntervalSeconds(Integer.parseInt(value.trim()));
        }
    }

    public static final class Previous extends ReadingAction {
        @Override public void actionPerformed(AnActionEvent e) { NovelReader.getInstance().turn(-1); }
    }

    public static final class Chapters extends ReadingAction {
        @Override public void actionPerformed(AnActionEvent e) {
            var reader = NovelReader.getInstance();
            var book = reader.currentBook();
            if (book == null) return;
            if (book.chapters().isEmpty()) {
                Messages.showInfoMessage(e.getProject(),
                        "未识别到章节标题。支持独占一行的“第一章”“第 123 章”“Chapter 1”等标题。\n仍会自动记住当前阅读位置，无需从头翻。",
                        "选择章节");
                return;
            }
            var selected = book.chapters().getFirst();
            for (var chapter : book.chapters()) {
                if (chapter.page() > reader.page()) break;
                selected = chapter;
            }
            JBPopupFactory.getInstance().createPopupChooserBuilder(book.chapters())
                    .setTitle("选择章节（输入章节号或标题筛选）")
                    .setNamerForFiltering(NovelBook.Chapter::title)
                    .setSelectedValue(selected, true)
                    .setItemChosenCallback(chapter -> reader.selectChapter(book.id(), book.chapters().indexOf(chapter)))
                    .createPopup().showInFocusCenter();
        }
    }

    public static final class Next extends ReadingAction {
        @Override public void actionPerformed(AnActionEvent e) { NovelReader.getInstance().turn(1); }
    }
}
