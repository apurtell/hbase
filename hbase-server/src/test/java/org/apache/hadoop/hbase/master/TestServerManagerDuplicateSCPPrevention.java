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
package org.apache.hadoop.hbase.master;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseClassTestRule;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.master.assignment.AssignmentManager;
import org.apache.hadoop.hbase.master.procedure.MasterProcedureEnv;
import org.apache.hadoop.hbase.master.procedure.ServerCrashProcedure;
import org.apache.hadoop.hbase.procedure2.Procedure;
import org.apache.hadoop.hbase.procedure2.ProcedureExecutor;
import org.apache.hadoop.hbase.testclassification.MasterTests;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.ArgumentCaptor;

/**
 * Tests for ServerManager that ensure duplicate ServerCrashProcedures are not scheduled during
 * Master startup.
 */
@Category({ MasterTests.class, SmallTests.class })
public class TestServerManagerDuplicateSCPPrevention {

  @ClassRule
  public static final HBaseClassTestRule CLASS_RULE =
    HBaseClassTestRule.forClass(TestServerManagerDuplicateSCPPrevention.class);

  private ServerManager serverManager;
  private MasterServices mockMaster;
  private ProcedureExecutor<MasterProcedureEnv> mockProcExec;
  private AssignmentManager mockAssignmentManager;
  private RegionServerList mockStorage;
  private Configuration conf;

  // Test server names
  private ServerName server1;
  private ServerName server2;
  private ServerName server3;
  private ServerName server4;

  @Before
  @SuppressWarnings("unchecked")
  public void setUp() {
    conf = HBaseConfiguration.create();
    server1 = ServerName.valueOf("host1", 16020, 123456789L);
    server2 = ServerName.valueOf("host2", 16020, 123456790L);
    server3 = ServerName.valueOf("host3", 16020, 123456791L);
    server4 = ServerName.valueOf("host4", 16020, 123456792L);
    // Mock the master services
    mockMaster = mock(MasterServices.class);
    mockProcExec = (ProcedureExecutor<MasterProcedureEnv>) mock(ProcedureExecutor.class);
    mockAssignmentManager = mock(AssignmentManager.class);
    mockStorage = mock(RegionServerList.class);
    when(mockMaster.getConfiguration()).thenReturn(conf);
    when(mockMaster.getMasterProcedureExecutor()).thenReturn(mockProcExec);
    when(mockMaster.getAssignmentManager()).thenReturn(mockAssignmentManager);
    // Create ServerManager with mocks
    serverManager = spy(new ServerManager(mockMaster, mockStorage));
  }

  /** No existing SCPs. All candidate dead servers should get new SCPs scheduled. */
  @Test
  public void testNoExistingSCPs() throws Exception {
    Map<ServerName, Long> deadServersFromPE = Collections.emptyMap();
    Set<ServerName> liveServersFromWALDir = Set.of(server1, server2, server3);
    List<Procedure<MasterProcedureEnv>> allProcedures =
      Collections.<Procedure<MasterProcedureEnv>> emptyList();
    when(mockProcExec.getProcedures())
      .thenReturn((List<Procedure<MasterProcedureEnv>>) allProcedures);
    serverManager.findDeadServersAndProcess(deadServersFromPE, liveServersFromWALDir);
    ArgumentCaptor<ServerName> serverCaptor = ArgumentCaptor.forClass(ServerName.class);
    verify(serverManager, times(3)).expireServer(serverCaptor.capture());
    Set<ServerName> expiredServers = new HashSet<>(serverCaptor.getAllValues());
    assertEquals("All 3 servers should be expired", 3, expiredServers.size());
    assertTrue("Server1 should be expired", expiredServers.contains(server1));
    assertTrue("Server2 should be expired", expiredServers.contains(server2));
    assertTrue("Server3 should be expired", expiredServers.contains(server3));
  }

