package com.jtyang.test.recorder;

import com.android.ddmlib.IDevice;
import com.google.gct.testrecorder.event.ElementDescriptor;
import com.google.gct.testrecorder.event.TestRecorderEvent;
import com.google.gct.testrecorder.event.TestRecorderEventListener;
import com.google.gct.testrecorder.ui.RecordingDialog;
import com.google.gct.testrecorder.util.ClassHelper;
import com.google.gct.testrecorder.util.ImageHelper;
import com.google.gct.testrecorder.util.UiAutomatorNodeHelper;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileChooser.ex.FileSaverDialogImpl;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.jtyang.test.recorder.MyTestRecorderEvent.DummyTestRecordEvent;
import org.apache.commons.io.FileUtils;
import org.jetbrains.android.facet.AndroidFacet;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.google.gct.testrecorder.event.TestRecorderEvent.TEXT_CHANGE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.commons.lang.StringUtils.isEmpty;

/**
 * Proxy of {@link RecordingDialog} in order to record hierarchy/screenshot on {@link RecordingDialog#onEvent} called.
 *
 * @author jtyang
 * @see com.google.gct.testrecorder.ui.RecordingDialog
 */
public class MyRecordingDialog extends RecordingDialog implements TestRecorderEventListener {

    private final IDevice myDevice;
    private final String myPackageName;
    private final boolean myIsRecordingTest;
    private final Project myProject;
    // Redundant file might be created during recording,
    // so map filename to File object for further filtering
    private final Map<String, File> hierarchyXmlFiles = new HashMap<>();
    private final Map<String, File> screenshotFiles = new HashMap<>();
    private final DummyTestRecordEvent startEvent;
    private MyTestRecorderEvent previousEvent;

    public MyRecordingDialog(AndroidFacet facet, IDevice device, String packageName, String launchedActivityName, boolean isRecordingTest) {
        super(facet, device, packageName, launchedActivityName, isRecordingTest);
        myDevice = device;
        myPackageName = packageName;
        myIsRecordingTest = isRecordingTest;
        myProject = facet.getModule().getProject();
        previousEvent = startEvent = new DummyTestRecordEvent("RECORD_START");
    }

    @Override
    public void onEvent(TestRecorderEvent event) {
        if (TestRecorderEvent.SUPPORTED_EVENTS.contains(event.getEventType())) {
            MyTestRecorderEvent myTestRecorderEvent = MyTestRecorderEvent.getMyRecorderEvent(event);
            super.onEvent(myTestRecorderEvent);
            // Do not interrupt text input, as it brings performance overhead,
            // also is meaningless 'cause what we interest in is the state when input finish
            if (!TEXT_CHANGE.equals(previousEvent.getEventType()) || !TEXT_CHANGE.equals(event.getEventType())) {
                // When the task queue (indeed execute immediately),
                // it record the result state of previous event
                getMyRecorderScreenshotTask(previousEvent).queue();
                previousEvent = myTestRecorderEvent;
            }
        }
    }

