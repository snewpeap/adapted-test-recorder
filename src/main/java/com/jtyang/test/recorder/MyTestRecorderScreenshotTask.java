package com.jtyang.test.recorder;

import com.android.ddmlib.IDevice;
import com.google.gct.testrecorder.ui.ScreenshotCallback;
import com.google.gct.testrecorder.ui.TestRecorderScreenshotTask;
import com.intellij.openapi.project.Project;

import java.io.File;
import java.lang.reflect.Field;

/**
 * Work with {@link UiAutomatorModelStub} to enable access to XML dump file
 *
 * @author jtyang
 */
public class MyTestRecorderScreenshotTask extends TestRecorderScreenshotTask {
    private final ScreenshotCallback callback;

    public MyTestRecorderScreenshotTask(Project project, IDevice device, String packageName, ScreenshotCallback callback) {
        super(project, device, packageName, callback);
        this.callback = callback;
    }

    @Override
    public void onSuccess() {
        try {
            Class<?> superclass = getClass().getSuperclass();
            Field success = superclass.getDeclaredField("success");
            success.setAccessible(true);
            Field myUiHierarchyLocalFile = superclass.getDeclaredField("myUiHierarchyLocalFile");
            myUiHierarchyLocalFile.setAccessible(true);
            if (success.getBoolean(this)) {
                callback.onSuccess(getScreenshot(), new UiAutomatorModelStub(((File) myUiHierarchyLocalFile.get(this))));
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
        }
    }
}
