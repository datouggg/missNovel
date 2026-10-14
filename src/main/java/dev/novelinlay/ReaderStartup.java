package dev.novelinlay;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;

public final class ReaderStartup implements StartupActivity.DumbAware {
    @Override public void runActivity(Project project) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) NovelReader.getInstance().start(project);
        });
    }
}
