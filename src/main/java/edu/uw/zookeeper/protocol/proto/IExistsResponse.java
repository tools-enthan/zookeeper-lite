package edu.uw.zookeeper.protocol.proto;

import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.proto.ExistsResponse;

@Operational(opcode=OpCode.EXISTS)
public class IExistsResponse extends ICodedRecord<ExistsResponse> implements Records.Response, Records.StatGetter {

    public IExistsResponse() {
        this(new ExistsResponse());
    }
    
    public IExistsResponse(Stat stat) {
        this(new ExistsResponse(stat));
    }

    public IExistsResponse(ExistsResponse record) {
        super(record);
    }

    @Override
    public Stat getStat() {
        return get().getStat();
    }
}