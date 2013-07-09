package edu.uw.zookeeper.server;

import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Throwables;
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableFutureTask;
import edu.uw.zookeeper.Session;
import edu.uw.zookeeper.protocol.Message;
import edu.uw.zookeeper.protocol.SessionRequestMessage;
import edu.uw.zookeeper.protocol.proto.OpCode;
import edu.uw.zookeeper.protocol.proto.Records;
import edu.uw.zookeeper.util.ForwardingEventful;
import edu.uw.zookeeper.util.Processor;
import edu.uw.zookeeper.util.Promise;
import edu.uw.zookeeper.util.PromiseTask;
import edu.uw.zookeeper.util.Processors.*;
import edu.uw.zookeeper.util.Publisher;

public class ServerSessionRequestExecutor extends ForwardingEventful implements ServerExecutor.PublishingSessionRequestExecutor, Executor {

    public static ServerSessionRequestExecutor newInstance(
            Publisher publisher,
            ServerExecutor executor,
            long sessionId) {
        return newInstance(publisher, executor, processor(executor, sessionId), sessionId);
    }
    
    public static ServerSessionRequestExecutor newInstance(
            Publisher publisher,
            ServerExecutor executor,
            Processor<Message.ClientRequest, Message.ServerResponse> processor,
            long sessionId) {
        return new ServerSessionRequestExecutor(publisher, executor, processor, sessionId);
    }
    
    public static Processor<Message.ClientRequest, Message.ServerResponse> processor(
            ServerExecutor executor,
            long sessionId) {
        @SuppressWarnings("unchecked")
        Processor<Records.Request, Records.Response> responseProcessor = FilteredProcessors
                .newInstance(
                        DisconnectProcessor.filtered(sessionId, executor.sessions()),
                        FilteredProcessor.newInstance(
                                OpRequestProcessor.NotEqualsFilter
                                        .newInstance(OpCode.CLOSE_SESSION),
                                OpRequestProcessor.newInstance()));
        Processor<Records.Request, Records.Response> replyProcessor = RequestErrorProcessor.newInstance(responseProcessor);
        Processor<Message.ClientRequest, Message.ServerResponse> processor = SessionRequestProcessor.newInstance(replyProcessor, executor.zxids());
        return processor;
    }

    public static class SessionRequestTask extends ProcessorThunk<Message.ClientRequest, Message.ServerResponse> {
        public static SessionRequestTask newInstance(
                Processor<? super Message.ClientRequest, ? extends Message.ServerResponse> first,
                Message.ClientRequest second) {
            return new SessionRequestTask(first, second);
        }
        
        public SessionRequestTask(
                Processor<? super Message.ClientRequest, ? extends Message.ServerResponse> first,
                Message.ClientRequest second) {
            super(first, second);
        }}
    
    protected final Logger logger = LoggerFactory
            .getLogger(ServerSessionRequestExecutor.class);
    protected final long sessionId;
    protected final ServerExecutor executor;
    protected final Processor<Message.ClientRequest, Message.ServerResponse> processor;
    
    protected ServerSessionRequestExecutor(
            Publisher publisher,
            ServerExecutor executor,
            Processor<Message.ClientRequest, Message.ServerResponse> processor,
            long sessionId) {
        super(publisher);
        this.executor = executor;
        this.processor = processor;
        this.sessionId = sessionId;
        
        register(this);
    }
    
    public ServerExecutor executor() {
        return executor;
    }

    @Override
    public void execute(Runnable runnable) {
        executor().execute(runnable);
    }

    @Override
    public ListenableFuture<Message.ServerResponse> submit(Message.ClientRequest request) {
        return submit(request, PromiseTask.<Message.ServerResponse>newPromise());
    }
    
    @Override
    public ListenableFuture<Message.ServerResponse> submit(Message.ClientRequest request,
            Promise<Message.ServerResponse> promise) {
        touch();
        if (logger.isDebugEnabled()) {
            logger.debug(String.format("0x%s: Submitting %s", Long.toHexString(sessionId), request));
        }
        ListenableFutureTask<Message.ServerResponse> task = ListenableFutureTask.create(SessionRequestTask.newInstance(processor, request));
        execute(task);
        return task;
    }
    
    @Override
    public void post(Object event) {
        super.post(event);
    }

    @Subscribe
    public void handleSessionStateEvent(Session.State event) {
        if (Session.State.SESSION_EXPIRED == event) {
            try {
                submit(SessionRequestMessage.newInstance(0, Records.Requests.getInstance().get(OpCode.CLOSE_SESSION)));
            } catch (Exception e) {
                // TODO
                throw Throwables.propagate(e);
            }
        }
    }

    protected void touch() {
        executor().sessions().touch(sessionId);
    }
}
