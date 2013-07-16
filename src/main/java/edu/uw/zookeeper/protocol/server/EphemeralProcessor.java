package edu.uw.zookeeper.protocol.server;

import java.util.Iterator;
import java.util.concurrent.ConcurrentMap;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.MapMaker;
import com.google.common.collect.Multimaps;
import com.google.common.collect.SetMultimap;

import edu.uw.zookeeper.data.Stats;
import edu.uw.zookeeper.data.TxnOperation;
import edu.uw.zookeeper.data.TxnRequest;
import edu.uw.zookeeper.protocol.ProtocolRequestMessage;
import edu.uw.zookeeper.protocol.SessionRequest;
import edu.uw.zookeeper.protocol.proto.IDeleteRequest;
import edu.uw.zookeeper.protocol.proto.IMultiRequest;
import edu.uw.zookeeper.protocol.proto.IMultiResponse;
import edu.uw.zookeeper.protocol.proto.OpCode;
import edu.uw.zookeeper.protocol.proto.Records;
import edu.uw.zookeeper.util.Processors;
import edu.uw.zookeeper.util.Processors.ForwardingProcessor;

public class EphemeralProcessor extends ForwardingProcessor<TxnOperation.Request<Records.Request>, Records.Response> implements Processors.UncheckedProcessor<TxnOperation.Request<Records.Request>, Records.Response> {

    public static EphemeralProcessor create(
            Processors.UncheckedProcessor<TxnOperation.Request<Records.Request>, Records.Response> delegate) {
        return new EphemeralProcessor(delegate);
    }
    
    protected final Processors.UncheckedProcessor<TxnOperation.Request<Records.Request>, Records.Response> delegate;
    protected final SetMultimap<Long, String> bySession;
    protected final ConcurrentMap<String, Long> byPath;
    
    public EphemeralProcessor(
            Processors.UncheckedProcessor<TxnOperation.Request<Records.Request>, Records.Response> delegate) {
        this.delegate = delegate;
        this.bySession = Multimaps.synchronizedSetMultimap(HashMultimap.<Long, String>create());
        this.byPath = new MapMaker().makeMap();
    }
    
    @Override
    public Records.Response apply(TxnOperation.Request<Records.Request> input) {
        Records.Request request = input.getRecord();
        Records.Response response = delegate().apply(input);
        Long session = input.getSessionId();
        if (request.getOpcode() == OpCode.CLOSE_SESSION) {
            for (String path: bySession.get(session)) {
                ProtocolRequestMessage<Records.Request> nested = ProtocolRequestMessage.of(input.getXid(), (Records.Request) new IDeleteRequest(path, Stats.VERSION_ANY));
                apply(TxnRequest.of(input.getTime(), input.getZxid(), SessionRequest.of(session, nested, nested)));
            }
        } else {
            apply(session, request, response);
        }
        return response;
    }
    
    protected Records.Response apply(Long session, Records.Request request, Records.Response response) {
        switch (response.getOpcode()) {
        case CREATE:
        case CREATE2:
        {
            CreateMode mode;
            try {
                mode = CreateMode.fromFlag(((Records.CreateModeGetter) request).getFlags());
            } catch (KeeperException e) {
                throw new IllegalArgumentException(request.toString());
            }
            if (mode.isEphemeral()) {
                String path = ((Records.PathGetter) response).getPath();
                bySession.put(session, path);
                byPath.put(path, session);
            }
        
            break;
        }
        case DELETE:
        {
            String path = ((Records.PathGetter) request).getPath();
            Long owner = byPath.remove(path);
            if (owner != null) {
                bySession.remove(owner, path);
            }
            break;
        }
        case MULTI:
        {
            Iterator<Records.MultiOpRequest> requests = ((IMultiRequest) request).iterator();
            Iterator<Records.MultiOpResponse> responses = ((IMultiResponse) response).iterator();
            while (requests.hasNext()) {
                apply(session, requests.next(), responses.next());
            }
            break;
        }
        case CLOSE_SESSION:
        {
            throw new AssertionError(request.toString());
        }
        default:
            break;
        }
        return response;
    }
    
    @Override
    protected Processors.UncheckedProcessor<TxnOperation.Request<Records.Request>, Records.Response> delegate() {
        return delegate;
    }
}
