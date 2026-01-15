# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build System

Apache HBase uses Maven as its build system. The project follows a multi-module structure with over 50 submodules.

### Essential Build Commands

- **Build the project**: `mvn package` (not `mvn compile` - see pom.xml comment)
- **Clean build**: `mvn clean package`
- **Skip tests**: `mvn package -DskipTests`
- **Install locally**: `mvn install`
- **Generate site documentation**: `mvn site`

### Testing Commands

HBase has comprehensive test profiles for different test categories:

- **Run small tests only**: `mvn test -PrunSmallTests`
- **Run medium tests only**: `mvn test -PrunMediumTests`
- **Run large tests only**: `mvn test -PrunLargeTests`
- **Run all tests**: `mvn test -PrunAllTests`
- **Run specific test categories**:
  - Client tests: `mvn test -PrunClientTests`
  - Master tests: `mvn test -PrunMasterTests`
  - RegionServer tests: `mvn test -PrunRegionServerTests`
  - Replication tests: `mvn test -PrunReplicationTests`
  - Security tests: `mvn test -PrunSecurityTests`

### Development Commands

- **Format code**: `mvn spotless:apply` (formats Java code according to Eclipse formatter)
- **Check code style**: `mvn checkstyle:check`
- **Run SpotBugs**: `mvn spotbugs:check`
- **Generate test jars**: Maven automatically generates test JARs for modules

## Architecture Overview

### Core Modules

The project is organized into several key modules:

- **hbase-common**: Shared utilities, constants, and base classes
- **hbase-client**: Client APIs for connecting to HBase clusters
- **hbase-server**: RegionServer and Master server implementations
- **hbase-protocol-shaded**: Shaded protobuf definitions for internal communication
- **hbase-procedure**: Procedure framework for distributed operations
- **hbase-zookeeper**: ZooKeeper integration layer
- **hbase-hadoop-compat**: Hadoop compatibility layer
- **hbase-mapreduce**: MapReduce integration for bulk operations

### Key Architectural Components

1. **Master/RegionServer Architecture**: HBase follows a master-slave architecture where HMaster coordinates region assignments and HRegionServer serves data
2. **Region Management**: Data is partitioned into regions, each served by a RegionServer
3. **Procedure Framework**: Distributed operations use a procedure-based approach for reliability
4. **Shaded Dependencies**: Uses shaded versions of third-party libraries (Netty, Guava, Protobuf) to avoid classpath conflicts
5. **Multi-version Support**: Supports multiple Hadoop versions through profile activation

### Module Dependencies

- Most modules depend on hbase-common for shared utilities
- Server-side modules typically depend on hbase-protocol-shaded for RPC
- Client modules use hbase-client as the main API entry point
- Testing utilities are provided by hbase-testing-util

## Development Configuration

### Code Style

- **Eclipse formatter**: `dev-support/hbase_eclipse_formatter.xml`
- **Import order**: `dev-support/eclipse.importorder`
- **Checkstyle config**: Available in hbase-checkstyle module
- **Spotless integration**: Automatically formats code during build

### Java Version

- **Minimum Java**: Java 17
- **Compile source**: Java 17
- **Release target**: Java 17

### IDE Setup

- Import Eclipse code formatter: `dev-support/hbase_eclipse_formatter.xml`
- Import order configuration: `dev-support/eclipse.importorder`
- Checkstyle plugin can use configuration from hbase-checkstyle module

## Important Notes

1. **Build Requirement**: Always use `mvn package` instead of `mvn compile` for initial builds
2. **Shaded Dependencies**: Use shaded versions from hbase-thirdparty (Guava, Netty, etc.)
3. **Test Categories**: Tests are categorized by size (Small, Medium, Large) and functional area
4. **Hadoop Profiles**: Default profile is hadoop-3.0, activated automatically
5. **Logging**: Uses SLF4J with Log4j2 implementation - avoid other logging frameworks
6. **Protobuf**: Uses shaded protobuf with custom package prefixes

## Common Development Patterns

- **Audience Annotations**: Use @InterfaceAudience annotations to mark API stability
- **Configuration**: HBase configuration extends Hadoop Configuration
- **RPC**: Server communication uses protobuf-based RPC with shaded dependencies
- **Testing**: Prefer HBaseTestingUtil for integration tests, use appropriate test size annotations
