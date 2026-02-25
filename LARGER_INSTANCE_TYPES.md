# Proposal: Use Larger Instance Types

**Author:** David Manning  
**Date:** January 2026  
**Status:** Proposal for Analysis

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Problems Observed in Production](#problems-observed-in-production)
3. [Design and Philosophy](#design-and-philosophy)
4. [Cost-to-Serve (CTS) Optimization](#cost-to-serve-cts-optimization)
5. [Instance Sizing Guidelines](#instance-sizing-guidelines)
6. [Architecture Overview](#architecture-overview)
7. [AWS Instance Type Analysis](#aws-instance-type-analysis)
8. [EBS Storage Analysis](#ebs-storage-analysis)
9. [JVM Heap Sizing and Garbage Collection](#jvm-heap-sizing-and-garbage-collection)
10. [Off-Heap Memory and BucketCache](#off-heap-memory-and-bucketcache)
11. [NUMA Considerations](#numa-considerations)
12. [Kubernetes and Container Considerations](#kubernetes-and-container-considerations)
13. [Related Work and Dependencies](#related-work-and-dependencies)
14. [Concerns and Risk Mitigation](#concerns-and-risk-mitigation)
15. [Open Questions](#open-questions)
16. [Recommendations](#recommendations)
17. [References](#references)
18. [Appendix A: JVM Flags Quick Reference](#appendix-a-jvm-flags-quick-reference)
19. [Appendix B: Instance Cost Calculator](#appendix-b-instance-cost-calculator)
20. [Change Log](#change-log)

---

## Executive Summary

Our current HBase infrastructure runs on AWS EC2 instances that, while reliable, leave significant optimization opportunities on the table. Kubernetes and AWS EC2 are designed to allocate multiple workloads on each node, yet our current deployment model under-utilizes this capability. The `m` class of EC2 instances currently in use is non-burstable—at creation, each EC2 instance is allocated a fixed amount of vCPUs and memory from the underlying AWS hardware. This means that when a single workload experiences a spike in demand, it cannot access additional resources beyond its allocation, even if those resources sit idle on the same physical machine.

This document proposes a fundamental shift in how we provision and schedule HBase workloads. Rather than continuing to deploy one RegionServer per small-to-medium instance, we advocate for consolidating workloads onto larger instances where they can share resources more efficiently.

Move to larger instance types. By doubling the instance size, we can double the workload assigned to the instance while creating "burstable CPU" capacity for our workloads. A CPU hotspot now has access to 2× CPUs. A memory hotspot is less likely to exhaust the memory of the EC2 instance.

Increase the instance size by 4× and increase the workload per-node by 6×. This approach is likely to increase performance in all but the most extreme cluster-wide cases, while reducing overall spending. This more aggressive configuration targets cost-to-serve optimization while maintaining—or even improving—operational resilience.

### Key Benefits

| Benefit | Description |
|---------|-------------|
| **CPU Bursting** | Hot pods can burst to use nearly 2× the CPU they previously could |
| **Memory Headroom** | Reduced risk of OOM events and failed evictions |
| **Cost Optimization** | ~30% savings in EC2 costs for HBase nodepool with CTS-focused configuration |
| **Vertical Scaling** | Easier to scale individual pods (e.g., 64 GB NameNode or RegionServer) |
| **Alignment** | Better alignment with FKP, Core App, and 1P SSKU configurations |

---

## Problems Observed in Production

Understanding why this proposal matters requires examining the real-world constraints our clusters face today. The following CPU and memory limitations have been observed operating at scale in Hyperforce, and they illustrate a common pattern: individual workloads hitting resource ceilings while neighboring workloads on other nodes sit relatively idle. This inefficiency stems directly from our current one-workload-per-small-instance model, where there is no opportunity for resource sharing or bursting.

### CPU Hotspots

CPU saturation events are among the most disruptive issues in production. When a RegionServer or DataNode reaches 100% CPU utilization, latency spikes immediately, requests queue up, and downstream systems begin to experience timeouts. The following scenarios have been documented:

**Argus Clusters (Regex Scanning):** CPU hotspots when alerts using monex variables repeatedly scan the same keyspace with different regex filters. Heavy scan regex queries cause hotspotting and high cardinality.

**FEEDS Table Scans:** CPU hotspots in prod0 cluster during performance tests creating expensive Scans on the `FEEDS.FEED_ENTITY_READ` table (noisy neighbor scenarios).

**Meta Region During Recovery:** 100% CPU on RegionServers hosting `hbase:meta` during cluster recovery, as load on meta increases significantly.

**SYSTEM.CATALOG Under Load:** 100% CPU on RegionServers hosting Phoenix's `SYSTEM.CATALOG` table under increased query load.

**Migration Hotspots:** 100% CPU on RegionServers processing migration hotspots during data movement operations.

### Memory Exhaustion

Memory pressure presents a different but equally serious challenge. Unlike CPU constraints, which degrade performance gracefully, memory exhaustion can trigger cascading failures as the Linux OOM killer terminates processes or Kubernetes evicts pods unexpectedly.

**Master Nodepool OOM:** Running out of memory on m5.4xlarge EC2 instances in the master nodepool, leading to failed NameNode eviction and loss of the instance.

While doubling or quadrupling available CPU and memory will not guarantee resolution of every hotspot—some issues require application-level fixes—additional headroom consistently helps. A workload that previously had no room to burst can now absorb transient spikes, turning potential outages into minor latency blips.

---

## Design and Philosophy

The core insight behind this proposal is that resource isolation at the EC2 instance level is too coarse-grained for modern containerized workloads. Kubernetes already provides fine-grained resource management through cgroups, allowing multiple containers to share an instance's resources fairly while still providing isolation guarantees. By moving to larger instances, we leverage Kubernetes' scheduling capabilities more effectively, creating a pool of shared resources that individual workloads can draw from as needed.

This approach aligns with industry best practices for container orchestration. Rather than treating each EC2 instance as a dedicated server for a single workload, we treat it as a resource pool that Kubernetes manages dynamically. The result is better utilization, improved burst capacity, and—with careful configuration—meaningful cost savings.

### Current State ("TODAY")

**HBase Pool (100 × m5.4xlarge)**

- Runs 100 RegionServers and 200 DataNodes
- 1 RegionServer per node
- 2 DataNodes per node
- 1 RS + 2 DN share 16 vCPUs + 64 GB memory

**Yarn Pool (103 × m5.4xlarge)**

- Runs 100 NodeManagers + 3 RegionServer-sys
- 1 NodeManager per node, sharing 16 vCPUs + 64 GB
- 1 RegionServer-sys per node, 16 vCPUs + 64 GB

### Target State ("TOMORROW") - Performance Focus

**HBase Pool (50 × 8xlarge)**

- Runs 100 RegionServers and 200 DataNodes
- 2 RegionServers per node
- 4 DataNodes per node
- 2 RS + 4 DN share 32 vCPUs + 128 GB memory

**Yarn Pool (53 × 8xlarge)**

- Runs 100 NodeManagers + 3 RegionServer-sys
- 2 NodeManagers per node, sharing 32 vCPUs + 128 GB
- 1 RegionServer-sys per node, 32 vCPUs + 128 GB

### How It Works

The fundamental mechanism is straightforward: we effectively request the same total hardware from AWS, but we gain flexibility in how it is used. By co-locating multiple workloads on a single larger instance, we create the concept of "burstable CPU" for our workloads. When one RegionServer experiences a traffic spike, it can consume CPU cycles that would otherwise sit idle, effectively doubling (or more) its available compute capacity.

This works because Kubernetes enforces CPU usage via cgroups, which provide fair scheduling based on the `cpuRequests` of the containers. When a container requests 8 CPUs on a 32-CPU node, it is guaranteed access to those 8 CPUs under contention. However, when other containers are idle, it can burst beyond its request to consume available cycles. This behavior is automatic and requires no application changes—only proper configuration of Kubernetes resource requests and limits.

The approach scales naturally. Argus HBase clusters are already using 8xlarge instances, and the same argument can be made to move those to 16xlarge while scheduling 2× the workloads per node. For core clusters, we could consider moving from 4xlarge directly to 16xlarge, scheduling 4× the workloads per node. The optimal instance size depends on balancing burst capacity against blast radius, as discussed in subsequent sections.

---

## Cost-to-Serve (CTS) Optimization

Beyond performance improvements, this proposal offers a significant opportunity to reduce infrastructure costs. The key insight is that memory-optimized instances (the `r` class) provide double the memory for only ~30% higher cost compared to general-purpose instances (the `m` class). When combined with aggressive workload consolidation, this translates to substantial savings.

The CTS-focused configuration takes the performance-oriented approach further by consolidating more aggressively and selecting instance types optimized for our specific workload characteristics. HBase RegionServers are memory-intensive, benefiting from large heap sizes and off-heap caches. By choosing memory-optimized instances and packing more RegionServers per node, we can reduce our EC2 footprint while simultaneously improving cache hit rates and reducing GC pressure.

### CTS-Focused Configuration

**HBase Pool (17 × r8g.8xlarge)**

- Runs 100 RegionServers and 100 DataNodes (colocated)
- 6 RegionServers per node
- 6 DataNodes per node (colocated with RS)
- 6 RS + 6 DN share 32 vCPUs + 256 GB memory

**Yarn Pool (53 × m8g.8xlarge)**

- Runs 100 NodeManagers + 3 RegionServer-sys
- (2 NMs or 1 RS-sys) per node
- Sharing 32 vCPUs + 128 GB

### Key CTS Concepts

1. **RegionServer/DataNode Colocation:** RegionServers and DataNodes are scheduled together on the HBase nodepool. Each node runs 6 RS and 6 DN pods, ensuring data locality and efficient resource utilization.

2. **Memory-Optimized Graviton Instances:** Running Graviton4 `r8g`-class instance types (vs. `m`-class) gives 2× the memory for ~31% increase in cost. Using r8g.8xlarge at 2.45× the cost of m5.4xlarge ($1.885 vs $0.768/hr) but running fewer instances with higher consolidation saves ~39% in EC2 costs for the HBase nodepool while providing more CPU burst capacity and more memory per pod.

### Understanding `hbase_reserved` Costs

Cost attribution in Hyperforce relies on Kubernetes resource requests to allocate EC2 costs to specific services. When an EC2 instance has capacity that is not claimed by any container's resource requests, that capacity is attributed to a `_reserved` bucket. In the Hyperforce Cost Explorer, `_reserved` describes EC2 compute resources that we have requested but not utilized. Having a large amount of `_reserved` cost suggests over-provisioning of compute resources.

The current state is concerning: looking at core1/core2-hbase1a/hbase1b clusters grouped by Allocated Service, >⅓ of cost is allocated to the `hbase_reserved` bucket. This represents money spent on resources that sit idle, providing no value.

Minimizing `_reserved` requires either:

1. Using the CPU and memory in the provisioned EC2 nodes
2. Requesting those resources via Kubernetes container requests

The CTS configuration addresses this by packing more workloads per node, ensuring that a higher percentage of each instance's resources are claimed and utilized. When combined with memory-optimized instances that better match HBase's workload profile, the result is both lower absolute cost and better resource utilization.

---

## Instance Sizing Guidelines

Selecting the right instance size requires balancing multiple competing concerns. Larger instances provide more burst capacity and better resource utilization, but they also increase the blast radius when a node fails. The optimal choice depends on cluster size, workload characteristics, and operational constraints.

This section provides guidelines for making instance sizing decisions. These recommendations are based on observed behavior in production, AWS instance architecture characteristics, and Kubernetes scheduling semantics. They should be treated as starting points for environment-specific tuning rather than absolute rules.

### Sizing Decision Factors

| Factor | Consideration |
|--------|---------------|
| **Burst Capacity** | Larger instances = more resources available for hot pods |
| **Blast Radius** | Larger instances = more impact when a node fails |
| **Upgrade Batch Size** | Instance workload should not exceed upgrade batch capacity |
| **Cluster Size** | Small clusters may need to continue using smaller instance types |
| **NUMA Topology** | Avoid sizes that span multiple NUMA nodes |

### Recommended Instance Size Limits

| Size Class | Recommendation | Rationale |
|------------|----------------|-----------|
| **Sweet Spot** | m/r8g.8xlarge (32 vCPU, 128-256 GB) | Good balance of burst capacity, blast radius, and cost |
| **Maximum** | m/r8g.16xlarge or m/r8g.24xlarge | ~50% of underlying metal; likely tied to 1 NUMA node |
| **Avoid** | Sizes >24xlarge | Guaranteed to spread across 2+ NUMA nodes |

### Cluster Size Modes

Rather than treating instance sizing as a per-cluster decision, consider defining standardized operational modes that can be applied consistently across the fleet. This simplifies operations, reduces configuration drift, and makes capacity planning more predictable. The following modes provide a starting framework:

| Mode | Instance Types | Use Case |
|------|----------------|----------|
| **Tiny** | 4xlarge | Small/dev clusters |
| **Regular** | 8xlarge | Standard production |
| **Power** | 16xlarge | Large-scale production |

Each mode would have corresponding helm chart configurations, monitoring thresholds, and operational runbooks. Clusters could be promoted between modes as they grow, following a standardized migration procedure.

---

## Architecture Overview

To understand how larger instance types affect the system, it is helpful to review the overall architecture. The HBase deployment consists of multiple component layers, each with distinct resource requirements and scheduling constraints. This section describes the current architecture and how components interact across the infrastructure stack.

### System Architecture

The deployment spans multiple Kubernetes nodepools, each optimized for specific workload types. The HBase nodepool runs RegionServers and DataNodes—the core data-serving components. The Yarn nodepool hosts NodeManagers for batch processing along with system RegionServers. A separate master nodepool runs control plane components including NameNodes, HBase Masters, and ZooKeeper.

All persistent storage is provided by AWS EBS volumes, with different volume types selected based on I/O patterns. The overall architecture is illustrated below:

```
┌────────────────────────────────────────────────────────────────────┐
│                        Kubernetes Cluster                          │
├────────────────────────────────────────────────────────────────────┤
│  ┌─────────────────────┐    ┌─────────────────────────────────┐    │
│  │  HBase Nodepool     │    │      Yarn Nodepool              │    │
│  │  (r8g.8xlarge)      │    │      (m8g.8xlarge)              │    │
│  ├─────────────────────┤    ├─────────────────────────────────┤    │
│  │ RegionServer Pods   │    │ NodeManager Pods                │    │
│  │ DataNode Pods       │    │ RegionServer-sys Pods           │    │
│  │ (4 RS + 2-5 DN/node)│    │ DataNode Pods (cross-scheduled) │    │
│  └─────────────────────┘    └─────────────────────────────────┘    │
│                                                                    │
│  ┌─────────────────────┐                                           │
│  │   Master Nodepool   │                                           │
│  │   (m8g.8xlarge)     │                                           │
│  ├─────────────────────┤                                           │
│  │ NameNode Pods       │                                           │
│  │ HBase Master Pods   │                                           │
│  │ ZooKeeper Pods      │                                           │
│  └─────────────────────┘                                           │
└────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌────────────────────────────────────────────────────────────────────┐
│                        AWS EBS Storage                             │
├────────────────────────────────────────────────────────────────────┤
│  ┌─────────────────────┐    ┌─────────────────────────────────┐    │
│  │   ST1 Volumes       │    │   gp3/io2 Volumes               │    │
│  │ (HDFS Data Blocks)  │    │ (WAL, Metadata, Phoenix)        │    │
│  │ 1-16 TiB each       │    │ Higher IOPS for random I/O      │    │
│  └─────────────────────┘    └─────────────────────────────────┘    │
└────────────────────────────────────────────────────────────────────┘
```

### Component Stack

The system comprises multiple software layers, each with distinct performance characteristics and resource requirements. Understanding these layers helps explain why certain instance types and configurations are recommended:

| Layer | Component | Role |
|-------|-----------|------|
| **Query** | Apache Phoenix | SQL interface over HBase; latency-sensitive |
| **Storage** | Apache HBase | RegionServers, block cache, memstore |
| **Filesystem** | Apache HDFS | NameNode (metadata), DataNodes (blocks) |
| **Compute** | Apache Yarn | NodeManagers for batch processing |
| **Orchestration** | Kubernetes | Pod scheduling, resource management |
| **Infrastructure** | AWS EC2 + EBS | Compute instances and block storage |

Phoenix queries drive latency requirements, as end-user requests typically flow through this layer. HBase RegionServers are memory-intensive, benefiting from large heaps and off-heap caches. HDFS DataNodes are throughput-oriented, requiring network and EBS bandwidth more than CPU or memory. Understanding these profiles informs both instance type selection and workload co-location decisions.

---

## AWS Instance Type Analysis

AWS offers a wide range of EC2 instance types, each optimized for different workload profiles. Selecting the right instance type requires understanding the tradeoffs between compute, memory, network, and storage capabilities—as well as cost. This section analyzes the relevant instance families and provides guidance for HBase deployments.

The primary instance families under consideration are:

- **M-class (General Purpose):** Balanced compute, memory, and networking; suitable for mixed workloads
- **R-class (Memory Optimized):** Higher memory-to-vCPU ratio; ideal for memory-intensive applications like HBase
- **Graviton4 (r8g/m8g):** AWS-designed ARM processors offering ~11% cost savings over Intel with equivalent or better performance
- **Intel 7th Gen (r7i/m7i):** Latest Intel x86_64 option for workloads requiring x86 compatibility

### Instance Type Comparison Table

The following table compares relevant instance types across key dimensions. Pricing is based on on-demand rates in us-east-1; reserved instance pricing can reduce costs by 30-50%.

| Instance Type | vCPUs | Memory (GiB) | EBS Bandwidth (Mbps) | Volume Limit | Network (Gbps) | On-Demand Price/hr |
|---------------|-------|--------------|---------------------|--------------|----------------|-------------------|
| m5.4xlarge | 16 | 64 | 4,750 | 27 | Up to 10 | $0.768 |
| m8g.4xlarge | 16 | 64 | 10,000 | 128 | Up to 15 | $0.718 |
| m8g.8xlarge | 32 | 128 | 10,000 | 128 | 15 | $1.436 |
| m7i.8xlarge | 32 | 128 | 10,000 | 32 | 12.5 | $1.613 |
| **r8g.8xlarge** | 32 | 256 | 10,000 | 128 | 15 | $1.885 |
| **r7i.8xlarge** | 32 | 256 | 10,000 | 128 | 12.5 | $2.117 |
| m8g.16xlarge | 64 | 256 | 20,000 | 128 | 30 | $2.872 |
| m7i.16xlarge | 64 | 256 | 20,000 | 48 | 25 | $3.226 |
| r8g.16xlarge | 64 | 512 | 20,000 | 128 | 30 | $3.770 |
| r7i.16xlarge | 64 | 512 | 20,000 | 128 | 25 | $4.234 |
| r8g.24xlarge | 96 | 768 | 30,000 | 128 | 50 | $5.655 |
| r7i.24xlarge | 96 | 768 | 30,000 | 128 | 37.5 | $6.350 |
| **r8g.metal-24xl** | 96 | 768 | 30,000 | 128 | 40 | $5.660 |
| **r7i.metal-24xl** | 96 | 768 | 30,000 | 128 | 37.5 | $6.350 |
| r8g.metal-48xl | 192 | 1,536 | 40,000 | 128 | 50 | $11.310 |
| r7i.metal-48xl | 192 | 1,536 | 40,000 | 128 | 50 | $12.701 |

> **Source:** AWS EC2 Pricing (us-east-1, January 2026). Reserved Instance and Savings Plans pricing can reduce costs by 30-50%.

All prices shown are publicly listed AWS on-demand rates without corporate discounts applied. Actual costs will be lower with negotiated enterprise agreements, but percentage savings and relative comparisons remain consistent regardless of discount level.

### R8g/M8g Graviton4 Instance Family Advantages

Among the available options, the Graviton4-based R8g and M8g instance families stand out for HBase workloads. Built on AWS-designed Graviton4 processors, these instances offer significant improvements over Intel-based alternatives:

- **~11% lower cost** compared to equivalent Intel r7i/m7i instances
- **~30% better performance** than previous Graviton3 generation
- **Up to 128 EBS volume attachments** (matching r7i capabilities)
- **Higher network bandwidth** (up to 50 Gbps for largest sizes vs 37.5 Gbps for Intel)
- **DDR5 memory** with higher bandwidth
- Sizes up to **r8g.48xlarge** (192 vCPU, 1,536 GiB)

The ARM architecture requires recompilation of native code, but Java workloads like HBase run seamlessly on Graviton with OpenJDK ARM builds. The increased EBS volume attachment limit is particularly relevant for HBase deployments, where each DataNode may have multiple attached volumes.

### Cost Analysis: CTS Configuration

The following analysis demonstrates the cost implications of different configurations. The comparison assumes the same total workload (100 RegionServers) distributed across different instance counts and types:

| Configuration | Instance Count | Instance Type | Monthly Cost | Delta % |
|---------------|----------------|---------------|--------------|---------|
| **Current** | 100 | m5.4xlarge | ~$56,000 | Baseline |
| **Tomorrow (Performance)** | 50 | m8g.8xlarge | ~$52,000 | -7% |
| **Tomorrow (CTS)** | 25 | r8g.8xlarge | ~$34,000 | -39% |
| **Maximum Density** | 10 | r8g.16xlarge | ~$27,500 | -51% |

Costs shown are for HBase nodepool only, using publicly listed AWS rates. The -51% savings rate with Maximum Density applies regardless of corporate discount level.

---

## EBS Storage Analysis

Storage performance is often the limiting factor for HBase workloads. While compute resources can burst to handle traffic spikes, storage throughput is constrained by the EBS volume type and size. Understanding these constraints is essential for proper capacity planning and avoiding I/O bottlenecks.

AWS EBS offers multiple volume types, each optimized for different access patterns. HDFS workloads are predominantly sequential, making throughput-optimized volumes attractive. However, certain components (WAL, metadata) require low-latency random I/O, necessitating a mixed storage strategy.

### ST1 (Throughput Optimized HDD) - Production Standard

ST1 volumes are the production standard for HDFS DataNode storage due to their cost-effectiveness for sequential I/O workloads. These HDD-backed volumes provide high throughput at low cost, making them ideal for storing bulk data that is accessed sequentially during scans and compactions.

#### ST1 Performance Characteristics

| Metric | Value |
|--------|-------|
| **Baseline Throughput** | 40 MiB/s per TiB |
| **Burst Throughput** | 250 MiB/s per TiB |
| **Maximum Throughput** | 500 MiB/s per volume |
| **Volume Size Range** | 125 GiB - 16 TiB |
| **Cost** | ~$0.045/GB-month (us-east-1) |
| **IOPS** | 500 (fixed) |

#### ST1 Sizing for Optimal Performance

| Volume Size | Baseline Throughput | Burst Throughput | Notes |
|-------------|---------------------|------------------|-------|
| 1 TiB | 40 MiB/s | 250 MiB/s | Minimum recommended |
| 2 TiB | 80 MiB/s | 500 MiB/s | Good for typical DN |
| 4 TiB | 160 MiB/s | 500 MiB/s | High throughput |
| 12.5 TiB | 500 MiB/s | 500 MiB/s | Baseline = Burst |
| 16 TiB | 500 MiB/s | 500 MiB/s | Maximum size |

#### ST1 Best Practices

Maximizing ST1 performance requires attention to volume sizing and operating system configuration. The following practices ensure optimal throughput:

1. **Large Volume Sizes:** Use ≥2 TiB volumes to get reasonable baseline throughput
2. **Read-Ahead Tuning:** Set Linux read-ahead to 1 MiB for sequential workloads
3. **Queue Depth:** Maintain queue depth ≥4 for optimal throughput
4. **EBS-Optimized:** Use EBS-optimized instances (all modern instances support this)
5. **Avoid Random I/O:** ST1 is not suitable for small random I/O patterns

The most common mistake is under-sizing volumes. A 500 GB ST1 volume has only 20 MiB/s baseline throughput—far below what a busy DataNode requires. When consolidating workloads onto larger instances, ensure that EBS volume sizes scale appropriately to maintain adequate aggregate throughput.

---

## JVM Heap Sizing and Garbage Collection

### Overview

Moving to larger instance types unlocks the potential for larger JVM heaps, but this introduces new challenges around garbage collection. Traditional garbage collectors like G1GC work well for heaps up to 64 GB but can produce multi-second pauses on larger heaps. These pauses directly impact request latency, causing timeout cascades during GC events.

Running HBase + HDFS + Phoenix on large instances (32+ vCPUs, 256 GiB+ memory) requires careful JVM tuning to avoid GC pauses and optimize throughput. Fortunately, modern JVMs offer collector options specifically designed for large heaps with stringent latency requirements.

### Garbage Collector Options for Java 17+

Java 17 and later versions provide multiple garbage collector options, each with different performance characteristics. The choice of collector depends on heap size and latency requirements:

| Collector | Best For | Max Heap | Pause Times | Throughput |
|-----------|----------|----------|-------------|------------|
| **G1GC** | General purpose, ≤64 GB heaps | ~64 GB practical | 50-500ms | High |
| **ZGC** | Large heaps, low latency | 16 TB theoretical, 256-512 GB practical | <1ms | Medium-High |
| **Generational ZGC** (JDK 21+) | Very large heaps, lowest latency | 16 TB theoretical, 256-512 GB practical | <1ms | High |

### G1GC Configuration (Recommended for ≤64 GB Heaps)

G1GC (Garbage-First Garbage Collector) remains a solid choice for moderate heap sizes. It provides good throughput while maintaining sub-second pause times for heaps up to approximately 64 GB. For larger heaps, pause times can become unpredictable and problematic.

**When to use G1GC:**

- CTS configuration (4 RS per node with ~44 GB heap each)
- Performance configuration (2 RS per node with ~40 GB heap each)
- Any consolidated deployment where per-RS heap is ≤64 GB

The following configuration is optimized for HBase RegionServer workloads in a consolidated deployment:

```bash
# G1GC JVM Options for HBase RegionServer (CTS/Performance Config)
-XX:+UseG1GC
-Xms44g -Xmx44g                           # Fixed heap size (adjust per config)
-XX:+AlwaysPreTouch                        # Pre-fault heap pages
-XX:InitiatingHeapOccupancyPercent=40     # Start marking earlier
-XX:G1HeapWastePercent=10                 # Tolerate some waste
-XX:G1MixedGCCountTarget=16               # More mixed GC cycles
-XX:G1HeapRegionSize=16m                  # Region size for large heap
-XX:MaxGCPauseMillis=200                  # Target pause time
-XX:ParallelGCThreads=8                   # Match container cpuRequest
-XX:ConcGCThreads=2                       # Concurrent GC threads
-XX:+UseStringDeduplication               # Reduce string memory
```

The key parameters to adjust are `InitiatingHeapOccupancyPercent` (lower values start GC earlier, reducing pause times at the cost of throughput) and `MaxGCPauseMillis` (the target pause time the collector tries to achieve). Note that `ParallelGCThreads` should match the container's `cpuRequest`, not the total node vCPUs.

### ZGC Configuration (Recommended for >64 GB Heaps)

ZGC (Z Garbage Collector) is designed specifically for large heaps with stringent latency requirements. It performs most garbage collection work concurrently, resulting in pause times under 1 millisecond regardless of heap size.

**When to use ZGC:**

- Single RegionServer per node deployments with 128+ GB heaps
- NameNode with 64-96 GB heap (critical component, benefits from low-pause GC)
- Any component where per-instance heap exceeds 64 GB

**When NOT to use ZGC:**

- CTS or Performance configurations with multiple RS per node (use G1GC for 40-48 GB heaps)
- DataNodes (small heaps, G1GC is sufficient)
- HBase Master (small heap)

```bash
# ZGC JVM Options for Single RS (Java 17)
-XX:+UseZGC
-Xms128g -Xmx128g                         # Fixed heap size
-XX:+AlwaysPreTouch                       # Pre-fault heap pages
-XX:SoftMaxHeapSize=120g                  # Soft limit for ZGC
-XX:+ZUncommit                            # Return memory to OS
-XX:ZUncommitDelay=300                    # Uncommit delay (seconds)

# Generational ZGC (Java 21+) - Preferred for Single RS
-XX:+UseZGC
-XX:+ZGenerational                        # Enable generational mode
-Xms128g -Xmx128g
-XX:+AlwaysPreTouch
```

Generational ZGC, available in JDK 21+, adds a young generation to ZGC's concurrent collection model. This provides even better performance for workloads with high allocation rates, as short-lived objects can be collected more efficiently.

### Practical Heap Size Limits for ZGC

While ZGC is designed to handle extremely large heaps—up to 16 TB in theory—the practical limits for low-latency database workloads like HBase are considerably lower. Understanding these limits is essential for capacity planning.

#### ZGC Theoretical vs. Practical Limits

| Metric | Theoretical | Practical for HBase | Notes |
|--------|-------------|---------------------|-------|
| **Maximum Heap Size** | 16 TB | 256-512 GB | ZGC documentation supports up to 16 TB |
| **Recommended Range** | N/A | 64-256 GB | Well-tested range for database workloads |
| **Sweet Spot** | N/A | 128-192 GB | Optimal balance for HBase RegionServers |

#### Why the Practical Limit is Much Lower Than Theoretical

Several factors constrain the practical heap size for HBase RegionServers:

1. **Native Memory Overhead:** Beyond the Java heap, the JVM process requires substantial native memory for thread stacks, metaspace (class metadata), JIT code cache, GC data structures, and direct byte buffers. For HBase workloads, native memory overhead typically ranges from 20-40% of heap size. A 128 GB heap may require 160-180 GB of total process memory.

2. **Garbage Collection CPU Overhead:** While ZGC provides sub-millisecond pause times regardless of heap size, larger heaps require more CPU cycles for concurrent marking and relocation. At multi-terabyte scales, GC can consume 10-15% of available CPU, reducing throughput. For HBase's high-throughput requirements, heaps beyond 256-512 GB show diminishing returns.

3. **Memory Bandwidth and NUMA Effects:** Large heaps stress memory bandwidth and are more likely to span NUMA boundaries, introducing memory access latency. For latency-sensitive HBase queries, limiting heap size to what fits within a single NUMA node (typically 128-256 GB on modern instances) improves consistency.

4. **Failure Recovery Time:** Larger heaps take longer to allocate, pre-touch, and warm up. A 512 GB heap may require 30-60 seconds to initialize with `-XX:+AlwaysPreTouch`, extending container startup time and slowing recovery after pod restarts.

### The Compressed Oops "Dead Zone" (32-48 GB)

> [!CAUTION]
> **Heap sizes between 32 GB and 48 GB should be avoided.** This range falls into a JVM performance "dead zone" where compressed object pointers (compressed oops) are disabled, but the heap is not large enough to justify the resulting memory overhead.

#### What Are Compressed Oops?

In 64-bit JVMs, object references normally require 64 bits (8 bytes) each. However, when the heap size is 32 GB or less, the JVM can use compressed ordinary object pointers (compressed oops)—a technique that represents object references using only 32 bits (4 bytes). This works because objects are 8-byte aligned, allowing the JVM to store a 35-bit address in 32 bits by omitting the three always-zero alignment bits.

Compressed oops provide significant benefits:

| Benefit | Impact |
|---------|--------|
| **Reduced Memory Footprint** | 32-bit references use half the memory of 64-bit references |
| **Improved Cache Efficiency** | More object references fit in CPU L1/L2/L3 caches |
| **Faster GC Performance** | Less memory to traverse during garbage collection |
| **Lower Memory Bandwidth** | Reduced pressure on memory subsystem |

#### Why 32-48 GB Is a "Dead Zone"

When the heap size exceeds approximately 32 GB, the JVM automatically disables compressed oops because the address space can no longer be represented in 32 bits with 8-byte alignment. This transition has severe consequences:

1. **Memory Overhead Explosion:** All object references expand from 4 bytes to 8 bytes. For pointer-heavy data structures common in HBase (ConcurrentHashMaps, BlockCache entries, region metadata), this increases memory consumption by **15-30%**.

2. **Net Negative Memory Gain:** A 40 GB heap without compressed oops may hold *less* actual data than a 31 GB heap with compressed oops enabled. The additional 9 GB is consumed by the expanded pointer overhead rather than application data.

3. **Cache Performance Degradation:** Larger pointers mean fewer references fit in CPU caches. Cache miss rates increase, leading to higher memory latency and reduced throughput.

4. **Longer GC Pauses:** The garbage collector must traverse more memory (due to larger pointers) and scan more cache-unfriendly data structures, potentially increasing pause times.

#### Heap Size Guidelines: Avoiding the Dead Zone

| Heap Range | Compressed Oops | Recommendation |
|------------|-----------------|----------------|
| **≤31 GB** | ✅ Enabled | **OPTIMAL** for consolidated deployments |
| **32-47 GB** | ❌ Disabled | **AVOID** — dead zone with poor efficiency |
| **≥48 GB** | ❌ Disabled | **ACCEPTABLE** if memory needs justify overhead |

> [!IMPORTANT]
> **For consolidated deployments (multiple RS per node), keep heap sizes at or below 31 GB to benefit from compressed oops.** This is more memory-efficient than heaps in the 32-47 GB range.

#### Implications for This Proposal

The heap recommendations in this document must avoid the dead zone. There are two valid approaches:

**Option A: More RegionServers with Smaller Heaps (Preferred for CTS)**

- Deploy more RegionServers per node with heaps ≤31 GB each
- Benefits from compressed oops for better memory efficiency
- Example: 6-8 RS per r7i.8xlarge with 28-31 GB heap each

**Option B: Fewer RegionServers with Larger Heaps**

- Deploy fewer RegionServers per node with heaps ≥48 GB each (preferably ≥64 GB)
- Accepts the compressed oops overhead in exchange for simpler operations
- Example: 2-3 RS per r7i.8xlarge with 64-80 GB heap each

The configurations throughout this document have been adjusted to follow these guidelines.

#### HBase-Specific Considerations

HBase's memory architecture favors a balanced approach rather than maximum heap size:

- **Block Cache Effectiveness:** Beyond a certain point, additional heap provides diminishing cache hit rate improvements. Analysis of production workloads typically shows that cache hit rates plateau once the working set fits in cache—additional memory beyond this point is wasted.

- **Memstore Flushing:** Very large memstores delay flushes, which can improve write throughput but increases data loss risk and recovery time. Practical memstore sizes are limited by flush time requirements rather than available heap.

- **Off-Heap Alternatives:** HBase's BucketCache provides an alternative to large on-heap caches. A 64 GB heap with 64 GB off-heap BucketCache often outperforms a 128 GB heap with no BucketCache, while providing more predictable latency.

#### Recommended Heap Sizes by Workload Profile

The heap sizes below assume a **single RegionServer per node** deployment. For consolidated deployments (multiple RS per node), divide available memory by the number of RegionServers and use G1GC for the resulting smaller heaps.

| Workload Profile | Heap Size (Single RS) | GC | Consolidated Heap (6-8 RS) | Consolidated GC |
|------------------|-----------------------|-----|--------------------------|-----------------|
| **Read-Heavy (high cache benefit)** | 128-192 GB | ZGC | 28-31 GB | G1GC |
| **Write-Heavy (memstore focus)** | 64-96 GB | ZGC | 28-31 GB | G1GC |
| **Balanced / General Purpose** | 96-128 GB | ZGC | 28-31 GB | G1GC |
| **Latency-Critical (P99 < 10ms)** | 64-128 GB | Gen ZGC | 28-31 GB | G1GC |
| **Cost-Optimized (CTS)** | 48-64 GB | G1GC | 28-31 GB | G1GC |

With consolidated deployments, heap sizes are kept at ≤31 GB to benefit from compressed oops—providing better memory efficiency than the 32-47 GB "dead zone." Deploy more RegionServers per node (6-8 instead of 4) to compensate for smaller per-RS heaps. The aggregate BucketCache across multiple RS per node can partially compensate for smaller per-RS caches. Monitor cache hit rates and adjust off-heap allocations based on observed workload patterns.

#### Production Validation

Organizations running HBase at scale have reported the following experiences with ZGC:

- **AWS EMR:** Benchmarks demonstrate sub-millisecond P99.9 GC pauses with heaps up to 256 GB using Generational ZGC on JDK 21+.
- **Large-scale deployments:** Production systems commonly run 64-128 GB heaps with ZGC, with diminishing benefits observed above 192 GB.
- **Memory-optimized instances:** r7i.8xlarge (256 GB) instances typically allocate 128 GB to heap, 32-64 GB to off-heap caches, with the remainder for OS page cache and native memory.

The conclusion from field experience is that 128-192 GB represents the practical maximum for HBase RegionServer heaps when optimizing for consistent low latency. Larger heaps are possible but should only be deployed after validating that the additional memory provides measurable cache hit rate improvements for the specific workload.

### Heap Sizing Guidelines by Component

Different components have vastly different memory requirements. RegionServers benefit from large heaps to hold block caches and memstores, while DataNodes require minimal heap because they primarily shuffle data through OS page cache. The following guidelines provide starting points for heap sizing.

Heap sizing must account for the number of pods per node in the target deployment architecture. The recommendations below align with the consolidation ratios described in this proposal.

| Configuration | Instance Type | RS/Node | Heap per RS | Off-Heap per RS | GC | Total RS Memory |
|---------------|---------------|---------|-------------|-----------------|-----|-----------------|
| **Maximum Density** | r8g.16xlarge (512 GB) | 10 | 31 GB | 7 GB | G1GC | ~38 GB per RS |
| **CTS (High Consolidation)** | r8g.8xlarge (256 GB) | 6 | 31 GB | 7 GB | G1GC | ~38 GB per RS |
| **Performance (Moderate)** | m8g.8xlarge (128 GB) | 4 | 31 GB | 7 GB | G1GC | ~38 GB per RS |
| **Single RS (Large Heap)** | r8g.8xlarge (256 GB) | 1 | 128-160 GB | 32-48 GB | ZGC | ~180 GB per RS |

> **Avoid heaps in the 32-47 GB range.** These fall in the compressed oops "dead zone" where memory efficiency is poor. Use either ≤31 GB (to benefit from compressed oops) or ≥48 GB (to justify the overhead).

| Component | Instance Type | Recommended Heap | Off-Heap Reserve | Notes |
|-----------|---------------|------------------|------------------|-------|
| **DataNode** | (co-located) | 4-8 GB | OS page cache | Minimal heap needed |
| **NameNode** | m8g.8xlarge (128 GB) | 64-96 GB | 16-32 GB | Scales with block count |
| **HBase Master** | m8g.8xlarge (128 GB) | 8-16 GB | 4-8 GB | Scales with region count |

With the CTS configuration, RegionServer heaps are kept at ≤31 GB to benefit from compressed oops. With the Performance configuration, heaps are sized at 48-56 GB to justify the loss of compressed oops. ZGC is only recommended when running a single RegionServer per node with heaps >64 GB.

Note that NameNode heap requirements scale with the number of blocks in HDFS, not with data volume or request rate. Large clusters with many small files may require substantially larger NameNode heaps.

### Memory Allocation Formula

When planning memory allocation for a node, all consumers must be accounted for: JVM heaps, off-heap allocations, Kubernetes overhead, and the operating system itself.

#### CTS Configuration Example (6 RS + 6 DN on r8g.8xlarge)

The following example shows memory allocation for the CTS configuration with 6 RegionServers and 6 DataNodes (colocated) per 256 GB node. Heap sizes are kept at ≤31 GB to benefit from compressed oops:

```
Total Memory:          256 GB
├── OS/Kernel:           8 GB
├── Kubernetes:          4 GB
├── RegionServer 1:     38 GB
│   ├── Heap:           31 GB (compressed oops enabled)
│   └── Off-Heap:        7 GB (BucketCache, Netty, etc.)
├── RegionServer 2:     38 GB
├── RegionServer 3:     38 GB
├── RegionServer 4:     38 GB
├── RegionServer 5:     38 GB
├── RegionServer 6:     38 GB
├── DataNode 1:          4 GB (heap only, minimal native)
├── DataNode 2:          4 GB
├── DataNode 3:          4 GB
├── DataNode 4:          4 GB
├── DataNode 5:          4 GB
└── DataNode 6:          4 GB

Total RS Allocation:   228 GB (6 × 38 GB)
Total DN Allocation:    24 GB (6 × 4 GB)
Overhead (OS/K8s):      12 GB
                       --------
                       264 GB requested
                       256 GB available
                        -8 GB → Use 36 GB per RS (see below)
```

> **Note:** The above allocation exceeds 256 GB. To fit, reduce per-RS total allocation from 38 GB to 36 GB (31 GB heap + 5 GB off-heap):

```
Total Memory:          256 GB
├── OS/Kernel:           8 GB
├── Kubernetes:          4 GB
├── RegionServer 1-6:  216 GB (6 × 36 GB each)
│   ├── Heap:           31 GB (compressed oops enabled)
│   └── Off-Heap:        5 GB (BucketCache, Netty, etc.)
└── DataNode 1-6:       24 GB (6 × 4 GB each)

Total RS Allocation:   216 GB (6 × 36 GB)
Total DN Allocation:    24 GB (6 × 4 GB)
Overhead (OS/K8s):      12 GB
OS Page Cache:           4 GB (minimal headroom)
                       --------
                       256 GB
```

#### Performance Configuration Example (4 RS + 4 DN on m8g.8xlarge)

For the Performance configuration with 4 RegionServers and 4 DataNodes per 128 GB node. Heap sizes are kept at ≤31 GB to benefit from compressed oops:

```text
Total Memory:          128 GB
├── OS/Kernel:           6 GB
├── Kubernetes:          4 GB
├── RegionServer 1-4:  120 GB (4 × 30 GB each)
│   ├── Heap:           26 GB (compressed oops enabled)
│   └── Off-Heap:        4 GB (BucketCache, Netty, etc.)
└── DataNode 1-4:       16 GB (4 × 4 GB each)

                       --------
                       146 GB requested (exceeds 128 GB)
```

> **Note:** The 128 GB m8g.8xlarge cannot fit 4 RS at 31 GB heap. Options:
>
> - Use 3 RS per node: 3 × 36 GB = 108 GB + 12 GB DN + 10 GB overhead = 130 GB (tight fit)
> - Use smaller heap: 4 RS × 24 GB total = 96 GB + 16 GB DN + 10 GB overhead = 122 GB (fits)

**Recommended: 3 RS + 3 DN on m8g.8xlarge**

```text
Total Memory:          128 GB
├── OS/Kernel:           6 GB
├── Kubernetes:          4 GB
├── RegionServer 1-3:  108 GB (3 × 36 GB each)
│   ├── Heap:           31 GB (compressed oops enabled)
│   └── Off-Heap:        5 GB (BucketCache, Netty, etc.)
└── DataNode 1-3:       12 GB (3 × 4 GB each)

Total RS Allocation:   108 GB (3 × 36 GB)
Total DN Allocation:    12 GB (3 × 4 GB)
Overhead (OS/K8s):      10 GB
                       --------
                       130 GB (tight, but manageable with memory pressure)
```

With multiple RegionServers per node, consider the aggregate cache across all RS on the node when evaluating cache hit rates. Six RegionServers with 7 GB BucketCache each provides 42 GB aggregate off-heap cache per node.

### AWS EMR ZGC Benchmark Results

AWS has published benchmark results comparing Generational ZGC to G1GC for HBase workloads running on EMR. These results are directly applicable to our deployment and demonstrate the dramatic latency improvements possible with ZGC on large heaps:

| Metric | G1GC | Generational ZGC | Improvement |
|--------|------|------------------|-------------|
| **P99.9 GC Pause** | 500-2000ms | <1ms | >99% |
| **P99 Latency** | Variable | Consistent | Significant |
| **Throughput** | Baseline | 95-100% of G1 | Near-parity |
| **Memory Overhead** | Baseline | ~10-15% higher | Acceptable |

The throughput numbers are particularly noteworthy: earlier versions of ZGC sacrificed throughput for latency, but Generational ZGC largely closes this gap. The 10-15% memory overhead is a modest price to pay for eliminating GC-induced latency spikes.

> **Source:** [AWS Big Data Blog - Improve Amazon EMR HBase availability and tail latency using generational ZGC](https://aws.amazon.com/blogs/big-data/improve-amazon-emr-hbase-availability-and-tail-latency-using-generational-zgc/)

### ARM64/Graviton-Specific JVM Tuning

When running HBase on Graviton4 (ARM64) instances, additional JVM tuning considerations apply. Java workloads run seamlessly on Graviton with ARM-optimized OpenJDK builds, but specific configurations can maximize performance.

#### Recommended JDK Distribution

**Amazon Corretto** is the recommended JDK distribution for Graviton instances. Corretto 17 and 21 include ARM-specific optimizations that significantly improve performance:

- **Large System Extensions (LSE):** Corretto 11+ enables LSE by default, which provides atomic operations that improve performance for lock-contended workloads and reduce GC times
- **Optimized Intrinsics:** Modern JVMs on ARM64 include optimized intrinsics for math, crypto, and vector operations
- **Spin-Lock Optimizations:** Corretto includes improved spin-lock behavior within the JVM for ARM architecture

#### ARM64-Specific JVM Flags

The following JVM flags are recommended specifically for ARM64/Graviton deployments:

```bash
# ARM64-Optimized JVM Options for HBase RegionServer
-XX:-TieredCompilation                # Use C2 compiler directly (better for long-running servers)
-XX:ReservedCodeCacheSize=64M         # Smaller code cache aids CPU caching and prediction
-XX:InitialCodeCacheSize=64M          # Match initial to reserved size
-XX:CICompilerCount=2                 # Optimize compiler thread count for ARM
-XX:CompilationMode=high-only         # Skip lower-tier compilation (Corretto 17+)
```

For workloads using cryptographic operations (e.g., TLS connections between HBase components):

```bash
# Enable optimized AES/GCM intrinsics (backported to Corretto 11 and 17)
-XX:+UnlockDiagnosticVMOptions
-XX:+UseAESCTRIntrinsics
```

#### Cache Line Considerations

ARM processors use 64-byte cache lines (vs. 128-byte on some x86 processors). This can affect performance of `volatile` variables in high-contention scenarios due to false sharing. If profiling reveals contention issues:

```bash
# Relax contended annotation restrictions if needed
-XX:-RestrictContended
```

> [!NOTE]
> HBase's internal data structures are generally well-optimized for concurrent access. These flags should only be applied after profiling confirms contention issues.

#### G1GC on ARM64

G1GC works well on ARM64, but the following adjustments are recommended for Graviton:

```bash
# G1GC for ARM64 (Graviton4)
-XX:+UseG1GC
-Xms31g -Xmx31g                       # Keep ≤31 GB for compressed oops
-XX:+AlwaysPreTouch
-XX:InitiatingHeapOccupancyPercent=40
-XX:MaxGCPauseMillis=200
-XX:ParallelGCThreads=8               # Match container cpuRequest
-XX:ConcGCThreads=2
-XX:-TieredCompilation                # ARM64 optimization
-XX:ReservedCodeCacheSize=64M
```

#### ZGC on ARM64

ZGC is fully supported on Linux/AArch64 as of JDK 17. For large heap configurations:

```bash
# Generational ZGC for ARM64 (Java 21+, Single RS Configuration)
-XX:+UseZGC
-XX:+ZGenerational
-Xms128g -Xmx128g
-XX:+AlwaysPreTouch
-XX:+UseLargePages                    # Enable large pages for better throughput
-XX:-TieredCompilation                # ARM64 optimization
```

#### Container Settings for ARM64

When running in containers on Graviton instances, ensure proper CPU and memory detection:

```bash
# Container-aware settings for ARM64
-XX:ActiveProcessorCount=8            # Match cpuRequest, not node vCPUs
-XX:MaxRAMPercentage=75.0             # Use 75% of container memory
-XX:InitialRAMPercentage=75.0         # Start at same size
```

#### Operating System Recommendations

For optimal performance on Graviton4 instances:

- Use **Amazon Linux 2023**, **Ubuntu 22.04+**, or **RHEL 9+** with latest ARM64 kernel optimizations
- Set **swappiness to 0** (`vm.swappiness=0`) to prevent swapping, which severely impacts HBase performance
- Enable **transparent huge pages** for workloads that benefit from large memory pages
- Use ARM-optimized container images (multi-arch or arm64-specific builds)

#### Performance Validation

Graviton instances behave differently from x86 under load because vCPUs map directly to physical cores (not hyperthreads). To properly assess performance:

1. **Test under high load (80%+ CPU utilization)** — Testing at low loads can yield misleading results
2. **Establish clear baseline metrics** before migration
3. **Use parallel testing** — Deploy identical workloads on both Graviton and x86 to quantify differences
4. **Profile with Linux `perf`** — Use ARM-adapted profiling to identify bottlenecks

---

## Off-Heap Memory and BucketCache

Even with modern garbage collectors like ZGC, there are benefits to keeping frequently-accessed data outside the JVM heap. Off-heap memory is not subject to garbage collection, eliminating any GC-related latency for data stored there. HBase's BucketCache feature provides an off-heap cache layer that can hold tens of gigabytes of block data without affecting GC performance.

On large memory instances, off-heap BucketCache becomes essential. It allows us to cache more data (improving read performance) without inflating the JVM heap (which would increase GC pressure). This separation of concerns—heap for active objects, off-heap for cached data—is a key architectural pattern for high-performance HBase deployments.

### BucketCache Configuration

For large memory instances, off-heap BucketCache significantly reduces GC pressure. The size should be scaled based on available memory per RegionServer.

#### Single RS Per Node (Large Heap Configuration)

```xml
<!-- hbase-site.xml - Single RS with 128 GB heap -->
<property>
  <name>hbase.bucketcache.ioengine</name>
  <value>offheap</value>
</property>
<property>
  <name>hbase.bucketcache.size</name>
  <value>32768</value> <!-- 32 GB in MB -->
</property>
<property>
  <name>hbase.bucketcache.combinedcache.enabled</name>
  <value>true</value>
</property>
<property>
  <name>hfile.block.cache.size</name>
  <value>0.2</value> <!-- L1 on-heap cache: 20% of heap = ~25 GB -->
</property>
```

#### CTS Configuration (6 RS Per Node)

With consolidated deployments, each RegionServer has less memory. Reduce BucketCache proportionally:

```xml
<!-- hbase-site.xml - CTS config with 31 GB heap per RS -->
<property>
  <name>hbase.bucketcache.ioengine</name>
  <value>offheap</value>
</property>
<property>
  <name>hbase.bucketcache.size</name>
  <value>5120</value> <!-- 5 GB in MB per RS (30 GB aggregate per node) -->
</property>
<property>
  <name>hbase.bucketcache.combinedcache.enabled</name>
  <value>true</value>
</property>
<property>
  <name>hfile.block.cache.size</name>
  <value>0.2</value> <!-- L1 on-heap cache: 20% of heap = ~6 GB -->
</property>
```

While per-RS cache is smaller, the aggregate BucketCache across 6 RS (30 GB) is comparable to the single-RS configuration (32 GB). Net cache capacity per node is similar.

### Memory Distribution with BucketCache

With BucketCache enabled, HBase uses a multi-tier caching architecture. Understanding these tiers helps in capacity planning and performance tuning:

| Cache Layer | Location | Single RS (128 GB heap) | CTS (31 GB heap per RS) | Purpose |
|-------------|----------|-------------------------|-------------------------|---------|
| **L1 (BlockCache)** | On-Heap | ~25 GB (20% of heap) | ~6 GB (20% of heap) | Hot data, index blocks |
| **L2 (BucketCache)** | Off-Heap | 32 GB | 5 GB | Warm data, reduces I/O |
| **L3 (Page Cache)** | OS | Remaining RAM | Minimal (claimed by pods) | Cold data, HDFS reads |

Data flows from L3 (cold) to L2 (warm) to L1 (hot) based on access patterns. The L1 on-heap cache provides the fastest access but is size-limited by GC constraints. The L2 off-heap cache provides a large capacity tier without GC impact. The L3 OS page cache serves as a backstop, caching HDFS block data that has been recently read from EBS.

With 6 RS per node, OS page cache is minimal because container memory requests claim most of the node's memory. However, aggregate L1+L2 cache across all RS per node (6 × 11 GB = 66 GB) is substantial.

### Memstore Configuration

While caching affects read performance, memstore configuration affects write performance. The memstore holds write data before it is flushed to disk, acting as a write buffer. Proper sizing ensures that the memstore can absorb write bursts without triggering premature flushes:

```xml
<property>
  <name>hbase.regionserver.global.memstore.size</name>
  <value>0.4</value> <!-- 40% of heap for writes -->
</property>
<property>
  <name>hbase.regionserver.global.memstore.size.lower.limit</name>
  <value>0.35</value> <!-- Start flushing at 35% -->
</property>
```

---

## NUMA Considerations

Non-Uniform Memory Access (NUMA) is a hardware architecture where memory access times vary depending on which CPU socket is accessing which memory bank. On x86 servers with multiple CPU sockets, large instances can span multiple NUMA nodes. However, AWS Graviton4 processors have a different architecture that simplifies NUMA considerations.

### Graviton4 NUMA Topology

Graviton4 processors contain up to 96 cores on a single chip. For instances up to 96 vCPUs, all cores reside on a single physical processor, resulting in a **single NUMA domain**:

| Instance Size | vCPUs | NUMA Nodes | Notes |
|---------------|-------|------------|-------|
| r8g.8xlarge | 32 | 1 | Single chip, no NUMA concerns |
| r8g.16xlarge | 64 | 1 | Single chip, no NUMA concerns |
| r8g.24xlarge | 96 | 1 | Full chip, single NUMA node |
| r8g.metal-48xl | 192 | 2 | Dual-socket, requires NUMA awareness |

This is a significant advantage of Graviton4 over x86 instances of similar size. The r8g.16xlarge (64 vCPUs) used in the Maximum Density configuration operates as a single NUMA domain, eliminating the cross-NUMA latency penalties that would affect comparable Intel instances.

### NUMA-Aware JVM Configuration

For instances with 2+ NUMA nodes (primarily metal instances), consider:

```bash
# Check NUMA topology
numactl --hardware

# JVM NUMA support (for multi-NUMA instances)
-XX:+UseNUMA
-XX:+UseNUMAInterleaving  # For large heaps spanning NUMA nodes
```

For Graviton4 instances up to r8g.24xlarge, NUMA-aware configuration is **not required**. The single-chip architecture provides uniform memory access to all cores.

**Recommendation:** For HBase deployments, prefer Graviton4 r8g instances up to 24xlarge size. NUMA tuning is only necessary for metal instances (r8g.metal-48xl).

---

## Kubernetes and Container Considerations

The benefits of larger instance types can only be realized with proper Kubernetes configuration. Resource requests and limits control how workloads share node resources, and incorrect settings can negate the advantages of consolidation. This section covers the key configuration considerations for containerized HBase deployments.

The central tradeoff is between resource guarantees and burst capacity. Higher resource requests provide stronger guarantees but reduce flexibility. Lower requests allow more overcommitment and bursting but risk contention during peak periods. The optimal configuration depends on workload characteristics and risk tolerance.

### CPU and Memory Requests/Limits

Kubernetes uses resource requests for scheduling decisions and limits for enforcement. Understanding the difference is crucial for proper configuration:

```yaml
# CTS Configuration: RegionServer pod spec (6 RS per r8g.8xlarge node)
resources:
  requests:
    cpu: "5"        # 32 vCPUs / 6 RS ≈ 5 per RS
    memory: "36Gi"  # ~36 GB per RS (31 GB heap + 5 GB off-heap)
  limits:
    cpu: "10"       # Allow burst to 10 vCPUs when other pods are idle
    memory: "36Gi"  # Hard memory limit
```

```yaml
# Performance Configuration: RegionServer pod spec (3 RS per m8g.8xlarge node)
resources:
  requests:
    cpu: "10"       # 32 vCPUs / 3 RS ≈ 10 per RS
    memory: "36Gi"  # ~36 GB per RS (31 GB heap + 5 GB off-heap)
  limits:
    cpu: "16"       # Allow burst to 16 vCPUs
    memory: "36Gi"  # Hard memory limit
```

### ActiveProcessorCount for Containers

A subtle but important issue arises with CPU detection in containers. The JVM uses the detected processor count to size internal thread pools, GC parallelism, and other runtime parameters. In a container environment, the JVM might see all of the host's CPUs (e.g., 32) even though the container is only allocated a fraction of them (e.g., 8). This mismatch can cause excessive thread creation and context switching.

Modern JVMs (Java 17+) include cgroup-aware detection that should correctly identify container CPU limits. However, explicit configuration provides certainty:

```bash
# Explicitly set processor count based on cpuRequest
-XX:ActiveProcessorCount=8

# Or let JVM use cgroup-aware detection (Java 17+)
# The JVM should auto-detect from cgroup CPU limits
```

When using Kubernetes CPU limits (as opposed to just requests), the JVM should automatically detect the correct value. When only requests are specified without limits, explicit configuration may be necessary.

> **Reference:** [JDK-8281571](https://bugs.java.com/bugdatabase/view_bug?bug_id=8281571) - JVM CPU detection in containers

### Recommended Kubernetes Configuration

| Setting | Recommendation | Rationale |
|---------|----------------|-----------|
| **cpuRequest** | Set accurately based on steady-state | Enables proper bin-packing |
| **cpuLimit** | Remove or set high | Allows CPU bursting |
| **memoryRequest** | Equal to memoryLimit | Prevents OOM eviction |
| **memoryLimit** | Match actual JVM + overhead | Hard boundary |
| **PDB** | ≤2 for DataNodes | Allows rolling upgrades |

### TopologySpreadConstraints

Kubernetes TopologySpreadConstraints ensure that pods are distributed evenly across failure domains (zones, nodes, etc.). While important for resilience, overly strict constraints can interfere with efficient bin-packing on larger instances.

```yaml
topologySpreadConstraints:
  - maxSkew: 1
    topologyKey: topology.kubernetes.io/zone
    whenUnsatisfiable: DoNotSchedule
    labelSelector:
      matchLabels:
        app: regionserver
```

When consolidating workloads onto larger instances, consider relaxing `maxSkew` for DataNodes to improve bin-packing efficiency. HDFS provides its own data placement guarantees through rack awareness, so tight Kubernetes-level constraints may be redundant for DataNode pods.

---

## Related Work and Dependencies

This proposal does not exist in isolation. Several related initiatives and existing configurations affect the design and must be considered during implementation. Conversely, this proposal may enable or accelerate other planned improvements.

### Container Requests and Limits

Proper resource requests/limits are foundational to this proposal's success. Without accurate resource requests, Kubernetes cannot make good scheduling decisions, and the burst capacity benefits are unrealized:

- Update `cpuRequests`, `memoryRequests`, and `memoryLimits` for all containers
- **Do not set cpuLimits** unless absolutely required (allows bursting)
- Default `cpuLimit=32` in helm charts becomes relevant for nodes >8xlarge

### Alluxio and Phoenix-REST

Planned deployments of Alluxio and Phoenix-REST will introduce new resource demands that should be considered in capacity planning:

| Service | CPU Needs | Memory Needs | Instance Store |
|---------|-----------|--------------|----------------|
| **Alluxio** | Medium | High (caching) | Preferred |
| **Phoenix-REST** | Medium | Medium | Not required |

Larger instances provide headroom for co-locating these services without requiring additional nodepools. However, the resource profiles should be validated before deployment to ensure adequate capacity.

### Autoscaling and CPU Utilization

RegionServer CPU utilization in steady state is relatively low, which is part of why consolidation is attractive. However, this proposal packs more RegionServers onto a node, raising average CPU utilization per node. If RegionServers handle more load on average due to reduced headroom, autoscaling calculations may need adjustment to trigger scale-out earlier.

### Memory Utilization and Heap Settings

Current heap sizes vary significantly across environments, reflecting different tuning histories rather than intentional differentiation:

| Environment | RegionServer Heap |
|-------------|-------------------|
| Hyperforce Core | 31 GB |
| Hyperforce Argus | 64 GB |
| 1P Core | 48 GB |

This inconsistency suggests rationalization is needed, especially with planned off-heap BucketCache improvements that change the optimal heap-to-off-heap ratio. The migration to larger instances provides a natural opportunity to standardize heap configurations.

### Load Balancing and Shedding

Traffic spikes from internal batch jobs are a significant driver of over-provisioning. We maintain extra capacity to absorb these spikes, even though they are predictable and could potentially be shaped or shed. Implementing load shedding or admission control could reduce provisioning requirements, complementing the consolidation benefits described in this proposal.

### Node Thrashing

The ASG (Auto Scaling Group) thrashing issue may be affected by nodepool changes. Thrashing occurs when nodes are repeatedly added and removed due to oscillating demand signals. With larger instances, each node addition/removal has greater impact, potentially amplifying thrashing problems—or, conversely, larger instances may reduce thrashing by providing more burst headroom that absorbs the oscillations. This interaction requires monitoring during rollout.

---

## Concerns and Risk Mitigation

Any significant infrastructure change carries risk. This section identifies the primary concerns with the proposed approach and describes mitigation strategies. The goal is not to eliminate risk—that is impossible—but to ensure risks are understood and managed appropriately.

### Blast Radius

The most significant concern with larger instances is increased blast radius. When a node fails, all workloads on that node are affected. Doubling workload per node doubles the impact of any single node failure. This tradeoff is inherent to consolidation and must be carefully managed:

| Concern | Mitigation |
|---------|------------|
| **HDFS Block Placement** | Verify DataNode AZ rack assignments to prevent two replicas on same node |
| **Replica Co-location** | Continue limiting RegionServer-sys to one per node (AZ) |
| **Recovery Time** | Ensure one node's workload ≤ upgrade batch size |

### Master Nodepool Cost

The master nodepool presents a different tradeoff. Control plane components (NameNode, HBase Master, ZooKeeper) have strict availability requirements—losing quorum can cause cluster-wide outages. Doubling master nodepool instance sizes without consolidating workloads doubles cost, but provides valuable headroom for these critical components.

This tradeoff explicitly sacrifices cost for availability. While difficult to quantify in normal operations, the value becomes apparent during incident response when extra capacity enables faster recovery.

### Maximum Density Configuration

> **Blast Radius Constraint:** This configuration is designed to limit blast radius to **10% of RegionServers per node failure**. For a 100-RS cluster, this means 10 RS per node maximum. While higher density is technically possible (up to 20 RS per node on metal instances), we constrain density to maintain acceptable failure impact for production workloads.

#### HBase Pool (10 × r8g.16xlarge)

- Runs 100 RegionServers and 100 DataNodes (colocated)
- 10 RegionServers per node (10% blast radius)
- 10 DataNodes per node (colocated with RS)
- 10 RS + 10 DN share 64 vCPUs + 512 GB memory

#### Yarn Pool (26 × m8g.8xlarge)

- Runs 100 NodeManagers + 3 RegionServer-sys
- (4 NMs or 1 RS-sys) per node
- Sharing 32 vCPUs + 128 GB

This configuration uses r8g.16xlarge instances (512 GB memory) which are right-sized for the 10% blast radius constraint. The instance provides sufficient memory for 10 RegionServers with 31 GB heaps each while maintaining compressed oops benefits, plus headroom for colocated DataNodes and OS page cache.

> **Why not denser?** Metal instances (r8g.metal-24xl, 768 GB) could technically support 20 RS per node, achieving up to 63% HBase nodepool cost reduction. However, this would result in a single node failure impacting 20% of all RegionServers. For production environments, we constrain density to 10 RS per node to limit blast radius to 10%, accepting lower cost savings in exchange for better failure isolation.

#### Memory Allocation for Maximum Density (10 RS + 10 DN on r8g.16xlarge)

```text
Total Memory:          512 GB
├── OS/Kernel:          10 GB
├── Kubernetes:          4 GB
├── RegionServer 1-10: 360 GB (10 × 36 GB each)
│   ├── Heap:           31 GB (compressed oops enabled)
│   └── Off-Heap:        5 GB (BucketCache, Netty, etc.)
├── DataNode 1-10:      40 GB (10 × 4 GB each)
└── OS Page Cache:     ~98 GB (available for HDFS reads)

Total RS Allocation:   360 GB (10 × 36 GB)
Total DN Allocation:    40 GB (10 × 4 GB)
Overhead:               14 GB
                       --------
                       414 GB utilized, 98 GB for page cache
```

This allocation provides healthy headroom and ~98 GB of OS page cache for improved HDFS read performance.

#### Maximum Density Cost Analysis (HBase Nodepool Only)

| Configuration | Instance Type | Count | RS/Node | Monthly Cost | vs Baseline | Blast Radius |
|---------------|---------------|-------|---------|--------------|-------------|--------------|
| **Baseline** | m5.4xlarge | 100 | 1 | $56,064 | — | 1% |
| **CTS v1.4** | r8g.8xlarge | 17 | 6 | $23,391 | -58% | 6% |
| **Max Density** | r8g.16xlarge | 10 | 10 | $27,521 | -51% | 10% |

#### Graviton4 NUMA Advantage for 16xlarge

Unlike Intel instances of similar size, the r8g.16xlarge (64 vCPUs) operates as a single NUMA domain because it fits within a single 96-core Graviton4 chip. No NUMA-aware configuration is required.

This is a significant operational advantage—the Maximum Density configuration avoids the cross-NUMA latency penalties that would affect comparable Intel x86 instances.

#### Blast Radius Impact on Region Recovery

Increasing consolidation from CTS (6 RS/node) to Maximum Density (10 RS/node) increases the number of regions affected by a single node failure. This section quantifies the recovery impact to help inform configuration decisions.

**Recovery Components:**

| Recovery Phase | Typical Duration | Scales With |
|----------------|------------------|-------------|
| **Region Detection** | 30-90 seconds | ZooKeeper session timeout |
| **Region Assignment** | 1-2 seconds/region | Number of affected regions |
| **WAL Replay** | 10-60 seconds | Unflushed memstore size |
| **Cache Warmup** | 1-5 minutes | Working set size |

**Blast Radius Comparison (100 RS Cluster, ~1000 Regions):**

| Configuration | RS/Node | Regions Affected | Assignment Time | Total Recovery | Increase |
|---------------|---------|------------------|-----------------|----------------|----------|
| **CTS v1.4** | 6 | ~60 regions | ~90 sec | 2-4 min | Baseline |
| **Max Density** | 10 | ~100 regions | ~150 sec | 4-6 min | ~1.5× |

> **Recovery Time Impact:** A single node failure with Maximum Density affects ~67% more regions than CTS, resulting in approximately 1.5× longer recovery time.

**Mitigations for Maximum Density:**

1. **Aggressive Memstore Flushing:** Reduce `hbase.hregion.memstore.flush.size` to limit WAL replay time
2. **Faster ZK Timeout:** Reduce session timeout (with caution) for quicker failure detection
3. **Region Pre-splitting:** More regions per RS enables faster parallel recovery
4. **Enhanced Monitoring:** Faster alerting on node health reduces detection time

> **Note:** Master nodepool was already doubled outside this proposal due to availability concerns (W-18122302).

### DataNode Distribution

If using R-class (memory-optimized) instances for the CTS configuration but not fully utilizing the additional memory, resources are wasted. This concern is addressed through several strategies:

1. Ensure block cache/off-heap is configured to use available memory
2. Consider n-class (network optimized) instances if data movement is the primary constraint
3. Monitor memory utilization post-migration and adjust configurations accordingly

The additional memory on R-class instances should not sit idle. If workload characteristics indicate that memory is not the limiting factor, a different instance type may be more appropriate.

---

## Open Questions

The following questions require further investigation before finalizing the implementation plan. Some can be answered through testing in non-production environments; others require consultation with platform teams or deeper analysis of production metrics.

### Scheduling and Topology

1. If DataNode scheduling is relaxed to both `hbase` and `yarn` nodepools, does that help with node thrashing?

2. If DataNode PDB remains at 2, does this impact upgrades or patching? Would node draining be blocked?

3. Should TopologySpreadConstraints `maxSkew` and/or `whenUnsatisfiable:DoNotSchedule` be relaxed for better bin-packing?

### Resource Utilization

1. Will we efficiently use memory of R-class instances without other changes? Off-heap caches, page cache, etc.?

2. Should we consider n-class (network optimized) if data movement is the limiting factor?

3. What will Alluxio and Phoenix-REST demands be for CPU/memory and instance types?

### Volume and Scheduling Limits

1. Is Kubernetes smart enough to not schedule a DataNode if the node can't mount the volumes? (m5/m6 limited to 27 volumes)

2. Are there node-local limits (DNS requests, etc.) when scheduling more workloads per node?

### JVM and NUMA

1. Do we need to set `-XX:ActiveProcessorCount`? Should RegionServers on 96 vCPU nodes think they have 96 CPUs?

### Operations

1. Can we remove `cpuLimit` from containers? Which containers need limits for QoS?

2. What stagger release process and monitoring duration is appropriate?

3. For largest clusters, should we move to 4 TB EBS volumes to prevent underutilized nodes due to volume limits?

4. How does this interact with Graviton migration plans?

5. What are current observed values for CPU, memory, network, EBS over time? What are the limiting factors?

---

## Recommendations

Based on the analysis in this document, we recommend a phased approach to adopting larger instance types. This approach balances the desire for quick wins against the need for careful validation in a production environment. Each phase builds on the previous one, providing checkpoints for evaluation before proceeding.

The timeline below assumes dedicated engineering focus. Actual duration may vary based on team capacity and competing priorities.

### Cost Analysis Overview

A key goal of this proposal is to achieve at least cost-neutral changes, with cost savings being highly desirable. The following analysis demonstrates that the recommended approach delivers significant savings while improving performance and resilience.

> **Pricing Note:** All dollar figures in this section use publicly listed AWS on-demand rates without corporate discounts. With enterprise agreements, absolute dollar amounts will be lower, but percentage savings (-18%) and relative proportions remain unchanged. Focus on the percentage deltas when evaluating the financial case.

#### Baseline: Current State Costs (Per Cluster)

| Component | Instance Type | Count | Hourly Rate | Monthly Cost |
|-----------|---------------|-------|-------------|--------------|
| HBase Nodepool | m5.4xlarge | 100 | $0.768 | $56,064 |
| Yarn Nodepool | m5.4xlarge | 103 | $0.768 | $57,752 |
| Master Nodepool | m5.4xlarge | 6 | $0.768 | $3,363 |
| **Total** | | **209** | | **$117,179** |

#### Target: Recommended State Costs (Per Cluster)

| Component | Instance Type | Count | Hourly Rate | Monthly Cost | Change |
|-----------|---------------|-------|-------------|--------------|--------|
| HBase Nodepool | r8g.8xlarge | 25 | $1.885 | $34,401 | -39% |
| Yarn Nodepool | m8g.8xlarge | 53 | $1.436 | $55,572 | -4% |
| Master Nodepool | m8g.8xlarge | 6 | $1.436 | $6,290 | +87% |
| **Total** | | **84** | | **$96,263** | **-18%** |

#### Cost Savings Summary (Per Cluster)

| Metric | Current | Target | Difference | Delta % |
|--------|---------|--------|------------|---------|
| **Monthly EC2 Cost** | $117,179 | $96,263 | -$20,916 | -18% |
| **Annual EC2 Cost** | $1,406,148 | $1,155,156 | -$250,992 | -18% |
| **Instance Count** | 209 | 84 | -125 | -60% |
| **Management Overhead** | Higher | Lower | Reduced | — |

> **Note:** All cost figures in this section are per cluster.

The master nodepool cost increase is intentional—doubling master instance size provides critical headroom for NameNode and HBase Master components, improving availability during incidents. This cost is offset by savings in the HBase nodepool.

#### Reserved Instance Optimization

The savings above assume on-demand pricing. With Reserved Instances or Savings Plans, both configurations see proportional reductions. Note that the -18% relative savings rate is preserved across all commitment levels:

| Commitment | Current Monthly | Target Monthly | Savings | Delta % |
|------------|-----------------|----------------|---------|---------|
| On-Demand | $117,179 | $96,263 | $20,916 | -18% |
| 1-Year RI (No Upfront) | $87,884 | $72,197 | $15,687 | -18% |
| 1-Year RI (All Upfront) | $76,165 | $62,571 | $13,595 | -18% |
| 3-Year RI (All Upfront) | $52,731 | $43,318 | $9,413 | -18% |

Even with aggressive reserved instance commitments, the target configuration remains 18% more cost-effective than the current state. Corporate discounts apply equally to both configurations, preserving the percentage savings.

### Phase 1: Foundation (Month 1-2)

1. **Audit Current State**
   - Collect 13-week metrics on CPU, memory, GC pauses, network, EBS throughput
   - Identify CPU-bound vs memory-bound vs I/O-bound workloads
   - Document current heap sizes, GC settings, container requests/limits

2. **Right-Size Containers**
   - Update cpuRequests based on P99 utilization
   - Set memoryRequests = memoryLimits
   - Remove or increase cpuLimits

#### Phase 1 Cost Impact

| Activity | Cost Impact | Notes |
|----------|-------------|-------|
| Audit Current State | $0 | Engineering time only |
| Right-Size Containers | -5% to -10% | Reduces `_reserved` allocation; improves cost attribution |

Right-sizing containers does not directly reduce EC2 costs, but it enables more accurate cost attribution and identifies over-provisioned resources. This phase may reveal opportunities to reduce instance counts even before migrating to larger instance types.

**Per-Cluster Impact:** $5,000-$10,000/month savings (-4% to -9%) from improved bin-packing and reduced `hbase_reserved` allocation.

### Phase 2: Pilot (Month 3-4)

1. **Test Instance Types**
   - Pilot r8g.8xlarge in non-production cluster
   - Compare G1GC vs ZGC (or Generational ZGC if on JDK 21)
   - Monitor: GC pauses, P99 latency, CPU/memory utilization
   - Validate ARM compatibility with existing Java workloads

2. **Validate Storage**
   - Test ST1 volume sizing (2+ TiB for baseline throughput)
   - Verify EBS bandwidth is not limiting
   - Confirm volume attachment limits (R8g supports 128 volumes)

#### Phase 2 Cost Impact

| Activity | Cost Impact | Notes |
|----------|-------------|-------|
| Pilot Cluster (non-prod) | +$5,000-$10,000 | Temporary additional capacity for testing |
| Storage Validation | $0 | Uses existing volumes; may identify optimization opportunities |

During the pilot phase, there is a temporary cost increase for running parallel infrastructure. This investment is essential for de-risking the migration. The pilot cluster should be sized to represent production workloads accurately—typically 10-20% of a production cluster's scale.

**Pilot Cost Estimate (2 months, per cluster type):**

- 5× r8g.8xlarge instances for testing: ~$6,900/month
- Additional EBS volumes for validation: ~$500/month
- **Total pilot investment: ~$14,800**

This one-time investment per cluster type enables annual savings of **$250,992 per cluster (-18%)** once fully rolled out. A single pilot validates the configuration for all clusters of similar profile, so the investment is amortized across the fleet.

### Phase 3: Rollout (Month 5-6)

1. **Staged Migration**
   - Start with one region/cluster
   - Monitor for 2+ weeks before expanding
   - Maintain capacity reservations during transition

2. **Update Configurations**
   - Adjust helm charts for new instance types
   - Update topologySpreadConstraints if needed
   - Configure BucketCache for off-heap memory utilization

#### Phase 3 Cost Impact

| Activity | Cost Impact | Notes |
|----------|-------------|-------|
| Staged Migration (overlap period) | +15-25% temporarily | Running old and new infrastructure in parallel |
| Capacity Reservation Updates | $0 | Shift existing reservations to new instance types |
| Full Rollout Complete | -18% ongoing | Target state achieved |

During the rollout, there is a temporary cost spike as both old and new infrastructure run in parallel. This overlap is necessary to ensure zero-downtime migration and provide rollback capability. Plan for 2-4 weeks of overlap per cluster.

**Rollout Cost Projection (per cluster):**

| Week | Old Infrastructure | New Infrastructure | Total | vs. Baseline |
|------|-------------------|-------------------|-------|--------------|
| 1-2 | 100% ($117K) | 25% ($24K) | $141K | +20% |
| 3-4 | 50% ($59K) | 75% ($72K) | $131K | +12% |
| 5-6 | 25% ($29K) | 100% ($96K) | $125K | +7% |
| 7+ | 0% | 100% ($96K) | $96K | -18% |

Approximately 3 months after completing migration (Month 9), the cumulative savings offset the temporary overlap costs. Peak investment of ~$40,000 vs status quo is recovered through monthly savings of $20,916.

#### Cumulative Cost Analysis (First Year)

| Period | Monthly Cost | Cumulative Spend | Status Quo Cumulative | vs. Status Quo |
|--------|--------------|------------------|----------------------|----------------|
| Months 1-2 (Foundation) | $112,000 | $224,000 | $234,358 | -$10,358 |
| Months 3-4 (Pilot) | $122,000 | $468,000 | $468,716 | -$716 |
| Months 5-6 (Rollout) | $128,000 | $724,000 | $703,074 | +$20,926 |
| Months 7-12 (Steady State) | $96,000 | $1,300,000 | $1,406,148 | -$106,148 |

By end of Year 1, the migration achieves net savings of approximately $106,000 per cluster (-8%) despite the investment in piloting and migration overlap. Year 2 and beyond see full annual savings of $250,992 per cluster (-18%).

### Recommended Configuration Matrix

The following matrix summarizes the recommended configurations for each component type. These recommendations align with the consolidation ratios in the CTS and Performance configurations—they should be validated through testing and adjusted based on observed behavior:

| Workload | Instance Type | RS/Node | Heap per RS | GC | Off-Heap per RS |
|----------|---------------|---------|-------------|-----|-----------------|
| **Max Density RegionServer** | r8g.16xlarge (512 GB) | 10 | 31 GB | G1GC | BucketCache 5 GB |
| **CTS RegionServer** | r8g.8xlarge (256 GB) | 6 | 31 GB | G1GC | BucketCache 5 GB |
| **Performance RegionServer** | m8g.8xlarge (128 GB) | 3 | 31 GB | G1GC | BucketCache 5 GB |
| **Single RS (Large Heap)** | r8g.8xlarge (256 GB) | 1 | 128 GB | ZGC | BucketCache 32 GB |
| **DataNode** | (colocated) | 6-10 | 4 GB | G1GC | OS page cache |
| **NameNode** | m8g.8xlarge (128 GB) | 1 | 64-96 GB | ZGC | - |
| **HBase Master** | m8g.8xlarge (128 GB) | 1 | 12 GB | G1GC | - |

**Configuration Selection:**

- **Max Density RegionServer:** For maximum cost savings with controlled blast radius. Uses r8g.16xlarge with 10 RS per node (10% blast radius). Achieves -51% HBase nodepool cost reduction vs baseline. No NUMA tuning required on Graviton4.
- **CTS RegionServer:** For cost-optimized deployments with 6 RS per node. Uses G1GC with 31 GB heaps (compressed oops enabled). Aggregate BucketCache across 6 RS is 30 GB per node.
- **Performance RegionServer:** For moderate consolidation with 3 RS per node on smaller instances.
- **Single RS (Large Heap):** For workloads requiring maximum per-RS cache. Uses ZGC for sub-millisecond GC pauses on 128 GB heap. Only applicable if not using multi-RS-per-node consolidation.

### Cost-Benefit Summary (Per Cluster)

The following table summarizes the financial case for this migration. All figures are per cluster.

| Metric | Value | Delta % | Notes |
|--------|-------|---------|-------|
| **Peak Transition Investment** | ~$40,000 | — | Maximum cumulative overspend vs status quo |
| **Ongoing Annual Savings** | ~$250,992 | -18% | At steady state ($20,916/month) |
| **Payback Period** | ~3 months | — | After migration completes (Month 9 from start) |
| **Year 1 Net Savings** | ~$106,000 | -8% | After absorbing all transition costs |
| **3-Year Net Benefit** | ~$608,000 | -14% | Year 1 + 2 years steady-state |
| **5-Year Net Benefit** | ~$1,110,000 | -16% | Year 1 + 4 years steady-state |

> **Note:** Delta percentages represent cumulative savings vs status quo spending over the same period. Steady-state savings rate is -18% annually. These figures use publicly listed AWS rates; with corporate discounts, dollar amounts decrease but percentage savings remain unchanged.

#### Alternative Configurations: Cost Comparison (Per Cluster)

If the full CTS configuration is too aggressive, intermediate options provide partial savings:

| Configuration | Monthly Cost | Delta % | Annual Savings | Risk Level | Blast Radius |
|---------------|--------------|---------|----------------|------------|-------------|
| **Current (Baseline)** | $117,179 | — | — | Low | 1% |
| **Performance Focus (2× consolidation, m8g)** | $104,000 | -11% | $158,148 | Low | 2% |
| **Moderate CTS (3× consolidation, r8g)** | $100,000 | -15% | $206,148 | Medium | 3% |
| **Aggressive CTS (4× consolidation, r8g)** | $96,263 | -18% | $250,992 | Medium-High | 4% |
| **Maximum CTS (6× consolidation, r8g)** | $88,000 | -25% | $350,148 | High | 6% |
| **Maximum Density (10× consolidation, r8g.16xlarge)** | $89,093 | **-24%** | $337,032 | High | 10% |

Delta percentages are the key metric for comparison. Corporate discounts reduce absolute dollars but preserve percentage savings.

> **Maximum Density achieves -24% total cluster cost reduction** (HBase nodepool -51%) with a controlled 10% blast radius per node failure. This represents the maximum density achievable while maintaining acceptable failure isolation for production workloads. Higher density (e.g., 20 RS per node on metal instances) is technically possible but would result in 20% blast radius—unacceptable for most production environments.

The recommended "Aggressive CTS" configuration with Graviton4 balances meaningful cost savings with acceptable risk, delivering -18% cost reduction per cluster. Organizations seeking maximum density within acceptable blast radius constraints should consider the Maximum Density option at -24% with 10% blast radius.

---

## References

The following resources informed this proposal and may be useful for deeper exploration of specific topics.

### AWS Documentation

- [Amazon EC2 R8g Instance Types (Graviton4)](https://aws.amazon.com/ec2/instance-types/r8g/) — Memory-optimized instances used for HBase/Maximum Density nodepools
- [Amazon EC2 M8g Instance Types (Graviton4)](https://aws.amazon.com/ec2/instance-types/m8g/) — General-purpose instances used for Yarn/Master nodepools
- [AWS Graviton Processors](https://aws.amazon.com/ec2/graviton/) — Overview of Graviton4 architecture and benefits
- [Amazon EBS Throughput Optimized HDD (ST1)](https://aws.amazon.com/ebs/throughput-optimized/)
- [EBS Volume Limits and Performance](https://docs.aws.amazon.com/ebs/latest/userguide/hdd-vols.html)
- [Improve Amazon EMR HBase availability using generational ZGC](https://aws.amazon.com/blogs/big-data/improve-amazon-emr-hbase-availability-and-tail-latency-using-generational-zgc/)

### JVM and GC Tuning

- [Oracle Java 17 GC Tuning Guide - G1GC](https://docs.oracle.com/en/java/javase/17/gctuning/garbage-first-g1-garbage-collector1.html)
- [Oracle ZGC Documentation](https://docs.oracle.com/en/java/javase/17/gctuning/z-garbage-collector.html)
- [JDK-8281571 - JVM CPU detection in containers](https://bugs.java.com/bugdatabase/view_bug?bug_id=8281571)
- [Tuning G1GC for your HBase Cluster](https://blogsarchive.apache.org/hbase/entry/tuning_g1gc_for_your_hbase)
- [Compressed Oops in the HotSpot JVM](https://docs.oracle.com/javase/10/vm/java-hotspot-virtual-machine-performance-enhancements.htm)
- [Atlassian: Avoid Heap Sizes Between 32-47 GB](https://support.atlassian.com/jira/kb/do-not-use-heap-sizes-between-32-gb-and-47-gb-in-jira-compressed-oops/)

### HBase and HDFS

- [Apache HBase Reference Guide - Memory](https://hbase.apache.org/book.html#regionserver.arch.memstore)
- [Cloudera HBase Hardware Requirements](https://docs.cloudera.com/documentation/enterprise/release-notes/topics/hardware_requirements_guide.html)
- [Phoenix Tuning Guide](https://phoenix.apache.org/tuning_guide.html)

### Kubernetes

- [Kubernetes Storage Limits](https://kubernetes.io/docs/concepts/storage/storage-limits/)
- [Extended Resources for Nodes](https://kubernetes.io/docs/tasks/administer-cluster/extended-resource-node/)
- [cgroup v2 Pressure Metrics](https://facebookmicrosites.github.io/cgroup2/docs/pressure-metrics.html)

### Internal References

- Container right-sizing tech talk (Oct 2025)
- EC2 Basics - Instances and Reservations
- HCR for reservation management
- ASG thrashing issue documentation

---

## Appendix A: JVM Flags Quick Reference

This appendix provides copy-paste-ready JVM flag configurations for common scenarios. These configurations encapsulate the recommendations discussed in the main document and can be used as starting points for deployment.

### Heap Sizing Guidelines

When selecting heap sizes, observe the following constraints:

| Heap Size Range | Recommendation | Notes |
|-----------------|----------------|-------|
| **≤31 GB** | Safe zone | Compressed oops enabled, optimal memory efficiency |
| **32-47 GB** | Avoid | Compressed oops disabled, but heap not large enough to justify overhead |
| **48-64 GB** | Acceptable with G1GC | Only for single RS configs where larger heap is needed |
| **65-256 GB** | Recommended for ZGC | Single RS per node configs; ZGC provides sub-ms pauses |
| **257-512 GB** | Large deployment zone | Requires validation; ensure workload benefits from additional cache |
| **>512 GB** | Extreme territory | Rarely justified for HBase; diminishing returns likely |

Never set heap sizes between 32 GB and 47 GB. This range falls outside the compressed oops threshold but provides insufficient additional capacity to compensate for the increased pointer overhead. Increasing heap from 31 GB to 40 GB can actually *reduce* effective usable memory.

The CTS and Performance configurations use 31 GB heaps per RegionServer to benefit from compressed oops.

### G1GC for CTS Configuration (6 RS per node, 31 GB heap each)

```bash
-XX:+UseG1GC
-Xms31g -Xmx31g
-XX:+AlwaysPreTouch
-XX:InitiatingHeapOccupancyPercent=40
-XX:G1HeapWastePercent=10
-XX:G1MixedGCCountTarget=16
-XX:G1HeapRegionSize=16m
-XX:MaxGCPauseMillis=200
-XX:ParallelGCThreads=5             # Match container cpuRequest (32/6 ≈ 5)
-XX:ConcGCThreads=2
```

### G1GC for Performance Configuration (3 RS per node, 31 GB heap each)

```bash
-XX:+UseG1GC
-Xms31g -Xmx31g
-XX:+AlwaysPreTouch
-XX:InitiatingHeapOccupancyPercent=40
-XX:G1HeapWastePercent=10
-XX:G1MixedGCCountTarget=16
-XX:G1HeapRegionSize=16m
-XX:MaxGCPauseMillis=200
-XX:ParallelGCThreads=10            # Match container cpuRequest (32/3 ≈ 10)
-XX:ConcGCThreads=2
```

### ZGC for Single RS Configuration (1 RS per node, 128 GB heap)

```bash
-XX:+UseZGC
-Xms128g -Xmx128g
-XX:+AlwaysPreTouch
-XX:SoftMaxHeapSize=120g
```

### Generational ZGC (Java 21+, Single RS Configuration)

```bash
-XX:+UseZGC
-XX:+ZGenerational
-Xms128g -Xmx128g
-XX:+AlwaysPreTouch
```

### Container-Aware Settings

```bash
-XX:ActiveProcessorCount=8          # Match cpuRequest, not node vCPUs
-XX:MaxRAMPercentage=75.0           # Use 75% of container memory
-XX:InitialRAMPercentage=75.0       # Start at same size
```

In consolidated deployments, `ParallelGCThreads` and `ActiveProcessorCount` should match the container's `cpuRequest` (e.g., 8), not the total node vCPUs (e.g., 32). This prevents excessive thread creation and context switching.

### ARM64/Graviton-Specific Flags

When running on Graviton4 instances, add these ARM64-optimized flags:

```bash
# ARM64/Graviton Optimization (add to G1GC or ZGC configs above)
-XX:-TieredCompilation                # Use C2 compiler directly (better for servers)
-XX:ReservedCodeCacheSize=64M         # Smaller code cache for better CPU caching
-XX:InitialCodeCacheSize=64M          # Match initial to reserved
-XX:CICompilerCount=2                 # Optimize compiler threads for ARM
-XX:CompilationMode=high-only         # Skip lower-tier compilation (Corretto 17+)
```

For workloads with significant TLS/encryption (SASL, encrypted connections):

```bash
# Optimized cryptographic intrinsics for ARM64
-XX:+UnlockDiagnosticVMOptions
-XX:+UseAESCTRIntrinsics
```

> **Note:** Use Amazon Corretto 17+ for best ARM64 performance. Corretto includes LSE (Large System Extensions) optimizations that improve lock-contended workload performance and reduce GC times.

---

## Appendix B: Instance Cost Calculator

This appendix provides formulas and examples for calculating infrastructure costs.

All calculations use publicly listed AWS on-demand rates without corporate discounts. With enterprise agreements, absolute dollar figures will be lower, but percentage deltas remain unchanged. When presenting to stakeholders, emphasize the percentage savings (-18%) rather than specific dollar amounts.

### Formula

```
Monthly Cost = Instances × Hourly Rate × 730 hours
```

### Current State Calculation (Per Cluster)

```
HBase Pool:  100 × $0.768/hr × 730 = $56,064/month
Yarn Pool:   103 × $0.768/hr × 730 = $57,752/month
Master Pool:   6 × $0.768/hr × 730 =  $3,363/month
─────────────────────────────────────────────────
Total (per cluster):                 $117,179/month
```

### Target State Calculation (Per Cluster, CTS Configuration with Graviton4)

```
HBase Pool:   25 × $1.885/hr × 730 = $34,401/month  (Delta: -39%)
Yarn Pool:    53 × $1.436/hr × 730 = $55,572/month  (Delta: -4%)
Master Pool:   6 × $1.436/hr × 730 =  $6,290/month  (Delta: +87%)
─────────────────────────────────────────────────────────────────
Total (per cluster):                  $96,263/month  (Delta: -18%)

Monthly Savings (per cluster): $20,916  │  DELTA: -18%
Annual Savings (per cluster):  $250,992 │  DELTA: -18%
```

The -18% cost reduction with Graviton4 (vs -11% with Intel r7i) is the consistent savings rate that applies regardless of corporate discount level.

### Migration Cost Projection (Per Cluster)

```
Phase 1 (Months 1-2):  $112,000/month average  (foundation work)
Phase 2 (Months 3-4):  $122,000/month average  (pilot + overlap)
Phase 3 (Months 5-6):  $128,000/month average  (migration overlap)
Steady State (7+):      $96,000/month          (target achieved)

First Year Total (per cluster):      $1,300,000
Status Quo Total (per cluster):      $1,406,148
───────────────────────────────────────────────
Year 1 Net Savings (per cluster):      $106,148
Year 2+ Annual Savings (per cluster):  $250,992
```

### Break-Even Analysis (Per Cluster)

```
Peak Transition Investment (per cluster):     ~$40,000
  - Peak cumulative overspend vs status quo at end of Month 6
  - Includes pilot infrastructure and migration overlap costs

Monthly Savings at Steady State (per cluster): $20,916

Break-Even Point: 40,000 ÷ 20,916 = 1.9 months after migration complete
                  (approximately Month 9 from project start)

Year 1 Net Savings: $106,148 per cluster (after all transition costs)
```

### Per-Cluster Savings Summary

```
Per Cluster Metrics (at -18% steady-state savings rate):
  - Year 1 Net Savings:     $106,148  (Delta: -8% vs status quo Year 1)
  - Year 2+ Annual Savings: $250,992  (Delta: -18% vs status quo annual)
  - 5-Year Benefit:       $1,110,000  (Delta: -16% vs status quo 5-year)
```

The -18% annual savings rate applies regardless of corporate discounts. Dollar figures may vary with discount levels; percentage savings remain fixed.

### Reserved Instance Savings (Per Cluster)

| Term | Upfront | RI Discount | Current Monthly | Target Monthly | Target vs Current |
|------|---------|-------------|-----------------|----------------|-------------------|
| On-Demand | None | Baseline | $117,179 | $96,263 | -18% |
| 1-Year No Upfront | None | ~25% | $87,884 | $72,197 | -18% |
| 1-Year All Upfront | Yes | ~35% | $76,165 | $62,571 | -18% |
| 3-Year No Upfront | None | ~40% | $70,307 | $57,757 | -18% |
| 3-Year All Upfront | Yes | ~55% | $52,731 | $43,318 | -18% |

---

*Document Version: 1.3*  
*Last Updated: January 15, 2026*

---

## Change Log

### Version 1.3 (January 2026)

- Added Maximum Density Configuration using r8g.16xlarge instances
  - Enables 10 RegionServers per node with 31 GB heaps (compressed oops enabled)
  - Achieves -51% HBase nodepool cost reduction vs baseline (-24% total cluster cost)
  - Blast radius constrained to 10% per node failure for production suitability
  - Annual savings per cluster: $337,032 with Maximum Density option
- Added metal instance types to instance comparison table (r8g.metal-24xl, r7i.metal-24xl, r8g.metal-48xl, r7i.metal-48xl)
- Added comprehensive NUMA considerations for 16xlarge instances
  - Documented NUMA-aware JVM configuration (-XX:+UseNUMA, -XX:+UseNUMAInterleaving)
  - Added numactl binding examples for per-NUMA-node RS placement
- Added memory allocation example for 10 RS on 512 GB instance
- Added blast radius impact analysis for region recovery time
  - Documented 2.5× increase in affected regions per node failure (10% vs 4%)
  - Estimated recovery time impact: 2-3× longer cluster recovery
- Updated recommended configuration matrix with Max Density RegionServer option
- Updated alternative configurations table with Maximum Density option and blast radius column
- Cross-validation fixes for internal consistency:
  - Standardized CTS configuration to 6 RS per node with 31 GB heaps (compressed oops enabled)
  - Standardized Performance configuration to 3 RS per node with 31 GB heaps
  - Fixed memory allocation examples to fit within instance memory limits
  - All consolidated configurations now use 31 GB heaps to benefit from compressed oops
  - Updated CTS from "cross-pool scheduling" to colocation model
  - Maximum Density now shows 10 RS + 10 DN colocated per r8g.16xlarge node
- NUMA section updates based on Graviton4 architecture:
  - Corrected NUMA topology: r8g.16xlarge has single NUMA node (64 vCPUs on 96-core chip)
  - Removed unnecessary NUMA-aware JVM configuration for r8g up to 24xlarge
  - NUMA tuning only required for metal instances (r8g.metal-48xl)
- Cost table fixes:
  - Updated CTS cost line: 17 instances × 6 RS = 102 RS, ~$23K/month (-58%)
  - Updated blast radius comparison table: CTS now shows 6% blast radius
- JVM Appendix updates:
  - Updated all G1GC examples to use 31 GB heaps
  - Updated ParallelGCThreads to match container cpuRequest per configuration
- All section references now consistently use 6 RS/node for CTS, 3 RS/node for Performance

### Version 1.2 (January 2026)

- Migrated to AWS Graviton4 (ARM) instances
  - Changed HBase nodepool recommendation from r7i.8xlarge to r8g.8xlarge
  - Changed Yarn and Master nodepool recommendations from m6i.8xlarge to m8g.8xlarge
  - Graviton4 instances provide ~11% cost savings over equivalent Intel instances
- Improved cost savings from -11% to -18%
  - Annual savings per cluster increased from $148,572 to $250,992
  - Monthly savings per cluster increased from $12,381 to $20,916
  - 5-year benefit per cluster increased from $620,000 to $1,110,000
- Added comprehensive ARM64/Graviton-specific JVM tuning section
- Updated all cost analysis tables, projections, and appendices with Graviton4 pricing
- Added ARM compatibility consideration to pilot phase (validate ARM workload compatibility)
- Updated instance type comparison table to feature Graviton4 instances
- Note: Java workloads like HBase run seamlessly on Graviton with OpenJDK ARM builds

### Version 1.1 (January 2026)

- Added comprehensive section on the JVM compressed oops "dead zone" (32-48 GB heap sizes)
- Documented why heaps between 32-48 GB should be avoided (compressed oops disabled but heap not large enough to justify overhead)
- Updated all heap size recommendations to avoid the dead zone:
  - CTS configuration: Changed from 4 RS with 40-48 GB heap to 6-8 RS with 28-31 GB heap (benefits from compressed oops)
  - Performance configuration: Changed from 40-48 GB heap to 48-64 GB heap (justifies loss of compressed oops)
- Updated memory allocation examples to reflect new heap sizing
- Added guidance for two valid approaches: more RS with smaller heaps (≤31 GB) or fewer RS with larger heaps (≥48 GB)
- Updated G1GC and ZGC usage recommendations to align with dead zone guidance

### Version 1.0 (January 2026)

- Initial proposal document
