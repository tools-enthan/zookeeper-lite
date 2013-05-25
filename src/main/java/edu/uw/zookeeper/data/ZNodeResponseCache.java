package edu.uw.zookeeper.data;


import static com.google.common.base.Preconditions.checkArgument;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;

import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.collect.Maps;

import edu.uw.zookeeper.Event;
import edu.uw.zookeeper.client.ClientExecutor;
import edu.uw.zookeeper.data.ZNodeLabelTrie.Pointer;
import edu.uw.zookeeper.data.ZNodeLabelTrie.SimplePointer;
import edu.uw.zookeeper.protocol.OpSessionResult;
import edu.uw.zookeeper.protocol.Operation;
import edu.uw.zookeeper.protocol.SessionReplyWrapper;
import edu.uw.zookeeper.protocol.SessionRequestWrapper;
import edu.uw.zookeeper.protocol.client.ClientProtocolConnection;
import edu.uw.zookeeper.protocol.client.ClientProtocolConnection.RequestFuture;
import edu.uw.zookeeper.protocol.proto.IGetACLResponse;
import edu.uw.zookeeper.protocol.proto.IGetDataResponse;
import edu.uw.zookeeper.protocol.proto.IMultiRequest;
import edu.uw.zookeeper.protocol.proto.IMultiResponse;
import edu.uw.zookeeper.protocol.proto.ISetACLRequest;
import edu.uw.zookeeper.protocol.proto.ISetDataRequest;
import edu.uw.zookeeper.protocol.proto.Records;
import edu.uw.zookeeper.protocol.proto.Records.MultiOpRequest;
import edu.uw.zookeeper.protocol.proto.Records.MultiOpResponse;
import edu.uw.zookeeper.util.ForwardingEventful;
import edu.uw.zookeeper.util.ForwardingPromise;
import edu.uw.zookeeper.util.Promise;
import edu.uw.zookeeper.util.Publisher;
import edu.uw.zookeeper.util.SettableFuturePromise;

/**
 * Only caches the results of operations submitted through this wrapper.
 * 
 * TODO: create eventful and non-eventful versions
 * 
 * TODO: need to compare the zxids when creating/deleting nodes as well
 * and create an event for tree structure changes (adds/deletes)
 */
public class ZNodeResponseCache<E extends ZNodeResponseCache.NodeCache<E>> extends ForwardingEventful implements ClientExecutor {

    public static ZNodeResponseCache<SimpleZNodeCache> newInstance(
            Publisher publisher, ClientExecutor client) {
        return newInstance(publisher, client, SimpleZNodeCache.root());
    }
    
    public static <E extends ZNodeResponseCache.AbstractNodeCache<E>> ZNodeResponseCache<E> newInstance(
            Publisher publisher, ClientExecutor client, E root) {
        return newInstance(publisher, client, ZNodeLabelTrie.of(root));
    }
    
    public static <E extends ZNodeResponseCache.AbstractNodeCache<E>> ZNodeResponseCache<E> newInstance(
            Publisher publisher, ClientExecutor client, ZNodeLabelTrie<E> trie) {
        return new ZNodeResponseCache<E>(publisher, client, trie);
    }

    public static enum View {
        DATA(Records.DataHolder.class), 
        ACL(Records.AclHolder.class), 
        STAT(Records.StatHolder.class);
        
        public static View ofType(Class<? extends Records.View> type) {
            for (View v: values()) {
                if (type == v.type()) {
                    return v;
                }
            }
            throw new IllegalArgumentException(type.toString());
        }
        
        private final Class<? extends Records.View> type;
        
        private View(Class<? extends Records.View> type) {
            this.type = type;
        }
        
        public Class<? extends Records.View> type() {
            return type;
        }
    }
    
    @Event
    public static class ViewUpdate {
        
        public static ViewUpdate ifUpdated(
                View view, 
                StampedReference<? extends Records.View> previousValue, 
                StampedReference<? extends Records.View> updatedValue) {
            if (updatedValue.stamp().compareTo(previousValue.stamp()) < 0) {
                return null;
            }
            switch (view) {
            case DATA:
            {
                byte[] prev = ((Records.DataHolder)previousValue.get()).getData();
                byte[] updated = ((Records.DataHolder)updatedValue.get()).getData();
                if (! Arrays.equals(prev, updated)) {
                    return null;
                }
                break;
            }
            case ACL:
            {
                List<ACL> prev = ((Records.AclHolder)previousValue.get()).getAcl();
                List<ACL> updated = ((Records.AclHolder)updatedValue.get()).getAcl();
                if (! prev.equals(updated)) {
                    return null;
                }
                break;
            }
            case STAT:
            {
                Stat prev = ((Records.StatHolder)previousValue.get()).getStat();
                Stat updated = ((Records.StatHolder)updatedValue.get()).getStat();
                if (! prev.equals(updated)) {
                    return null;
                }
                break;
            }
            default:
                throw new AssertionError();
            }
            return of(view, previousValue, updatedValue);
        }
        
