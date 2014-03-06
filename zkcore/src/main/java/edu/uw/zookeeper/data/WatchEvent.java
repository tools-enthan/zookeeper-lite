
package edu.uw.zookeeper.data;

import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;

import com.google.common.base.Objects;

import edu.uw.zookeeper.protocol.Message;
import edu.uw.zookeeper.protocol.ProtocolResponseMessage;
import edu.uw.zookeeper.protocol.proto.IWatcherEvent;
import edu.uw.zookeeper.protocol.proto.OpCodeXid;
import edu.uw.zookeeper.protocol.proto.Records;

public class WatchEvent {

    public static WatchEvent fromRecord(IWatcherEvent record) {
        return of(
                EventType.fromInt(record.getType()), 
                KeeperState.fromInt(record.getState()), 
                (ZNodePath) ZNodeLabelVector.fromString(record.getPath()));
    }
    
    public static WatchEvent of(EventType eventType, KeeperState keeperState, ZNodePath path) {
        return new WatchEvent(eventType, keeperState, path);
    }
    
    private final ZNodePath path;
    private final KeeperState keeperState;
    private final EventType eventType;
    
    public WatchEvent(EventType eventType, KeeperState keeperState, ZNodePath path) {
        this.keeperState = keeperState;
        this.eventType = eventType;
        this.path = path;
    }
    
    public KeeperState getState() {
        return keeperState;
    }
    
    public EventType getType() {
        return eventType;
    }
    
    public ZNodePath getPath() {
        return path;
    }

    public Message.ServerResponse<IWatcherEvent> toMessage() {
        return ProtocolResponseMessage.of(
                OpCodeXid.NOTIFICATION.xid(), 
                OpCodeXid.NOTIFICATION_ZXID,
                new IWatcherEvent(
                    getType().getIntValue(), 
                    getState().getIntValue(), 
                    getPath().toString()));
    }

    @Override
    public String toString() {
        return Records.toBeanString(this);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (! (obj instanceof WatchEvent)) {
            return false;
        }
        WatchEvent other = (WatchEvent) obj;
        return Objects.equal(getType(), other.getType())
                && Objects.equal(getState(), other.getState())
                && Objects.equal(getPath(), other.getPath());
    }
    
    @Override
    public int hashCode() {
        return Objects.hashCode(getType(), getState(), getPath());
    }
}
