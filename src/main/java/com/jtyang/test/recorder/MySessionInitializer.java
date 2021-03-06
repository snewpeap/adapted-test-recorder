package com.jtyang.test.recorder;

import com.android.SdkConstants;
import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.CollectingOutputReceiver;
import com.android.ddmlib.IDevice;
import com.android.tools.idea.run.AndroidSessionInfo;
import com.android.tools.idea.run.ApkProviderUtil;
import com.android.tools.idea.run.activity.ActivityLocatorUtils;
import com.android.tools.idea.run.activity.DefaultActivityLocator;
import com.google.common.base.Strings;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gct.testrecorder.debugger.BreakpointCommand;
import com.google.gct.testrecorder.debugger.BreakpointDescriptor;
import com.google.gct.testrecorder.debugger.SessionInitializer;
import com.google.gct.testrecorder.run.TestRecorderRunConfigurationProxy;
import com.google.gct.testrecorder.settings.TestRecorderSettings;
import com.google.gct.testrecorder.ui.RecordingDialog;
import com.intellij.debugger.DebuggerManagerEx;
import com.intellij.debugger.DefaultDebugEnvironment;
import com.intellij.debugger.engine.DebugProcess;
import com.intellij.debugger.engine.DebugProcessImpl;
import com.intellij.debugger.engine.DebugProcessListener;
import com.intellij.debugger.engine.JavaDebugProcess;
import com.intellij.debugger.engine.RemoteDebugProcessHandler;
import com.intellij.debugger.impl.DebuggerManagerListener;
import com.intellij.debugger.impl.DebuggerSession;
import com.intellij.execution.DefaultExecutionResult;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.configurations.RemoteConnection;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageDialogBuilder;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiClass;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugProcessStarter;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import org.jetbrains.android.dom.manifest.Activity;
import org.jetbrains.android.dom.manifest.ActivityAlias;
import org.jetbrains.android.dom.manifest.Application;
import org.jetbrains.android.dom.manifest.Manifest;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.android.sdk.AndroidSdkUtils;
import org.jetbrains.annotations.NotNull;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.google.gct.testrecorder.event.TestRecorderEvent.*;

/**
 * Basically a replica of {@link SessionInitializer},
 * except replace {@link RecordingDialog} with {@link MyRecordingDialog}.
 *
 * @author jtyang the copycat
 */
public class MySessionInitializer extends SessionInitializer {
    private static final Logger LOGGER = Logger.getInstance(SessionInitializer.class);
    // A replacement press back breakpoint descriptor as a workaround for emulators with API 28+ that cannot reliably handle,
    // i.e., without occasionally freezing, the regular PRESS_BACK breakpoint.
    private static final BreakpointDescriptor PRESS_BACK_EMULATOR_28_BREAKPOINT_DESCRIPTOR =
            new BreakpointDescriptor(PRESS_BACK_EMULATOR_28, "android.app.Activity", "onBackPressed", "()V", false);
    private final Set<BreakpointDescriptor> myBreakpointDescriptors = Sets.newHashSet();
    private final Set<BreakpointCommand> myBreakpointCommands = Sets.newHashSet();
    private final AndroidFacet myFacet;
    private final Project myProject;
    private final ExecutionEnvironment myEnvironment;
    private final TestRecorderRunConfigurationProxy myTestRecorderConfigurationProxy;
    private final RunConfiguration myRunConfiguration;
    private final boolean myIsRecordingTest;
    private IDevice myDevice;
    private String myPackageName;
    private volatile DebuggerSession myDebuggerSession;
    private volatile DebuggerManagerListener myDebuggerManagerListener;
    private volatile RecordingDialog myRecordingDialog;
    private volatile boolean myFailedToStart;

