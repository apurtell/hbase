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

/**
 * Multi-group {@link org.apache.hadoop.hbase.consensus.raft.executor.RaftNodeExecutor} hosting. One
 * {@link MultiGroupExecutor} owns a shared {@link java.util.concurrent.ScheduledThreadPoolExecutor}.
 * Each Raft group gets a {@link GroupExecutor} view with an MPSC mailbox and an {@code scheduled}
 * flag so that at most one drain runnable per group polls that mailbox at a time.
 * {@link GroupExecutor#onRaftNodeTerminate()} cancels delayed work and discards queued tasks but
 * does <strong>not</strong> shut down the parent pool. Only {@link MultiGroupExecutor#close()}
 * does that.
 */
package org.apache.hadoop.hbase.consensus.handler.executor;