    protected MyTestRecorderScreenshotTask getMyRecorderScreenshotTask(MyTestRecorderEvent event) {
        return new MyTestRecorderScreenshotTask(myProject, myDevice, myPackageName, (image, model) -> {
            File hierarchyXml = ((UiAutomatorModelStub) model).getXmlDumpFile();
            String hierarchyXmlFilename = hierarchyXml.getName();
            hierarchyXmlFiles.put(hierarchyXmlFilename, hierarchyXml);
            event.setHierarchy(hierarchyXmlFilename);
            try {
                File screenshot = File.createTempFile("ui_screenshot", ".png");
                screenshot.deleteOnExit();
                BufferedImage preparedImage = ImageHelper.rotateImage(image, UiAutomatorNodeHelper.getRotation(model.getXmlRootNode()));
                ImageIO.write(preparedImage, "png", screenshot);
                String screenshotFilename = screenshot.getName();
                screenshotFiles.put(screenshotFilename, screenshot);
                event.setScreenshot(screenshotFilename);
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    @Override
    protected void doOKAction() {
        if (myIsRecordingTest) {
            super.doOKAction();
        } else {
            // Result state of last event is not recorded so do it here
            getMyRecorderScreenshotTask(previousEvent).queue();
            String recordName = myPackageName + "_record_" + System.currentTimeMillis();
            FileSaverDescriptor descriptor = new FileSaverDescriptor("Setup Record Directory", "Setup or choose a directory to persist record files");
            FileSaverDialogImpl fileSaverDialog = new FileSaverDialogImpl(descriptor, this.myProject);
            VirtualFileWrapper fileWrapper = fileSaverDialog.save((VirtualFile) null, recordName);
            if (fileWrapper != null) {
                File recordDir = fileWrapper.getFile();
                if (recordDir.mkdirs()) {
                    try {
                        saveRecord(recordDir);
                    } catch (Exception ex) {
                        String message = isEmpty(ex.getMessage()) ? "Unknown error" : ex.getMessage();
                        Messages.showMessageDialog(getRootPane(), message, "Could not Save Robo Script to a File", null);
                    }
                }
            }

            if (fileSaverDialog.isOK()) {
                close(OK_EXIT_CODE);
            }
        }
    }

    public void saveRecord(File recordDirectory) throws Exception {
        Method getAllModelActions = this.getClass().getSuperclass().getDeclaredMethod("getAllModelActions");
        getAllModelActions.setAccessible(true);
        List<?> ret = (List<?>) getAllModelActions.invoke(this);
        List<MyTestRecorderEvent> allModelActions = new ArrayList<>(ret.size()+1);
        allModelActions.add(startEvent);
        for (Object o : ret) {
            // Consider only MyTestRecorderEvents.
            if (o instanceof MyTestRecorderEvent) {
                allModelActions.add(((MyTestRecorderEvent) o));
            }
        }
        File jsonScriptFile = new File(recordDirectory, "robo_script.json");
        FileUtils.write(jsonScriptFile, getJsonForActions(myProject, allModelActions), UTF_8);
        // Filter hierarchy and screenshot file that occur in related fields of MyTestRecorderEvent object
        // and copy them from %TEMP% to destined directory
        for (MyTestRecorderEvent event : allModelActions) {
            FileUtils.copyFileToDirectory(hierarchyXmlFiles.get(event.getHierarchy()), recordDirectory);
            FileUtils.copyFileToDirectory(screenshotFiles.get(event.getScreenshot()), recordDirectory);
        }
    }

    public String getJsonForActions(Project project, List<MyTestRecorderEvent> events) {
        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.registerTypeAdapter(ElementDescriptor.class, new MyElementDescriptorSerializer(project));
        return gsonBuilder.setPrettyPrinting().create().toJson(events);
    }

    public static class MyElementDescriptorSerializer implements JsonSerializer<ElementDescriptor> {

        private final Project project;

        public MyElementDescriptorSerializer(Project project) {
            this.project = project;
        }

        @Override
        public JsonElement serialize(ElementDescriptor elementDescriptor, Type type, JsonSerializationContext jsonSerializationContext) {
            JsonObject jsonObject = new JsonObject();
            jsonObject.addProperty("className", ClassHelper.getInternalName(project, elementDescriptor.getClassName()));
            jsonObject.addProperty("recyclerViewChildPosition", elementDescriptor.getRecyclerViewChildPosition());
            jsonObject.addProperty("adapterViewChildPosition", elementDescriptor.getAdapterViewChildPosition());
            jsonObject.addProperty("groupViewChildPosition", elementDescriptor.getGroupViewChildPosition());
            jsonObject.addProperty("resourceId", elementDescriptor.getResourceId());
            jsonObject.addProperty("contentDescription", elementDescriptor.getContentDescription());
            jsonObject.addProperty("text", elementDescriptor.getText());
            return jsonObject;
        }
    }
}