    public MySessionInitializer(AndroidFacet facet, ExecutionEnvironment environment, TestRecorderRunConfigurationProxy testRecorderConfigurationProxy, RunConfiguration runConfiguration, boolean isRecordingTest) {
        super(facet, environment, testRecorderConfigurationProxy, runConfiguration, isRecordingTest);
        myFacet = facet;
        myProject = myFacet.getModule().getProject();
        myEnvironment = environment;
        myTestRecorderConfigurationProxy = testRecorderConfigurationProxy;
        myRunConfiguration = runConfiguration;
        myIsRecordingTest = isRecordingTest;
        // TODO: Although more robust than android.view.View#performClick() breakpoint, this might miss "contrived" clicks,
        // originating from the View object itself (e.g., as a result of processing a touch event).
        myBreakpointDescriptors.add(new BreakpointDescriptor(VIEW_CLICK, "android.view.View$PerformClick", "run", "()V", false));
        myBreakpointDescriptors.add(new BreakpointDescriptor(VIEW_LONG_CLICK, SdkConstants.CLASS_VIEW, "performLongClick", "()Z", false));
        myBreakpointDescriptors.add(new BreakpointDescriptor(LIST_ITEM_CLICK, "android.widget.AbsListView", "performItemClick",
                "(Landroid/view/View;IJ)Z", false));
        myBreakpointDescriptors.add(new BreakpointDescriptor(TEXT_CHANGE, "android.widget.TextView$ChangeWatcher", "beforeTextChanged",
                "(Ljava/lang/CharSequence;III)V", true));
        myBreakpointDescriptors.add(new BreakpointDescriptor(TEXT_CHANGE, "android.widget.TextView$ChangeWatcher", "onTextChanged",
                "(Ljava/lang/CharSequence;III)V", false));
        // TODO: This breakpoint is for a finished input event rather than just press back,
        // so some filtering is required when the breakpoint is hit.
        myBreakpointDescriptors.add(new BreakpointDescriptor(PRESS_BACK, "android.view.inputmethod.InputMethodManager",
                "invokeFinishedInputEventCallback",
                "(Landroid/view/inputmethod/InputMethodManager$PendingEvent;Z)V", false));
        myBreakpointDescriptors.add(new BreakpointDescriptor(PRESS_EDITOR_ACTION, "android.widget.TextView", "onEditorAction", "(I)V", false));
        myBreakpointDescriptors.add(new BreakpointDescriptor(VIEW_SWIPE, "android.support.v4.view.ViewPager", "smoothScrollTo", "(III)V", false));
        myBreakpointDescriptors.add(new BreakpointDescriptor(DELAYED_MESSAGE_POST, "android.os.Handler", "postDelayed",
                "(Ljava/lang/Runnable;J)Z", false));
        myBreakpointDescriptors.add(new BreakpointDescriptor(WINDOW_CONTENT_CHANGED, "android.view.ViewRootImpl$SendWindowContentChangedAccessibilityEvent",
                "run", "()V", false));
        myBreakpointDescriptors.add(new BreakpointDescriptor(LAZY_CLASSES_LOADER, "android.os.Handler", "dispatchMessage",
                "(Landroid/os/Message;)V", false));
        myBreakpointDescriptors.add(new BreakpointDescriptor(PERMISSIONS_REQUEST, "android.app.Activity", "requestPermissions",
                "([Ljava/lang/String;I)V", false));
    }

    @Override
    public void run() {
        synchronized (this) {
            myDebuggerManagerListener = new DebuggerManagerListener() {
                @Override
                public void sessionCreated(DebuggerSession session) {
                    myDebuggerSession = session;
                    myDebuggerSession.getProcess().addDebugProcessListener(createDebugProcessListener());
                }

                @Override
                public void sessionDetached(DebuggerSession session) {
                    if (myDebuggerSession == session) {
                        DebuggerManagerEx.getInstanceEx(myProject).removeDebuggerManagerListener(myDebuggerManagerListener);
                    }
                }
            };
        }
        DebuggerManagerEx.getInstanceEx(myProject).addDebuggerManagerListener(myDebuggerManagerListener);
        try {
            assignDevice();
        } catch (final Exception e) {
            myFailedToStart = true;
            ApplicationManager.getApplication().invokeLater(() -> Messages.showErrorDialog(myProject, e.getMessage(), "Test Recorder Startup Failure"));
            stopTestRecorder();
        }
    }

