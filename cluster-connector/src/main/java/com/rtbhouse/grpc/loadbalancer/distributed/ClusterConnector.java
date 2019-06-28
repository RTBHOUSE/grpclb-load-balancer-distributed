package com.rtbhouse.grpc.loadbalancer.distributed;

import static java.lang.Math.ceil;

import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MultiMapConfig;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.EntryListener;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.hazelcast.core.MapEvent;
import com.hazelcast.core.Member;
import com.hazelcast.core.MemberAttributeEvent;
import com.hazelcast.core.MembershipEvent;
import com.hazelcast.core.MembershipListener;
import com.hazelcast.core.MultiMap;
import com.hazelcast.map.listener.MapListener;
import com.hazelcast.nio.Address;
import com.spotify.dns.DnsSrvResolver;
import com.spotify.dns.DnsSrvResolvers;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.lb.v1.ServerList;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The purpose of this class is to create a smart gRPC load balance service, which can be used in
 * any gRPC server. ClusterConnector serves as a factory and manager for the load balance service.
 * You can obtain it by calling start() on ClusterConnector and then using the getLBService()
 * method.
 *
 * <p>What does it do? Servers that serve the LoadBalanceService become a part of the same cluster
 * which maintains global distributed data structures holding information about currently active
 * servers. This allows all servers performing load balancing to have access to the current state of
 * the cluster and serve the same list of servers to clients. Also, the servers select a leader
 * whose purpose is to remove inactive servers.
 *
 * <p>How does it work? When a server becomes active the following things happen: 1) it becomes a
 * member of Hazelcast cluster 2) it signs up in the serviceToServers multimap and it updates the
 * serverList in serviceToServerList to include itself if it is healthy or does not check health 3)
 * all serves in the cluster get notified by their ServerListListeners about an update 4) the
 * servers send an updated list to clients subscribed to them
 *
 * <p>When a server goes down the following things happen: 1) the simple case is that the
 * MemberRemovalListener was up and running on current leader server and received information about
 * Hazelcast cluster member becoming inactive 2) it removes the server from serviceToServers and
 * updates the serverList in serviceToServerList
 *
 * <p>A little bit more complicated case is that a server became inactive shortly after a new leader
 * was chosen and MemberRemovalListener was not running yet, in that case: 1) after
 * MemberRemovalListener starts, the evictAbsentServers() method is called 2) the method takes
 * servers from serviceToServers and keeps only those whose addresses are present in the Hazelcast
 * member cluster 3) then it removes the absent servers from serviceToServers and updates lists in
 * serviceToServerList
 *
 * <p>When a server checks its health and becomes unhealthy the following things happen: 1) it
 * removes (withdraws) itself from serviceToServers and updates the serverList in
 * serviceToServerList 2) it stops signing up until it becomes healthy again
 *
 * <p>Again, updating serviceToServerList triggers ServerListListeners, which causes all servers to
 * send an updated list of servers to clients subscribed to them.
 *
 * <p>Tips and gotchas
 *
 * <p>You have to provide a list of some known cluster members (by Builder useMemberAddresses
 * method), so that underlying Hazelcast cluster can form. If at least one of the servers provided
 * is working, then the cluster can form. So you need not list all of the servers you want to
 * perform load balancing. BUT if you prefer not to specify such a list, you can provide DNS service
 * name and resolve other servers' addresses automatically, by checking SRV records for given DNS
 * service name.
 *
 * <p>By default internal Hazelcast instance chooses ports automatically from range 5701-5801, so
 * you can either be careful not to use them or specify port/port range for Hazelcast to choose
 * from. If you specify a range, Hazelcast instance will automatically choose the first unused one.
 *
 * <p>Leader, whose purpose is to remove inactive members, needs to step down once in a while. When
 * a network partition occurs and split-brain situation happens, after the partition ends, there
 * might be two leaders and there is no guarantee they will work properly, so the step down of such
 * leaders will reset the situation back to normal.
 *
 * <p>You can use your own health check service to sign up in cluster only if the server passes the
 * check.
 *
 * <p>Hazelcast produces a lot of logs, so its logging is turned off by default but you can turn it
 * on with Builder's useHazelcastLogging method.
 */
