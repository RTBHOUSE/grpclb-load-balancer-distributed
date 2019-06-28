package com.rtbhouse.grpc.loadbalancer.distributed;

import com.rtbhouse.grpc.loadbalancer.tests.EchoGrpc;
import com.rtbhouse.grpc.loadbalancer.tests.EchoReply;
import com.rtbhouse.grpc.loadbalancer.tests.EchoRequest;
import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RunWith(MockitoJUnitRunner.class)
public class IntegrationTest {
  private static final Logger logger = LoggerFactory.getLogger(IntegrationTest.class);

  private AtomicInteger s1ResponseCount = new AtomicInteger(0);
  private AtomicInteger s2ResponseCount = new AtomicInteger(0);
  private AtomicInteger s3ResponseCount = new AtomicInteger(0);

  @BeforeClass
  public static void enableGrpclb() {
    /* When you have "normal" command-line program, you can enable grpclb through command-line
     * argument: -Dio.grpc.internal.DnsNameResolverProvider.enable_grpclb=true*/
    System.setProperty("io.grpc.internal.DnsNameResolverProvider.enable_grpclb", "true");
  }

  @Before
  public void zeroCounters() {
    s1ResponseCount = new AtomicInteger(0);
    s2ResponseCount = new AtomicInteger(0);
    s3ResponseCount = new AtomicInteger(0);
  }

  @Test
  public void simpleTest() throws IOException, InterruptedException {
    logger.info("Starting simple test");
    TestServer server = new TestServer(9090, new EchoService("1"));
    server.start();
    logger.info("Server 1 started at port 9090 with weight 100");

    logger.info("Starting client, making requests every 0,1s for 10 seconds");
    Thread clientThread = new SimpleEchoClient(10);
    clientThread.start();
    Thread.sleep(3000);

    clientThread.join();

    logger.info("Stopping server at 9090");
    server.stop();
    server.blockUntilShutdown();

    Assert.assertEquals(10, s1ResponseCount.get());
  }

  @Test
  public void differentWeightsTest() throws IOException, InterruptedException {
    logger.info("Starting different weights test");
    TestServer server1 = new TestServer(9090, BackendServer.DEFAULT_WEIGHT, new EchoService("1"));
    TestServer server2 =
        new TestServer(9091, 3 * BackendServer.DEFAULT_WEIGHT, new EchoService("2"));
    server1.start();
    logger.info("Server 1 started at port 9090 with weight 100");
    server2.start();
    logger.info("Server 2 started at port 9091 with weight 300");

    logger.info("Starting client, making requests every 0,1s for 10 seconds");
    Thread clientThread = new SimpleEchoClient(100);
    clientThread.start();
    clientThread.join();

    logger.info("Stopping server 1 at 9090");
    server1.stop();
    server1.blockUntilShutdown();
    logger.info("Stopping server 2 at 9091");
    server2.stop();
    server2.blockUntilShutdown();

    Assert.assertEquals(100, s1ResponseCount.get() + s2ResponseCount.get());
    Assert.assertTrue(Math.abs(3 * s1ResponseCount.get() - s2ResponseCount.get()) < 25);
  }

  @Test
  public void failingServersTest() throws IOException, InterruptedException {
    logger.info("Starting failing servers test");
    TestServer server1 = new TestServer(9090, new EchoService("1"));
    TestServer server2 = new TestServer(9091, new EchoService("2"));
    server1.start();
    logger.info("Server 1 started at port 9090 with weight 100");
    server2.start();
    logger.info("Server 2 started at port 9091 with weight 100");
    Thread.sleep(1000);

    logger.info("Starting client, making requests every 0,1s for 20 seconds");
    Thread clientThread = new SimpleEchoClient(200);
    clientThread.start();
    Thread.sleep(3000);

    logger.info("Stopping server 1 at 9090");
    server1.stop();
    server1.blockUntilShutdown();
    logger.info("Server 1 down");
    Thread.sleep(2000);

    logger.info("Starting server 1 at 9090 again");
    server1 = new TestServer(9090, new EchoService("1"));
    server1.start();
    logger.info("Started server 1");
    Thread.sleep(5000);

    logger.info("Stopping server 2 at 9091");
    server2.stop();
    server2.blockUntilShutdown();
    logger.info("Server 2 down");

    clientThread.join();

    logger.info("Stopping server 1 at 9090");
    server1.stop();
    server1.blockUntilShutdown();
    logger.info("Server 1 down");
    Thread.sleep(3000);

    Assert.assertEquals(200, s1ResponseCount.get() + s2ResponseCount.get());
    Assert.assertTrue(s1ResponseCount.get() > 0 && s2ResponseCount.get() > 0);
  }

