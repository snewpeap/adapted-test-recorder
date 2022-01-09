package com.jtyang.test.recorder;

import com.google.gct.testrecorder.event.TestRecorderEvent;

import java.util.Optional;

/**
 * Extend {@link TestRecorderEvent} with hierarchy and screenshot.
 *
 * @author jtyang
 */
public class MyTestRecorderEvent extends TestRecorderEvent {
    private String hierarchy;
    private String screenshot;

    public MyTestRecorderEvent(String eventType, long timestamp) {
        super(eventType, timestamp);
    }

    public String getHierarchy() {
        return hierarchy;
    }

    public void setHierarchy(String hierarchy) {
        this.hierarchy = hierarchy;
    }

    public String getScreenshot() {
        return screenshot;
    }

    public void setScreenshot(String screenshot) {
        this.screenshot = screenshot;
    }

    /**
     * get a {@link MyTestRecorderEvent} object from its superclass instance
     */
    public static MyTestRecorderEvent getMyRecorderEvent(TestRecorderEvent event) {
        MyTestRecorderEvent myTestRecorderEvent = new MyTestRecorderEvent(event.getEventType(), event.getTimestamp());
        myTestRecorderEvent.setActionCode(event.getActionCode());
        myTestRecorderEvent.setCanScrollTo(event.canScrollTo());
        myTestRecorderEvent.setReplacementText(event.getReplacementText());
        myTestRecorderEvent.setDelayTime(event.getDelayTime());
        Optional.ofNullable(event.getRequestedPermissions()).ifPresent(myTestRecorderEvent::setRequestedPermissions);
        myTestRecorderEvent.setSwipeDirection(event.getSwipeDirection());
        for (int i = 0; i < event.getElementDescriptorsCount(); i++) {
            myTestRecorderEvent.addElementDescriptor(event.getElementDescriptor(i));
        }
        return myTestRecorderEvent;
    }

    public static class DummyTestRecordEvent extends MyTestRecorderEvent {

        public DummyTestRecordEvent(String dummyEventType) {
            super(dummyEventType, System.currentTimeMillis());
        }
    }
}
