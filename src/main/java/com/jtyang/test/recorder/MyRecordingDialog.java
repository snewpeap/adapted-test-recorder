package com.jtyang.test.recorder;

import com.android.ddmlib.IDevice;
import com.google.gct.testrecorder.event.ElementAction;
import com.google.gct.testrecorder.event.ElementDescriptor;
import com.google.gct.testrecorder.event.TestRecorderEvent;
import com.google.gct.testrecorder.event.TestRecorderEventListener;
import com.google.gct.testrecorder.ui.RecordingDialog;
import com.google.gct.testrecorder.util.ClassHelper;
import com.google.gson.*;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileChooser.ex.FileSaverDialogImpl;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import org.apache.commons.io.FileUtils;
import org.jetbrains.android.facet.AndroidFacet;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.commons.lang.StringUtils.isEmpty;

/**
 * Proxy of {@link RecordingDialog} in order to record hierarchy/screenshot on {@link RecordingDialog#onEvent} called.
 *
 * @author jtyang
 * @see com.google.gct.testrecorder.ui.RecordingDialog
 */
public class MyRecordingDialog extends RecordingDialog implements TestRecorderEventListener {

    private final String myPackageName;
    private final boolean myIsRecordingTest;
    private final Project myProject;

    public MyRecordingDialog(AndroidFacet facet, IDevice device, String packageName, String launchedActivityName, boolean isRecordingTest) {
        super(facet, device, packageName, launchedActivityName, isRecordingTest);
        myPackageName = packageName;
        myIsRecordingTest = isRecordingTest;
        myProject = facet.getModule().getProject();
    }

    @Override
    public void onEvent(TestRecorderEvent event) {
        if (TestRecorderEvent.SUPPORTED_EVENTS.contains(event.getEventType())) {
            MyTestRecorderEvent myRecorderEvent = MyTestRecorderEvent.getMyRecorderEvent(event);
            // TODO Fill with real hierarchy (may be file path)
            myRecorderEvent.setHierarchy("test");
            // TODO Also take screenshot
            super.onEvent(myRecorderEvent);
        }
    }


    @Override
    protected void doOKAction() {
        if (myIsRecordingTest) {
            super.doOKAction();
        } else {
            FileSaverDescriptor descriptor = new FileSaverDescriptor("Save Robo Script", "Save Robo script to a file", "json");
            FileSaverDialogImpl fileSaverDialog = new FileSaverDialogImpl(descriptor, this.myProject);
            VirtualFileWrapper fileWrapper = fileSaverDialog.save((VirtualFile)null, myPackageName + "_robo_script_" + System.currentTimeMillis());
            if (fileWrapper != null) {
                try {
                    Method getAllModelActions = this.getClass().getSuperclass().getDeclaredMethod("getAllModelActions");
                    getAllModelActions.setAccessible(true);
                    //noinspection unchecked
                    List<ElementAction> allModelActions = (List<ElementAction>) getAllModelActions.invoke(this);
                    FileUtils.write(fileWrapper.getFile(), getJsonForActions(myProject, allModelActions), UTF_8);
                } catch (Exception ex) {
                    String message = isEmpty(ex.getMessage()) ? "Unknown error" : ex.getMessage();
                    Messages.showMessageDialog(getRootPane(), message, "Could not Save Robo Script to a File", null);
                }
            }

            if (fileSaverDialog.isOK()) {
                close(OK_EXIT_CODE);
            }
        }
    }

    public String getJsonForActions(Project project, List<ElementAction> actions) {
        // Consider only MyTestRecorderEvents.
        List<MyTestRecorderEvent> testRecorderEvents = new ArrayList<>();
        for (ElementAction action : actions) {
            if (action instanceof MyTestRecorderEvent) {
                testRecorderEvents.add((MyTestRecorderEvent) action);
            }
        }
        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.registerTypeAdapter(ElementDescriptor.class, new MyElementDescriptorSerializer(project));
        return gsonBuilder.setPrettyPrinting().create().toJson(testRecorderEvents);
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
