# Task 4: WAL Split Quarantine - Detailed Design Document

## 1. Overview

This document provides the detailed design for implementing WAL Split Quarantine functionality in HBase. This feature prevents a single failed WAL split from blocking entire cluster recovery by automatically quarantining problematic WAL files after repeated failures.

### 1.1 Goals

1. Add automatic quarantine capability to `SplitWALProcedure` after configurable number of failures
2. Implement operator-driven quarantine via a new HBCK2 command
3. Add JMX metrics for monitoring quarantined WAL counts
4. Preserve WAL files for later offline analysis and potential data recovery

### 1.2 Non-Goals

- Automatic recovery or replay of quarantined WALs
- Integration with backup/restore workflows
- Changes to WAL splitting logic itself (only procedure-level changes)

## 2. Background

### 2.1 Current Behavior

The `SplitWALProcedure` is a `StateMachineProcedure` with three states:

```
ACQUIRE_SPLIT_WAL_WORKER → DISPATCH_WAL_TO_WORKER → RELEASE_SPLIT_WORKER
```

Today, when WAL splitting fails (the WAL file still exists after the split attempt), the procedure loops back to `ACQUIRE_SPLIT_WAL_WORKER` indefinitely. This can cause a single problematic WAL to block:
- The parent `ServerCrashProcedure` from completing
- All region assignments for the crashed server

**Known Issues in Existing Code:**

1. **Worker Leak on IOException:** When `isSplitWALFinished()` throws an IOException, the procedure suspends and retries while still holding the worker. This can cause worker exhaustion during HDFS instability.

2. **No Backoff on Split Failure:** When the WAL still exists after split (`!finished`), the procedure retries immediately without backoff. This can cause rapid-fire retries that generate excessive load.

This design addresses both issues in addition to adding quarantine functionality.

### 2.2 Existing Infrastructure

- **Corrupt Directory:** `/hbase/corrupt` already exists for WALs that fail during parsing (detected by `WALSplitter`). We will reuse this directory for quarantined WALs.
- **Retry Counter:** `ProcedureUtil.createRetryCounter()` provides exponential backoff (base 1s, max 10min).
- **HBCK2 Pattern:** HBCK commands follow: Proto definition → `MasterRpcServices` → `Hbck` interface → `HBaseHbck` client.

## 3. Detailed Design

### 3.1 SplitWALProcedure Modifications

#### 3.1.1 New State: QUARANTINE_WAL

Add a new state to the `SplitWALState` enum in `MasterProcedure.proto`:

```protobuf
enum SplitWALState {
  ACQUIRE_SPLIT_WAL_WORKER = 1;
  DISPATCH_WAL_TO_WORKER = 2;
  RELEASE_SPLIT_WORKER = 3;
  QUARANTINE_WAL = 4;  // NEW
}
```

#### 3.1.2 New Instance Variables

The `SplitWALProcedure` class requires two new instance variables to support the quarantine functionality:

1. **`failureCount`**: An integer counter that tracks consecutive split failures for this specific WAL. Each time the procedure detects that the WAL still exists after a split attempt (indicating failure), this counter is incremented. When automatic quarantine is enabled, this counter is compared against the configured threshold to determine if the WAL should be quarantined. This counter is intentionally *not* persisted to the procedure store—on Master restart, it resets to zero. This design choice supports clean cold boot scenarios where transient infrastructure issues (e.g., temporary HDFS unavailability) may have resolved, giving the WAL splitting another fair chance before quarantine.

2. **`forceQuarantine`**: A volatile boolean flag that enables operator-initiated quarantine via the HBCK2 command. When an operator identifies a stuck `SplitWALProcedure` and invokes `quarantine_wal`, this flag is set to `true`. On the procedure's next execution cycle, the `shouldQuarantine()` check will return `true` regardless of the failure count or automatic quarantine configuration. The `volatile` modifier ensures visibility across threads, as the flag is set from the RPC handler thread but read from the procedure executor thread.

These fields work together to provide both automated (threshold-based) and manual (operator-driven) quarantine triggers, giving operators flexibility in how they handle problematic WALs during recovery.

Add the following fields to `SplitWALProcedure`:

```java
public class SplitWALProcedure
  extends StateMachineProcedure<MasterProcedureEnv, MasterProcedureProtos.SplitWALState>
  implements ServerProcedureInterface {
  
  // Existing fields...
  private String walPath;
  private ServerName worker;
  private ServerName crashedServer;
  private RetryCounter retryCounter;
  
  // NEW: Track failure count for quarantine decision
  private int failureCount = 0;
  
  // NEW: Flag to force quarantine (set by HBCK2 command)
  private volatile boolean forceQuarantine = false;
```

#### 3.1.3 Configuration Constants

Add new configuration constants to `HConstants.java`:

```java
/**
 * Configuration key to enable automatic WAL quarantine after repeated failures.
 * When enabled, a WAL that fails to split after the configured number of attempts
 * will be moved to the corrupt directory.
 */
public static final String SPLIT_WAL_QUARANTINE_ENABLED = 
    "hbase.splitwal.quarantine.enabled";

/**
 * Default value for automatic WAL quarantine (disabled).
 */
public static final boolean DEFAULT_SPLIT_WAL_QUARANTINE_ENABLED = false;

/**
 * Configuration key for the number of failed split attempts before automatic quarantine.
 */
public static final String SPLIT_WAL_QUARANTINE_FAILURE_THRESHOLD = 
    "hbase.splitwal.quarantine.failure.threshold";

/**
 * Default number of failures before automatic quarantine (10 attempts).
 */
public static final int DEFAULT_SPLIT_WAL_QUARANTINE_FAILURE_THRESHOLD = 10;
```

The default configuration disables automatic quarantine (`false`) with a conservative threshold (`10`). This is appropriate for normal operations where false positives are costly. However, for cold boot workflows to meet a shorter RTO requirement, quarantine must be enabled with more aggressive settings.

#### 3.1.4 Modified State Machine

Update `executeFromState()` in `SplitWALProcedure`:

The `executeFromState()` method is the core of the `StateMachineProcedure` pattern—it is called repeatedly by the procedure executor, advancing through states until the procedure completes. The existing implementation cycles through three states: `ACQUIRE_SPLIT_WAL_WORKER` → `DISPATCH_WAL_TO_WORKER` → `RELEASE_SPLIT_WORKER`. When splitting fails (the WAL file still exists after the attempt), the procedure currently loops back to `ACQUIRE_SPLIT_WAL_WORKER` indefinitely, which can block cluster recovery.

The primary modification occurs in the `RELEASE_SPLIT_WORKER` state handler. After detecting a failed split—either through an `IOException` when checking completion status, or by finding that the WAL file still exists after the split attempt—the code now increments the `failureCount` before deciding how to proceed. This count provides the data needed for threshold-based quarantine decisions.

