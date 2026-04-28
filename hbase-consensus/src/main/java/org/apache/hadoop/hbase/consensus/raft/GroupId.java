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
package org.apache.hadoop.hbase.consensus.raft;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.apache.yetus.audience.InterfaceAudience;

import org.apache.hbase.thirdparty.com.google.protobuf.ByteString;
import org.apache.hbase.thirdparty.com.google.protobuf.UnsafeByteOperations;

/**
 * Immutable value handle for a Raft group's identifying bytes.
 * <p>
 * The wire encoding of a group id is its raw byte sequence (typically UTF-8 of the user-facing
 * label). A {@link GroupId} wraps that byte sequence once and caches three derived views needed on
 * hot paths:
 * <ul>
 * <li>The defensive copy of the bytes themselves.</li>
 * <li>A {@link ByteString} view via {@link UnsafeByteOperations#unsafeWrap(byte[])} so the wire
 * encoder can attach the bytes to a protobuf builder without re-copying.</li>
 * <li>A lazily-computed UTF-8 {@link String} for log messages and {@link Object#toString()}.</li>
 * </ul>
 * <p>
 * Equality and hash code are over the byte content, so {@link GroupId}s constructed independently
 * from the same wire bytes compare equal and hash the same. This makes them safe to use as keys in
 * {@link java.util.concurrent.ConcurrentHashMap}s and as dispatcher registry keys (which key on the
 * inbound wire {@link ByteString} that compares equal to {@link #byteString()}).
 * <p>
 * Construct via {@link #of(byte[])}, {@link #of(String)}, or {@link #of(Object)} (the
 * passthrough/normalize entry used at API boundaries that historically accepted {@code Object}). To
 * defensively rewrap a wire {@link ByteString} (its backing buffer is owned by the producer and
 * must not be aliased into long-lived state), use {@link #fromWire(ByteString)}.
 */
@InterfaceAudience.Private
public final class GroupId {

  private final byte[] bytes;
  private final ByteString byteString;
  private volatile String stringForm;

  private GroupId(byte[] bytes) {
    this.bytes = bytes;
    this.byteString = UnsafeByteOperations.unsafeWrap(bytes);
  }

  /** Returns a {@link GroupId} that wraps a defensive copy of {@code bytes}. */
  @NonNull
  public static GroupId of(@NonNull byte[] bytes) {
    return new GroupId(bytes.clone());
  }

  /** Returns a {@link GroupId} for the UTF-8 encoding of {@code label}. */
  @NonNull
  public static GroupId of(@NonNull String label) {
    return new GroupId(label.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Normalizes {@code groupId} to a {@link GroupId}. Returns the input unchanged when it is already
   * a {@link GroupId}; wraps {@code byte[]} via {@link #of(byte[])}; otherwise interprets it as a
   * label via {@link Object#toString()} and {@link #of(String)}. Used at API boundaries that have
   * historically accepted {@code Object} group ids.
   */
  @NonNull
  public static GroupId of(@NonNull Object groupId) {
    if (groupId instanceof GroupId) {
      return (GroupId) groupId;
    }
    if (groupId instanceof byte[]) {
      return of((byte[]) groupId);
    }
    if (groupId instanceof String) {
      return of((String) groupId);
    }
    return of(groupId.toString());
  }

  /**
   * Returns a {@link GroupId} whose byte content is a defensive copy of {@code wire}. Use this on
   * the inbound side when stashing a wire-derived id into long-lived state, since the
   * {@link ByteString} returned by the protobuf parser may share its backing buffer with the
   * decoded frame.
   */
  @NonNull
  public static GroupId fromWire(@NonNull ByteString wire) {
    return new GroupId(wire.toByteArray());
  }

  /** Returns the underlying bytes. Callers must not mutate the returned array. */
  @NonNull
  public byte[] bytes() {
    return bytes;
  }

  /** Returns a cached {@link ByteString} view over the underlying bytes (zero-copy). */
  @NonNull
  public ByteString byteString() {
    return byteString;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof GroupId)) {
      return false;
    }
    return Arrays.equals(bytes, ((GroupId) o).bytes);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(bytes);
  }

  @Override
  public String toString() {
    String s = stringForm;
    if (s == null) {
      s = new String(bytes, StandardCharsets.UTF_8);
      stringForm = s;
    }
    return s;
  }
}