    @NotNull
    private DebugProcessListener createDebugProcessListener() {
        return new DebugProcessListener() {
            @Override
            public void processAttached(@NotNull DebugProcess process) {
                if (myFailedToStart) {
                    stopDebugger();
                    return;
                }
                AndroidSessionInfo sessionInfo = process.getProcessHandler().getUserData(AndroidSessionInfo.KEY);
                if (sessionInfo != null && sessionInfo.getRunConfiguration() != myRunConfiguration) {
                    // Not my debugger session (probably, my session failed midway) => stop listening.
                    DebuggerManagerEx.getInstanceEx(myProject).removeDebuggerManagerListener(myDebuggerManagerListener);
                    return;
                }
                // Mute any user-defined breakpoints to avoid Test Recorder hanging the app when such a breakpoint gets hit.
                // This event arrives before initBreakpoints is called in DebugProcessEvents,
                // but after XDebugSession is supposed to be initialized, so looks like a perfect time to mute breakpoints.
                // Muting breakpoints requires read access.
                ApplicationManager.getApplication().runReadAction(() -> {
                    for (XDebugSession debugSession : XDebuggerManager.getInstance(myProject).getDebugSessions()) {
                        debugSession.setBreakpointMuted(true);
                    }
                });
                scheduleBreakpointCommands(myDevice);
                if (myRecordingDialog == null) { // The initial debug process, open Test Recorder dialog.
                    // Detect the launched activity name outside the dispatch thread to avoid pausing it until dumb mode is over.
                    String launchedActivityName = detectLaunchedActivityName();
                    // TODO: Open the dialog after all breakpoints are set up (i.e., the scheduled actions are actually executed).
                    // Also, consider waiting for the app to be ready first (e.g., such that we can take a screenshot).
                    ApplicationManager.getApplication().invokeLater(() -> {
                        //Show Test Recorder dialog after adding and enabling breakpoints.
                        try {
                            myRecordingDialog = getRecordingDialog(myFacet, myDevice, myPackageName, launchedActivityName, myIsRecordingTest);
                        } catch (NoSuchFieldException e) {
                            e.printStackTrace();
                            DebuggerManagerEx.getInstanceEx(myProject).removeDebuggerManagerListener(myDebuggerManagerListener);
                        }
                        myRecordingDialog.setDebuggerSession(myDebuggerSession);
                        for (BreakpointCommand breakpointCommand : myBreakpointCommands) {
                            breakpointCommand.setEventListener(myRecordingDialog);
                        }
                        myRecordingDialog.show();
                        // The dialog is no longer modal, so wait till it is closed before stopping the recorder.
                        // TODO: Find a way to achieve this without busy-waiting.
                        new Thread(() -> {
                            while (myRecordingDialog.isShowing()) {
                                try {
                                    Thread.sleep(1000);
                                } catch (InterruptedException e) {
                                    // ignore
                                }
                            }
                            stopTestRecorder();
                        }).start();
                    });
                } else {
                    // The restarted debug process, reuse the already shown Test Recorder dialog.
                    myRecordingDialog.setDebuggerSession(myDebuggerSession);
                    for (BreakpointCommand breakpointCommand : myBreakpointCommands) {
                        breakpointCommand.setEventListener(myRecordingDialog);
                    }
                }
            }
            @Override
            public void processDetached(@NotNull DebugProcess process, boolean closedByUser) {
                if (myRecordingDialog != null && myRecordingDialog.isShowing()) {
                    // Since the recoding dialog is still up, the process has detached accidentally, so try to restart debugging.
                    promptToRestartDebugging();
                }
            }
        };
    }

    // The adjustment 2022/1/7
    public RecordingDialog getRecordingDialog(AndroidFacet facet, IDevice device, String packageName, String launchedActivityName, boolean isRecordingTest) throws NoSuchFieldException {
        return new MyRecordingDialog(facet, device, packageName, launchedActivityName, isRecordingTest);
    }