Before each retry attempt, the code calls `shouldQuarantine(env)` to evaluate whether quarantine criteria have been met. This check returns true if either the automatic threshold has been exceeded (when enabled) or an operator has requested immediate quarantine via HBCK2. When quarantine is warranted, the state machine transitions to the new `QUARANTINE_WAL` state rather than looping back to `ACQUIRE_SPLIT_WAL_WORKER` for another attempt.

The new `QUARANTINE_WAL` state handler executes the quarantine operation itself. It moves the problematic WAL to the corrupt directory, logs an ERROR-level message warning of potential data loss, updates the JMX metrics, and then completes the procedure successfully. By completing successfully rather than failing, the procedure allows its parent `ServerCrashProcedure` to proceed with the remainder of recovery, even though this particular WAL was not successfully split.

**Bug Fix: Worker Release on IOException.** The existing code does not release the worker when `isSplitWALFinished()` throws an `IOException`—it suspends and retries while still holding the worker, which can cause worker exhaustion during HDFS instability. This design fixes that bug by releasing the worker in the `IOException` catch block before suspending. This is a behavioral change from the existing code, not a preservation of existing behavior.

**Backoff on Retry.** The retry logic uses `ProcedureUtil.createRetryCounter()` for exponential backoff (starting at one second and growing to a maximum of ten minutes) on both `IOException` and `!finished` failure paths. This gives transient infrastructure issues time to resolve before the quarantine threshold is reached. Note: The existing code only uses backoff for `IOException`, not for `!finished`. This design adds backoff to both paths to prevent rapid-fire retries that could trigger quarantine within seconds.

All states must be idempotent for reentry after Master restart. See Section 4 for detailed idempotency analysis.

```java
@Override
protected Flow executeFromState(MasterProcedureEnv env, MasterProcedureProtos.SplitWALState state)
    throws ProcedureSuspendedException, ProcedureYieldException, InterruptedException {
  SplitWALManager splitWALManager = env.getMasterServices().getSplitWALManager();
  switch (state) {
    case ACQUIRE_SPLIT_WAL_WORKER:
      worker = splitWALManager.acquireSplitWALWorker(this);
      setNextState(MasterProcedureProtos.SplitWALState.DISPATCH_WAL_TO_WORKER);
      return Flow.HAS_MORE_STATE;
      
    case DISPATCH_WAL_TO_WORKER:
      assert worker != null;
      addChildProcedure(new SplitWALRemoteProcedure(worker, crashedServer, walPath));
      setNextState(MasterProcedureProtos.SplitWALState.RELEASE_SPLIT_WORKER);
      return Flow.HAS_MORE_STATE;
      
    case RELEASE_SPLIT_WORKER:
      boolean finished;
      try {
        finished = splitWALManager.isSplitWALFinished(walPath);
      } catch (IOException ioe) {
        // Release worker before handling the failure - critical to avoid worker leak
        // Guard against double-release on reentry (see Section 4.1.3)
        if (worker != null) {
          splitWALManager.releaseSplitWALWorker(worker);
          worker = null;
        }
        
        // IOException during check counts as a failure
        failureCount++;
        LOG.warn("Failed to check whether splitting wal {} succeeded (attempt {}), "
            + "will retry", walPath, failureCount, ioe);
        
        if (shouldQuarantine(env)) {
          setNextState(MasterProcedureProtos.SplitWALState.QUARANTINE_WAL);
          return Flow.HAS_MORE_STATE;
        }
        
        if (retryCounter == null) {
          retryCounter = ProcedureUtil.createRetryCounter(env.getMasterConfiguration());
        }
        long backoff = retryCounter.getBackoffTimeAndIncrementAttempts();
        LOG.warn("Wait {} seconds to retry", backoff / 1000);
        setTimeout(Math.toIntExact(backoff));
        setState(ProcedureProtos.ProcedureState.WAITING_TIMEOUT);
        skipPersistence();
        throw new ProcedureSuspendedException();
      }
      
      // Release worker - guard against double-release on reentry (see Section 4.1.3)
      if (worker != null) {
        splitWALManager.releaseSplitWALWorker(worker);
        worker = null;
      }
      
      if (!finished) {
        // WAL still exists after split attempt - this is a failure
        failureCount++;
        LOG.warn("Failed to split wal {} (attempt {}), retry...", walPath, failureCount);
        
        if (shouldQuarantine(env)) {
          setNextState(MasterProcedureProtos.SplitWALState.QUARANTINE_WAL);
          return Flow.HAS_MORE_STATE;
        }
        
        // Use backoff before retry to avoid rapid-fire retries
        // This is a behavioral change from existing code which retries immediately
        if (retryCounter == null) {
          retryCounter = ProcedureUtil.createRetryCounter(env.getMasterConfiguration());
        }
        long backoff = retryCounter.getBackoffTimeAndIncrementAttempts();
        LOG.info("Wait {} seconds before retry attempt {}", backoff / 1000, failureCount + 1);
        setTimeout(Math.toIntExact(backoff));
        setState(ProcedureProtos.ProcedureState.WAITING_TIMEOUT);
        setNextState(MasterProcedureProtos.SplitWALState.ACQUIRE_SPLIT_WAL_WORKER);
        skipPersistence();
        throw new ProcedureSuspendedException();
      }
      
      ServerCrashProcedure.updateProgress(env, getParentProcId());
      return Flow.NO_MORE_STATE;
      
    case QUARANTINE_WAL:
      return executeQuarantine(env, splitWALManager);
      
    default:
      throw new UnsupportedOperationException("unhandled state=" + state);
  }
}

/**
 * Determine if the WAL should be quarantined based on failure count and configuration.
 */
private boolean shouldQuarantine(MasterProcedureEnv env) {
  // Check if force quarantine was requested via HBCK2
  if (forceQuarantine) {
    LOG.info("Force quarantine requested for WAL {}", walPath);
    return true;
  }
  
  Configuration conf = env.getMasterConfiguration();
  boolean quarantineEnabled = conf.getBoolean(
      HConstants.SPLIT_WAL_QUARANTINE_ENABLED,
      HConstants.DEFAULT_SPLIT_WAL_QUARANTINE_ENABLED);
  
  if (!quarantineEnabled) {
    return false;
  }
  
  int threshold = conf.getInt(
      HConstants.SPLIT_WAL_QUARANTINE_FAILURE_THRESHOLD,
      HConstants.DEFAULT_SPLIT_WAL_QUARANTINE_FAILURE_THRESHOLD);
  
  return failureCount >= threshold;
}

/**
 * Execute the quarantine operation - move WAL to corrupt directory.
 * This method is idempotent for reentry after Master restart.
 */
private Flow executeQuarantine(MasterProcedureEnv env, SplitWALManager splitWALManager)
    throws ProcedureSuspendedException {
  try {
    SplitWALManager.QuarantineResult result = 
        splitWALManager.quarantineWAL(walPath, crashedServer);
    
    if (result.isNewlyQuarantined()) {
      // Only log the data loss warning and update metrics on first quarantine
      LOG.error("Quarantining WAL {} for crashed server {} after {} failed split attempts. "
          + "POTENTIAL DATA LOSS: Edits in this WAL will not be recovered automatically. "
          + "The WAL has been moved to the corrupt directory for manual analysis. "
          + "Use WALPlayer or similar tools to attempt manual recovery.",
          walPath, crashedServer, failureCount);
      
      // Update metrics only when we actually quarantine
      env.getMasterServices().getMasterMetrics().incrementQuarantinedWALCount();
      
      LOG.info("WAL {} successfully quarantined to {}", walPath, result.getQuarantinePath());
    } else if (result.getQuarantinePath() != null) {
      // Already quarantined - this is idempotent reentry
      LOG.info("WAL {} was already quarantined at {} (reentry after restart)",
          walPath, result.getQuarantinePath());
    } else {
      // WAL not found - may have been processed normally
      LOG.warn("WAL {} not found for quarantine; proceeding with recovery", walPath);
    }
    
    // Complete successfully regardless - the parent SCP can proceed
    ServerCrashProcedure.updateProgress(env, getParentProcId());
    return Flow.NO_MORE_STATE;
    
  } catch (IOException e) {
    LOG.error("Failed to quarantine WAL {}, will retry", walPath, e);
    // Use retry counter for quarantine operation itself
    if (retryCounter == null) {
      retryCounter = ProcedureUtil.createRetryCounter(env.getMasterConfiguration());
    }
    long backoff = retryCounter.getBackoffTimeAndIncrementAttempts();
    setTimeout(Math.toIntExact(backoff));
    setState(ProcedureProtos.ProcedureState.WAITING_TIMEOUT);
    skipPersistence();
    throw new ProcedureSuspendedException();
  }
}

/**
 * Called by HBCK2 to force quarantine of this procedure's WAL.
 */
public void setForceQuarantine() {
  this.forceQuarantine = true;
}

/**
 * Get the current failure count for this procedure.
 */
public int getFailureCount() {
  return failureCount;
}
```

