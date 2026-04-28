/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.consensus.raft.impl.local;

import static java.util.Objects.requireNonNull;
import static org.apache.hadoop.hbase.consensus.raft.RaftNodeStatus.TERMINATED;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.RaftNode;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.impl.handler.LeaderHeartbeatAckHandler;
import org.apache.hadoop.hbase.consensus.raft.impl.handler.LeaderHeartbeatHandler;
import org.apache.hadoop.hbase.consensus.raft.model.message.LeaderHeartbeat;
import org.apache.hadoop.hbase.consensus.raft.model.message.LeaderHeartbeatAck;
import org.apache.hadoop.hbase.consensus.raft.model.message.RaftMessage;
import org.apache.hadoop.hbase.consensus.raft.transport.BulkHeartbeatAckFrame;
import org.apache.hadoop.hbase.consensus.raft.transport.BulkHeartbeatFrame;
import org.apache.hadoop.hbase.consensus.raft.transport.Transport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LocalTransport implements Transport {
  private static final Logger LOG = LoggerFactory.getLogger(LocalTransport.class);
  private final RaftEndpoint localEndpoint;
  private final ConcurrentMap<RaftEndpoint, RaftNode> nodes = new ConcurrentHashMap<>();
  private final Firewall firewall;

  public LocalTransport(RaftEndpoint localEndpoint) {
    this.localEndpoint = requireNonNull(localEndpoint);
    this.firewall = new Firewall();
  }

  @Override
  public void send(@NonNull RaftEndpoint target, @NonNull RaftMessage message) {
    if (localEndpoint.equals(target)) {
      throw new IllegalArgumentException(
        localEndpoint.getId() + " cannot send " + message + " to itself!");
    }
    RaftNode node = nodes.get(target);
    if (node == null || firewall.shouldDropMessage(target, message)) {
      return;
    }
    try {
      RaftMessage maybeAlteredMessage = firewall.tryAlterMessage(target, message);
      if (maybeAlteredMessage == null) {
        LOG.error(message + " sent from " + localEndpoint.getId() + " to " + target.getId()
          + " is altered to null!");
        return;
      }
      node.handle(maybeAlteredMessage);
    } catch (Exception e) {
      LOG.error("Send " + message + " to " + target + " failed.", e);
    }
  }

  @Override
  public boolean isReachable(@NonNull RaftEndpoint endpoint) {
    return nodes.containsKey(endpoint);
  }

  @Override
  public void sendBulkHeartbeat(@NonNull RaftEndpoint target, @NonNull BulkHeartbeatFrame frame) {
    if (localEndpoint.equals(target)) {
      throw new IllegalArgumentException(
        localEndpoint.getId() + " cannot send a bulk heartbeat to itself!");
    }
    if (firewall.shouldDropBulkHeartbeats(target)) {
      return;
    }
    RaftNode node = nodes.get(target);
    if (!(node instanceof RaftNodeImpl)) {
      return;
    }
    RaftNodeImpl rni = (RaftNodeImpl) node;
    final RaftEndpoint followerEndpoint = rni.getLocalEndpoint();
    final Transport followerTransport = rni.getTransport();
    for (LeaderHeartbeat hb : frame.getEntries()) {
      if (firewall.shouldDropMessage(target, hb)) {
        continue;
      }
      try {
        RaftMessage altered = firewall.tryAlterMessage(target, hb);
        if (!(altered instanceof LeaderHeartbeat)) {
          continue;
        }
        LeaderHeartbeat alteredHb = (LeaderHeartbeat) altered;
        Consumer<LeaderHeartbeatAck> ackSink =
          ack -> followerTransport.sendBulkHeartbeatAck(alteredHb.getSender(),
            new BulkHeartbeatAckFrame(followerEndpoint, 0L, 0L, Collections.singletonList(ack)));
        rni.getExecutor().executeControl(new LeaderHeartbeatHandler(rni, alteredHb, ackSink));
      } catch (Exception e) {
        LOG.error("Bulk heartbeat dispatch to {} failed.", target, e);
      }
    }
  }

  @Override
  public void sendBulkHeartbeatAck(@NonNull RaftEndpoint target,
    @NonNull BulkHeartbeatAckFrame frame) {
    if (localEndpoint.equals(target)) {
      throw new IllegalArgumentException(
        localEndpoint.getId() + " cannot send a bulk heartbeat ack to itself!");
    }
    if (firewall.shouldDropBulkHeartbeats(target)) {
      return;
    }
    RaftNode node = nodes.get(target);
    if (!(node instanceof RaftNodeImpl)) {
      return;
    }
    RaftNodeImpl rni = (RaftNodeImpl) node;
    for (LeaderHeartbeatAck ack : frame.getEntries()) {
      if (firewall.shouldDropMessage(target, ack)) {
        continue;
      }
      try {
        RaftMessage altered = firewall.tryAlterMessage(target, ack);
        if (!(altered instanceof LeaderHeartbeatAck)) {
          continue;
        }
        LeaderHeartbeatAck alteredAck = (LeaderHeartbeatAck) altered;
        rni.getExecutor().executeControl(new LeaderHeartbeatAckHandler(rni, alteredAck));
      } catch (Exception e) {
        LOG.error("Bulk heartbeat ack dispatch to {} failed.", target, e);
      }
    }
  }

  /** Adds the given Raft node to the known Raft nodes map. */
  public void discoverNode(RaftNode node) {
    RaftEndpoint endpoint = node.getLocalEndpoint();
    if (localEndpoint.equals(endpoint)) {
      throw new IllegalArgumentException(localEndpoint + " cannot discover itself!");
    }
    RaftNode existingNode = nodes.putIfAbsent(endpoint, node);
    if (existingNode != null && existingNode != node && existingNode.getStatus() != TERMINATED) {
      throw new IllegalArgumentException(localEndpoint + " already knows: " + endpoint);
    }
  }

  /** Removes the given Raft node from the known Raft nodes map. */
  public void undiscoverNode(RaftNodeImpl node) {
    RaftEndpoint endpoint = node.getLocalEndpoint();
    if (localEndpoint.equals(endpoint)) {
      throw new IllegalArgumentException(localEndpoint + " cannot undiscover itself!");
    }
    nodes.remove(node.getLocalEndpoint(), node);
  }

  /** Returns the firewall returned by this transport object. */
  public Firewall getFirewall() {
    return firewall;
  }
}
