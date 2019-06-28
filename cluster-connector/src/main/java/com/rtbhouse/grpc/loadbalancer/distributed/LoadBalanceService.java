package com.rtbhouse.grpc.loadbalancer.distributed;

import com.google.protobuf.Duration;
import io.grpc.lb.v1.InitialLoadBalanceResponse;
import io.grpc.lb.v1.LoadBalanceRequest;
import io.grpc.lb.v1.LoadBalanceResponse;
import io.grpc.lb.v1.LoadBalancerGrpc;
import io.grpc.lb.v1.ServerList;
import io.grpc.stub.StreamObserver;
import java.net.InetAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoadBalanceService extends LoadBalancerGrpc.LoadBalancerImplBase {
  private static final Logger logger = LoggerFactory.getLogger(LoadBalanceService.class);
  private final Duration statsInterval = Duration.newBuilder().setSeconds(0).build();
  /** key: name of service value: list of servers providing such service */
  private final Map<String, ServerList> servers;
  /** key: name of service value: list of clients subscribed to the service */
  private final Map<String, Set<StreamObserver<LoadBalanceResponse>>> clients =
      new ConcurrentHashMap<>();

  LoadBalanceService(Map<String, ServerList> servers) {
    this.servers = servers;
  }

  @Override
  public StreamObserver<LoadBalanceRequest> balanceLoad(
      final StreamObserver<LoadBalanceResponse> responseObserver) {

    return new StreamObserver<LoadBalanceRequest>() {
      private AtomicBoolean first = new AtomicBoolean(true);
      private String service;

      @Override
      public void onNext(LoadBalanceRequest loadBalanceRequest) {
        if (first.compareAndSet(true, false)) {
          service = loadBalanceRequest.getInitialRequest().getName();
          logger.debug("Got initial request from client for service {}", service);

          clients.putIfAbsent(service, ConcurrentHashMap.newKeySet());
          servers.putIfAbsent(service, ServerList.newBuilder().build());

          clients.get(service).add(responseObserver);

          InitialLoadBalanceResponse initialResponse =
              InitialLoadBalanceResponse.newBuilder()
                  .setClientStatsReportInterval(statsInterval)
                  .build();

          LoadBalanceResponse response =
              LoadBalanceResponse.newBuilder().setInitialResponse(initialResponse).build();
          responseObserver.onNext(response);
        }

        LoadBalanceResponse response =
            LoadBalanceResponse.newBuilder().setServerList(servers.get(service)).build();

        logger.debug(
            "Sending to client list of size: {}", response.getServerList().getServersList().size());
        responseObserver.onNext(response);
      }

      @Override
      public void onError(Throwable throwable) {
        removeClient();
        logger.error(throwable.getMessage(), throwable);
      }

      @Override
      public void onCompleted() {
        responseObserver.onCompleted();
        removeClient();
        logger.debug("onCompleted");
      }

      private void removeClient() {
        if (service == null) {
          return;
        }
        clients.get(service).remove(responseObserver);
      }
    };
  }

  /**
   * Updates "servers" with the new servers lists for the given service. Informs every client
   * subscribed to the service about the change.
   *
   * @param service name of the service
   * @param newServerList updated list of servers providing such service
   */
  public synchronized void update(String service, ServerList newServerList) {
    try {
      if (!newServerList.getServersList().isEmpty()) {
        logger.debug(
            "Update for service: {}, first server on list: {}",
            service,
            InetAddress.getByAddress(
                    newServerList.getServersList().get(0).getIpAddress().toByteArray())
                .getHostAddress());
      }
    } catch (Exception e) {
      logger.debug("Update for {} <Unknown host exception>", service);
    }

    /* Send new servers list to clients. */
    LoadBalanceResponse response =
        LoadBalanceResponse.newBuilder().setServerList(newServerList).build();

    /* Make sure not to iterate over null */
    clients.putIfAbsent(service, ConcurrentHashMap.newKeySet());

    Set<StreamObserver<LoadBalanceResponse>> clientObservers = clients.get(service);
    logger.debug("Sending updates to <{}> clients", clientObservers.size());
    for (StreamObserver<LoadBalanceResponse> client : clientObservers) {
      client.onNext(response);
    }
  }
}
