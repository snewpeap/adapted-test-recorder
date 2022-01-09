package com.jtyang.test.recorder;

import com.android.uiautomator.UiAutomatorModel;

import java.io.File;

/**
 * {@link UiAutomatorModel} that provide access to original XML dump file
 *
 * @author jtyang
 */
public class UiAutomatorModelStub extends UiAutomatorModel {
    private final File xmlDumpFile;

    public UiAutomatorModelStub(File xmlDumpFile) {
        // TODO:    So far the file needn't to be parsed entirely,
        //          should we avoid super() from parsing the dump file?
        super(xmlDumpFile);
        this.xmlDumpFile = xmlDumpFile;
    }

    public File getXmlDumpFile() {
        return xmlDumpFile;
    }
}
