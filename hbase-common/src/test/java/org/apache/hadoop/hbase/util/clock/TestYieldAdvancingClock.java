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
package org.apache.hadoop.hbase.util.clock;

import static org.junit.Assert.assertTrue;

import org.apache.hadoop.hbase.HBaseClassTestRule;
import org.apache.hadoop.hbase.testclassification.MiscTests;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.EnvironmentEdge.Clock;
import org.apache.hadoop.hbase.util.HashedBytes;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Category({MiscTests.class, SmallTests.class})
public class TestYieldAdvancingClock {

  final Logger LOG = LoggerFactory.getLogger(TestYieldAdvancingClock.class);
  final HashedBytes KEY = new HashedBytes(Bytes.toBytes("key"));

  @ClassRule
  public static final HBaseClassTestRule CLASS_RULE =
    HBaseClassTestRule.forClass(TestYieldAdvancingClock.class);

  @Test
  public void testAdvance() throws Exception {
    Clock clock = new YieldAdvancingClock(KEY);
    long last = clock.currentTime();
    for (int i = 0; i < 100; i++) {
      long now = clock.currentTimeAdvancing();
      assertTrue("Time did not advance", now > last);
      last = now;
    }
  }

  @Test
  public void testYield() throws Exception {
    InstrumentedYieldAdvancingClock clock = new InstrumentedYieldAdvancingClock(KEY);
    long last = clock.currentTime();
    for (int i = 0; i < 100; i++) {
      // We have to do currentTime immediately before currentTimeAdvancing so both
      // are likely to fall within the same clock tick.
      long now = clock.currentTimeAdvancing();
      assertTrue("Did not advance", now > last);
      last = now;
    }
    LOG.info("ok={}, yields={}",
      ((InstrumentedYieldAdvancingClock)clock).countOk.longValue(),
      ((InstrumentedYieldAdvancingClock)clock).countYields.longValue());
    assertTrue("Did not yield",
      ((InstrumentedYieldAdvancingClock)clock).countYields.longValue() > 0);
  }

}
