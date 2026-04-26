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
package org.apache.hadoop.hbase.consensus.raft.impl.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag(SmallTests.TAG)
public class TestLong2ObjectHashMap extends TestBase {
  @Test
  public void testRejectsNegativeCapacity() {
    assertThatThrownBy(() -> new Long2ObjectHashMap<>(-1, Long2ObjectHashMap.DEFAULT_LOAD_FACTOR))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessage("Initial capacity cannot be negative: -1");
  }

  @Test
  public void testRejectsZeroLoadFactor() {
    assertThatThrownBy(() -> new Long2ObjectHashMap<>(8, 0.0d))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessage("Load factor must be > 0 and < 1: 0.0");
  }

  @Test
  public void testRejectsUnitLoadFactor() {
    assertThatThrownBy(() -> new Long2ObjectHashMap<>(8, 1.0d))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessage("Load factor must be > 0 and < 1: 1.0");
  }

  @Test
  public void testCreatesMap() {
    Long2ObjectHashMap<String> map = new Long2ObjectHashMap<>(8, 0.6d);
    map.put(1L, "value");
    assertThat(map.get(1L)).isEqualTo("value");
    assertThat(map.size()).isEqualTo(1);
  }
}