#### 3.1.5 Serialization Updates

Update `serializeStateData()` and `deserializeStateData()` to handle the new field:

```java
@Override
protected void serializeStateData(ProcedureStateSerializer serializer) throws IOException {
  super.serializeStateData(serializer);
  MasterProcedureProtos.SplitWALData.Builder builder =
      MasterProcedureProtos.SplitWALData.newBuilder();
  builder.setWalPath(walPath).setCrashedServer(ProtobufUtil.toServerName(crashedServer));
  if (worker != null) {
    builder.setWorker(ProtobufUtil.toServerName(worker));
  }
  // Note: failureCount is intentionally NOT serialized
  // This allows clean restart behavior for cold boot scenarios
  serializer.serialize(builder.build());
}
```

No changes needed to `deserializeStateData()` since we don't persist `failureCount`.

### 3.2 SplitWALManager Modifications

The `SplitWALManager` class is responsible for coordinating WAL splitting across the cluster. It manages worker assignments, tracks split progress, and determines when splitting is complete. To support the quarantine feature, we extend this class with methods that handle the physical movement of WAL files to the quarantine directory.

The quarantine operation reuses the existing `/hbase/corrupt` directory that HBase already uses for WAL files that fail during parsing in `WALSplitter`. By placing quarantined WALs in this same location, we maintain consistency with existing operational patterns—operators already know to check this directory for problematic files that require manual attention.

The implementation organizes quarantined WALs into server-specific subdirectories under the corrupt directory, using the pattern `/hbase/corrupt/<server-name>/<wal-file>`. This structure makes it easy to identify which crashed server a quarantined WAL belonged to, which is essential information for manual recovery attempts using tools like `WALPlayer`.

Three new methods are added to `SplitWALManager`. The `quarantineWAL()` method is the primary interface used by `SplitWALProcedure` when it decides to quarantine a WAL. It takes the WAL path and crashed server name, constructs the appropriate target path in the corrupt directory, creates the server subdirectory if needed, and performs the file move using `CommonFSUtils.renameAndSetModifyTime()` to preserve proper file metadata.

The `quarantineWALByPath()` method provides an alternative entry point for the HBCK2 command when quarantining a WAL directly by path, without an active `SplitWALProcedure`. This method must extract the server name from the WAL's directory path, handling both normal WAL directories and those with the `-splitting` suffix that indicates an in-progress split operation.

Finally, the `listQuarantinedWALs()` and `countQuarantinedWALs()` methods support operational visibility by allowing enumeration of all quarantined WAL files. These methods scan the corrupt directory structure and return the paths of all quarantined files, which is useful for monitoring and cleanup operations.

