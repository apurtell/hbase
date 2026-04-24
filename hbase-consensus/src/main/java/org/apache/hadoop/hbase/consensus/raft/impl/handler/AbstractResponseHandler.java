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
package org.apache.hadoop.hbase.consensus.raft.impl.handler;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.model.message.RaftMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for Raft RPC response handlers.
 * <p>
 * If {@link RaftMessage#getSender()} is not a known Raft group member, then the response is
 * ignored.
 */
public abstract class AbstractResponseHandler<T extends RaftMessage>
  extends AbstractMessageHandler<T> {
  AbstractResponseHandler(RaftNodeImpl raftNode, T response) {
    super(raftNode, response);
  }

  @Override
  protected final void handle(@NonNull T response) {
    requireNonNull(response);
    if (!node.state().isKnownMember(response.getSender())) {
      Logger logger = LoggerFactory.getLogger(getClass());
      logger.warn("{} Won't run, since {} is unknown to us.", localEndpointStr(),
        response.getSender().getId());
      return;
    }
    handleResponse(response);
  }

  protected abstract void handleResponse(@NonNull T response);
}
