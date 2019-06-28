package com.rtbhouse.grpc.loadbalancer.distributed;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The purpose of this class is purely to demonstrate how to use ClusterConnector in an existing
 * server. You can run a plenty of them, and then run the hello-client to see how the requests are
 * being distributed. Example usage: LOCAL=1 java -jar /
 * examples/example-server/target/example-server-1.0-shaded.jar 9090
 */
public class ExampleServer {
  private static final Logger logger = LoggerFactory.getLogger(ExampleServer.class);
  private int port;
  private Server server;
  private ArrayList<BindableService> services = new ArrayList<>();
  private ClusterConnector connector;

  public ExampleServer(int port, ClusterConnector connector, BindableService... services) {
    this.port = port;
    this.connector = connector;
    Collections.addAll(this.services, services);
  }

  public void start() throws IOException {
    ServerBuilder<?> serverBuilder = ServerBuilder.forPort(this.port);
    for (BindableService service : this.services) {
      serverBuilder.addService(service);
    }

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  System.err.println("*** shutting down gRPC server since JVM is shutting down");
                  // Use stderr here since the logger may have been reset by its JVM shutdown hook.
                  this.stop();
                  System.err.println("*** server shut down");
                }));

    server = serverBuilder.build().start();
    logger.info("Server started, listening on {}", port);
  }

  public void stop() {
    if (server != null) {
      server.shutdown();
    }
    if (connector != null) {
      connector.stop(false);
    }
  }

  /** Await termination on the main thread since the grpc library uses daemon threads. */
  public void blockUntilShutdown() throws InterruptedException {
    if (server != null) {
      server.awaitTermination();
    }
  }

  public static void main(String[] args) throws IOException {
    int port = Integer.parseInt(args[0]);
    ClusterConnector connector =
        new ClusterConnector.Builder()
            .natIsUsed(true)
            .setServerPort(port) // watch out for ports used by Hazelcast (5701 - 5801)
            .useMemberAddresses(new String[] {"127.0.0.1"})
            // .useDnsServiceName("hello.mimgrpc.me") // alternative for useMemberAddresses
            .setLeaderStepDownPeriod(30000)
            .setServedServicesNames(new String[] {"hello.mimgrpc.me:5000"})
            .build();

    connector.start();
    LoadBalanceService lbService = connector.getLBService();
    ExampleServer server =
        new ExampleServer(port, connector, lbService, new ExampleGreeterService(port));

    server.start();
  }
}
