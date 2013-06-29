package edu.uw.zookeeper.server;

import java.net.SocketAddress;
import java.util.AbstractMap;
import java.util.Map;

import com.typesafe.config.Config;

import edu.uw.zookeeper.AbstractMain;
import edu.uw.zookeeper.RuntimeModule;
import edu.uw.zookeeper.ServerInetAddressView;
import edu.uw.zookeeper.ServerView;
import edu.uw.zookeeper.net.ServerConnectionFactory;
import edu.uw.zookeeper.netty.server.NettyServerModule;
import edu.uw.zookeeper.protocol.Message;
import edu.uw.zookeeper.protocol.server.ServerCodecConnection;
import edu.uw.zookeeper.util.Application;
import edu.uw.zookeeper.util.Arguments;
import edu.uw.zookeeper.util.Configuration;
import edu.uw.zookeeper.util.DefaultsFactory;
import edu.uw.zookeeper.util.ParameterizedFactory;
import edu.uw.zookeeper.util.ServiceApplication;
import edu.uw.zookeeper.util.ServiceMonitor;

public enum ServerApplicationModule implements ParameterizedFactory<RuntimeModule, Application> {
    INSTANCE;
    
    public static ServerApplicationModule getInstance() {
        return INSTANCE;
    }

    public static class ConfigurableServerAddressViewFactory implements DefaultsFactory<Configuration, ServerInetAddressView> {

        public static ConfigurableServerAddressViewFactory newInstance() {
            return newInstance(DEFAULT_CONFIG_PATH, DEFAULT_CONFIG_KEY, DEFAULT_ARG, DEFAULT_ADDRESS, DEFAULT_PORT);
        }

        public static ConfigurableServerAddressViewFactory newInstance(
                String arg, String configKey, String configPath, String defaultAddress, int defaultPort) {
            return new ConfigurableServerAddressViewFactory(configPath,configKey, arg, defaultAddress, defaultPort);
        }
        
        public static final String DEFAULT_ARG = "clientAddress";
        public static final String DEFAULT_CONFIG_KEY = "ClientAddress";
        public static final String DEFAULT_CONFIG_PATH = "";
        public static final String DEFAULT_ADDRESS = "";
        public static final int DEFAULT_PORT = 2181;

        private final String configPath;
        private final String configKey;
        private final String arg;
        private final String defaultAddress;
        private final int defaultPort;
        
        public ConfigurableServerAddressViewFactory(
                String configPath, String configKey, String arg, String defaultAddress, int defaultPort) {
            this.arg = arg;
            this.configKey = configKey;
            this.configPath = configPath;
            this.defaultAddress = defaultAddress;
            this.defaultPort = defaultPort;
        }
        
        @Override
        public ServerInetAddressView get() {
            return ServerInetAddressView.of(
                    defaultAddress, defaultPort);
        }

        @Override
        public ServerInetAddressView get(Configuration value) {
            Arguments arguments = value.asArguments();
            if (! arguments.has(arg)) {
                arguments.add(arguments.newOption(arg, "Address"));
            }
            arguments.parse();
            Map.Entry<String, String> args = new AbstractMap.SimpleImmutableEntry<String,String>(arg, configKey);
            @SuppressWarnings("unchecked")
            Config config = value.withArguments(configPath, args);
            if (config.hasPath(configKey)) {
            String input = config.getString(configKey);
                return ServerInetAddressView.fromString(input);
            } else {
                return get();
            }
        }
    }
    
    @Override
    public Application get(RuntimeModule runtime) {
        ServiceMonitor monitor = runtime.serviceMonitor();
        AbstractMain.MonitorServiceFactory monitorsFactory = AbstractMain.monitors(monitor);

        NettyServerModule nettyServer = NettyServerModule.newInstance(runtime);

        SessionParametersPolicy policy = DefaultSessionParametersPolicy.create(runtime.configuration());
        ExpiringSessionManager sessions = ExpiringSessionManager.newInstance(runtime.publisherFactory().get(), policy);
        ExpireSessionsTask expires = monitorsFactory.apply(ExpireSessionsTask.newInstance(sessions, runtime.executors().asScheduledExecutorServiceFactory().get(), runtime.configuration()));

        ServerExecutor serverExecutor = ServerExecutor.newInstance(runtime.executors().asListeningExecutorServiceFactory().get(), runtime.publisherFactory(), sessions);

        ParameterizedFactory<SocketAddress, ? extends ServerConnectionFactory<Message.ServerMessage, ServerCodecConnection>> serverConnectionFactory = 
                nettyServer.get(
                        ServerCodecConnection.codecFactory(),
                        ServerCodecConnection.factory());
        ServerView.Address<?> address = ConfigurableServerAddressViewFactory.newInstance().get(runtime.configuration());
        ServerConnectionFactory<Message.ServerMessage, ServerCodecConnection> serverConnections = 
                monitorsFactory.apply(serverConnectionFactory.get(address.get()));
        
        ServerConnectionListener server = ServerConnectionListener.newInstance(serverConnections, serverExecutor, serverExecutor, serverExecutor);
        
        return ServiceApplication.newInstance(runtime.serviceMonitor());
    }

}