public class ClusterConnector {
  private static final int DEFAULT_WEIGHT = 100;
  private static final int SERVER_LIST_LENGTH_RATIO = 3;

  private static final int DEFAULT_SIGNUP_OR_WITHDRAW_PERIOD = 1000; // in milliseconds
  private static final int DEFAULT_LEADER_STEP_DOWN_PERIOD = 30000; // in milliseconds
  private static final Integer DEFAULT_HEARTBEAT_INTERVAL = 1; // in seconds
  private static final Integer DEFAULT_EVICTION_INTERVAL = 2; // in seconds
  private static final String GRPCLB_DNS_PREFIX = "_grpclb._tcp.";

  private static final Logger logger = LoggerFactory.getLogger(ClusterConnector.class);

  private final List<String> knownClusterMembers;

  private LoadBalanceService lbService;

  private HazelcastInstance hz;
  private MultiMap<String, BackendServer> serviceToServers;
  private IMap<String, ServerList> serviceToServerList;
  private IMap<Object, Object> lockMap;

  /** This server details */
  private BackendServer serverDetails;

  private Address serverAddress;
  private int serverWeight;

  private String[] servedServicesNames;

  private final boolean checkingHealth;
  private final HealthGrpc.HealthBlockingStub healthBlockingStub;
  private final Server inProcessServer;
  private final ManagedChannel inProcessChannel;

  private volatile boolean isLeader = false;
  private volatile boolean stopped = true;

  private ScheduledExecutorService signUpOrWithdrawThreadHandle;
  private Thread leadershipThread;

  private Integer signUpOrWithdrawPeriod;
  private Integer leaderStepDownPeriod;
  private Integer heartbeatInterval;
  private Integer evictionInterval;

  private boolean hazelcastLogging;
  private Integer hazelcastPort;
  private Integer hazelcastMinPort;
  private Integer hazelcastMaxPort;

