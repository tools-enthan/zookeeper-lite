package edu.uw.zookeeper.protocol.client;

import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.engio.mbassy.common.IConcurrentSet;
import net.engio.mbassy.common.StrongConcurrentSet;

import com.google.common.util.concurrent.ListenableFuture;

import edu.uw.zookeeper.common.LoggingPromise;
import edu.uw.zookeeper.common.Promise;
import edu.uw.zookeeper.common.TimeValue;
import edu.uw.zookeeper.protocol.Message;
import edu.uw.zookeeper.protocol.ConnectMessage;
import edu.uw.zookeeper.protocol.Operation;
import edu.uw.zookeeper.protocol.ProtocolConnection;
import edu.uw.zookeeper.protocol.SessionListener;


public class OperationClientExecutor<C extends ProtocolConnection<? super Message.ClientSession, ? extends Operation.Response,?,?,?>>
    extends PendingQueueClientExecutor.Forwarding<Operation.Request, Message.ServerResponse<?>, PendingQueueClientExecutor.RequestTask<Operation.Request, Message.ServerResponse<?>>, C> {

    public static <C extends ProtocolConnection<? super Message.ClientSession, ? extends Operation.Response,?,?,?>> OperationClientExecutor<C> newInstance(
            ConnectMessage.Request request,
            C connection,
            ScheduledExecutorService executor) {
        return newInstance(
                request,
                AssignXidProcessor.newInstance(),
                connection,
                executor);
    }

    public static <C extends ProtocolConnection<? super Message.ClientSession, ? extends Operation.Response,?,?,?>> OperationClientExecutor<C> newInstance(
            ConnectMessage.Request request,
            AssignXidProcessor xids,
            C connection,
            ScheduledExecutorService executor) {
        return newInstance(
                ConnectTask.connect(connection, request),
                xids,
                connection,
                TimeValue.milliseconds(request.getTimeOut()),
                executor);
    }

    public static <C extends ProtocolConnection<? super Message.ClientSession, ? extends Operation.Response,?,?,?>> OperationClientExecutor<C> newInstance(
            ListenableFuture<ConnectMessage.Response> session,
            AssignXidProcessor xids,
            C connection,
            TimeValue timeOut,
            ScheduledExecutorService executor) {
        return new OperationClientExecutor<C>(
                xids,
                session,
                connection,
                timeOut,
                executor,
                new StrongConcurrentSet<SessionListener>(),
                connection,
                LogManager.getLogger(OperationClientExecutor.class));
    }

    protected final OperationActor actor;
    protected final AssignXidProcessor xids;
    
    protected OperationClientExecutor(
            AssignXidProcessor xids,
            ListenableFuture<ConnectMessage.Response> session,
            C connection,
            TimeValue timeOut,
            ScheduledExecutorService scheduler,
            IConcurrentSet<SessionListener> listeners,
            Executor executor,
            Logger logger) {
        super(session, connection, timeOut, scheduler, listeners);
        this.xids = xids;
        this.actor = new OperationActor(executor, logger);
    }

    @Override
    public ListenableFuture<Message.ServerResponse<?>> submit(
            Operation.Request request, Promise<Message.ServerResponse<?>> promise) {
        RequestTask<Operation.Request, Message.ServerResponse<?>> task = 
                RequestTask.of(request, LoggingPromise.create(logger(), promise));
        if (! send(task)) {
            task.cancel(true);
        }
        return task;
    }
    
    @Override
    protected OperationActor actor() {
        return actor;
    }

    protected class OperationActor extends ForwardingActor {

        protected OperationActor(
                Executor executor,
                Logger logger) {
            super(executor, logger);
        }

        @Override
        protected boolean apply(RequestTask<Operation.Request, Message.ServerResponse<?>> input) {
            if (! input.isDone()) {
                // Assign xids here so we can properly track message request -> response
                Message.ClientRequest<?> message = (Message.ClientRequest<?>) xids.apply(input.task());
                write(message, input.promise());
            }
            return true;
        }
    }
}
