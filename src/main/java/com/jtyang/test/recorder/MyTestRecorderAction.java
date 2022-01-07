package com.jtyang.test.recorder;

import com.android.ide.common.repository.GradleCoordinate;
import com.android.tools.idea.projectsystem.AndroidModuleSystem;
import com.android.tools.idea.projectsystem.GoogleMavenArtifactId;
import com.android.tools.idea.projectsystem.ProjectSystemUtil;
import com.android.tools.idea.run.AndroidSessionInfo;
import com.google.gct.testrecorder.debugger.SessionInitializer;
import com.google.gct.testrecorder.run.TestRecorderRunConfigurationProxy;
import com.google.gct.testrecorder.ui.TestRecorderAction;
import com.intellij.execution.ExecutionTargetManager;
import com.intellij.execution.RunManagerEx;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.LocatableConfigurationBase;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.ui.popup.list.ListPopupImpl;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Basically a replica of {@link TestRecorderAction},
 * except replace {@link SessionInitializer} with {@link MySessionInitializer}.
 *
 * @author jtyang the copycat
 */
public class MyTestRecorderAction extends TestRecorderAction {
    @Override
    public void update(AnActionEvent event) {
        super.update(event);
        event.getPresentation().setVisible(true);
    }

    @Override
    public void actionPerformed(AnActionEvent event) {
        Project project = event.getProject();
        if (project != null && !project.isDisposed()) {
            myLaunchTestRecorder(project, isRecordingTestAction(event));
        }
    }

    private void myLaunchTestRecorder(Project project, boolean isRecordingTest) {
        List<RunConfiguration> suitableRunConfigurations = TestRecorderAction.getSuitableRunConfigurations(project);
        if (suitableRunConfigurations.isEmpty()) {
            String message = "Please create an Android Application or Blaze Command Run configuration with a valid module and Default or Specified launch activity.";
            Messages.showDialog(project, message, "No Suitable Run Configuration Found", new String[]{"OK"}, 0, null);
        } else if (suitableRunConfigurations.size() == 1) {
            myLaunchTestRecorderOnConfiguration(project, suitableRunConfigurations.get(0), isRecordingTest);
        } else {
            RunnerAndConfigurationSettings selectedConfiguration = RunManagerEx.getInstanceEx(project).getSelectedConfiguration();
            RunConfiguration selectedRunConfiguration;
            if (selectedConfiguration != null && suitableRunConfigurations.contains((selectedRunConfiguration = selectedConfiguration.getConfiguration()))) {
                myLaunchTestRecorderOnConfiguration(project, selectedRunConfiguration, isRecordingTest);
            } else {
                ListPopupImpl configurationPickerPopup = new ListPopupImpl(project, new BaseListPopupStep<>("Pick Configuration to Launch", suitableRunConfigurations) {
                    @NotNull
                    public String getTextFor(RunConfiguration runConfiguration) {
                        return runConfiguration.getName();
                    }

                    public PopupStep<?> onChosen(RunConfiguration runConfiguration, boolean finalChoice) {
                        return doFinalStep(() -> myLaunchTestRecorderOnConfiguration(project, runConfiguration, isRecordingTest));
                    }
                });
                configurationPickerPopup.showCenteredInCurrentWindow(project);
            }
        }

    }

    public void myLaunchTestRecorderOnConfiguration(Project project, RunConfiguration runConfiguration, boolean isRecordingTest) {
        TestRecorderRunConfigurationProxy runConfigurationProxy = TestRecorderRunConfigurationProxy.getInstance(runConfiguration);
        if (runConfigurationProxy == null) {
            throw new RuntimeException("Could not obtain an instance of TestRecorderRunConfigurationProxy");
        } else {
            Module module = runConfigurationProxy.getModule();
            AndroidModuleSystem moduleSystem = ProjectSystemUtil.getModuleSystem(module);
            for (GoogleMavenArtifactId artifactId : GoogleMavenArtifactId.values()) {
                if (artifactId.getMavenGroupId().startsWith("androidx.compose.")) {
                    GradleCoordinate coordinate = moduleSystem.getResolvedDependency(artifactId.getCoordinate("+"));
                    if (coordinate != null) {
                        String message = "Espresso Testing Framework does not support Compose projects.";
                        Messages.showDialog(project, message, "Espresso Test Cannot Be Recorded", new String[]{"OK"}, 0, null);
                        return;
                    }
                }
            }

            LocatableConfigurationBase<?> testRecorderConfiguration = runConfigurationProxy.getTestRecorderRunConfiguration();
            ExecutionEnvironmentBuilder builder = ExecutionEnvironmentBuilder.createOrNull(testRecorderConfiguration.getProject(), DefaultDebugExecutor.getDebugExecutorInstance(), testRecorderConfiguration);
            if (builder == null) {
                throw new RuntimeException("Could not create execution environment builder");
            } else {
                ExecutionEnvironment environment = builder.build();
                AndroidFacet facet = AndroidFacet.getInstance(module);
                if (facet == null) {
                    throw new RuntimeException("Could not obtain Android facet for module: " + module.getName());
                } else {
                    AndroidSessionInfo oldSessionInfo = AndroidSessionInfo.findOldSession(module.getProject(), null, runConfiguration, ExecutionTargetManager.getInstance(module.getProject()).getActiveTarget());
                    if (oldSessionInfo != null) {
                        oldSessionInfo.getProcessHandler().detachProcess();
                    }
                    try {
                        // Although method `execution` is marked as deprecated,
                        // method `setCallBack` in suggest usage is still a Internal API
                        //noinspection deprecation
                        environment.getRunner().execute(environment, (descriptor) -> ApplicationManager.getApplication().executeOnPooledThread(getSessionInitializer(facet, environment, runConfigurationProxy, testRecorderConfiguration, isRecordingTest)));
                    } catch (Exception e) {
                        String message = StringUtils.isEmpty(e.getMessage()) ? "Unknown error" : e.getMessage();
                        Messages.showDialog(project, message, "Could not Start Debugging of the App", new String[]{"OK"}, 0, null);
                    }
                }
            }
        }
    }

    // The adjustment 2022/1/7
    public SessionInitializer getSessionInitializer(AndroidFacet facet, ExecutionEnvironment environment, TestRecorderRunConfigurationProxy testRecorderConfigurationProxy, RunConfiguration runConfiguration, boolean isRecordingTest) {
        return new MySessionInitializer(facet, environment, testRecorderConfigurationProxy, runConfiguration, isRecordingTest);
    }

    @SuppressWarnings("unused")
    public boolean isRecordingTestAction(AnActionEvent event) {
        return false;
    }
}