```java
/**
 * Directory name for quarantined WALs within the corrupt directory.
 * Full path will be: /hbase/corrupt/<server-name>/<wal-file>
 */
public static final String QUARANTINE_DIR = HConstants.CORRUPT_DIR_NAME;

/**
 * Result of a quarantine operation. Used to ensure idempotency on reentry.
 */
public static class QuarantineResult {
  private final Path quarantinePath;
  private final boolean newlyQuarantined;
  
  public QuarantineResult(Path quarantinePath, boolean newlyQuarantined) {
    this.quarantinePath = quarantinePath;
    this.newlyQuarantined = newlyQuarantined;
  }
  
  /** Path where WAL is quarantined, or null if WAL didn't exist */
  public Path getQuarantinePath() { return quarantinePath; }
  
  /** True if this call actually moved the file; false if already quarantined or not found */
  public boolean isNewlyQuarantined() { return newlyQuarantined; }
}

/**
 * Quarantine a problematic WAL by moving it to the corrupt directory.
 * This method is idempotent - calling it multiple times for the same WAL is safe.
 * See Section 4.1.4 for idempotency analysis.
 * 
 * @param walPath Path to the WAL file to quarantine
 * @param crashedServer The server that owned this WAL
 * @return QuarantineResult indicating what happened
 * @throws IOException if the move operation fails
 */
public QuarantineResult quarantineWAL(String walPath, ServerName crashedServer) 
    throws IOException {
  Path sourcePath = new Path(rootDir, walPath);
  
  // Construct target path: /hbase/corrupt/<server-name>/<wal-file>
  Path corruptDir = new Path(rootDir, QUARANTINE_DIR);
  Path serverCorruptDir = new Path(corruptDir, crashedServer.getServerName());
  Path targetPath = new Path(serverCorruptDir, sourcePath.getName());
  
  // Check current state for idempotency
  boolean sourceExists = fs.exists(sourcePath);
  boolean targetExists = fs.exists(targetPath);
  
  if (targetExists) {
    // Already quarantined - idempotent reentry
    LOG.info("WAL already quarantined at {} (idempotent reentry)", targetPath);
    return new QuarantineResult(targetPath, false);  // false = not newly quarantined
  }
  
  if (!sourceExists) {
    // Neither source nor target exists - WAL may have been processed normally
    LOG.warn("WAL {} does not exist and not found in quarantine directory; "
        + "may have been processed normally or already deleted", sourcePath);
    return new QuarantineResult(null, false);
  }
  
  // Source exists, target does not - perform the move
  // Ensure the directory exists
  if (!fs.exists(serverCorruptDir)) {
    if (!fs.mkdirs(serverCorruptDir)) {
      throw new IOException("Failed to create quarantine directory: " + serverCorruptDir);
    }
    LOG.info("Created quarantine directory: {}", serverCorruptDir);
  }
  
  // Move the WAL file
  if (!CommonFSUtils.renameAndSetModifyTime(fs, sourcePath, targetPath)) {
    throw new IOException("Failed to move WAL " + sourcePath + " to " + targetPath);
  }
  LOG.info("Quarantined WAL {} to {}", sourcePath, targetPath);
  
  return new QuarantineResult(targetPath, true);  // true = newly quarantined
}

/**
 * Quarantine a WAL by path directly (for HBCK2 command).
 * This method is idempotent - calling it multiple times for the same WAL is safe.
 * 
 * @param walPath Full path to the WAL file
 * @return QuarantineResult indicating what happened
 * @throws IOException if the move operation fails or server name cannot be parsed
 */
public QuarantineResult quarantineWALByPath(Path walPath) throws IOException {
  // Extract server name from WAL path
  // WAL paths are typically: /hbase/WALs/<server-name>-splitting/<wal-file>
  // or: /hbase/WALs/<server-name>/<wal-file>
  // Note: getServerNameFromWALDirectoryName expects the directory, not the file path
  Path walDir = walPath.getParent();
  ServerName serverName = AbstractFSWALProvider.getServerNameFromWALDirectoryName(walDir);
  if (serverName == null) {
    // Fallback: use parent directory name as server identifier
    String serverDirName = walDir.getName();
    // Remove -splitting suffix if present
    if (serverDirName.endsWith(AbstractFSWALProvider.SPLITTING_EXT)) {
      serverDirName = serverDirName.substring(0, 
          serverDirName.length() - AbstractFSWALProvider.SPLITTING_EXT.length());
    }
    try {
      serverName = ServerName.parseServerName(serverDirName);
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new IOException("Cannot parse server name from WAL path: " + walPath 
          + " (directory: " + serverDirName + ")", e);
    }
    if (serverName == null) {
      throw new IOException("Cannot determine server name from WAL path: " + walPath);
    }
  }
  
  // Construct relative path for quarantine
  String relativePath = CommonFSUtils.removeWALRootPath(walPath, conf);
  return quarantineWAL(relativePath, serverName);
}

/**
 * List all quarantined WAL files.
 * 
 * @return List of paths to quarantined WAL files
 * @throws IOException if listing fails
 */
public List<Path> listQuarantinedWALs() throws IOException {
  List<Path> result = new ArrayList<>();
  Path corruptDir = new Path(rootDir, QUARANTINE_DIR);
  
  if (!fs.exists(corruptDir)) {
    return result;
  }
  
  // List all server directories under corrupt dir
  FileStatus[] serverDirs = fs.listStatus(corruptDir);
  for (FileStatus serverDir : serverDirs) {
    if (serverDir.isDirectory()) {
      FileStatus[] wals = fs.listStatus(serverDir.getPath());
      for (FileStatus wal : wals) {
        if (!wal.isDirectory()) {
          result.add(wal.getPath());
        }
      }
    }
  }
  
  return result;
}

/**
 * Count quarantined WAL files.
 * 
 * @return Number of quarantined WAL files
 * @throws IOException if listing fails
 */
public int countQuarantinedWALs() throws IOException {
  return listQuarantinedWALs().size();
}
```

### 3.3 HBCK2 Command: quarantine_wal

#### 3.3.1 Proto Definition Updates

Add to `Master.proto`:

```protobuf
message QuarantineWALRequest {
  // Either proc_id OR wal_path must be specified, not both
  optional uint64 proc_id = 1;
  optional string wal_path = 2;
}

message QuarantineWALResponse {
  required bool success = 1;
  optional string quarantine_path = 2;  // Path where WAL was moved
  optional string error_message = 3;    // Error message if success=false
}
```

Add to the `HbckService`:

```protobuf
service HbckService {
  // ... existing methods ...
  
  /** Quarantine a stuck WAL split procedure or WAL file directly */
  rpc QuarantineWAL(QuarantineWALRequest)
    returns(QuarantineWALResponse);
}
```

#### 3.3.2 Hbck Interface Addition

The `Hbck` interface defines the client-facing API for HBCK2 operations. Two new methods are added to support the quarantine functionality. The `quarantineWALByProcId()` method allows operators to target a specific stuck `SplitWALProcedure` by its procedure ID, which can be obtained from the Master UI or procedure store. The `quarantineWALByPath()` method provides a fallback for cases where the procedure has already been bypassed or no longer exists, allowing direct quarantine of a WAL file by its HDFS path.

```java
/**
 * Quarantine a stuck WAL split procedure by procedure ID.
 * This forces the SplitWALProcedure to move its WAL to the corrupt directory
 * and complete successfully, unblocking recovery.
 * 
 * <p><b>Important:</b> This operation is <b>asynchronous</b>. It sets a flag on the
 * procedure and wakes it up, but the actual quarantine happens when the procedure
 * executes. The return value will be null or empty on success because the quarantine
 * hasn't occurred yet. Monitor the procedure's completion or check for the WAL in
 * the corrupt directory to confirm success.
 * 
 * @param procId The procedure ID of a stuck SplitWALProcedure
 * @return Always null or empty on success (async operation); the quarantine path
 *         is not available until the procedure completes
 * @throws IOException if the procedure is not found, not a SplitWALProcedure,
 *         already finished, or a network error occurs
 */
String quarantineWALByProcId(long procId) throws IOException;

/**
 * Quarantine a WAL file directly by path.
 * This moves the WAL to the corrupt directory without requiring an active procedure.
 * Useful when the SplitWALProcedure has already been bypassed or does not exist.
 * 
 * <p><b>Important:</b> This operation is <b>synchronous</b>. The WAL is moved
 * within this RPC call and the quarantine path is returned upon success.
 * 
 * @param walPath The HDFS path to the WAL file
 * @return Path where the WAL was quarantined, or null if the WAL was not found
 *         (may have been processed normally or already quarantined)
 * @throws IOException if the move operation fails or a network error occurs
 */
String quarantineWALByPath(String walPath) throws IOException;
```