  @Test
  public void serverSubstitutionTest() throws IOException, InterruptedException {
    logger.info("Starting server substitution test");
    TestServer server1 = new TestServer(9090, new EchoService("1"));
    TestServer server2 = new TestServer(9091, new EchoService("2"));
    server1.start();
    logger.info("Server 1 started at port 9090 with weight 100");

    logger.info("Starting client, making requests every 0,1s for 15 seconds");
    Thread clientThread = new SimpleEchoClient(150);
    clientThread.start();

    server2.start();
    logger.info("Server 2 started at port 9091 with weight 100");

    Thread.sleep(5000);

    logger.info("Stopping server 1 at 9090");
    server1.stop();
    server1.blockUntilShutdown();
    logger.info("Server 1 down");
    Thread.sleep(2000);

    clientThread.join();

    logger.info("Stopping server 2 at 9091");
    server2.stop();
    server2.blockUntilShutdown();
    logger.info("Server 2 down");
    Thread.sleep(3000);

    Assert.assertEquals(150, s1ResponseCount.get() + s2ResponseCount.get());
  }

  @Test
  public void multipleClientsTest() throws IOException, InterruptedException {
    logger.info("Starting multiple clients test");
    TestServer server1 = new TestServer(9090, new EchoService("1"));
    TestServer server2 = new TestServer(9091, new EchoService("2"));
    server1.start();
    logger.info("Server 1 started at port 9090 with weight 100");
    server2.start();
    logger.info("Server 2 started at port 9091 with weight 100");
    Thread.sleep(1000);

    logger.info("Starting client 1, making requests every 0,1s for 5 seconds");
    Thread clientThread1 = new SimpleEchoClient(50);
    clientThread1.start();

    logger.info("Starting client 2, making requests every 0,1s for 4 seconds");
    Thread clientThread2 = new SimpleEchoClient(40);
    clientThread2.start();

    clientThread1.join();
    clientThread2.join();

    logger.info("Stopping server 1 at 9090");
    server1.stop();
    server1.blockUntilShutdown();
    logger.info("Server 1 down");

    logger.info("Stopping server 2 at 9091");
    server2.stop();
    server2.blockUntilShutdown();
    logger.info("Server 2 down");

    Assert.assertEquals(90, s1ResponseCount.get() + s2ResponseCount.get());
  }

  @Test
  public void multipleServicesMultipleClientsTest() throws IOException, InterruptedException {
    logger.info("Starting multiple services, multiple clients test");
    TestServer server1 =
        new TestServer(9090, new String[] {"hello.mimgrpc.me:5000"}, new EchoService("1"));
    TestServer server2 =
        new TestServer(
            9091,
            new String[] {"hello.mimgrpc.me:5000", "hello.mimgrpc.me:5001"},
            new EchoService("2"));
    TestServer server3 =
        new TestServer(9092, new String[] {"hello.mimgrpc.me:5002"}, new EchoService("3"));
    server1.start();
    logger.info("Server 1 started at port 9090 with weight 100");
    server2.start();
    logger.info("Server 2 started at port 9091 with weight 100");
    server3.start();
    logger.info("Server 3 started at port 9092 with weight 100");
    Thread.sleep(1000);

    logger.info(
        "Starting client 1, making requests every 0,1s for 5 seconds for service hello.mimgrpc.me:5000");
    Thread clientThread1 = new SimpleEchoClient(50, 5000);
    clientThread1.start();

    logger.info(
        "Starting client 2, making requests every 0,1s for 5 seconds for service hello.mimgrpc.me:5001");
    Thread clientThread2 = new SimpleEchoClient(50, 5001);
    clientThread2.start();

    logger.info(
        "Starting client 3, making requests every 0,1s for 5 seconds for service hello.mimgrpc.me:5001");
    Thread clientThread3 = new SimpleEchoClient(50, 5002);
    clientThread3.start();

    clientThread1.join();
    clientThread2.join();
    clientThread3.join();

    logger.info("Stopping server 1 at 9090");
    server1.stop();
    server1.blockUntilShutdown();
    logger.info("Server 1 down");

    logger.info("Stopping server 2 at 9091");
    server2.stop();
    server2.blockUntilShutdown();
    logger.info("Server 2 down");

    logger.info("Stopping server 3 at 9092");
    server3.stop();
    server3.blockUntilShutdown();
    logger.info("Server 3 down");

    Assert.assertEquals(150, s1ResponseCount.get() + s2ResponseCount.get() + s3ResponseCount.get());
  }

