/*
 * Copyright 2018 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package tech.pegasys.pantheon.controller;

import static org.apache.logging.log4j.LogManager.getLogger;

import tech.pegasys.pantheon.config.GenesisConfigFile;
import tech.pegasys.pantheon.config.GenesisConfigOptions;
import tech.pegasys.pantheon.config.IbftConfigOptions;
import tech.pegasys.pantheon.consensus.common.EpochManager;
import tech.pegasys.pantheon.consensus.common.VoteProposer;
import tech.pegasys.pantheon.consensus.common.VoteTally;
import tech.pegasys.pantheon.consensus.common.VoteTallyUpdater;
import tech.pegasys.pantheon.consensus.ibft.IbftChainObserver;
import tech.pegasys.pantheon.consensus.ibft.IbftContext;
import tech.pegasys.pantheon.consensus.ibft.IbftEventQueue;
import tech.pegasys.pantheon.consensus.ibft.IbftProcessor;
import tech.pegasys.pantheon.consensus.ibft.IbftStateMachine;
import tech.pegasys.pantheon.consensus.ibft.network.IbftNetworkPeers;
import tech.pegasys.pantheon.consensus.ibft.protocol.IbftProtocolManager;
import tech.pegasys.pantheon.consensus.ibft.protocol.IbftSubProtocol;
import tech.pegasys.pantheon.consensus.ibftlegacy.IbftLegacyVotingBlockInterface;
import tech.pegasys.pantheon.consensus.ibftlegacy.IbftProtocolSchedule;
import tech.pegasys.pantheon.consensus.ibftlegacy.protocol.Istanbul64Protocol;
import tech.pegasys.pantheon.consensus.ibftlegacy.protocol.Istanbul64ProtocolManager;
import tech.pegasys.pantheon.crypto.SECP256K1.KeyPair;
import tech.pegasys.pantheon.ethereum.ProtocolContext;
import tech.pegasys.pantheon.ethereum.blockcreation.MiningCoordinator;
import tech.pegasys.pantheon.ethereum.chain.GenesisState;
import tech.pegasys.pantheon.ethereum.chain.MutableBlockchain;
import tech.pegasys.pantheon.ethereum.core.BlockHashFunction;
import tech.pegasys.pantheon.ethereum.core.Hash;
import tech.pegasys.pantheon.ethereum.core.Synchronizer;
import tech.pegasys.pantheon.ethereum.core.TransactionPool;
import tech.pegasys.pantheon.ethereum.db.DefaultMutableBlockchain;
import tech.pegasys.pantheon.ethereum.db.KeyValueStoragePrefixedKeyBlockchainStorage;
import tech.pegasys.pantheon.ethereum.db.WorldStateArchive;
import tech.pegasys.pantheon.ethereum.eth.EthProtocol;
import tech.pegasys.pantheon.ethereum.eth.manager.EthProtocolManager;
import tech.pegasys.pantheon.ethereum.eth.sync.DefaultSynchronizer;
import tech.pegasys.pantheon.ethereum.eth.sync.SyncMode;
import tech.pegasys.pantheon.ethereum.eth.sync.SynchronizerConfiguration;
import tech.pegasys.pantheon.ethereum.eth.sync.state.SyncState;
import tech.pegasys.pantheon.ethereum.eth.transactions.TransactionPoolFactory;
import tech.pegasys.pantheon.ethereum.mainnet.ProtocolSchedule;
import tech.pegasys.pantheon.ethereum.mainnet.ScheduleBasedBlockHashFunction;
import tech.pegasys.pantheon.ethereum.p2p.api.ProtocolManager;
import tech.pegasys.pantheon.ethereum.p2p.config.SubProtocolConfiguration;
import tech.pegasys.pantheon.ethereum.p2p.wire.SubProtocol;
import tech.pegasys.pantheon.ethereum.worldstate.KeyValueStorageWorldStateStorage;
import tech.pegasys.pantheon.services.kvstore.KeyValueStorage;
import tech.pegasys.pantheon.services.kvstore.RocksDbKeyValueStorage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.Logger;

public class IbftPantheonController implements PantheonController<IbftContext> {

  private static final Logger LOG = getLogger();
  private final GenesisConfigOptions genesisConfig;
  private final ProtocolSchedule<IbftContext> protocolSchedule;
  private final ProtocolContext<IbftContext> context;
  private final Synchronizer synchronizer;
  private final SubProtocol ethSubProtocol;
  private final ProtocolManager ethProtocolManager;
  private final IbftProtocolManager ibftProtocolManager;
  private final KeyPair keyPair;
  private final TransactionPool transactionPool;
  private final IbftProcessor ibftProcessor;
  private final Runnable closer;

  IbftPantheonController(
      final GenesisConfigOptions genesisConfig,
      final ProtocolSchedule<IbftContext> protocolSchedule,
      final ProtocolContext<IbftContext> context,
      final SubProtocol ethSubProtocol,
      final ProtocolManager ethProtocolManager,
      final IbftProtocolManager ibftProtocolManager,
      final Synchronizer synchronizer,
      final KeyPair keyPair,
      final TransactionPool transactionPool,
      final IbftProcessor ibftProcessor,
      final Runnable closer) {

    this.genesisConfig = genesisConfig;
    this.protocolSchedule = protocolSchedule;
    this.context = context;
    this.ethSubProtocol = ethSubProtocol;
    this.ethProtocolManager = ethProtocolManager;
    this.ibftProtocolManager = ibftProtocolManager;
    this.synchronizer = synchronizer;
    this.keyPair = keyPair;
    this.transactionPool = transactionPool;
    this.ibftProcessor = ibftProcessor;
    this.closer = closer;
  }

  public static PantheonController<IbftContext> init(
      final Path home,
      final GenesisConfigFile genesisConfig,
      final SynchronizerConfiguration taintedSyncConfig,
      final boolean ottomanTestnetOperation,
      final int networkId,
      final KeyPair nodeKeys)
      throws IOException {
    final KeyValueStorage kv =
        RocksDbKeyValueStorage.create(Files.createDirectories(home.resolve(DATABASE_PATH)));
    final ProtocolSchedule<IbftContext> protocolSchedule =
        IbftProtocolSchedule.create(genesisConfig.getConfigOptions());
    final BlockHashFunction blockHashFunction =
        ScheduleBasedBlockHashFunction.create(protocolSchedule);
    final GenesisState genesisState = GenesisState.fromConfig(genesisConfig, protocolSchedule);
    final KeyValueStoragePrefixedKeyBlockchainStorage blockchainStorage =
        new KeyValueStoragePrefixedKeyBlockchainStorage(kv, blockHashFunction);
    final MutableBlockchain blockchain =
        new DefaultMutableBlockchain(genesisState.getBlock(), blockchainStorage);

    final KeyValueStorageWorldStateStorage worldStateStorage =
        new KeyValueStorageWorldStateStorage(kv);
    final WorldStateArchive worldStateArchive = new WorldStateArchive(worldStateStorage);
    genesisState.writeStateTo(worldStateArchive.getMutable(Hash.EMPTY_TRIE_HASH));

    final IbftConfigOptions ibftConfig = genesisConfig.getConfigOptions().getIbftConfigOptions();
    final EpochManager epochManager = new EpochManager(ibftConfig.getEpochLength());

    final VoteTally voteTally =
        new VoteTallyUpdater(epochManager, new IbftLegacyVotingBlockInterface())
            .buildVoteTallyFromBlockchain(blockchain);

    final VoteProposer voteProposer = new VoteProposer();

    final ProtocolContext<IbftContext> protocolContext =
        new ProtocolContext<>(
            blockchain, worldStateArchive, new IbftContext(voteTally, voteProposer));

    final SynchronizerConfiguration syncConfig = taintedSyncConfig.validated(blockchain);
    final boolean fastSyncEnabled = syncConfig.syncMode().equals(SyncMode.FAST);
    final EthProtocolManager ethProtocolManager;
    final SubProtocol ethSubProtocol;
    if (ottomanTestnetOperation) {
      LOG.info("Operating on Ottoman testnet.");
      ethSubProtocol = Istanbul64Protocol.get();
      ethProtocolManager =
          new Istanbul64ProtocolManager(
              protocolContext.getBlockchain(),
              networkId,
              fastSyncEnabled,
              syncConfig.downloaderParallelism());
    } else {
      ethSubProtocol = EthProtocol.get();
      ethProtocolManager =
          new EthProtocolManager(
              protocolContext.getBlockchain(),
              networkId,
              fastSyncEnabled,
              syncConfig.downloaderParallelism());
    }
    final SyncState syncState =
        new SyncState(
            protocolContext.getBlockchain(), ethProtocolManager.ethContext().getEthPeers());
    final Synchronizer synchronizer =
        new DefaultSynchronizer<>(
            syncConfig,
            protocolSchedule,
            protocolContext,
            ethProtocolManager.ethContext(),
            syncState);

    final IbftEventQueue ibftEventQueue = new IbftEventQueue();

    blockchain.observeBlockAdded(new IbftChainObserver(ibftEventQueue));

    final IbftStateMachine ibftStateMachine = new IbftStateMachine();
    final IbftProcessor ibftProcessor =
        new IbftProcessor(ibftEventQueue, ibftConfig.getRequestTimeoutMillis(), ibftStateMachine);
    final ExecutorService processorExecutor = Executors.newSingleThreadExecutor();
    processorExecutor.submit(ibftProcessor);

    final Runnable closer =
        () -> {
          ibftProcessor.stop();
          processorExecutor.shutdownNow();
          try {
            processorExecutor.awaitTermination(5, TimeUnit.SECONDS);
          } catch (final InterruptedException e) {
            LOG.error("Failed to shutdown ibft processor executor");
          }
          try {
            kv.close();
          } catch (final IOException e) {
            LOG.error("Failed to close key value storage", e);
          }
        };

    final TransactionPool transactionPool =
        TransactionPoolFactory.createTransactionPool(
            protocolSchedule, protocolContext, ethProtocolManager.ethContext());

    final IbftNetworkPeers peers =
        new IbftNetworkPeers(protocolContext.getConsensusState().getVoteTally());

    return new IbftPantheonController(
        genesisConfig.getConfigOptions(),
        protocolSchedule,
        protocolContext,
        ethSubProtocol,
        ethProtocolManager,
        new IbftProtocolManager(ibftEventQueue, peers),
        synchronizer,
        nodeKeys,
        transactionPool,
        ibftProcessor,
        closer);
  }

  @Override
  public ProtocolContext<IbftContext> getProtocolContext() {
    return context;
  }

  @Override
  public ProtocolSchedule<IbftContext> getProtocolSchedule() {
    return protocolSchedule;
  }

  @Override
  public GenesisConfigOptions getGenesisConfigOptions() {
    return genesisConfig;
  }

  @Override
  public Synchronizer getSynchronizer() {
    return synchronizer;
  }

  @Override
  public SubProtocolConfiguration subProtocolConfiguration() {
    return new SubProtocolConfiguration()
        .withSubProtocol(ethSubProtocol, ethProtocolManager)
        .withSubProtocol(IbftSubProtocol.get(), ibftProtocolManager);
  }

  @Override
  public KeyPair getLocalNodeKeyPair() {
    return keyPair;
  }

  @Override
  public TransactionPool getTransactionPool() {
    return transactionPool;
  }

  @Override
  public MiningCoordinator getMiningCoordinator() {
    return null;
  }

  @Override
  public void close() {
    closer.run();
  }
}