#### 3.3.3 HBaseHbck Implementation

Add to `HBaseHbck.java`:

```java
@Override
public String quarantineWALByProcId(long procId) throws IOException {
  try {
    MasterProtos.QuarantineWALResponse response = 
        this.hbck.quarantineWAL(rpcControllerFactory.newController(),
            MasterProtos.QuarantineWALRequest.newBuilder()
                .setProcId(procId)
                .build());
    if (!response.getSuccess()) {
      throw new IOException("Failed to quarantine WAL: " + response.getErrorMessage());
    }
    return response.getQuarantinePath();
  } catch (ServiceException se) {
    LOG.debug("Failed to quarantine WAL for procedure {}", procId, se);
    throw new IOException(se);
  }
}

@Override
public String quarantineWALByPath(String walPath) throws IOException {
  try {
    MasterProtos.QuarantineWALResponse response = 
        this.hbck.quarantineWAL(rpcControllerFactory.newController(),
            MasterProtos.QuarantineWALRequest.newBuilder()
                .setWalPath(walPath)
                .build());
    if (!response.getSuccess()) {
      throw new IOException("Failed to quarantine WAL: " + response.getErrorMessage());
    }
    return response.getQuarantinePath();
  } catch (ServiceException se) {
    LOG.debug("Failed to quarantine WAL at path {}", walPath, se);
    throw new IOException(se);
  }
}
```

#### 3.3.4 MasterRpcServices Implementation

The `MasterRpcServices` class handles the server-side processing of RPC requests to the Master. The `quarantineWAL` method implements the core logic for the HBCK2 quarantine command, supporting two distinct modes of operation based on how the operator invokes it.

When the operator provides a procedure ID, the method looks up the procedure in the `MasterProcedureExecutor` and validates that it exists, is a `SplitWALProcedure`, and has not already completed. If validation passes, it sets the `forceQuarantine` flag on the procedure and then wakes it up using the new `wakeupProcedure()` helper method. This wake-up is necessary because a procedure in the retry backoff cycle may be suspended in the `WAITING_TIMEOUT` state, potentially sleeping for up to ten minutes. Without an explicit wake-up, the operator would have to wait for the current backoff to expire before the quarantine takes effect. The quarantine itself happens asynchronously when the procedure resumes execution and checks the `forceQuarantine` flag.

When the operator provides a WAL path instead, the method bypasses the procedure framework entirely and calls `SplitWALManager.quarantineWALByPath()` directly. This synchronous path is useful when a `SplitWALProcedure` has already been bypassed using the existing HBCK2 `bypass` command but the underlying WAL file still needs to be moved to the corrupt directory. In this case, the method can return the quarantine path immediately since the operation completes within the RPC call.

Both paths include proper audit logging using `getClientIdAuditPrefix()` to record who initiated the quarantine, and both update the JMX metrics to track quarantined WAL counts.

```java
@Override
public MasterProtos.QuarantineWALResponse quarantineWAL(RpcController controller,
    MasterProtos.QuarantineWALRequest request) throws ServiceException {
  MasterProtos.QuarantineWALResponse.Builder responseBuilder = 
      MasterProtos.QuarantineWALResponse.newBuilder();
  
  try {
    checkMasterProcedureExecutor();
    
    if (request.hasProcId()) {
      // Quarantine by procedure ID
      long procId = request.getProcId();
      LOG.info("{} quarantine WAL for procedure {}", server.getClientIdAuditPrefix(), procId);
      
      Procedure<?> proc = server.getMasterProcedureExecutor().getProcedure(procId);
      if (proc == null) {
        responseBuilder.setSuccess(false);
        responseBuilder.setErrorMessage("Procedure " + procId + " not found");
        return responseBuilder.build();
      }
      
      if (!(proc instanceof SplitWALProcedure)) {
        responseBuilder.setSuccess(false);
        responseBuilder.setErrorMessage("Procedure " + procId + 
            " is not a SplitWALProcedure, it is a " + proc.getClass().getSimpleName());
        return responseBuilder.build();
      }
      
      SplitWALProcedure splitWALProc = (SplitWALProcedure) proc;
      
      if (splitWALProc.isFinished()) {
        responseBuilder.setSuccess(false);
        responseBuilder.setErrorMessage("Procedure " + procId + " has already finished");
        return responseBuilder.build();
      }
      
      // Set force quarantine flag - the procedure will pick this up on next execution
      splitWALProc.setForceQuarantine();
      
      // Wake up the procedure if it's suspended.
      // Use the new wakeupProcedure() helper method (see Section 3.5)
      boolean wokenUp = server.getMasterProcedureExecutor().wakeupProcedure(splitWALProc);
      if (wokenUp) {
        LOG.info("Procedure {} woken up for immediate quarantine", procId);
      } else {
        LOG.info("Procedure {} already running or about to run; quarantine flag set", procId);
      }
      
      responseBuilder.setSuccess(true);
      // Note: We can't return the quarantine path yet as it hasn't happened.
      // The procedure will complete asynchronously. The caller should monitor
      // the procedure's completion or check for the WAL in the corrupt directory.
      
    } else if (request.hasWalPath()) {
      // Quarantine by WAL path directly
      String walPath = request.getWalPath();
      LOG.info("{} quarantine WAL at path {}", server.getClientIdAuditPrefix(), walPath);
      
      Path hdfsPath = new Path(walPath);
      SplitWALManager.QuarantineResult result = 
          server.getSplitWALManager().quarantineWALByPath(hdfsPath);
      
      responseBuilder.setSuccess(true);
      if (result.getQuarantinePath() != null) {
        responseBuilder.setQuarantinePath(result.getQuarantinePath().toString());
      }
      
      // Update metrics only if we actually quarantined (idempotency)
      if (result.isNewlyQuarantined()) {
        server.getMasterMetrics().incrementQuarantinedWALCount();
      }
      
    } else {
      responseBuilder.setSuccess(false);
      responseBuilder.setErrorMessage("Either proc_id or wal_path must be specified");
    }
    
  } catch (IOException e) {
    LOG.error("Failed to quarantine WAL", e);
    responseBuilder.setSuccess(false);
    responseBuilder.setErrorMessage(e.getMessage());
  }
  
  return responseBuilder.build();
}
```

### 3.4 JMX Metrics

#### 3.4.1 MetricsMaster Updates

Add to `MetricsMasterSource.java`:

```java
/**
 * Name of the metric for quarantined WAL count.
 */
String QUARANTINED_WAL_COUNT = "quarantinedWALCount";
String QUARANTINED_WAL_COUNT_DESC = "Number of WAL files that have been quarantined";
```

Add to `MetricsMasterSourceImpl.java`:

```java
private MutableGaugeLong quarantinedWALCount;

// In init():
quarantinedWALCount = metricsRegistry.newGauge(QUARANTINED_WAL_COUNT, 
    QUARANTINED_WAL_COUNT_DESC, 0L);

public void incrementQuarantinedWALCount() {
  quarantinedWALCount.incr();
}

public long getQuarantinedWALCount() {
  return quarantinedWALCount.value();
}
```

Add to `MetricsMaster.java`:

```java
public void incrementQuarantinedWALCount() {
  masterSource.incrementQuarantinedWALCount();
}
```

### 3.5 ProcedureExecutor Helper Method

When an operator invokes the HBCK2 `quarantine_wal` command, they expect immediate action. However, a `SplitWALProcedure` that is retrying after failures will be in the `WAITING_TIMEOUT` state, suspended in the `TimeoutExecutorThread`'s delay queue waiting for its backoff timer to expire. The backoff uses exponential growth (1s, 2s, 4s, ... up to 10 minutes), meaning a procedure that has been retrying for a while could be sleeping for up to 10 minutes before it naturally wakes.

Simply setting the `forceQuarantine` flag without waking the procedure would leave the operator waiting for an indeterminate period—unacceptable for an emergency recovery tool.

We could document that the HBCK2 command only sets a flag, and the actual quarantine occurs when the procedure's current timeout expires. This avoids any changes to the procedure framework. However, up to 10 minutes of delay is unacceptable for an emergency operator intervention. The HBCK2 `bypass` command provides a precedent for immediate action on stuck procedures.

We could try to set the procedure's timeout to 0 and re-add it to the timeout queue, causing it to fire immediately. However, manipulating the timeout externally while the procedure is in the delay queue could introduce race conditions.

To properly wake up a procedure that may be in `WAITING_TIMEOUT` state from external code (like `MasterRpcServices`), we need to add a public helper method that encapsulates the correct wake-up logic already proven in `bypassProcedure()`.

Add to `ProcedureExecutor.java`:

```java
/**
 * Wake up a suspended procedure so it can be re-executed.
 * Handles both WAITING_TIMEOUT procedures (in the timeout queue) and other suspended states.
 * 
 * @param procedure The procedure to wake up
 * @return true if the procedure was successfully woken up, false if it was already
 *         running or could not be found in the expected queue
 */
public boolean wakeupProcedure(Procedure<TEnvironment> procedure) {
  if (procedure == null || procedure.isFinished()) {
    LOG.debug("Cannot wake up procedure: {} (null or finished)", procedure);
    return false;
  }
  
  if (procedure.getState() == ProcedureState.WAITING_TIMEOUT) {
    // For WAITING_TIMEOUT procedures, remove from timeout queue and execute
    // See bypassProcedure() for this pattern
    if (timeoutExecutor.remove(procedure)) {
      LOG.debug("Waking up procedure {} from WAITING_TIMEOUT state", procedure);
      timeoutExecutor.executeTimedoutProcedure(procedure);
      return true;
    } else {
      // Procedure was not in timeout queue - may have already been dequeued and is running,
      // or its timeout just expired. This is not an error; the procedure will execute soon.
      LOG.info("Procedure {} in WAITING_TIMEOUT state but not found in timeout queue; "
          + "it may already be executing or about to execute", procedure);
      return false;
    }
  } else if (procedure.getState() == ProcedureState.WAITING) {
    // For WAITING procedures, set to runnable and add to scheduler
    LOG.debug("Waking up procedure {} from WAITING state", procedure);
    procedure.setState(ProcedureState.RUNNABLE);
    scheduler.addFront(procedure);
    return true;
  } else if (procedure.getState() == ProcedureState.RUNNABLE) {
    // Already runnable, just ensure it's in the scheduler
    LOG.debug("Procedure {} already RUNNABLE, adding to scheduler front", procedure);
    scheduler.addFront(procedure);
    return true;
  } else {
    LOG.debug("Cannot wake up procedure {} in state {}", procedure, procedure.getState());
    return false;
  }
}
```

This method encapsulates the same logic used in `bypassProcedure()` (lines 1085-1092) but exposes it for external callers, with enhancements:

1. **Returns boolean** indicating whether the wake-up succeeded, allowing callers to know if the procedure was actually woken up or was already running.
2. **Handles WAITING state** in addition to WAITING_TIMEOUT, covering procedures suspended on events.
3. **Provides detailed logging** when the procedure cannot be found in the timeout queue (which indicates it's already running or about to run—not an error condition).
4. **Handles RUNNABLE state** by ensuring the procedure is in the scheduler, even if it's already runnable.

This helper method may prove useful for other HBCK2 commands or operational tools that need to wake suspended procedures, making it a generally useful addition to the `ProcedureExecutor` API.

## 4. Idempotency Analysis

All states in `SplitWALProcedure` must be idempotent for reentry after Master restart. The procedure framework guarantees that on restart, procedures resume from their last persisted state. This means each state's logic may execute multiple times if the Master crashes after executing the state but before persisting the transition to the next state.

### 4.1 State-by-State Idempotency Analysis

#### 4.1.1 ACQUIRE_SPLIT_WAL_WORKER

If the Master crashes after acquiring a worker but before persisting the transition to `DISPATCH_WAL_TO_WORKER`, the procedure will re-enter this state and acquire a new worker, potentially "leaking" the previous worker in `WorkerAssigner`'s accounting. However, the existing `afterReplay()` method already handles this: when a procedure is loaded from the store, the `worker` field is restored from serialized state and `afterReplay()` re-registers it with the `WorkerAssigner` via `addUsedSplitWALWorker(worker)`. If we re-enter `ACQUIRE_SPLIT_WAL_WORKER`, the serialized `worker` field would be `null` (since the state transition didn't persist), so `afterReplay()` correctly does nothing and we acquire a fresh worker. This state is idempotent without modification.

#### 4.1.2 DISPATCH_WAL_TO_WORKER

If the Master crashes after adding the child `SplitWALRemoteProcedure` but before persisting the transition to `RELEASE_SPLIT_WORKER`, the procedure will re-enter this state, raising the concern of duplicate child procedure creation. However, the procedure framework handles this atomically: when `addChildProcedure()` is called, the child is added to a list that is only committed to the procedure store along with the state transition. If the Master crashes before persistence, the child was never persisted, so on restart there's no duplicate; if the crash occurs after persistence, we resume at `RELEASE_SPLIT_WORKER`, not `DISPATCH_WAL_TO_WORKER`. This state is idempotent without modification.

#### 4.1.3 RELEASE_SPLIT_WORKER

This state can transition to `NO_MORE_STATE` (success), `ACQUIRE_SPLIT_WAL_WORKER` (retry), or `QUARANTINE_WAL` (quarantine). The idempotency concern centers on the worker release operation, which decrements a counter in `WorkerAssigner`.