  private class TestServer {
    private final Logger logger = LoggerFactory.getLogger(TestServer.class);
    private int port;
    private int weight = 100;
    private Server server;
    private String[] servedServicesNames = new String[] {"hello.mimgrpc.me:5000"};
    private HealthGrpc.HealthImplBase healthCheckService;
    private ArrayList<BindableService> services = new ArrayList<>();
    private ClusterConnector connector;

    public TestServer(int port, int weight, BindableService... services) {
      this.port = port;
      this.weight = weight;
      Collections.addAll(this.services, services);
    }

    public TestServer(int port, BindableService... services) {
      this(port, 100, services);
    }

    public TestServer(
        HealthGrpc.HealthImplBase healthCheckService, int port, BindableService... services) {
      this(port, services);
      this.healthCheckService = healthCheckService;
    }

    public TestServer(int port, String[] servedServicesNames, BindableService... services) {
      this(port, services);
      this.servedServicesNames = servedServicesNames;
    }

    public void start() throws IOException {
      connector =
          new ClusterConnector.Builder()
              .natIsUsed(true)
              .setAddress(InetAddress.getLoopbackAddress())
              .setServerPort(port)
              .useHazelcastLogging(true)
              .setServerWeight(weight)
              .useHealthCheckService(healthCheckService)
              .useMemberAddresses(new String[] {InetAddress.getLoopbackAddress().getHostAddress()})
              .setLeaderStepDownPeriod(4000)
              .setServedServicesNames(servedServicesNames)
              .build();

      connector.start();
      logger.info(
          "Started connector at port ",
          connector.getConnectorPort() + " with known member address ",
          InetAddress.getLoopbackAddress().toString() + " (loopback)");

      this.services.add(connector.getLBService());
      ServerBuilder<?> serverBuilder = ServerBuilder.forPort(this.port);
      for (BindableService service : this.services) {
        serverBuilder.addService(service);
      }

      server = serverBuilder.build().start();
      logger.info("Server started, listening on {}", port);
    }

    public void stop() {
      logger.info("Shutting down server");
      if (server != null) {
        server.shutdown();
      }
      logger.info("Server down");
      if (connector != null) {
        connector.stop(false);
      }
      logger.info("Connector down");
    }

    /** Await termination on the main thread since the grpc library uses daemon threads. */
    public void blockUntilShutdown() throws InterruptedException {
      if (server != null) {
        server.awaitTermination();
      }
    }
  }

  private class EchoService extends EchoGrpc.EchoImplBase {
    private final String num;

    public EchoService(String num) {
      this.num = num;
    }

    @Override
    public void sayHello(EchoRequest req, StreamObserver<EchoReply> responseObserver) {
      EchoReply reply =
          EchoReply.newBuilder().setMessage(req.getMessage()).setServerNum(num).build();
      responseObserver.onNext(reply);
      responseObserver.onCompleted();
    }
  }

  private class SimpleEchoClient extends Thread {
    private final Logger logger = LoggerFactory.getLogger(IntegrationTest.SimpleEchoClient.class);

    private int port = 5000;
    private int no_requests;

    public SimpleEchoClient(int no_requests) {
      this.no_requests = no_requests;
    }

    public SimpleEchoClient(int no_requests, int port) {
      this(no_requests);
      this.port = port;
    }

    public void run() {
      ManagedChannel channel =
          ManagedChannelBuilder.forAddress("hello.mimgrpc.me", port).usePlaintext().build();
      EchoGrpc.EchoBlockingStub stub = EchoGrpc.newBlockingStub(channel);

      for (int i = 0; i < no_requests; i++) {
        try {
          EchoReply response = stub.sayHello(EchoRequest.newBuilder().setMessage("Hello!").build());
          logger.info("Got response from " + response.getServerNum());
          if (response.getServerNum().equals("1")) s1ResponseCount.incrementAndGet();
          else if (response.getServerNum().equals("2")) s2ResponseCount.incrementAndGet();
          else if (response.getServerNum().equals("3")) s3ResponseCount.incrementAndGet();
          Thread.sleep(100);
        } catch (InterruptedException e) {
          logger.warn("Client interrupted: ", e);
        } catch (StatusRuntimeException e) {
          logger.warn("Client: ", e);
          i--;
        }
      }

      logger.info(
          "Client summary: responses from s1: {}, from s2: {}, from s3: {}",
          s1ResponseCount,
          s2ResponseCount,
          s3ResponseCount);
      channel.shutdown();
      try {
        channel.awaitTermination(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        channel.shutdownNow();
        logger.debug("Forcefully shutting down client channel");
      }
      logger.info("Client exits");
    }
  }
}
