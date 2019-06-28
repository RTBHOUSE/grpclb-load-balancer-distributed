## gRPC-LB distributed load balancer
### Introduction
This project is an alternative for [a standalone grpclb load balancer](https://github.com/RTBHOUSE/grpclb-load-balancer/). The main concepts remain the same,
so you should get familiar with it first. Here we describe only the differences and details of this implementation.

### Overview

The idea is to avoid using standalone loadbalancer(s), but to let your backend servers serve grpclb protocol, thus a list of active servers to the clients.
So, in fact every server becomes a load balancer itself.
The only class you use for it is **ClusterConnector**. The purpose of this class is to create a smart gRPC load balance service, which can be used in any gRPC server. ClusterConnector serves as a factory and manager for the load balance service.
You can obtain it by calling start() on ClusterConnector, to join the cluster, and then using the getLBService() method. The returned **LoadBalancerService** can be passed directly to your gRPC server's builder, as a **BindableService**.

### What does it do? 
Servers that serve the LoadBalanceService become a part of the same Hazelcast cluster which maintains global distributed data structures holding information about currently active servers. This allows all servers performing load balancing to have access to the current state of the cluster and serve the same list of servers to clients. Also, the servers select a leader whose purpose is to remove inactive servers.

### How does it work? 
#### When a server becomes active the following things happen: 
1) it becomes a member of Hazelcast cluster
2) it signs up in the serviceToServers multimap and it updates the serverList in serviceToServerList to include itself if it is healthy or does not check health
3) all serves in the cluster get notified by their ServerListListeners about an update
4) the servers send an updated list to clients subscribed to them

#### When a server goes down the following things happen: 
1) the simple case is that the MemberRemovalListener was up and running on current leader server and received information about Hazelcast cluster member becoming inactive
2) it removes the server from serviceToServers and updates the serverList in serviceToServerList

A little bit more complicated case is that a server became inactive shortly after a new leader
was chosen and MemberRemovalListener was not running yet, in that case: 
1) after MemberRemovalListener starts, the evictAbsentServers() method is called
2) the method takes servers from serviceToServers and keeps only those whose addresses are present in the Hazelcast member cluster 
3) then it removes the absent servers from serviceToServers and updates lists in serviceToServerList

#### When a server checks its health and becomes unhealthy the following things happen:
1) it removes (withdraws) itself from serviceToServers and updates the serverList in serviceToServerList
2) it stops signing up until it becomes healthy again

Again, updating serviceToServerList triggers ServerListListeners, which causes all servers to
send an updated list of servers to clients subscribed to them.

### Tips and gotchas

* You have to provide a list of some known cluster members (by Builder `useMemberAddresses()` method), so that underlying Hazelcast cluster can form. If at least one of the servers provided is working, then the cluster can form. So you need not list all of the servers you want to perform load balancing. BUT if you prefer not to specify such a list, you can use an `useDnsServiceName()` builder's option - you provide DNS service name and other servers' addresses are being resolved automatically, by checking SRV records for given DNS service name. It is convenient, because in order to use grpclb itself you need to have such domain (clients find the load balancers through it), so you can simply use it here. As in the `useMemberAddresses()` case, everything will work fine if at least one of the servers resolved from DNS is up and running.

* By default internal Hazelcast instance chooses ports automatically from range 5701-5801, so you can either be careful not to use them or specify port/port range for Hazelcast to choose from. If you specify a range, Hazelcast instance will automatically choose the first unused one.

* Leader, whose purpose is to remove inactive members, needs to step down once in a while. When a network partition occurs and split-brain situation happens, after the partition ends, there might be two leaders and there is no guarantee they will work properly, so the step down of such leaders will reset the situation back to normal.

* You can use your own health check service to sign up in cluster only if the server passes the check.

* Hazelcast produces a lot of logs, so its logging is turned off by default but you can turn it on with Builder's useHazelcastLogging method.

### Example usage
You can find a simple server using ClusterConnector in ExampleServer class. Also I recommend you to look inside the IntegrationTest, which can help you further with understanding the workflow.

In order to run ExampleServers, use:
```
LOCAL=1 java -jar examples/example-server/target/example-server-1.0-shaded.jar 9090
```
ExampleServer uses localhost address for the discovery of other servers, so it can only be tested locally. But, of course, you can substitute the `useMemberAddresses` parameter given to builder.

In order to run HelloWorldClients, use:
```
java -Dio.grpc.internal.DnsNameResolverProvider.enable_grpclb=true -jar examples/hello-world-client/target/hello-world-client-1.0-shaded.jar "hello.mimgrpc.me:5000" 100
```