        public static ViewUpdate of(
                View view, 
                StampedReference<? extends Records.View> previousValue, 
                StampedReference<? extends Records.View> updatedValue) {
            return new ViewUpdate(view, previousValue, updatedValue);
        }
        
        private final View view;
        private final StampedReference<? extends Records.View> previousValue;
        private final StampedReference<? extends Records.View> updatedValue;
        
        public ViewUpdate(
                View view, 
                StampedReference<? extends Records.View> previousValue, 
                StampedReference<? extends Records.View> updatedValue) {
            this.view = view;
            this.previousValue = previousValue;
            this.updatedValue = updatedValue;
        }
        
        public View view() {
            return view;
        }
        
        public StampedReference<? extends Records.View> previousValue() {
            return previousValue;
        }
        
        public StampedReference<? extends Records.View> updatedValue() {
            return updatedValue;
        }
    }
    
    public static interface NodeCache<E extends NodeCache<E>> extends ZNodeLabelTrie.Node<E> {

        <T extends Records.View> StampedReference<? extends T> asView(View view);

        <T extends Records.View> StampedReference<? extends T> update(View view, StampedReference<T> value);
    }
    
    public abstract static class AbstractNodeCache<E extends AbstractNodeCache<E>> extends ZNodeLabelTrie.AbstractNode<E> implements NodeCache<E> {

        protected final Map<View, StampedReference.Updater<? extends Records.View>> views;

