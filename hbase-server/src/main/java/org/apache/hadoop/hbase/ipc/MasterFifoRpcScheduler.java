/**
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
package org.apache.hadoop.hbase.ipc;

import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.util.ExecutorPools;
import org.apache.hadoop.hbase.util.ExecutorPools.PoolType;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.yetus.audience.InterfaceStability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A special {@code }RpcScheduler} only used for master. This scheduler separates RegionServerReport
 * requests to independent handlers to avoid these requests block other requests. To use this
 * scheduler, please set "hbase.master.rpc.scheduler.factory.class" to
 * "org.apache.hadoop.hbase.ipc.MasterFifoRpcScheduler".
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
public class MasterFifoRpcScheduler extends FifoRpcScheduler {
  private static final Logger LOG = LoggerFactory.getLogger(MasterFifoRpcScheduler.class);

  private static final String REGION_SERVER_REPORT = "RegionServerReport";
  private final AtomicInteger rsReportQueueSize = new AtomicInteger(0);

  public MasterFifoRpcScheduler(Configuration conf) {
    super(conf);
  }

  @Override
  public void start() {
  }

  @Override
  public void stop() {
  }

  @Override
  public boolean dispatch(final CallRunner task) throws IOException, InterruptedException {
    String method = getCallMethod(task);    
    if (method != null && method.equals(REGION_SERVER_REPORT)) {      
      return executeRpcCall(ExecutorPools.getPool(PoolType.HANDLER_PRIO), rsReportQueueSize, task);
    } else {
      return executeRpcCall(ExecutorPools.getPool(PoolType.HANDLER), queueSize, task);
    }
  }

  @Override
  public int getGeneralQueueLength() {
    return ExecutorPools.getPool(PoolType.HANDLER_PRIO).getQueue().size() +
      ExecutorPools.getPool(PoolType.HANDLER).getQueue().size();
  }

  @Override
  public int getActiveRpcHandlerCount() {
    return ExecutorPools.getPool(PoolType.HANDLER_PRIO).getActiveCount() +
      ExecutorPools.getPool(PoolType.HANDLER).getActiveCount();
  }

  @Override
  public CallQueueInfo getCallQueueInfo() {
    String queueName = "Master Fifo Queue";

    HashMap<String, Long> methodCount = new HashMap<>();
    HashMap<String, Long> methodSize = new HashMap<>();

    CallQueueInfo callQueueInfo = new CallQueueInfo();
    callQueueInfo.setCallMethodCount(queueName, methodCount);
    callQueueInfo.setCallMethodSize(queueName, methodSize);

    updateMethodCountAndSizeByQueue(ExecutorPools.getPool(PoolType.HANDLER_PRIO).getQueue(),
      methodCount, methodSize);
    updateMethodCountAndSizeByQueue(ExecutorPools.getPool(PoolType.HANDLER).getQueue(),
      methodCount, methodSize);

    return callQueueInfo;
  }
}
