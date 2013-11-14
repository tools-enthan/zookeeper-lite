package edu.uw.zookeeper.protocol.client;

import edu.uw.zookeeper.common.Automaton;
import edu.uw.zookeeper.common.Pair;
import edu.uw.zookeeper.net.Connection;
import edu.uw.zookeeper.protocol.Operation;
import edu.uw.zookeeper.protocol.ZxidReference;

public class ZxidTracker implements ZxidReference  {
    
    public static class ZxidListener extends Pair<ZxidTracker,Connection<?,?,?>> implements Connection.Listener<Object> {

        public ZxidListener(ZxidTracker tracker, Connection<?,?,?> connection) {
            super(tracker, connection);
            second().subscribe(this);
        }

        public void handleSessionReply(Operation.ProtocolResponse<?> message) {
            first().update(message.zxid());
        }

        @Override
        public void handleConnectionState(Automaton.Transition<Connection.State> state) {
            if (state.to() == Connection.State.CONNECTION_CLOSED) {
                second().unsubscribe(this);
            }
        }

        @Override
        public void handleConnectionRead(Object message) {
            if (message instanceof Operation.ResponseId) {
                first().update(((Operation.ResponseId) message).zxid());
            }
        }
    }
    
    public static ZxidTracker create() {
        return new ZxidTracker(0L);
    }
    
    public static ZxidTracker create(long lastZxid) {
        return new ZxidTracker(lastZxid);
    }
    
    public static ZxidListener listener(ZxidTracker tracker, Connection<?,?,?> connection) {
        return new ZxidListener(tracker, connection);
    }
    
    protected volatile long lastZxid;
    
    protected ZxidTracker(long lastZxid) {
        super();
        this.lastZxid = lastZxid;
    }

    @Override
    public long get() {
        return lastZxid;
    }
    
    public synchronized long update(long zxid) {
        if (lastZxid < zxid) {
            lastZxid = zxid;
        }
        return lastZxid;
    }
}
