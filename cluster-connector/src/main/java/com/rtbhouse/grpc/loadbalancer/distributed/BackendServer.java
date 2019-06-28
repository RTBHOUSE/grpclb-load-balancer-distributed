package com.rtbhouse.grpc.loadbalancer.distributed;

import com.google.protobuf.ByteString;
import com.hazelcast.nio.Address;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import io.grpc.lb.v1.Server;
import java.io.IOException;
import java.net.UnknownHostException;

/**
 * This class is a container for server info stored in the distributed service to servers multimap.
 */
public class BackendServer implements com.hazelcast.nio.serialization.DataSerializable {
  public static final transient int DEFAULT_WEIGHT = 100;

  private Address serverAddress;
  private Address hazelcastAddress;
  private int weight;

  public BackendServer() {};

  public BackendServer(Address serverAddress, Address hazelcastAddress) {
    this(serverAddress, hazelcastAddress, DEFAULT_WEIGHT);
  }

  public BackendServer(Address serverAddress, Address hazelcastAddress, int weight) {
    this.serverAddress = serverAddress;
    this.hazelcastAddress = hazelcastAddress;
    this.weight = weight;
  }

  public Server toProto() throws UnknownHostException {
    return Server.newBuilder()
        .setIpAddress(ByteString.copyFrom(serverAddress.getInetAddress().getAddress()))
        .setPort(serverAddress.getPort())
        .build();
  }

  public Address getServerAddress() {
    return this.serverAddress;
  }

  public Address getHazelcastAddress() {
    return this.hazelcastAddress;
  }

  public int getWeight() {
    return this.weight;
  }

  @Override
  public void writeData(ObjectDataOutput out) throws IOException {
    serverAddress.writeData(out);
    hazelcastAddress.writeData(out);
    out.writeInt(weight);
  }

  @Override
  public void readData(ObjectDataInput in) throws IOException {
    serverAddress = new Address();
    serverAddress.readData(in);
    hazelcastAddress = new Address();
    hazelcastAddress.readData(in);
    weight = in.readInt();
  }
}
