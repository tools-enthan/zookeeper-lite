package edu.uw.zookeeper.protocol.client;

import edu.uw.zookeeper.protocol.Operation;
import edu.uw.zookeeper.protocol.SessionRequestWrapper;
import edu.uw.zookeeper.util.Generator;
import edu.uw.zookeeper.util.Processor;

public class AssignXidProcessor implements
        Processor<Operation.Request, Operation.SessionRequest>,
        Generator<Integer> {

    public static AssignXidProcessor newInstance() {
        return new AssignXidProcessor(XidIncrementer.newInstance());
    }

    public static AssignXidProcessor newInstance(Generator<Integer> xid) {
        return new AssignXidProcessor(xid);
    }

    private final Generator<Integer> xids;

    private AssignXidProcessor(Generator<Integer> xids) {
        this.xids = xids;
    }

    @Override
    public Operation.SessionRequest apply(Operation.Request request) {
        switch (request.opcode()) {
        case CREATE_SESSION:
            return null;
        default:
            break;
        }
        
        int xid;
        if (request instanceof Operation.XidHeader) {
            xid = ((Operation.XidHeader)request).xid();
        } else {
            xid = next();
        }
        return SessionRequestWrapper.create(xid, request);
    }

    @Override
    public Integer get() {
        return xids.get();
    }

    @Override
    public Integer next() {
        return xids.next();
    }
}
