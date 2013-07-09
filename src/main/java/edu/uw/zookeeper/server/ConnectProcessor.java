package edu.uw.zookeeper.server;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Predicate;

import edu.uw.zookeeper.Session;
import edu.uw.zookeeper.protocol.Message;
import edu.uw.zookeeper.protocol.ConnectMessage;
import edu.uw.zookeeper.util.Processor;
import edu.uw.zookeeper.util.Processors;
import edu.uw.zookeeper.util.Reference;

public class ConnectProcessor 
        implements Processor<ConnectMessage.Request, ConnectMessage.Response> {

    public static class Filtered implements Processors.FilteringProcessor<Message.Client, Message.Server> {
        public static enum Filter implements Predicate<Message.Client> {
            INSTANCE;
            
            public static Filter getInstance() {
                return INSTANCE;
            }
            
            @Override
            public boolean apply(@Nullable Message.Client input) {
                return (input instanceof ConnectMessage.Request);
            }
        }

        public static Filtered newInstance(Processor<ConnectMessage.Request, ConnectMessage.Response> processor) {
            return new Filtered(processor);
        }

        private final Processor<ConnectMessage.Request, ConnectMessage.Response> processor;
        
        protected Filtered(Processor<ConnectMessage.Request, ConnectMessage.Response> processor) {
            this.processor = processor;
        }
        
        @Override
        public Message.Server apply(Message.Client input) throws Exception {
            if (filter().apply(input)) {
                return processor.apply((ConnectMessage.Request)input);
            } else {
                return null;
            }
        }

        @Override
        public Predicate<? super Message.Client> filter() {
            return Filter.getInstance();
        }
    }
    
    public static Filtered filtered(
            SessionTable sessions,
            Reference<Long> lastZxid) {
        return Filtered.newInstance(newInstance(sessions, lastZxid));
    }
    
    public static ConnectProcessor newInstance(
            SessionTable sessions,
            Reference<Long> lastZxid) {
        return new ConnectProcessor(sessions, lastZxid);
    }
    
    protected final Logger logger = LoggerFactory
            .getLogger(ConnectProcessor.class);
    protected final SessionTable sessions;
    protected final Reference<Long> lastZxid;

    protected ConnectProcessor(
            SessionTable sessions,
            Reference<Long> lastZxid) {
        this.sessions = sessions;
        this.lastZxid = lastZxid;
    }

    public Reference<Long> lastZxid() {
        return lastZxid;
    }
    
    public SessionTable sessions() {
        return sessions;
    }

    @Override
    public ConnectMessage.Response apply(ConnectMessage.Request request) {
        // emulating the the behavior of ZooKeeperServer,
        // which is to just close the connection
        // without replying when the zxid is out of sync
        long myZxid = lastZxid().get();
        if (request.getLastZxidSeen() > myZxid) {
            throw new IllegalStateException(String.format("Zxid 0x%x > 0x%x",
                    Long.toHexString(request.getLastZxidSeen()),
                    Long.toHexString(myZxid)));
        }
        
        // TODO: readOnly?
        Session session = null;
        if (request instanceof ConnectMessage.Request.NewRequest) {
            session = sessions().validate(request.toParameters());
        } else if (request instanceof ConnectMessage.Request.RenewRequest) {
            try {
                session = sessions().validate(request.toSession());
            } catch (Exception e) {
                session = null;
            }
        } else {
            throw new IllegalArgumentException(request.toString());
        }
        ConnectMessage.Response response = (session == null)
            ? ConnectMessage.Response.Invalid.newInstance(request.getReadOnly(), request.getWraps())
            : ConnectMessage.Response.Valid.newInstance(session, request.getReadOnly(), request.getWraps());
        return response;
    }
}