  private ClusterConnector(Builder builder) {
    if (builder.address != null) {
      serverAddress = new Address(builder.address, builder.port);
    } else {
      serverAddress = new Address(getServerAddress(builder.natIsUsed), builder.port);
    }

    serverWeight = builder.serverWeight;
    servedServicesNames = builder.servedServicesNames;
    signUpOrWithdrawPeriod = builder.signupUpOrWithdrawPeriod;
    leaderStepDownPeriod = builder.leaderStepDownPeriod;
    heartbeatInterval = builder.heartbeatInterval;
    evictionInterval = builder.evictionInterval;
    hazelcastLogging = builder.hazelcastLogging;
    hazelcastPort = builder.hazelcastPort;
    hazelcastMinPort = builder.hazelcastMinPort;
    hazelcastMaxPort = builder.hazelcastMaxPort;

    if (builder.dnsServiceName == null) {
      knownClusterMembers = new ArrayList<>(Arrays.asList(builder.knownClusterMembers));
    } else {
      knownClusterMembers = getResolvedServerAddresses(builder.dnsServiceName);
    }

    if (builder.healthService == null) {
      inProcessServer = null;
      inProcessChannel = null;
      healthBlockingStub = null;
      checkingHealth = false;
    } else {
      String uniqueName = InProcessServerBuilder.generateName();
      inProcessServer =
          InProcessServerBuilder.forName(uniqueName).addService(builder.healthService).build();
      inProcessChannel = InProcessChannelBuilder.forName(uniqueName).build();
      healthBlockingStub = HealthGrpc.newBlockingStub(inProcessChannel);
      checkingHealth = true;
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public synchronized void start() throws IOException {
    Config config = new Config();

    config.setProperty("hazelcast.heartbeat.interval.seconds", heartbeatInterval.toString());
    config.setProperty("hazelcast.max.no.heartbeat.seconds", evictionInterval.toString());
    config.setProperty("hazelcast.shutdownhook.enabled", "false");

    if (!hazelcastLogging) {
      config.setProperty("hazelcast.logging.type", "none");
    }

    MultiMapConfig multiMapConfig = new MultiMapConfig();
    multiMapConfig.setName("service-servers");
    multiMapConfig.setBackupCount(0);
    multiMapConfig.setAsyncBackupCount(0);
    config.addMultiMapConfig(multiMapConfig);

    MapConfig mapConfig = new MapConfig();
    mapConfig.setName("service-serverlist");
    mapConfig.setBackupCount(0);
    mapConfig.setAsyncBackupCount(0);
    config.addMapConfig(mapConfig);

    mapConfig.setName("lock");
    config.addMapConfig(mapConfig);

    NetworkConfig network = config.getNetworkConfig();
    if (hazelcastPort != null) {
      network.setPort(hazelcastPort);
    }
    if (hazelcastMinPort != null && hazelcastMaxPort != null) {
      network.setPort(hazelcastMinPort);
      network.setPortCount(hazelcastMaxPort - hazelcastMinPort + 1);
    }

    JoinConfig join = network.getJoin();
    join.getTcpIpConfig().setEnabled(true);
    join.getMulticastConfig().setEnabled(false);
    for (String member : knownClusterMembers) {
      join.getTcpIpConfig().addMember(member).setEnabled(true);
    }

    hz = Hazelcast.newHazelcastInstance(config);
    lockMap = hz.getMap("lock");
    serviceToServers = hz.getMultiMap("service-servers");
    serviceToServerList = hz.getMap("service-serverlist");
    serviceToServerList.addEntryListener((MapListener) new ServerListListener(), true);

    lbService = new LoadBalanceService(serviceToServerList);

    Address hazelcastAddress = new Address(hz.getCluster().getLocalMember().getAddress());
    serverDetails = new BackendServer(serverAddress, hazelcastAddress, serverWeight);

    if (checkingHealth) {
      inProcessServer.start();
    }

    this.stopped = false;
    runSignUpOrWithdrawThread(signUpOrWithdrawPeriod);
    runLeadershipThread(leaderStepDownPeriod);
  }

  /**
   * If mayInterruptIfRunning is true and the server is the leader, the method doesn't wait for it
   * to step down, but rather forces it to finish immediately. Leadership thread might throw some
   * exceptions while stopping.
   */
  public synchronized void stop(boolean mayInterruptIfRunning) {
    this.stopped = true;
    if (mayInterruptIfRunning) {
      this.leadershipThread.interrupt();
    } else {
      try {
        this.wait();
      } catch (InterruptedException e) {
        logger.debug("InterruptedException while waiting for leadership thread to stop");
      }
    }
    if (mayInterruptIfRunning) {
      signUpOrWithdrawThreadHandle.shutdownNow();
    }
    signUpOrWithdrawThreadHandle.shutdown();

    if (checkingHealth) {
      inProcessChannel.shutdown();
      inProcessServer.shutdown();
    }

    if (isLeader) {
      try {
        lockMap.unlock("leader-lock");
      } catch (IllegalMonitorStateException e) {
        logger.debug("stop(): Tried to unlock the leader lock after stepping down as leader");
      }
    }
    hz.shutdown();
  }

  /** @return list of host:port addresses resolved from DNS SRV records */
  private List<String> getResolvedServerAddresses(String dnsName) {
    DnsSrvResolver resolver =
        DnsSrvResolvers.newBuilder()
            .cachingLookups(true)
            .retainingDataOnFailures(true)
            .dnsLookupTimeoutMillis(5000)
            .build();

    return resolver
        .resolve(GRPCLB_DNS_PREFIX + dnsName)
        .stream()
        .map(r -> r.host() + ":" + r.port())
        .collect(Collectors.toList());
  }

  /** @return this server's address */
  public static InetAddress getServerAddress(boolean natIsUsed) {
    try {
      if (System.getenv().get("LOCAL") != null) return InetAddress.getByName("127.0.0.1");

      String address;
      if (natIsUsed) {
        URL whatIsMyIp = new URL("http://checkip.amazonaws.com");
        BufferedReader in = new BufferedReader(new InputStreamReader(whatIsMyIp.openStream()));
        address = in.readLine();
        in.close();
      } else {
        address = InetAddress.getLocalHost().getHostAddress();
      }
      return InetAddress.getByName(address);
    } catch (IOException e) {
      logger.error("Could not get host address", e);
      throw new RuntimeException("Could not get host address", e);
    }
  }

  /**
   * The server needs to sign up in order for other servers to see it. This method periodically
   * makes sure the server has not been evicted by other servers and if the server checks health and
   * becomes unhealthy it withdraws the server until it becomes healthy again.
   *
   * @param millis how often should the server sign up
   */
  private void runSignUpOrWithdrawThread(int millis) {
    this.signUpOrWithdrawThreadHandle = Executors.newScheduledThreadPool(1);

    this.signUpOrWithdrawThreadHandle.scheduleAtFixedRate(
        () -> {
          for (String service : servedServicesNames) {
            boolean sendUpdate;

            if (checkingHealth || !serviceToServers.containsEntry(service, serverDetails)) {
              if (!checkingHealth || isHealthy()) {
                sendUpdate = serviceToServers.put(service, serverDetails);
                if (sendUpdate) {
                  logger.debug("Adding server for service {}", service);
                }
              } else { // not healthy
                sendUpdate = serviceToServers.remove(service, serverDetails);
                if (sendUpdate) {
                  logger.debug("Removing server for service {}", service);
                }
              }

              if (sendUpdate) {
                update(service);
              }
            }
          }
        },
        0,
        millis,
        TimeUnit.MILLISECONDS);
  }

  /**
   * Takes current state of serviceToServers and puts equivalent ServerList in serviceToServerList,
   * which notifies other servers about the change.
   */
  private void update(String service) {
    ServerList serverList = getServerList(service);
    serviceToServerList.set(service, serverList);
    logger.debug("update(): putting {} servers", serverList.getServersCount());
  }

  private boolean isHealthy() {
    HealthCheckResponse response =
        healthBlockingStub.check(HealthCheckRequest.getDefaultInstance());
    HealthCheckResponse.ServingStatus status = response.getStatus();
    return status.equals(HealthCheckResponse.ServingStatus.SERVING);
  }

  /** @param millis defines how often leaders should have a possibility to step down */
  private void runLeadershipThread(int millis) {
    this.leadershipThread =
        new Thread(
            () -> {
              try {
                String listenerRegistrationId = "";

                while (!stopped) {
                  try {
                    if (!isLeader) {
                      lockMap.lock("leader-lock");

                      if (stopped) {
                        lockMap.unlock("leader-lock");
                        break;
                      }

                      logger.debug(
                          "Server with address {} is the new leader",
                          serverDetails.getServerAddress().toString());
                    }
                  } catch (IllegalMonitorStateException e) {
                    logger.debug("Leader lock released because of exception {}", e);
                  }

                  isLeader = true;
                  listenerRegistrationId =
                      hz.getCluster().addMembershipListener(new MemberRemovalListener());
                  evictAbsentServers();

                  try {
                    Thread.sleep(millis);
                  } catch (InterruptedException e) {
                    logger.debug("Sleep interrupted, stepping down as leader sooner");
                  }

                  lockMap.unlock("leader-lock");
                  if (!lockMap.tryLock("leader-lock")) {
                    hz.getCluster().removeMembershipListener(listenerRegistrationId);
                    isLeader = false;
                    logger.debug(
                        "Server with address {} steps down as leader",
                        serverDetails.getServerAddress().toString());
                  } else {
                    logger.debug(
                        "Server with address {} gets reelected",
                        serverDetails.getServerAddress().toString());
                  }
                }

                if (isLeader) {
                  hz.getCluster().removeMembershipListener(listenerRegistrationId);
                  isLeader = false;
                  logger.debug(
                      "Server with address {} steps down as leader",
                      serverDetails.getServerAddress().toString());
                }

                Thread.sleep(1000);
                synchronized (ClusterConnector.this) {
                  ClusterConnector.this.notify();
                }
              } catch (Exception e) {
                logger.debug("Exception in leadership thread", e);
              }
            });

    this.leadershipThread.start();
  }

  /**
   * Removes from serviceToServers servers that are present in serviceToServers but are not members
   * of the cluster.
   */
  private void evictAbsentServers() {
    for (String service : serviceToServers.keySet()) {
      Collection<BackendServer> servers = serviceToServers.get(service);
      Set<BackendServer> presentBackendServers = getPresentBackendServers(service);
      List<BackendServer> absentServers = new LinkedList<>();

      for (BackendServer server : servers) {
        if (!presentBackendServers.contains(server)) {
          absentServers.add(server);
        }
      }

      for (BackendServer absentServer : absentServers) {
        serviceToServers.remove(service, absentServer);
      }

      if (!absentServers.isEmpty()) {
        update(service);
        logger.debug("Removed all absent backend servers for service {}", service);
      }
    }
  }

  /** @return BackendServers that are in serviceToServers and are members of the cluster */
  private Set<BackendServer> getPresentBackendServers(String service) {
    Set<Member> presentMembers = hz.getCluster().getMembers();
    Set<Address> presentAddresses = new HashSet<>();
    Set<BackendServer> presentBackendServers = new HashSet<>();

    for (Member member : presentMembers) {
      presentAddresses.add(member.getAddress());
    }

    Set<BackendServer> signedUpServers = new HashSet<>(serviceToServers.get(service));

    for (BackendServer server : signedUpServers) {
      if (presentAddresses.contains(server.getHazelcastAddress())) {
        presentBackendServers.add(server);
      }
    }

    return presentBackendServers;
  }

  /**
   * @return ServerList object containing servers that are currently serving the specified service
   */
  private ServerList getServerList(String service) {
    Collection<BackendServer> servers = serviceToServers.get(service);

    int listLen = servers.size() * SERVER_LIST_LENGTH_RATIO;
    int weightSum = 0;
    for (BackendServer server : servers) {
      weightSum += server.getWeight();
    }

    /* Create raw list first, as it is not possible to shuffle proto repeated field. */
    List<BackendServer> serverList = new LinkedList<>();
    for (BackendServer server : servers) {
      int timesOnList = (int) ceil(((double) server.getWeight() / (double) weightSum) * listLen);
      for (int i = 0; i < timesOnList; ++i) {
        serverList.add(server);
      }
    }
    Collections.shuffle(serverList);

    ServerList.Builder serverListBuilder = ServerList.newBuilder();
    for (BackendServer server : serverList) {
      io.grpc.lb.v1.Server serverPrototype = null;
      try {
        serverPrototype = server.toProto();
      } catch (UnknownHostException e) {
        logger.debug(
            "Exception while trying to get InetAddress for the server with address {}",
            server.getServerAddress());
      }
      serverListBuilder.addServers(serverPrototype);
    }

    return serverListBuilder.build();
  }

  /** @return port used by the inner Hazelcast instance */
  int getConnectorPort() {
    if (hz == null) {
      return -1;
    }

    return hz.getCluster().getLocalMember().getAddress().getPort();
  }

  /**
   * Returns null if used before calling start() on ClusterConnector. Otherwise returns the load
   * balance service that needs to be bound to a server in order to perform load balancing.
   */
  public LoadBalanceService getLBService() {
    return lbService;
  }

  /** Listens for removal of Hazelcast cluster members. */
  private class MemberRemovalListener implements MembershipListener {
    public void memberAdded(MembershipEvent membershipEvent) {}

    public void memberRemoved(MembershipEvent membershipEvent) {
      if (isLeader) {
        Member member = membershipEvent.getMember();

        logger.debug("MemberRemovalListener got event for {}", member.getAddress());

        for (String service : serviceToServers.keySet()) {
          for (BackendServer server : serviceToServers.get(service)) {
            if (server.getHazelcastAddress().equals(member.getAddress())) {
              serviceToServers.remove(service, server);
              logger.info(
                  "MemberRemovalListener evicted server with address {}", member.getAddress());
              update(service);
              break;
            }
          }
        }
      }
    }

    public void memberAttributeChanged(MemberAttributeEvent memberAttributeEvent) {}
  }

  /** Listens for changes to serviceToServerList. */
  private class ServerListListener implements EntryListener<String, ServerList> {
    private void update(EntryEvent<String, ServerList> event) {
      String service = event.getKey();
      ServerList serverList = event.getValue();
      lbService.update(service, serverList);
    }

    @Override
    public void entryAdded(EntryEvent<String, ServerList> event) {
      update(event);
      logger.debug("Listener for new service added service {}", event.getKey());
    }

    @Override
    public void entryEvicted(EntryEvent<String, ServerList> event) {
      update(event);
      logger.debug("Listener for service eviction removed service {}", event.getKey());
    }

    @Override
    public void entryRemoved(EntryEvent<String, ServerList> event) {
      update(event);
      logger.debug("Listener for service removal removed service {}", event.getKey());
    }

    @Override
    public void entryUpdated(EntryEvent<String, ServerList> event) {
      update(event);
      logger.debug("Listener for service update of {} updated server list", event.getKey());
    }

    @Override
    public void mapCleared(MapEvent event) {
      logger.warn("Map cleared event unsupported");
    }

    @Override
    public void mapEvicted(MapEvent event) {
      logger.warn("Map evicted event unsupported");
    }
  }

  public static final class Builder {
    private int port;
    private InetAddress address;
    private String[] knownClusterMembers;
    private String dnsServiceName;
    private String[] servedServicesNames;
    private int serverWeight = DEFAULT_WEIGHT;
    private boolean natIsUsed = false;
    private int signupUpOrWithdrawPeriod = DEFAULT_SIGNUP_OR_WITHDRAW_PERIOD;
    private int leaderStepDownPeriod = DEFAULT_LEADER_STEP_DOWN_PERIOD;
    private int heartbeatInterval = DEFAULT_HEARTBEAT_INTERVAL;
    private int evictionInterval = DEFAULT_EVICTION_INTERVAL;
    private HealthGrpc.HealthImplBase healthService;
    private boolean hazelcastLogging = false;
    private Integer hazelcastPort;
    private Integer hazelcastMinPort;
    private Integer hazelcastMaxPort;

    /**
     * By default, server discovers its address by InetAddress.getLocalHost().getHostAddress() call.
     * But, if you use NAT and don't have public IP address, it won't work. When this option is
     * enabled, server will ask http://checkip.amazonaws.com about its IP address.
     */
    public Builder natIsUsed(boolean val) {
      natIsUsed = val;
      return this;
    }

    /**
     * Provide list of other servers' addresses in host:port format directly. If at least one of
     * them is a cluster member and is alive, then the new server will successfully join the
     * cluster. Alternative: useDnsServiceName()
     */
    public Builder useMemberAddresses(String[] val) {
      knownClusterMembers = val;
      return this;
    }

    /**
     * Resolve other servers' addresses automatically, by checking SRV records for given domain,
     * just as clients do. If at least one of them is a cluster member and is alive, then the new
     * server will successfully join the cluster. Alternative: useMemberAddresses
     */
    public Builder useDnsServiceName(String val) {
      dnsServiceName = val;
      return this;
    }

    /**
     * Optional. Server uses the address to identify itself. By default it discovers its address on
     * it's own. For more information check out natIsUsed.
     */
    public Builder setAddress(InetAddress val) {
      address = val;
      return this;
    }

    /** This port has to be your server's port, because it is used to identify the server. */
    public Builder setServerPort(int val) {
      port = val;
      return this;
    }

    /**
     * Sets how often servers should check if they are in the distributed server set and add
     * themselves on the list if they aren't on the list. If health checking is enabled the server
     * will only add itself to the list if it passes the health check, additionally it will remove
     * itself from the list if it doesn't pass the health check.
     */
    public Builder setSignUpOrWithdrawPeriod(int millis) {
      signupUpOrWithdrawPeriod = millis;
      return this;
    }

    /**
     * Sets how often current leader of the cluster should give a chance to other servers to become
     * a leader. This is useful when after network partition split-brain situation happens and there
     * are two leaders, because they will eventually step down and only one leader will remain.
     * Default is 30 seconds.
     */
    public Builder setLeaderStepDownPeriod(int millis) {
      leaderStepDownPeriod = millis;
      return this;
    }

    /**
     * Sets how often underlying hazelcast algorithm should send heartbeats (in seconds). Default is
     * 1 second.
     */
    public Builder setHeartbeatInterval(int seconds) {
      heartbeatInterval = seconds;
      return this;
    }

    /**
     * Sets how much time (in seconds) should pass since last heartbeat from a server before
     * evicting it from the list of alive members in underlying hazelcast algorithm.
     */
    public Builder setEvictionInterval(int seconds) {
      evictionInterval = seconds;
      return this;
    }

    /**
     * Names of services (corresponding to bindable services) that this server serves, in host:port
     * format. Those have to be the same names, that clients use when creating gRPC channels, e.g.
     * through ManagedChannelBuilder.forAddress(host, port)
     */
    public Builder setServedServicesNames(String[] val) {
      servedServicesNames = val;
      return this;
    }

    /**
     * Optionally: you may set custom weight, to modify the percentage of requests that this server
     * receives. Default value is 100, and it means that server should receive c.a. 1/N of all
     * requests, where N is the total number of backend servers.
     */
    public Builder setServerWeight(int val) {
      serverWeight = val;
      return this;
    }

    /**
     * You can optionally provide a io.grpc.health.v1.HealthProto implementation, in order to add
     * the server to the distributed servers set only if it passes the healthcheck, and also to
     * unregister the server when it's not healthy.
     *
     * <p>To do this, there is an InProcessServer being run with this service in the background.
     */
    public Builder useHealthCheckService(HealthGrpc.HealthImplBase val) {
      healthService = val;
      return this;
    }

    /** When specified, inner Hazelcast instance will try to run using the given port. */
    public Builder useHazelcastPort(int val) {
      hazelcastPort = val;
      return this;
    }

    /**
     * When specified, inner Hazelcast instance will try to use the lowest possible port from the
     * given range. The default range is 5701-5801.
     */
    public Builder useHazelcastPortRange(int min, int max) {
      assert (min <= max);
      hazelcastMinPort = min;
      hazelcastMaxPort = max;
      return this;
    }

    /**
     * Optional. If not specified, inner Hazelcast instance logs will not be displayed. If specified
     * standard logger will be used.
     */
    public Builder useHazelcastLogging(boolean val) {
      hazelcastLogging = val;
      return this;
    }

    /** Build the server. */
    public ClusterConnector build() throws IOException {
      if (port == 0) throw new IllegalStateException("Port has to be set.");

      if ((knownClusterMembers != null && dnsServiceName != null)
          || (knownClusterMembers == null && dnsServiceName == null))
        throw new IllegalStateException(
            "Provide exactly one of the following arguments: MemberAddresses, dnsServiceName");

      return new ClusterConnector(this);
    }
  }
}