  /** Some servers already have active SCPs. Those should be skipped. */
  @Test
  public void testWithExistingSCPs() throws Exception {
    Map<ServerName, Long> deadServersFromPE = Collections.emptyMap();
    Set<ServerName> liveServersFromWALDir = Set.of(server1, server2, server3, server4);
    // Mock: server1 and server3 already have active SCPs
    ServerCrashProcedure mockSCP1 = createMockSCP(server1, false); // active
    ServerCrashProcedure mockSCP3 = createMockSCP(server3, false); // active
    @SuppressWarnings("unchecked")
    Procedure<MasterProcedureEnv> mockOtherProc =
      (Procedure<MasterProcedureEnv>) mock(Procedure.class); // non-SCP procedure
    when(mockOtherProc.isFinished()).thenReturn(false);
    List<Procedure<MasterProcedureEnv>> allProcedures =
      Arrays.<Procedure<MasterProcedureEnv>> asList(mockSCP1, mockSCP3, mockOtherProc);
    when(mockProcExec.getProcedures())
      .thenReturn((List<Procedure<MasterProcedureEnv>>) allProcedures);
    serverManager.findDeadServersAndProcess(deadServersFromPE, liveServersFromWALDir);
    ArgumentCaptor<ServerName> serverCaptor = ArgumentCaptor.forClass(ServerName.class);
    verify(serverManager, times(2)).expireServer(serverCaptor.capture());
    Set<ServerName> expiredServers = new HashSet<>(serverCaptor.getAllValues());
    assertEquals("Only 2 servers should be expired", 2, expiredServers.size());
    assertTrue("Server2 should be expired", expiredServers.contains(server2));
    assertTrue("Server4 should be expired", expiredServers.contains(server4));
    assertFalse("Server1 should NOT be expired (has active SCP)", expiredServers.contains(server1));
    assertFalse("Server3 should NOT be expired (has active SCP)", expiredServers.contains(server3));
  }

  /** All servers already have active SCPs. None should get new SCPs. */
  @Test
  public void testAllServersHaveActiveSCPs() throws Exception {
    Map<ServerName, Long> deadServersFromPE = Collections.emptyMap();
    Set<ServerName> liveServersFromWALDir = Set.of(server1, server2, server3);
    // All servers already have active SCPs
    ServerCrashProcedure mockSCP1 = createMockSCP(server1, false);
    ServerCrashProcedure mockSCP2 = createMockSCP(server2, false);
    ServerCrashProcedure mockSCP3 = createMockSCP(server3, false);
    List<Procedure<MasterProcedureEnv>> allProcedures =
      Arrays.<Procedure<MasterProcedureEnv>> asList(mockSCP1, mockSCP2, mockSCP3);
    when(mockProcExec.getProcedures())
      .thenReturn((List<Procedure<MasterProcedureEnv>>) allProcedures);
    serverManager.findDeadServersAndProcess(deadServersFromPE, liveServersFromWALDir);
    verify(serverManager, never()).expireServer(any(ServerName.class));
  }

  /** Previously scheduled SCPs should prevent duplicate scheduling. */
  @Test
  public void testExistingSCPsPrevention() throws Exception {
    Map<ServerName, Long> deadServersFromPE = Collections.emptyMap();
    Set<ServerName> liveServersFromWALDir = Set.of(server1, server2);
    // server1 has an existing SCP (should prevent duplicate)
    ServerCrashProcedure mockExistingSCP = createMockSCP(server1, true); // existing SCP
    List<Procedure<MasterProcedureEnv>> allProcedures =
      Arrays.<Procedure<MasterProcedureEnv>> asList(mockExistingSCP);
    when(mockProcExec.getProcedures())
      .thenReturn((List<Procedure<MasterProcedureEnv>>) allProcedures);
    serverManager.findDeadServersAndProcess(deadServersFromPE, liveServersFromWALDir);
    ArgumentCaptor<ServerName> serverCaptor = ArgumentCaptor.forClass(ServerName.class);
    verify(serverManager, times(1)).expireServer(serverCaptor.capture());
    ServerName expiredServer = serverCaptor.getValue();
    assertEquals("Only server2 should be expired (server1 has existing SCP)", server2,
      expiredServer);
  }

  /** Empty WAL directory case. */
  @Test
  public void testNoCandidateDeadServers() throws Exception {
    Map<ServerName, Long> deadServersFromPE = Collections.emptyMap();
    Set<ServerName> liveServersFromWALDir = Collections.emptySet();
    serverManager.findDeadServersAndProcess(deadServersFromPE, liveServersFromWALDir);
    // expireServer should not be called
    verify(serverManager, never()).expireServer(any(ServerName.class));
    // no interaction with ProcedureExecutor (early return)
    verify(mockProcExec, never()).getProcedures();
  }

  /**
   * Helper method to create a mock ServerCrashProcedure.
   */
  private ServerCrashProcedure createMockSCP(ServerName serverName, boolean finished) {
    ServerCrashProcedure mockSCP = mock(ServerCrashProcedure.class);
    when(mockSCP.getServerName()).thenReturn(serverName);
    when(mockSCP.isFinished()).thenReturn(finished);
    return mockSCP;
  }
}