        protected AbstractNodeCache(
                Optional<ZNodeLabelTrie.Pointer<E>> parent) {
            this(parent,
                    Collections.synchronizedMap(Maps.<View, StampedReference.Updater<? extends Records.View>>newEnumMap(View.class)));
        }
        protected AbstractNodeCache(
                Optional<ZNodeLabelTrie.Pointer<E>> parent,
                Map<View, StampedReference.Updater<? extends Records.View>> views) {
            super(parent);
            this.views = views;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends Records.View> StampedReference<? extends T> asView(View view) {
            StampedReference.Updater<? extends Records.View> updater = views.get(view);
            if (updater != null) {
                return (StampedReference<? extends T>) updater.get();
            } else {
                return null;
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends Records.View> StampedReference<? extends T> update(View view, StampedReference<T> value) {
            StampedReference.Updater<T> updater = (StampedReference.Updater<T>) views.get(view);
            if (updater == null) {
                synchronized (views) {
                    if (! views.containsKey(view)) {
                        updater = StampedReference.Updater.<T>newInstance(value);
                        StampedReference.Updater<? extends Records.View> prev = (StampedReference.Updater<T>) views.put(view, updater);
                        assert (prev == null);
                    } else {
                        updater = (StampedReference.Updater<T>) views.get(view);
                    }
                }
            }
            return updater.setIfGreater(value);
        }

        @Override
        public String toString() {
            return Objects.toStringHelper(this)
                    .add("path", path())
                    .add("children", children.keySet())
                    .add("views", views).toString();
        }
    }
    
    public static class SimpleZNodeCache extends AbstractNodeCache<SimpleZNodeCache> {

        public static SimpleZNodeCache root() {
            return new SimpleZNodeCache(Optional.<Pointer<SimpleZNodeCache>>absent());
        }

        protected SimpleZNodeCache(
                Optional<Pointer<SimpleZNodeCache>> parent) {
            super(parent);
        }

        @Override
        protected SimpleZNodeCache newChild(ZNodeLabel.Component label) {
            Pointer<SimpleZNodeCache> childPointer = SimplePointer.of(label, this);
            return new SimpleZNodeCache(Optional.of(childPointer));
        }
    }
    
    protected class PromiseWrapper extends ForwardingPromise<Operation.SessionResult> {

        protected final Promise<Operation.SessionResult> delegate;
        
        protected PromiseWrapper() {
            this(SettableFuturePromise.<Operation.SessionResult>create());
        }

        protected PromiseWrapper(Promise<Operation.SessionResult> delegate) {
            this.delegate = delegate;
        }
        
        @Override
        public boolean set(Operation.SessionResult result) {
            if (! isDone()) {
                handleResult(result);
            }
            return super.set(result);
        }
        
        @Override
        protected Promise<Operation.SessionResult> delegate() {
            return delegate;
        }
    }
    
    protected final ZNodeLabelTrie<E> trie;
    protected final ClientExecutor client;
    
    protected ZNodeResponseCache(
            Publisher publisher, ClientExecutor client, ZNodeLabelTrie<E> trie) {
        super(publisher);
        this.trie = trie;
        this.client = client;
    }
    
    public ClientExecutor asClient() {
        return client;
    }
    
    public ZNodeLabelTrie<E> asTrie() {
        return trie;
    }

    @Override
    public void register(Object object) {
        asClient().register(object);
        super.register(object);
    }

    @Override
    public void unregister(Object object) {
        asClient().unregister(object);
        super.unregister(object);
    }

    @Override
    public ClientProtocolConnection.RequestFuture submit(Operation.Request request) {
        // wrapper so that we can apply changes before our client sees them
        return client.submit(request, new PromiseWrapper());
    }
    
    @Override
    public RequestFuture submit(Operation.Request request, Promise<Operation.SessionResult> promise) {
        // wrapper so that we can apply changes before our client sees them
        return client.submit(request, new PromiseWrapper(promise));
        
    }

    protected boolean handleResult(Operation.SessionResult result) {
        boolean changed = false;
        long zxid = result.reply().zxid();
        Operation.Reply reply = result.reply().reply();
        Operation.Request request = result.request().request();
        switch (request.opcode()) {
        case CREATE:
        case CREATE2:
        {
            if (reply instanceof Operation.Response) {
                Records.PathHolder responseRecord = (Records.PathHolder) ((Operation.RecordHolder<?>)reply).asRecord();
                E node = asTrie().put(responseRecord.getPath());
                Records.CreateRecord record = (Records.CreateRecord)((Operation.RecordHolder<?>)request).asRecord();
                StampedReference<Records.CreateRecord> stampedRequest = StampedReference.of(zxid, record);
                changed = changed | update(node, stampedRequest);
                if (responseRecord instanceof Records.StatRecord) {
                    StampedReference<Records.StatRecord> stampedResponse = StampedReference.of(zxid, (Records.StatRecord)responseRecord);
                    changed = changed | update(node, stampedResponse);
                }
            }
            break;
        }
        case DELETE:
        {
            if (reply instanceof Operation.Response) {
                Records.PathHolder requestRecord = (Records.PathHolder)((Operation.RecordHolder<?>)request).asRecord();
                asTrie().remove(requestRecord.getPath());
                break;
            }
        }
        case EXISTS:
        {
            Records.PathHolder requestRecord = (Records.PathHolder)((Operation.RecordHolder<?>)request).asRecord();
            if (reply instanceof Operation.Response) {
                E node = asTrie().put(requestRecord.getPath());
                StampedReference<Records.StatRecord> stampedResponse = StampedReference.of(zxid, (Records.StatRecord)((Operation.RecordHolder<?>)reply).asRecord());
                changed = changed | update(node, stampedResponse);
            } else if (KeeperException.Code.NONODE == ((Operation.Error)reply).error()) {
                E node = asTrie().remove(requestRecord.getPath());
                changed = (node != null);
            }
            break;
        }
        case GET_ACL:
        {
            Records.PathHolder requestRecord = (Records.PathHolder)((Operation.RecordHolder<?>)request).asRecord();
            if (reply instanceof Operation.Response) {
                E node = asTrie().put(requestRecord.getPath());
                StampedReference<IGetACLResponse> stampedResponse = StampedReference.of(
                        result.reply().zxid(), (IGetACLResponse)((Operation.RecordHolder<?>)reply).asRecord());
                changed = changed | update(node, stampedResponse);
            } else if (KeeperException.Code.NONODE == ((Operation.Error)reply).error()) {
                E node = asTrie().remove(requestRecord.getPath());
                changed = (node != null);
            }
            break;
        }
        case GET_CHILDREN:
        case GET_CHILDREN2:        
        {
            Records.PathHolder requestRecord = (Records.PathHolder)((Operation.RecordHolder<?>)request).asRecord();
            if (reply instanceof Operation.Response) {
                E node = asTrie().put(requestRecord.getPath());
                Records.ChildrenHolder responseRecord = (Records.ChildrenHolder) ((Operation.RecordHolder<?>)reply).asRecord();
                for (String child: responseRecord.getChildren()) {
                    node.put(child);
                }
                if (responseRecord instanceof Records.StatRecord) {
                    StampedReference<Records.StatRecord> stampedResponse = StampedReference.of(zxid, (Records.StatRecord)responseRecord);
                    changed = changed | update(node, stampedResponse);
                }
            } else if (KeeperException.Code.NONODE == ((Operation.Error)reply).error()) {
                E node = asTrie().remove(requestRecord.getPath());
                changed = (node != null);
            }
            break;
        }
        case GET_DATA:
        {
            Records.PathHolder requestRecord = (Records.PathHolder)((Operation.RecordHolder<?>)request).asRecord();
            if (reply instanceof Operation.Response) {
                E node = asTrie().put(requestRecord.getPath());
                StampedReference<IGetDataResponse> stampedResponse = StampedReference.of(
                        result.reply().zxid(), (IGetDataResponse)((Operation.RecordHolder<?>)reply).asRecord());
                changed = changed | update(node, stampedResponse);
            } else if (KeeperException.Code.NONODE == ((Operation.Error)reply).error()) {
                E node = asTrie().remove(requestRecord.getPath());
                changed = (node != null);
            }
            break;
        }
        case MULTI:
        {
            if (reply instanceof Operation.Response) {
                int xid = result.request().xid();
                IMultiRequest requestRecord = (IMultiRequest) ((Operation.RecordHolder<?>)request).asRecord();
                IMultiResponse responseRecord = (IMultiResponse) ((Operation.RecordHolder<?>)reply).asRecord();
                Iterator<MultiOpRequest> requests = requestRecord.iterator();
                Iterator<MultiOpResponse> responses = responseRecord.iterator();
                while (requests.hasNext()) {
                    checkArgument(responses.hasNext());
                    Operation.SessionResult nestedResult = OpSessionResult.of(
                            SessionRequestWrapper.newInstance(xid, requests.next()), 
                            SessionReplyWrapper.create(xid, zxid, responses.next()));
                    changed = changed | handleResult(nestedResult);
                }
            } else {
                // TODO
            }
            break;
        }
        case SET_ACL:
        {
            ISetACLRequest requestRecord = (ISetACLRequest) ((Operation.RecordHolder<?>)request).asRecord();
            if (reply instanceof Operation.Response) {
                E node = asTrie().put(requestRecord.getPath());
                changed = changed | update(node, StampedReference.of(zxid, requestRecord));
                Records.StatHolder responseRecord = (Records.StatHolder) ((Operation.RecordHolder<?>)reply).asRecord();
                changed = changed | update(node, StampedReference.of(zxid, responseRecord));
            } else if (KeeperException.Code.NONODE == ((Operation.Error)reply).error()) {
                E node = asTrie().remove(requestRecord.getPath());
                changed = (node != null);
            }
            break;
        }
        case SET_DATA:
        {
            ISetDataRequest requestRecord = (ISetDataRequest) ((Operation.RecordHolder<?>)request).asRecord();
            if (reply instanceof Operation.Response) {
                E node = asTrie().put(requestRecord.getPath());
                changed = changed | update(node, StampedReference.of(zxid, requestRecord));
                Records.StatHolder responseRecord = (Records.StatHolder) ((Operation.RecordHolder<?>)reply).asRecord();
                changed = changed | update(node, StampedReference.of(zxid, responseRecord));
            } else if (KeeperException.Code.NONODE == ((Operation.Error)reply).error()) {
                E node = asTrie().remove(requestRecord.getPath());
                changed = (node != null);
            }
            break;
        }
        default:
            break;
        }
        return changed;
    }
    
    protected boolean update(E node, StampedReference<? extends Records.View> value) {
        boolean changed = false;
        for (View view: View.values()) {
            if (view.type().isInstance(value.get())) {
                StampedReference<? extends Records.View> prev = node.update(view, value);
                ViewUpdate event = ViewUpdate.ifUpdated(view, prev, value);
                if (event != null) {
                    changed = true;
                    post(event);
                }
            }
        }
        return changed;
    }
}