    /**
     * There are two major uses for the fully qualified launched activity name:
     * 1) As a template value for the base class of the generated instrumentation test and
     * 2) For establishing the package name and suggested class name of the generated test class.
     */
    @NotNull
    private String detectLaunchedActivityName() {
        if (!Strings.isNullOrEmpty(myTestRecorderConfigurationProxy.getLaunchActivityClass())) {
            return myTestRecorderConfigurationProxy.getLaunchActivityClass();
        }
        return DumbService.getInstance(myProject).runReadActionInSmartMode(() -> {
            String activityName = "unknownPackage.unknownActivity";
            try {
                activityName = new DefaultActivityLocator(myFacet).getQualifiedActivityName(myDevice);
            } catch (Exception e) {
                return activityName;
            }
            // If alias, replace with the actual activity.
            Manifest manifest;
            if ((manifest = Manifest.getMainManifest(myFacet)) == null || manifest.getApplication() == null) {
                return activityName;
            }
            Application application = manifest.getApplication();
            for (Activity activity : application.getActivities()) {
                if (activityName.equals(ActivityLocatorUtils.getQualifiedName(activity))) {
                    return activityName; // Not an alias, return as is.
                }
            }
            for (ActivityAlias activityAlias : application.getActivityAliases()) {
                if (activityName.equals(ActivityLocatorUtils.getQualifiedName(activityAlias))) {
                    // It is an alias, return the actual activity name.
                    PsiClass psiClass = activityAlias.getTargetActivity().getValue();
                    if (psiClass != null) {
                        String qualifiedName = psiClass.getQualifiedName();
                        if (qualifiedName != null) {
                            return qualifiedName;
                        }
                    }
                    // Could not establish the actual activity, so return the alias (should not really happen).
                    return activityName;
                }
            }
            // Neither actual activity nor alias - should not happen, but return the originally found activity for the sake of completeness.
            return activityName;
        });
    }

    private void promptToRestartDebugging() {
        // Do NOT use ApplicationManager.getApplication().invokeLater(...) here
        // as the prompt dialog will not show up until the main dialog is closed.
        SwingUtilities.invokeLater(() -> {
            String title = "Test Recorder Has Detached from the Device VM";
            if (isDeviceConnected()) {
                // The device is still connected, so the app might have crashed or some other VM issue happened, and thus,
                // it is impossible to reconnect.
                Messages.showMessageDialog(myRecordingDialog.getRootPane(),
                        "Test Recorder stopped recording your actions because the app stopped.", title, null);
                return;
            }
            String message = "Test Recorder stopped recording your actions because it has detached from the device VM.\n" +
                    "Please fix the connection and click Resume to continue.";
            // Keep trying until a successful reconnection or the user explicitly stops attempting to reconnect.
            while (message != null) {
                myDebuggerSession = null;
                if (myRecordingDialog != null) {
                    myRecordingDialog.setDebuggerSession(null);
                }
                boolean shouldResume =
                        MessageDialogBuilder.yesNo(title, message).yesText("Resume").noText("Stop").icon(null).ask(myRecordingDialog.getRootPane());
                message = null;
                if (shouldResume) {
                    try {
                        restartDebugging();
                    } catch (Exception e) {
                        message = "Could not reattach the debugger: " + e.getMessage();
                    }
                }
            }
        });
    }

    private void restartDebugging() throws ExecutionException {
        reconnectToDevice();
        String debugPort = Integer.toString(myDevice.getClient(myPackageName).getDebuggerListenPort());
        RemoteConnection connection = new RemoteConnection(true, "localhost", debugPort, false);
        RunProfileState state = (executor, runner) -> new DefaultExecutionResult();
        final RemoteDebugProcessHandler processHandler = new RemoteDebugProcessHandler(myProject);
        DefaultDebugEnvironment debugEnvironment = new DefaultDebugEnvironment(myEnvironment, state, connection, false) {
            @Override
            public ExecutionResult createExecutionResult() {
                return new DefaultExecutionResult(null, processHandler);
            }
        };
        DebuggerManagerEx.getInstanceEx(myProject).attachVirtualMachine(debugEnvironment);
        if (myDebuggerSession == null) {
            throw new RuntimeException("Could not attach the virtual machine!");
        }
        XDebuggerManager.getInstance(myProject).startSession(myEnvironment, new XDebugProcessStarter() {
            @Override
            @NotNull
            public XDebugProcess start(@NotNull XDebugSession session) {
                return JavaDebugProcess.create(session, myDebuggerSession);
            }
        });
        // Notify that debugging has started.
        processHandler.startNotify();
    }

