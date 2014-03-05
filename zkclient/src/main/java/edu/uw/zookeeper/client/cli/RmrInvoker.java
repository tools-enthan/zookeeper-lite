package edu.uw.zookeeper.client.cli;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;

import org.apache.zookeeper.KeeperException;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import edu.uw.zookeeper.client.ClientExecutor;
import edu.uw.zookeeper.client.TreeFetcher;
import edu.uw.zookeeper.common.Pair;
import edu.uw.zookeeper.common.Processor;
import edu.uw.zookeeper.common.Promise;
import edu.uw.zookeeper.common.PromiseTask;
import edu.uw.zookeeper.common.SettableFuturePromise;
import edu.uw.zookeeper.data.Operations;
import edu.uw.zookeeper.data.ZNodePath;
import edu.uw.zookeeper.protocol.Operation;
import edu.uw.zookeeper.protocol.proto.OpCode;
import edu.uw.zookeeper.protocol.proto.Records;

public class RmrInvoker extends AbstractIdleService implements Invoker<RmrInvoker.Command> {

    @Invokes(commands={Command.class})
    public static RmrInvoker create(Shell shell) {
        return new RmrInvoker(shell);
    }

    public static enum Command {
        @CommandDescriptor(
                names = {"rmr", "deleteall"}, 
                description="Recursive delete", 
                arguments = {
                        @ArgumentDescriptor(token = TokenType.PATH)})
        RMR;
    }
    
    protected final Shell shell;
    protected final Set<ListenableFuture<DeleteRoot>> pending;
    
    public RmrInvoker(Shell shell) {
        this.shell = shell;
        this.pending = Collections.synchronizedSet(Sets.<ListenableFuture<DeleteRoot>>newHashSet());
    }

    @Override
    public void invoke(final Invocation<Command> input)
            throws Exception {
        ZNodePath root = (ZNodePath) input.getArguments()[1];
        ClientExecutor<? super Records.Request, ?, ?> client = shell.getEnvironment().get(ClientExecutorInvoker.CLIENT_KEY).getConnectionClientExecutor();
        final ListenableFuture<DeleteRoot> future = Futures.transform(
                TreeFetcher.<Set<ZNodePath>>builder().setClient(client).setResult(new ComputeLeaves()).setRoot(root).build(), new DeleteRoot(client, root));
        Futures.addCallback(future, new FutureCallback<DeleteRoot>(){
            @Override
            public void onSuccess(DeleteRoot result) {
                pending.remove(future);
                try {
                    shell.println(String.format("%s => OK", input));
                    shell.flush();
                } catch (IOException e) {
                    onFailure(e);
                }
            }
            @Override
            public void onFailure(Throwable t) {
                pending.remove(future);
                try {
                    shell.printThrowable(new RuntimeException(String.format("%s => FAILED (%s)", input, t)));
                } catch (IOException e) {
                }
                if (isRunning()) {
                    stopAsync();
                }
            }});
        pending.add(future);
    }

    @Override
    protected void startUp() throws Exception {
        for (Command command: Command.values()) {
            shell.getCommands().withCommand(command);
        }
    }

    @Override
    protected void shutDown() throws Exception {
        synchronized (pending) {
            for (ListenableFuture<?> e: Iterables.consumingIterable(pending)) {
                e.cancel(true);
            }
        }
    }

    protected static class ComputeLeaves implements Processor<Optional<Pair<Records.Request, ListenableFuture<? extends Operation.ProtocolResponse<?>>>>, Optional<Set<ZNodePath>>> {

        protected final Set<ZNodePath> leaves;
        
        public ComputeLeaves() {
            this.leaves = Sets.newHashSet();
        }
        
        @Override
        public synchronized Optional<Set<ZNodePath>> apply(
                Optional<Pair<Records.Request, ListenableFuture<? extends Operation.ProtocolResponse<?>>>> input)
                throws Exception {
            if (input.isPresent()) {
                Records.Response response = input.get().second().get().record();
                if (response instanceof Records.ChildrenGetter) {
                    if (((Records.ChildrenGetter) response).getChildren().isEmpty()) {
                        leaves.add(ZNodePath.of(((Records.PathGetter) input.get().first()).getPath()));
                    }
                }
                return Optional.absent();
            } else {
                return Optional.of(leaves);
            }
        }
    }
    
    protected static class DeleteRoot implements AsyncFunction<Optional<Set<ZNodePath>>, DeleteRoot> {

        protected final ClientExecutor<? super Records.Request, ?, ?> client;
        protected final ZNodePath root;
        
        public DeleteRoot(ClientExecutor<? super Records.Request, ?, ?> client, ZNodePath root) {
            this.client = client;
            this.root = root;
        }

        @Override
        public ListenableFuture<DeleteRoot> apply(Optional<Set<ZNodePath>> result) {
            if (result.isPresent()) {
                DeleteLeaves task = new DeleteLeaves(result.get(), SettableFuturePromise.<DeleteRoot>create());
                task.run();
                return task;
            } else {
                // TODO
                throw new UnsupportedOperationException();
            }
        }
            
        protected class DeleteLeaves extends PromiseTask<Set<ZNodePath>, DeleteRoot> implements FutureCallback<ZNodePath> {
            
            public DeleteLeaves(Set<ZNodePath> task, Promise<DeleteRoot> promise) {
                super(task, promise);
            }
            
            public synchronized void run() {
                if (task().isEmpty()) {
                    set(DeleteRoot.this);
                } else {
                    for (ZNodePath p: ImmutableSet.copyOf(task())) {
                        DeleteLeaf operation = new DeleteLeaf(p);
                        operation.run();
                    }
                }
            }

            @Override
            public synchronized void onSuccess(ZNodePath leaf) {
                task().remove(leaf);
                ZNodePath parent = (ZNodePath) leaf.head();
                if (parent.startsWith(root)) {
                    boolean empty = true;
                    for (ZNodePath p: task()) {
                        if (p.startsWith(parent)) {
                            empty = false;
                            break;
                        }
                    }
                    if (empty) {
                        task().add(parent);
                        DeleteLeaf operation = new DeleteLeaf(parent);
                        operation.run();
                    }
                }
                if (task().isEmpty()) {
                    set(DeleteRoot.this);
                }
            }

            @Override
            public void onFailure(Throwable t) {
                setException(t);
            }

            protected class DeleteLeaf implements FutureCallback<Operation.ProtocolResponse<?>> {
                
                protected final ZNodePath leaf;
                
                public DeleteLeaf(ZNodePath leaf) {
                    this.leaf = leaf;
                }
                
                public void run() {
                    Futures.addCallback(
                            client.submit(Operations.Requests.delete().setPath(leaf).build()), 
                            this);
                }

                @Override
                public void onSuccess(Operation.ProtocolResponse<?> result) {
                    if (result.record().opcode() == OpCode.DELETE) {
                        DeleteLeaves.this.onSuccess(leaf);
                    } else {
                        // TODO
                        onFailure(KeeperException.create(((Operation.Error) result.record()).error()));
                    }
                }

                @Override
                public void onFailure(Throwable t) {
                    DeleteLeaves.this.onFailure(t);
                }
            }
        }
    }
}