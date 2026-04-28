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
import org.apache.hadoop.hbase.consensus.protobuf.generated.ConsensusProtos;
import org.apache.yetus.audience.InterfaceAudience;

import org.apache.hbase.thirdparty.com.google.protobuf.ByteString;
import org.apache.hbase.thirdparty.com.google.protobuf.UnsafeByteOperations;

/**
 * Immutable value handle for a Raft endpoint's identifying bytes.
 * <p>
 * The wire encoding of an endpoint id is its raw byte sequence (typically UTF-8 of the user-facing
 * label). An {@link EndpointId} wraps that byte sequence once and caches three derived views needed
 * on hot paths:
 * <ul>
 * <li>The defensive copy of the bytes themselves.</li>
 * <li>A {@link ByteString} view via {@link UnsafeByteOperations#unsafeWrap(byte[])}.</li>
 * <li>A pre-built {@link ConsensusProtos.RaftEndpointPB} message that the wire encoder can attach
 * directly to outbound builders without re-encoding the id every send.</li>
 * </ul>
 * <p>
 * Equality and hash code are over the byte content, so two {@link EndpointId}s constructed
 * independently from the same wire bytes compare equal and hash the same. This makes them safe to
 * use as keys in {@link java.util.concurrent.ConcurrentHashMap}s.
 * <p>
 * Construct via {@link #of(byte[])}, {@link #of(String)}, or {@link #of(Object)}. To rewrap a wire
 * {@link ByteString} into a long-lived id (the wire ByteString may share its backing buffer with
 * the decoded frame), use {@link #fromWire(ByteString)}.
 */
@InterfaceAudience.Private
public final class EndpointId {

  private final byte[] bytes;
  private final ByteString byteString;
  private final ConsensusProtos.RaftEndpointPB pb;
  private volatile String stringForm;

  private EndpointId(byte[] bytes) {
    this.bytes = bytes;
    this.byteString = UnsafeByteOperations.unsafeWrap(bytes);
    this.pb = ConsensusProtos.RaftEndpointPB.newBuilder().setId(byteString).build();
  }

  /** Returns an {@link EndpointId} that wraps a defensive copy of {@code bytes}. */
  @NonNull
  public static EndpointId of(@NonNull byte[] bytes) {
    return new EndpointId(bytes.clone());
  }

  /** Returns an {@link EndpointId} for the UTF-8 encoding of {@code label}. */
  @NonNull
  public static EndpointId of(@NonNull String label) {
    return new EndpointId(label.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Normalizes {@code endpointId} to an {@link EndpointId}. Returns the input unchanged when it is
   * already an {@link EndpointId}; wraps {@code byte[]} via {@link #of(byte[])}; otherwise
   * interprets it as a label via {@link Object#toString()} and {@link #of(String)}.
   */
  @NonNull
  public static EndpointId of(@NonNull Object endpointId) {
    if (endpointId instanceof EndpointId) {
      return (EndpointId) endpointId;
    }
    if (endpointId instanceof byte[]) {
      return of((byte[]) endpointId);
    }
    if (endpointId instanceof String) {
      return of((String) endpointId);
    }
    return of(endpointId.toString());
  }

  /**
   * Returns an {@link EndpointId} whose byte content is a defensive copy of {@code wire}. Use this
   * on the inbound side when stashing a wire-derived id into long-lived state, since the
   * {@link ByteString} returned by the protobuf parser may share its backing buffer with the
   * decoded frame.
   */
  @NonNull
  public static EndpointId fromWire(@NonNull ByteString wire) {
    return new EndpointId(wire.toByteArray());
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

  /**
   * Returns a cached {@link ConsensusProtos.RaftEndpointPB} carrying this endpoint's id bytes.
   * Outbound encoders should reuse this instance instead of building a fresh PB on every send.
   */
  @NonNull
  public ConsensusProtos.RaftEndpointPB pb() {
    return pb;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof EndpointId)) {
      return false;
    }
    return Arrays.equals(bytes, ((EndpointId) o).bytes);
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
