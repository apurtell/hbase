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
package org.apache.hadoop.hbase.consensus.handler.transport;

import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.exceptions.HBaseException;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * Thrown by {@link EndpointResolver#resolve(RaftEndpoint)} when no address is registered for the
 * given endpoint. {@link CoalescingTransport#send} catches this and silently drops the message.
 */
@InterfaceAudience.Private
public class UnknownEndpointException extends HBaseException {
  private static final long serialVersionUID = 1L;

  public UnknownEndpointException(RaftEndpoint endpoint) {
    super("No address registered for endpoint " + endpoint.getId());
  }
}