If the Master crashes after releasing the worker but before persisting the state transition, the procedure will reload with the worker field still set from the previous persistence. The existing `afterReplay()` mechanism handles this correctly: it re-registers the worker with `WorkerAssigner` via `addUsedSplitWALWorker()`, so when the replayed execution releases the worker again, the net effect is zero—exactly as if the original release had succeeded. This makes the state idempotent across restarts.

The `worker != null` guard serves a separate purpose: preventing double-release within a single execution when both the IOException catch block and the normal path might attempt to release the worker. By setting `worker = null` immediately after releasing, any subsequent release attempt in the same execution sees null and skips the operation. Since the null assignment persists atomically with the next state transition, crashes before that persistence are handled by `afterReplay()` as described above.

#### 4.1.4 QUARANTINE_WAL

If the Master crashes after moving the WAL to the corrupt directory but before completing the procedure, reentry will attempt the move again. This raises three idempotency concerns: the source file no longer exists (already moved), metrics would be double-counted, and the data loss error log would be emitted again. To address these concerns, the `quarantineWAL()` method checks both source and target paths before taking action. If the target already exists, this is idempotent reentry and we simply log that the WAL was already quarantined. If the source is missing and the target doesn't exist either, we warn that the WAL may have been processed normally. Only when the source exists and the target doesn't do we perform the actual move. The method returns a `QuarantineResult` indicating whether a file was actually moved, which the caller uses to conditionally update metrics and emit the data loss warning only on the first quarantine:

```java
public QuarantineResult quarantineWAL(String walPath, ServerName crashedServer) throws IOException {
  Path sourcePath = new Path(rootDir, walPath);
  Path targetPath = new Path(new Path(new Path(rootDir, QUARANTINE_DIR), 
      crashedServer.getServerName()), sourcePath.getName());
  
  if (fs.exists(targetPath)) {
    LOG.info("WAL already quarantined at {} (idempotent reentry)", targetPath);
    return new QuarantineResult(targetPath, false);
  }
  
  if (!fs.exists(sourcePath)) {
    LOG.warn("WAL {} does not exist and not found in quarantine; may have been processed normally", 
        sourcePath);
    return new QuarantineResult(null, false);
  }
  
  // Source exists, target does not - perform the move
  if (!fs.exists(serverCorruptDir) && !fs.mkdirs(serverCorruptDir)) {
    throw new IOException("Failed to create quarantine directory: " + serverCorruptDir);
  }
  if (!CommonFSUtils.renameAndSetModifyTime(fs, sourcePath, targetPath)) {
    throw new IOException("Failed to move WAL " + sourcePath + " to " + targetPath);
  }
  return new QuarantineResult(targetPath, true);
}
```

The `executeQuarantine()` method uses the result to ensure metrics are only updated and warnings only logged when a file is actually quarantined:

```java
QuarantineResult result = splitWALManager.quarantineWAL(walPath, crashedServer);
if (result.isNewlyQuarantined()) {
  LOG.error("Quarantining WAL {} for crashed server {} after {} failed split attempts. "
      + "POTENTIAL DATA LOSS: Edits in this WAL will not be recovered automatically.",
      walPath, crashedServer, failureCount);
  env.getMasterServices().getMasterMetrics().incrementQuarantinedWALCount();
}
```

### 4.2 Summary of Idempotency Requirements

| State | Idempotent? | Mechanism |
|-------|-------------|-----------|
| ACQUIRE_SPLIT_WAL_WORKER | ✅ Yes | Handled by `afterReplay()` and serialization |
| DISPATCH_WAL_TO_WORKER | ✅ Yes | Framework handles child procedure atomicity |
| RELEASE_SPLIT_WORKER | ✅ Yes | Guard with `worker != null` check; set to null after release |
| QUARANTINE_WAL | ✅ Yes | Check target exists before move; return status for conditional metrics |

## 5. State Machine Diagram

The following diagram illustrates the modified `SplitWALProcedure` state machine with the new `QUARANTINE_WAL` state. This procedure is a `StateMachineProcedure` that coordinates the splitting of a single WAL file by delegating the work to an available RegionServer via `SplitWALRemoteProcedure`.

The key modification is the addition of the `QUARANTINE_WAL` state, which is entered from `RELEASE_SPLIT_WORKER` when either:
1. Automatic quarantine is enabled and the failure count exceeds the configured threshold, or
2. The `forceQuarantine` flag has been set by an HBCK2 command

**Failure paths that increment `failureCount`:**
- **IOException path:** When `isSplitWALFinished()` throws an IOException (e.g., HDFS unavailable), the worker is released, `failureCount` is incremented, and the procedure enters backoff before retrying or quarantining.
- **!finished path:** When the WAL still exists after the split attempt (split failed), the worker is released, `failureCount` is incremented, and the procedure enters backoff before retrying or quarantining.

Both failure paths now use exponential backoff (a behavioral change from existing code which only used backoff for IOException). This prevents rapid-fire retries that could trigger quarantine within seconds.

Once in `QUARANTINE_WAL`, the procedure moves the problematic WAL to the corrupt directory and completes successfully, allowing the parent `ServerCrashProcedure` to proceed with recovery.

```
                           ┌─────────────────────────────────────────────────────┐
                           │                         (retry after backoff)       │
                           ▼                                                     │
    ┌───────────────────────────────────────┐                                    │
    │     ACQUIRE_SPLIT_WAL_WORKER          │                                    │
    │                                       │                                    │
    │  - Acquire worker from SplitWALManager│                                    │
    │  - Set worker field                   │                                    │
    └───────────────────┬───────────────────┘                                    │
                        │                                                        │
                        ▼                                                        │
    ┌───────────────────────────────────────┐                                    │
    │     DISPATCH_WAL_TO_WORKER            │                                    │
    │                                       │                                    │
    │  - Create SplitWALRemoteProcedure     │                                    │
    │  - Add as child procedure             │                                    │
    └───────────────────┬───────────────────┘                                    │
                        │                                                        │
                        ▼                                                        │
    ┌───────────────────────────────────────┐                                    │
    │     RELEASE_SPLIT_WORKER              │                                    │
    │                                       │                                    │
    │  - Check if WAL split finished        │                                    │
    │  - Release worker                     │                                    │
    │  - Increment failureCount if failed   │                                    │
    └───────────────────┬───────────────────┘                                    │
                        │                                                        │
           ┌────────────┼────────────┬───────────────┐                           │
           │            │            │               │                           │
           ▼            │            ▼               ▼                           │
      [Success]         │     [!finished]      [IOException]                     │
           │            │            │               │                           │
           │            │            │   (release worker, failureCount++,        │
           │            │            │    backoff, then retry or quarantine)     │
           │            │            │               │                           │
           │            │            │◄──────────────┘                           │
           │            │            │                                           │
           │            │            ├───────────────────────────────────────────┘
           │            │            │  (if !shouldQuarantine: backoff, retry)
           │            │            │
           │            │            ▼ (if shouldQuarantine)
           │            │   ┌───────────────────────────────────────┐
           │            │   │     QUARANTINE_WAL                    │
           │            │   │                                       │
           │            │   │  - Log ERROR about potential data loss│
           │            │   │  - Move WAL to corrupt directory      │
           │            │   │  - Update metrics                     │
           │            │   └───────────────────┬───────────────────┘
           │            │                       │
           ▼            ▼                       ▼
    ┌───────────────────────────────────────────────────────────────┐
    │                     NO_MORE_STATE (Complete)                  │
    │                                                               │
    │  - Parent SCP can proceed                                     │
    └───────────────────────────────────────────────────────────────┘
```

