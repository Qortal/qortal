# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Qortal Core is the blockchain and node component of the Qortal decentralized infrastructure platform. It's a Java 11 application built with Maven that provides:
- Blockchain consensus and transaction processing
- REST API for interacting with the network
- QDN (Qortal Data Network) for decentralized data storage
- Q-Apps runtime for decentralized applications
- Cross-chain trading with Bitcoin, Litecoin, Dogecoin, Digibyte, Ravencoin, and PirateChain

## Build Commands

```bash
# Build the project (creates target/qortal-*.jar)
mvn clean package

# Install dependencies and build
mvn install

# Run tests (disabled by default)
mvn test -DskipJUnitTests=false

# Run a single test class
mvn test -DskipJUnitTests=false -Dtest=ArbitraryTransactionTests

# Run a single test method
mvn test -DskipJUnitTests=false -Dtest=ArbitraryTransactionTests#testArbitraryWithFee

# Regenerate protobuf/gRPC classes (normally skipped)
mvn compile -Dprotoc.skip=false
```

## Running the Node

```bash
# Basic run (requires settings.json in working directory)
java -jar target/qortal-*.jar

# With recommended JVM flags
./start.sh
```

## Architecture

### Entry Point
- `org.qortal.controller.Controller` - Main class, singleton that orchestrates all node operations

### Core Packages

**`org.qortal.block`** - Block and blockchain management
- `BlockChain` - Singleton representing the entire chain; loads config from `blockchain.json`
- `Block` - Individual block processing, validation, and minting

**`org.qortal.transaction`** - Transaction types (41 types defined in `Transaction.TransactionType`)
- Base class `Transaction` with subclasses like `ArbitraryTransaction`, `PaymentTransaction`, `ChatTransaction`
- Each transaction type has corresponding `*TransactionData` in `org.qortal.data.transaction`

**`org.qortal.repository`** - Data persistence layer
- `Repository` interface with sub-repositories (AccountRepository, BlockRepository, etc.)
- `HSQLDBRepositoryFactory` - HSQLDB implementation in `org.qortal.repository.hsqldb`
- Database schema updates in `HSQLDBDatabaseUpdates`

**`org.qortal.api`** - REST API (Jetty + Jersey)
- Resources in `org.qortal.api.resource` (e.g., `ArbitraryResource`, `BlocksResource`)
- API available at port 12391 (mainnet) or 62391 (testnet)
- Swagger UI at `/api-documentation`

**`org.qortal.arbitrary`** - QDN (Qortal Data Network)
- `ArbitraryDataTransactionBuilder` - Creates ARBITRARY transactions for QDN publishes
- `ArbitraryDataReader`/`ArbitraryDataWriter` - Read/write QDN resources
- Services defined in `org.qortal.arbitrary.misc.Service`

**`org.qortal.crosschain`** - Cross-chain atomic swaps
- `Bitcoiny` - Base class for Bitcoin-like chains
- `*ACCT*` classes - Automated Cross-Chain Trading contracts (compiled CIYAM AT code)
- `ElectrumX` - Electrum server communication

**`org.qortal.network`** - P2P networking
- `Network` - Manages peer connections
- `Peer` - Individual peer connection
- Message types in `org.qortal.network.message`

**`org.qortal.controller.arbitrary`** - QDN controllers
- `ArbitraryDataManager` - Coordinates data fetching/hosting
- `ArbitraryDataFileManager` - File chunk management

### Configuration
- `settings.json` - Node settings (loaded by `org.qortal.settings.Settings`)
- `blockchain.json` - Chain parameters, feature triggers, genesis block (in `src/main/resources`)

### Q-Apps Integration
- `src/main/resources/q-apps/q-apps.js` - Frontend JavaScript API injected into Q-Apps
- qortalRequest actions (e.g., `PUBLISH_QDN_RESOURCE`) are handled by the Qortal UI, which calls Core's REST API

## Testing

Tests extend `org.qortal.test.common.Common` which sets up an in-memory HSQLDB repository. Test accounts (alice, bob, chloe, dilbert) are pre-defined with known private keys.

```java
// Typical test setup
public class MyTest extends Common {
    @Before
    public void beforeTest() throws DataException {
        Common.useDefaultSettings();
    }
}
```

## Key Patterns

- **Repository pattern**: All database access goes through `Repository` interface obtained via `RepositoryManager.getRepository()`
- **Transaction lifecycle**: Build `TransactionData` → Create `Transaction` → Validate → Process → Commit
- **Feature triggers**: Blockchain behavior changes at specific heights/timestamps defined in `BlockChain.FeatureTrigger`
- **Singleton controllers**: Most managers are singletons accessed via `getInstance()`