    private void scheduleBreakpointCommands(IDevice device) {
        myBreakpointCommands.clear();
        DebugProcessImpl debugProcess = myDebuggerSession.getProcess();
        for (BreakpointDescriptor breakpointDescriptor : myBreakpointDescriptors) {
            if (device.getVersion().getApiLevel() >= 28) {
                if (breakpointDescriptor.eventType.equals(DELAYED_MESSAGE_POST)) {
                    // Skip setting the delayed message breakpoint on Android 28+ as it freezes recording in some scenarios.
                    continue;
                }
                if (device.isEmulator() && breakpointDescriptor.eventType.equals(PRESS_BACK)) {
                    // Use a replacement press back breakpoint descriptor for emulators with API 28+.
                    breakpointDescriptor = PRESS_BACK_EMULATOR_28_BREAKPOINT_DESCRIPTOR;
                }
            }
            BreakpointCommand breakpointCommand = new BreakpointCommand(debugProcess, breakpointDescriptor);
            myBreakpointCommands.add(breakpointCommand);
            debugProcess.getManagerThread().schedule(breakpointCommand);
        }
    }

    private void stopTestRecorder() {
        stopDebugger();
        if (myDevice != null && TestRecorderSettings.getInstance().CLEAN_AFTER_FINISH) {
            try {
                // Clear app data such that there is no stale state => the generated test can run (pass) immediately.
                myDevice.executeShellCommand("pm clear " + myPackageName, new CollectingOutputReceiver(), 5, TimeUnit.SECONDS);
            } catch (Exception e) {
                LOGGER.warn("Exception stopping the app", e);
            }
        }
    }

    private void stopDebugger() {
        DebuggerManagerEx.getInstanceEx(myProject).removeDebuggerManagerListener(myDebuggerManagerListener);
        if (TestRecorderSettings.getInstance().STOP_APP_AFTER_RECORDING) {
            if (myDebuggerSession != null) {
                XDebugSession xDebugSession = myDebuggerSession.getXDebugSession();
                if (xDebugSession != null) {
                    xDebugSession.stop();
                }
            }
        } else {
            // Keep the process running, but disable breakpoints such that it is not slowed down.
            for (BreakpointCommand breakpointCommand : myBreakpointCommands) {
                breakpointCommand.disable();
            }
        }
    }

    private void assignDevice() {
        List<ListenableFuture<IDevice>> listenableFutures = myTestRecorderConfigurationProxy.getDeviceFutures(myEnvironment);
        if (listenableFutures == null || listenableFutures.size() != 1) {
            throw new RuntimeException("Test Recorder should be launched on a single device!");
        }
        try {
            myDevice = listenableFutures.get(0).get();
        } catch (Exception e) {
            throw new RuntimeException("Exception while waiting for the device to become ready ", e);
        }
        if (myDevice.getVersion().getApiLevel() < 19) {
            throw new RuntimeException("Test Recorder supports devices and emulators running Android API level 19 (Android 4.4 Kit Kat) and higher.");
        }
        try {
            myPackageName = ApkProviderUtil.computePackageName(myFacet);
        } catch (Exception e) {
            throw new RuntimeException("Could not compute package name!");
        }
    }

    private void reconnectToDevice() {
        AndroidDebugBridge debugBridge = AndroidSdkUtils.getDebugBridge(myProject);
        if (debugBridge == null) {
            throw new RuntimeException("Could not obtain the debug bridge!");
        }
        for (IDevice device : debugBridge.getDevices()) {
            if (myDevice.getSerialNumber().equals(device.getSerialNumber())) {
                myDevice = device;
                return;
            }
        }
        throw new RuntimeException("Could not find the original device to reconnect to!");
    }
    private boolean isDeviceConnected() {
        AndroidDebugBridge debugBridge = AndroidSdkUtils.getDebugBridge(myProject);
        if (debugBridge != null) {
            for (IDevice device : debugBridge.getDevices()) {
                if (myDevice.getSerialNumber().equals(device.getSerialNumber())) {
                    return true;
                }
            }
        }
        return false;
    }
}
