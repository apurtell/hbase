/**
 *
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
package org.apache.hadoop.hbase.monitoring;

import java.io.IOException;
import java.io.PrintWriter;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;

import org.apache.yetus.audience.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.util.ExecutorPools;
import org.apache.hadoop.hbase.util.VersionInfo;
import org.apache.hadoop.hbase.util.ExecutorPools.PoolType;

@InterfaceAudience.Private
public abstract class StateDumpServlet extends HttpServlet {
  static final long DEFAULT_TAIL_KB = 100;
  private static final long serialVersionUID = 1L;

  protected void dumpVersionInfo(PrintWriter out) {
    VersionInfo.writeTo(out);

    out.println("Hadoop " + org.apache.hadoop.util.VersionInfo.getVersion());
    out.println("Source code repository " + org.apache.hadoop.util.VersionInfo.getUrl()
      + " revision=" + org.apache.hadoop.util.VersionInfo.getRevision());
    out.println("Compiled by " + org.apache.hadoop.util.VersionInfo.getUser() +
        " on " + org.apache.hadoop.util.VersionInfo.getDate());
  }

  protected boolean isShowQueueDump(Configuration conf){
    return conf.getBoolean("hbase.regionserver.servlet.show.queuedump", true);
  }

  protected long getTailKbParam(HttpServletRequest request) {
    String param = request.getParameter("tailkb");
    if (param == null) {
      return DEFAULT_TAIL_KB;
    }
    return Long.parseLong(param);
  }

  protected void dumpExecutors(PrintWriter out) throws IOException {
    for (PoolType pt: PoolType.values()) {
      ExecutorPools.dumpPool(pt, out);
      ExecutorPools.dumpScheduler(pt, out);
    }
  }
}