## 6. Configuration Reference

| Configuration Key | Default | Description |
|-------------------|---------|-------------|
| `hbase.splitwal.quarantine.enabled` | `false` | Enable automatic WAL quarantine after repeated failures |
| `hbase.splitwal.quarantine.failure.threshold` | `10` | Number of failed attempts before automatic quarantine |

## 7. HDFS Directory Structure

After quarantine:

```
/hbase/
  ├── WALs/
  │   ├── server1,16020,1234567890/
  │   │   └── server1%2C16020%2C1234567890.1234567890123
  │   └── server1,16020,1234567890-splitting/
  │       └── (empty after quarantine)
  │
  ├── oldWALs/
  │   └── (successfully processed WALs)
  │
  └── corrupt/                                            ← Quarantine directory
      └── server1,16020,1234567890/                       ← Server subdirectory
          └── server1%2C16020%2C1234567890.1234567890123  ← Quarantined WAL
```

## 8. Testing Strategy

### Unit Tests

1. **Configuration Tests**
   - Verify quarantine does not trigger when `hbase.splitwal.quarantine.enabled` is false (default)
   - Verify quarantine triggers after threshold when enabled
   - Verify custom threshold values are respected

2. **Automatic Quarantine Tests**
   - Verify WAL is quarantined after configured number of failed split attempts
   - Verify quarantined WAL is placed in correct server subdirectory (`/hbase/corrupt/<server-name>/`)
   - Verify `failureCount` increments correctly on each failure
   - Verify procedure completes successfully after quarantine (unblocking parent SCP)

3. **HBCK2 Command Tests**
   - Verify `quarantine_wal` by procedure ID triggers immediate quarantine
   - Verify `quarantine_wal` by WAL path quarantines directly without procedure
   - Verify HBCK2 rejects non-SplitWALProcedure procedure IDs
   - Verify HBCK2 rejects already-finished procedures
   - Verify HBCK2 wakes up suspended procedures in WAITING_TIMEOUT state
   - Verify proper error messages for invalid requests

4. **Metrics Tests**
   - Verify `quarantinedWALCount` JMX metric increments on quarantine
   - Verify metrics are not double-counted on procedure reentry
   - Verify metrics increment for both automatic and HBCK2-triggered quarantine

5. **Worker Management Tests**
   - Verify worker is released when `isSplitWALFinished()` throws IOException
   - Verify worker is released before transitioning to `QUARANTINE_WAL` state
   - Verify worker is released on both IOException and `!finished` failure paths
   - Verify no double-release occurs on procedure reentry (worker set to null after release)

6. **State Idempotency Tests**
   - Verify `failureCount` is not persisted and resets after Master restart
   - Verify quarantine is idempotent when target already exists in corrupt directory
   - Verify quarantine handles gracefully when WAL not found (processed normally)
   - Verify `QuarantineResult.isNewlyQuarantined()` returns true only when file is actually moved
   - Verify `QuarantineResult.isNewlyQuarantined()` returns false when already quarantined
   - Verify worker double-release is prevented on RELEASE_SPLIT_WORKER reentry
   - Verify data loss ERROR log only emitted once (not on reentry)

7. **Backoff Behavior Tests**
   - Verify backoff is used on `!finished` failure path (behavioral change from existing code)
   - Verify backoff is used on `IOException` failure path
   - Verify `retryCounter` is shared across IOException and !finished paths within same procedure
   - Verify backoff timing follows exponential pattern (1s, 2s, 4s, ... up to 10min)

8. **wakeupProcedure Tests**
   - Verify `wakeupProcedure` returns true when procedure is successfully woken from WAITING_TIMEOUT
   - Verify `wakeupProcedure` returns false when procedure not found in timeout queue (already running)
   - Verify `wakeupProcedure` handles WAITING state by setting to RUNNABLE
   - Verify `wakeupProcedure` returns false for finished procedures
   - Verify `wakeupProcedure` logs INFO when procedure not found in timeout queue

9. **Error Handling Tests**
   - Verify `quarantineWALByPath` throws IOException for malformed server name in path
   - Verify `quarantineWALByPath` includes helpful error message with path and directory name
   - Verify `quarantineWALByPath` handles paths not under WAL root gracefully

### Acceptance Criteria

Per the parent design document:
- Unit tests for the modified `SplitWALProcedure` state machine including the new `QUARANTINE_WAL` state
- Unit tests for `SplitWALManager.quarantineWAL()` and related methods
- Unit tests for the HBCK2 `quarantine_wal` command via `MasterRpcServices`

## 9. Operational Considerations

### 9.1 Monitoring

Operators should monitor:

1. **JMX Metric:** `Hadoop:service=HBase,name=Master,sub=Server` → `quarantinedWALCount`
   - Alert if > 0 (any quarantined WAL requires attention)

2. **Log Monitoring:** Watch for ERROR level messages containing "Quarantining WAL"
   - These indicate potential data loss requiring manual intervention

### 9.2 Recovery of Quarantined WALs

To attempt recovery of quarantined WALs:

```bash
# List quarantined WALs
hdfs dfs -ls -R /hbase/corrupt/

# Use WALPlayer to replay edits
hbase org.apache.hadoop.hbase.mapreduce.WALPlayer \
  /hbase/corrupt/server1,16020,1234567890/server1%2C16020%2C1234567890.1234567890123 \
  <target-table>
```

### 9.3 Cleanup

After manual recovery or decision to discard:

```bash
# Remove quarantined WAL after recovery
hdfs dfs -rm /hbase/corrupt/<server>/<wal-file>

# Or remove all quarantined WALs for a server
hdfs dfs -rm -r /hbase/corrupt/<server>
```

## 10. Rollback Plan

If issues are discovered:

1. Disable automatic quarantine: `hbase.splitwal.quarantine.enabled=false`
2. Restart Masters to pick up configuration
3. Quarantined WALs remain in `/hbase/corrupt/` for manual handling
