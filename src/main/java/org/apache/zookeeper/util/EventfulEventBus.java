package org.apache.zookeeper.util;

import com.google.common.eventbus.EventBus;

public class EventfulEventBus extends EventBus implements Eventful {
    
    public EventfulEventBus() {
        super();
    }
}
