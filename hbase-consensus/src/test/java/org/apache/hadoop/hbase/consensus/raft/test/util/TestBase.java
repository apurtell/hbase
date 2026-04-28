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
package org.apache.hadoop.hbase.consensus.raft.test.util;

import java.util.Optional;
import org.apache.hadoop.hbase.HBaseJupiterExtension;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for {@code hbase-consensus} JUnit 5 tests. Combines the project-local timing watcher
 * with the shared {@link HBaseJupiterExtension}, which enforces the {@code @Tag(SmallTests.TAG)}
 * style scale tag, applies the per-tag class timeout (mirrors {@code HBaseClassTestRule} on the
 * JUnit 4 side), and installs the {@code TestSecurityManager} that intercepts {@code System.exit}.
 * <p>
 * Test classes that extend {@code TestBase} should annotate themselves with one of
 * {@code @Tag(SmallTests.TAG)}, {@code @Tag(MediumTests.TAG)}, {@code @Tag(LargeTests.TAG)} or
 * {@code @Tag(IntegrationTests.TAG)}; otherwise the extension fails the class.
 */
@ExtendWith({ HBaseJupiterExtension.class, TestBase.TimingTestWatcher.class })
public class TestBase {
  private static final Logger LOG = LoggerFactory.getLogger(TestBase.class);
  static final ExtensionContext.Namespace NS =
    ExtensionContext.Namespace.create(TimingTestWatcher.class);

  public static class TimingTestWatcher implements TestWatcher, BeforeTestExecutionCallback {
    @Override
    public void beforeTestExecution(ExtensionContext context) {
      context.getStore(NS).put("start", System.nanoTime());
      LOG.info("- STARTED: {}", context.getDisplayName());
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
      long elapsed = elapsed(context);
      LOG.info("+ SUCCEEDED: {} IN {}", context.getDisplayName(), format(elapsed));
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
      long elapsed = elapsed(context);
      LOG.info("- FAILED: {} IN {}", context.getDisplayName(), format(elapsed));
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
      long elapsed = elapsed(context);
      LOG.info("- ABORTED: {} IN {}", context.getDisplayName(), format(elapsed));
    }

    @Override
    public void testDisabled(ExtensionContext context, Optional<String> reason) {
      LOG.info("- DISABLED: {}{}", context.getDisplayName(),
        reason.map(r -> " (" + r + ")").orElse(""));
    }

    private long elapsed(ExtensionContext context) {
      Long start = context.getStore(NS).get("start", Long.class);
      return (start != null) ? System.nanoTime() - start : 0;
    }

    private String format(long nanos) {
      long micros = nanos / 1000;
      long millis = micros / 1000;
      long secs = millis / 1000;
      if (secs > 0) {
        return secs + " secs";
      }
      if (millis > 0) {
        return millis + " millis";
      }
      if (micros > 0) {
        return micros + " micros";
      }
      return nanos + " nanos";
    }
  }
}
