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
package tech.pegasys.pantheon.ethereum.eth.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import tech.pegasys.pantheon.ethereum.chain.Blockchain;
import tech.pegasys.pantheon.ethereum.chain.MutableBlockchain;
import tech.pegasys.pantheon.ethereum.core.Block;
import tech.pegasys.pantheon.ethereum.core.BlockBody;
import tech.pegasys.pantheon.ethereum.core.BlockHeader;
import tech.pegasys.pantheon.ethereum.core.Hash;
import tech.pegasys.pantheon.ethereum.core.InMemoryTestFixture;
import tech.pegasys.pantheon.ethereum.core.TransactionReceipt;
import tech.pegasys.pantheon.ethereum.eth.EthProtocol;
import tech.pegasys.pantheon.ethereum.eth.EthProtocol.EthVersion;
import tech.pegasys.pantheon.ethereum.eth.manager.MockPeerConnection.PeerSendHandler;
import tech.pegasys.pantheon.ethereum.eth.manager.ethtaskutils.BlockchainSetupUtil;
import tech.pegasys.pantheon.ethereum.eth.messages.BlockBodiesMessage;
import tech.pegasys.pantheon.ethereum.eth.messages.BlockHeadersMessage;
import tech.pegasys.pantheon.ethereum.eth.messages.EthPV62;
import tech.pegasys.pantheon.ethereum.eth.messages.EthPV63;
import tech.pegasys.pantheon.ethereum.eth.messages.GetBlockBodiesMessage;
import tech.pegasys.pantheon.ethereum.eth.messages.GetBlockHeadersMessage;
import tech.pegasys.pantheon.ethereum.eth.messages.GetReceiptsMessage;
import tech.pegasys.pantheon.ethereum.eth.messages.NewBlockMessage;
import tech.pegasys.pantheon.ethereum.eth.messages.ReceiptsMessage;
import tech.pegasys.pantheon.ethereum.eth.messages.StatusMessage;
import tech.pegasys.pantheon.ethereum.mainnet.MainnetProtocolSchedule;
import tech.pegasys.pantheon.ethereum.mainnet.ProtocolSchedule;
import tech.pegasys.pantheon.ethereum.p2p.api.MessageData;
import tech.pegasys.pantheon.ethereum.p2p.api.PeerConnection;
import tech.pegasys.pantheon.ethereum.p2p.wire.Capability;
import tech.pegasys.pantheon.ethereum.p2p.wire.DefaultMessage;
import tech.pegasys.pantheon.ethereum.testutil.BlockDataGenerator;
import tech.pegasys.pantheon.util.uint.UInt256;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.google.common.collect.Lists;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public final class EthProtocolManagerTest {

  private static Blockchain blockchain;
  private static ProtocolSchedule<Void> protocolSchedule;
  private static BlockDataGenerator gen;

  @BeforeClass
  public static void setup() {
    gen = new BlockDataGenerator(0);
    final BlockchainSetupUtil<Void> blockchainSetupUtil = BlockchainSetupUtil.forTesting();
    blockchainSetupUtil.importAllBlocks();
    blockchain = blockchainSetupUtil.getBlockchain();
    protocolSchedule = blockchainSetupUtil.getProtocolSchedule();
    assert (blockchainSetupUtil.getMaxBlockNumber() >= 20L);
  }

  @Test
  public void disconnectOnUnsolicitedMessage() {
    try (final EthProtocolManager ethManager = new EthProtocolManager(blockchain, 1, true, 1)) {
      final MessageData messageData =
          BlockHeadersMessage.create(Collections.singletonList(blockchain.getBlockHeader(1).get()));
      final MockPeerConnection peer = setupPeer(ethManager, (cap, msg, conn) -> {});
      ethManager.processMessage(EthProtocol.ETH63, new DefaultMessage(peer, messageData));
      assertThat(peer.isDisconnected()).isTrue();
    }
  }

  @Test
  public void disconnectOnFailureToSendStatusMessage() {
    try (final EthProtocolManager ethManager = new EthProtocolManager(blockchain, 1, true, 1)) {
      final MessageData messageData =
          BlockHeadersMessage.create(Collections.singletonList(blockchain.getBlockHeader(1).get()));
      final MockPeerConnection peer =
          setupPeerWithoutStatusExchange(ethManager, (cap, msg, conn) -> {});
      ethManager.processMessage(EthProtocol.ETH63, new DefaultMessage(peer, messageData));
      assertThat(peer.isDisconnected()).isTrue();
    }
  }

  @Test
  public void disconnectOnWrongChainId() {
    try (final EthProtocolManager ethManager = new EthProtocolManager(blockchain, 1, true, 1)) {
      final MessageData messageData =
          BlockHeadersMessage.create(Collections.singletonList(blockchain.getBlockHeader(1).get()));
      final MockPeerConnection peer =
          setupPeerWithoutStatusExchange(ethManager, (cap, msg, conn) -> {});

      // Send status message with wrong chain
      final StatusMessage statusMessage =
          StatusMessage.create(
              EthVersion.V63,
              2222,
              blockchain.getChainHead().getTotalDifficulty(),
              blockchain.getChainHeadHash(),
              blockchain.getBlockHeader(BlockHeader.GENESIS_BLOCK_NUMBER).get().getHash());
      ethManager.processMessage(EthProtocol.ETH63, new DefaultMessage(peer, statusMessage));

      ethManager.processMessage(EthProtocol.ETH63, new DefaultMessage(peer, messageData));
      assertThat(peer.isDisconnected()).isTrue();
    }
  }

  @Test
  public void disconnectOnWrongGenesisHash() {
    try (final EthProtocolManager ethManager = new EthProtocolManager(blockchain, 1, true, 1)) {
      final MessageData messageData =
          BlockHeadersMessage.create(Collections.singletonList(blockchain.getBlockHeader(1).get()));
      final MockPeerConnection peer =
          setupPeerWithoutStatusExchange(ethManager, (cap, msg, conn) -> {});

      // Send status message with wrong chain
      final StatusMessage statusMessage =
          StatusMessage.create(
              EthVersion.V63,
              1,
              blockchain.getChainHead().getTotalDifficulty(),
              gen.hash(),
              blockchain.getBlockHeader(BlockHeader.GENESIS_BLOCK_NUMBER).get().getHash());
      ethManager.processMessage(EthProtocol.ETH63, new DefaultMessage(peer, statusMessage));

      ethManager.processMessage(EthProtocol.ETH63, new DefaultMessage(peer, messageData));
      assertThat(peer.isDisconnected()).isTrue();
    }
  }

  @Test(expected = ConditionTimeoutException.class)
  public void doNotDisconnectOnValidMessage() {
    try (final EthProtocolManager ethManager = new EthProtocolManager(blockchain, 1, true, 1)) {
      final MessageData messageData =
          GetBlockBodiesMessage.create(Collections.singletonList(gen.hash()));
      final MockPeerConnection peer = setupPeer(ethManager, (cap, msg, conn) -> {});
      ethManager.processMessage(EthProtocol.ETH63, new DefaultMessage(peer, messageData));
      Awaitility.await()
          .catchUncaughtExceptions()
          .atMost(200, TimeUnit.MILLISECONDS)
          .until(peer::isDisconnected);
    }
  }

  @Test
  public void respondToGetHeaders() throws ExecutionException, InterruptedException {
    final CompletableFuture<Void> done = new CompletableFuture<>();
    try (final EthProtocolManager ethManager = new EthProtocolManager(blockchain, 1, true, 1)) {
      final long startBlock = 5L;
      final int blockCount = 5;
      final MessageData messageData =
          GetBlockHeadersMessage.create(startBlock, blockCount, 0, false);
      final PeerSendHandler onSend =
          (cap, message, conn) -> {
            if (message.getCode() == EthPV62.STATUS) {
              // Ignore status message
              return;
            }
            assertThat(message.getCode()).isEqualTo(EthPV62.BLOCK_HEADERS);
            final BlockHeadersMessage headersMsg = BlockHeadersMessage.readFrom(message);
            final List<BlockHeader> headers =
                Lists.newArrayList(headersMsg.getHeaders(protocolSchedule));
            assertThat(headers.size()).isEqualTo(blockCount);
            for (int i = 0; i < blockCount; i++) {
              assertThat(headers.get(i).getNumber()).isEqualTo(startBlock + i);
            }
            message.release();
            done.complete(null);
          };
      final PeerConnection peer = setupPeer(ethManager, onSend);
      ethManager.processMessage(EthProtocol.ETH63, new DefaultMessage(peer, messageData));
      done.get();
    }
  }

  @Test
  public void respondToGetHeadersWithinLimits() throws ExecutionException, InterruptedException {
    final CompletableFuture<Void> done = new CompletableFuture<>();
    final int limit = 5;
    try (final EthProtocolManager ethManager =
        new EthProtocolManager(blockchain, 1, true, 1, limit)) {
      final long startBlock = 5L;
      final int blockCount = 10;
      final MessageData messageData =
          GetBlockHeadersMessage.create(startBlock, blockCount, 0, false);
      final PeerSendHandler onSend =
          (cap, message, conn) -> {
            if (message.getCode() == EthPV62.STATUS) {
              // Ignore status message
              return;
            }
            assertThat(message.getCode()).isEqualTo(EthPV62.BLOCK_HEADERS);
            final BlockHeadersMessage headersMsg = BlockHeadersMessage.readFrom(message);
            final List<BlockHeader> headers =
                Lists.newArrayList(headersMsg.getHeaders(protocolSchedule));
            assertThat(headers.size()).isEqualTo(limit);
            for (int i = 0; i < limit; i++) {
              assertThat(headers.get(i).getNumber()).isEqualTo(startBlock + i);
            }
            message.release();
            done.complete(null);
          };
      final PeerConnection peer = setupPeer(ethManager, onSend);
      ethManager.processMessage(EthProtocol.ETH63, new DefaultMessage(peer, messageData));
      done.get();
    }
  }

  @Test
  public void respondToGetHeadersReversed() throws ExecutionException, InterruptedException {
    final CompletableFuture<Void> done = new CompletableFuture<>();
    try (final EthProtocolManager ethManager = new EthProtocolManager(blockchain, 1, true, 1)) {
      final long endBlock = 10L;
      final int blockCount = 5;
      final MessageData messageData = GetBlockHeadersMessage.create(endBlock, blockCount, 0, true);
      final PeerSendHandler onSend =
          (cap, message, conn) -> {
            if (message.getCode() == EthPV62.STATUS) {
              // Ignore status message
              return;
            }
            assertThat(message.getCode()).isEqualTo(EthPV62.BLOCK_HEADERS);
            final BlockHeadersMessage headersMsg = BlockHeadersMessage.readFrom(message);
            final List<BlockHeader> headers =
                Lists.newArrayList(headersMsg.getHeaders(protocolSchedule));
            assertThat(headers.size()).isEqualTo(blockCount);
            for (int i = 0; i < blockCount; i++) {
              assertThat(headers.get(i).getNumber()).isEqualTo(endBlock - i);
            }
            message.release();
            done.complete(null);
          };
      final PeerConnection peer = setupPeer(ethManager, onSend);
      ethManager.processMessage(EthProtocol.ETH63, new DefaultMessage(peer, messageData));
      done.get();
    }
  }

  @Test
  public void respondToGetHeadersWithSkip() throws ExecutionException, InterruptedException {
    final CompletableFuture<Void> done = new CompletableFuture<>();
    try (final EthProtocolManager ethManager = new EthProtocolManager(blockchain, 1, true, 1)) {
      final long startBlock = 5L;
      final int blockCount = 5;
      final int skip = 1;
      final MessageData messageData =
          GetBlockHeadersMessage.create(startBlock, blockCount, 1, false);
      final PeerSendHandler onSend =
          (cap, message, conn) -> {
            if (message.getCode() == EthPV62.STATUS) {
              // Ignore status message
              return;
            }
            assertThat(message.getCode()).isEqualTo(EthPV62.BLOCK_HEADERS);
            final BlockHeadersMessage headersMsg = BlockHeadersMessage.readFrom(message);
            final List<BlockHeader> headers =
                Lists.newArrayList(headersMsg.getHeaders(protocolSchedule));
            assertThat(headers.size()).isEqualTo(blockCount);
            for (int i = 0; i < blockCount; i++) {
              assertThat(headers.get(i).getNumber()).isEqualTo(startBlock + i * (skip + 1));
            }
            message.release();
            done.complete(null);
          };
      final PeerConnection peer = setupPeer(ethManager, onSend);
      ethManager.processMessage(EthProtocol.ETH63, new DefaultMessage(peer, messageData));
      done.get();
    }
  }

  @Test
  public void respondToGetHeadersReversedWithSkip()
      throws ExecutionException, InterruptedException {
    final CompletableFuture<Void> done = new CompletableFuture<>();
    try (final EthProtocolManager ethManager = new EthProtocolManager(blockchain, 1, true, 1)) {
      final long endBlock = 10L;
      final int blockCount = 5;
      final int skip = 1;
      final MessageData messageData =
          GetBlockHeadersMessage.create(endBlock, blockCount, skip, true);
      final PeerSendHandler onSend =
          (cap, message, conn) -> {
            if (message.getCode() == EthPV62.STATUS) {
              // Ignore status message
              return;
            }
            assertThat(message.getCode()).isEqualTo(EthPV62.BLOCK_HEADERS);
            final BlockHeadersMessage headersMsg = BlockHeadersMessage.readFrom(message);
            final List<BlockHeader> headers =
                Lists.newArrayList(headersMsg.getHeaders(protocolSchedule));
            assertThat(headers.size()).isEqualTo(blockCount);
            for (int i = 0; i < blockCount; i++) {
              assertThat(headers.get(i).getNumber()).isEqualTo(endBlock - i * (skip + 1));
            }
            message.release();
            done.complete(null);
          };
      final PeerConnection peer = setupPeer(ethManager, onSend);
      ethManager.processMessage(EthProtocol.ETH63, new DefaultMessage(peer, messageData));
      done.get();
    }
  }

  private MockPeerConnection setupPeer(
      final EthProtocolManager ethManager, final PeerSendHandler onSend) {
    final MockPeerConnection peer = setupPeerWithoutStatusExchange(ethManager, onSend);
    final StatusMessage statusMessage =
        StatusMessage.create(
            EthVersion.V63,
            1,
            blockchain.getChainHead().getTotalDifficulty(),
            blockchain.getChainHeadHash(),
            blockchain.getBlockHeader(BlockHeader.GENESIS_BLOCK_NUMBER).get().getHash());
    ethManager.processMessage(EthProtocol.ETH63, new DefaultMessage(peer, statusMessage));
    return peer;
  }

  private MockPeerConnection setupPeerWithoutStatusExchange(
      final EthProtocolManager ethManager, final PeerSendHandler onSend) {
    final Set<Capability> caps = new HashSet<>(Arrays.asList(EthProtocol.ETH63));
    final MockPeerConnection peer = new MockPeerConnection(caps, onSend);
    ethManager.handleNewConnection(peer);
    return peer;
  }

  @Test
  public void respondToGetHeadersPartial() throws ExecutionException, InterruptedException {
    final CompletableFuture<Void> done = new CompletableFuture<>();
    try (final EthProtocolManager ethManager = new EthProtocolManager(blockchain, 1, true, 1)) {
      final long startBlock = blockchain.getChainHeadBlockNumber() - 1L;
      final int blockCount = 5;
      final MessageData messageData =
          GetBlockHeadersMessage.create(startBlock, blockCount, 0, false);
      final PeerSendHandler onSend =
          (cap, message, conn) -> {
            if (message.getCode() == EthPV62.STATUS) {
              // Ignore status message
              return;
            }
            assertThat(message.getCode()).isEqualTo(EthPV62.BLOCK_HEADERS);
            final BlockHeadersMessage headersMsg = BlockHeadersMessage.readFrom(message);
            final List<BlockHeader> headers =
                Lists.newArrayList(headersMsg.getHeaders(protocolSchedule));
            assertThat(headers.size()).isEqualTo(2);
            for (int i = 0; i < 2; i++) {
              assertThat(headers.get(i).getNumber()).isEqualTo(startBlock + i);
            }
            message.release();
            done.complete(null);
          };
      final PeerConnection peer = setupPeer(ethManager, onSend);
      ethManager.processMessage(EthProtocol.ETH63, new DefaultMessage(peer, messageData));
      done.get();
    }
  }

  @Test
  public void respondToGetHeadersEmpty() throws ExecutionException, InterruptedException {
    final CompletableFuture<Void> done = new CompletableFuture<>();
    try (final EthProtocolManager ethManager = new EthProtocolManager(blockchain, 1, true, 1)) {
      final long startBlock = blockchain.getChainHeadBlockNumber() + 1;
      final int blockCount = 5;
      final MessageData messageData =
          GetBlockHeadersMessage.create(startBlock, blockCount, 0, false);
      final PeerSendHandler onSend =
          (cap, message, conn) -> {
            if (message.getCode() == EthPV62.STATUS) {
              // Ignore status message
              return;
            }
            assertThat(message.getCode()).isEqualTo(EthPV62.BLOCK_HEADERS);
            final BlockHeadersMessage headersMsg = BlockHeadersMessage.readFrom(message);
            final List<BlockHeader> headers =
                Lists.newArrayList(headersMsg.getHeaders(protocolSchedule));
            assertThat(headers.size()).isEqualTo(0);
            message.release();
            done.complete(null);
          };
      final PeerConnection peer = setupPeer(ethManager, onSend);
      ethManager.processMessage(EthProtocol.ETH63, new DefaultMessage(peer, messageData));
      done.get();
    }
  }

  @Test
  public void respondToGetBodies() throws ExecutionException, InterruptedException {
    final CompletableFuture<Void> done = new CompletableFuture<>();
    try (final EthProtocolManager ethManager = new EthProtocolManager(blockchain, 1, true, 1)) {
      // Setup blocks query
      final long startBlock = blockchain.getChainHeadBlockNumber() - 5;
      final int blockCount = 2;
      final Block[] expectedBlocks = new Block[blockCount];
      for (int i = 0; i < blockCount; i++) {
        final BlockHeader header = blockchain.getBlockHeader(startBlock + i).get();
        final BlockBody body = blockchain.getBlockBody(header.getHash()).get();
        expectedBlocks[i] = new Block(header, body);
      }
      final List<Hash> hashes =
          Arrays.stream(expectedBlocks).map(Block::getHash).collect(Collectors.toList());
      final MessageData messageData = GetBlockBodiesMessage.create(hashes);

      // Define handler to validate response
      final PeerSendHandler onSend =
          (cap, message, conn) -> {
            if (message.getCode() == EthPV62.STATUS) {
              // Ignore status message
              return;
            }
            assertThat(message.getCode()).isEqualTo(EthPV62.BLOCK_BODIES);
            final BlockBodiesMessage blocksMessage = BlockBodiesMessage.readFrom(message);
            final List<BlockBody> bodies =
                Lists.newArrayList(blocksMessage.bodies(protocolSchedule));
            assertThat(bodies.size()).isEqualTo(blockCount);
            for (int i = 0; i < blockCount; i++) {
              assertThat(expectedBlocks[i].getBody()).isEqualTo(bodies.get(i));
            }
            message.release();
            done.complete(null);
          };

      // Run test
      final PeerConnection peer = setupPeer(ethManager, onSend);
      ethManager.processMessage(EthProtocol.ETH63, new DefaultMessage(peer, messageData));
      done.get();
    }
  }

  @Test
  public void respondToGetBodiesWithinLimits() throws ExecutionException, InterruptedException {
    final CompletableFuture<Void> done = new CompletableFuture<>();
    final int limit = 5;
    try (final EthProtocolManager ethManager =
        new EthProtocolManager(blockchain, 1, true, 1, limit)) {
      // Setup blocks query
      final int blockCount = 10;
      final long startBlock = blockchain.getChainHeadBlockNumber() - blockCount;
      final Block[] expectedBlocks = new Block[blockCount];
      for (int i = 0; i < blockCount; i++) {
        final BlockHeader header = blockchain.getBlockHeader(startBlock + i).get();
        final BlockBody body = blockchain.getBlockBody(header.getHash()).get();
        expectedBlocks[i] = new Block(header, body);
      }
      final List<Hash> hashes =
          Arrays.stream(expectedBlocks).map(Block::getHash).collect(Collectors.toList());
      final MessageData messageData = GetBlockBodiesMessage.create(hashes);

      // Define handler to validate response
      final PeerSendHandler onSend =
          (cap, message, conn) -> {
            if (message.getCode() == EthPV62.STATUS) {
              // Ignore status message
              return;
            }
            assertThat(message.getCode()).isEqualTo(EthPV62.BLOCK_BODIES);
            final BlockBodiesMessage blocksMessage = BlockBodiesMessage.readFrom(message);
            final List<BlockBody> bodies =
                Lists.newArrayList(blocksMessage.bodies(protocolSchedule));
            assertThat(bodies.size()).isEqualTo(limit);
            for (int i = 0; i < limit; i++) {
              assertThat(expectedBlocks[i].getBody()).isEqualTo(bodies.get(i));
            }
            message.release();
            done.complete(null);
          };

      // Run test
      final PeerConnection peer = setupPeer(ethManager, onSend);
      ethManager.processMessage(EthProtocol.ETH63, new DefaultMessage(peer, messageData));
      done.get();
    }
  }

  @Test
  public void respondToGetBodiesPartial() throws ExecutionException, InterruptedException {
    final CompletableFuture<Void> done = new CompletableFuture<>();
    try (final EthProtocolManager ethManager = new EthProtocolManager(blockchain, 1, true, 1)) {
      // Setup blocks query
      final long expectedBlockNumber = blockchain.getChainHeadBlockNumber() - 1;
      final BlockHeader header = blockchain.getBlockHeader(expectedBlockNumber).get();
      final BlockBody body = blockchain.getBlockBody(header.getHash()).get();
      final Block expectedBlock = new Block(header, body);

      final List<Hash> hashes = Arrays.asList(gen.hash(), expectedBlock.getHash(), gen.hash());
      final MessageData messageData = GetBlockBodiesMessage.create(hashes);

      // Define handler to validate response
      final PeerSendHandler onSend =
          (cap, message, conn) -> {
            if (message.getCode() == EthPV62.STATUS) {
              // Ignore status message
              return;
            }
            assertThat(message.getCode()).isEqualTo(EthPV62.BLOCK_BODIES);
            final BlockBodiesMessage blocksMessage = BlockBodiesMessage.readFrom(message);
            final List<BlockBody> bodies =
                Lists.newArrayList(blocksMessage.bodies(protocolSchedule));
            assertThat(bodies.size()).isEqualTo(1);
            assertThat(expectedBlock.getBody()).isEqualTo(bodies.get(0));
            message.release();
            done.complete(null);
          };

      // Run test
      final PeerConnection peer = setupPeer(ethManager, onSend);
      ethManager.processMessage(EthProtocol.ETH63, new DefaultMessage(peer, messageData));
      done.get();
    }
  }

  @Test
  public void respondToGetReceipts() throws ExecutionException, InterruptedException {
    final CompletableFuture<Void> done = new CompletableFuture<>();
    try (final EthProtocolManager ethManager = new EthProtocolManager(blockchain, 1, true, 1)) {
      // Setup blocks query
      final long startBlock = blockchain.getChainHeadBlockNumber() - 5;
      final int blockCount = 2;
      final List<List<TransactionReceipt>> expectedReceipts = new ArrayList<>(blockCount);
      final List<Hash> blockHashes = new ArrayList<>(blockCount);
      for (int i = 0; i < blockCount; i++) {
        final BlockHeader header = blockchain.getBlockHeader(startBlock + i).get();
        expectedReceipts.add(blockchain.getTxReceipts(header.getHash()).get());
        blockHashes.add(header.getHash());
      }
      final MessageData messageData = GetReceiptsMessage.create(blockHashes);

      // Define handler to validate response
      final PeerSendHandler onSend =
          (cap, message, conn) -> {
            if (message.getCode() == EthPV62.STATUS) {
              // Ignore status message
              return;
            }
            assertThat(message.getCode()).isEqualTo(EthPV63.RECEIPTS);
            final ReceiptsMessage receiptsMessage = ReceiptsMessage.readFrom(message);
            final List<List<TransactionReceipt>> receipts =
                Lists.newArrayList(receiptsMessage.receipts());
            assertThat(receipts.size()).isEqualTo(blockCount);
            for (int i = 0; i < blockCount; i++) {
              assertThat(expectedReceipts.get(i)).isEqualTo(receipts.get(i));
            }
            message.release();
            done.complete(null);
          };

      // Run test
      final PeerConnection peer = setupPeer(ethManager, onSend);
      ethManager.processMessage(EthProtocol.ETH63, new DefaultMessage(peer, messageData));
      done.get();
    }
  }

  @Test
  public void respondToGetReceiptsWithinLimits() throws ExecutionException, InterruptedException {
    final CompletableFuture<Void> done = new CompletableFuture<>();
    final int limit = 5;
    try (final EthProtocolManager ethManager =
        new EthProtocolManager(blockchain, 1, true, 1, limit)) {
      // Setup blocks query
      final int blockCount = 10;
      final long startBlock = blockchain.getChainHeadBlockNumber() - blockCount;
      final List<List<TransactionReceipt>> expectedReceipts = new ArrayList<>(blockCount);
      final List<Hash> blockHashes = new ArrayList<>(blockCount);
      for (int i = 0; i < blockCount; i++) {
        final BlockHeader header = blockchain.getBlockHeader(startBlock + i).get();
        expectedReceipts.add(blockchain.getTxReceipts(header.getHash()).get());
        blockHashes.add(header.getHash());
      }
      final MessageData messageData = GetReceiptsMessage.create(blockHashes);

      // Define handler to validate response
      final PeerSendHandler onSend =
          (cap, message, conn) -> {
            if (message.getCode() == EthPV62.STATUS) {
              // Ignore status message
              return;
            }
            assertThat(message.getCode()).isEqualTo(EthPV63.RECEIPTS);
            final ReceiptsMessage receiptsMessage = ReceiptsMessage.readFrom(message);
            final List<List<TransactionReceipt>> receipts =
                Lists.newArrayList(receiptsMessage.receipts());
            assertThat(receipts.size()).isEqualTo(limit);
            for (int i = 0; i < limit; i++) {
              assertThat(expectedReceipts.get(i)).isEqualTo(receipts.get(i));
            }
            message.release();
            done.complete(null);
          };

      // Run test
      final PeerConnection peer = setupPeer(ethManager, onSend);
      ethManager.processMessage(EthProtocol.ETH63, new DefaultMessage(peer, messageData));
      done.get();
    }
  }

  @Test
  public void respondToGetReceiptsPartial() throws ExecutionException, InterruptedException {
    final CompletableFuture<Void> done = new CompletableFuture<>();
    try (final EthProtocolManager ethManager = new EthProtocolManager(blockchain, 1, true, 1)) {
      // Setup blocks query
      final long blockNumber = blockchain.getChainHeadBlockNumber() - 5;
      final int blockCount = 2;
      final BlockHeader header = blockchain.getBlockHeader(blockNumber).get();
      final List<TransactionReceipt> expectedReceipts =
          blockchain.getTxReceipts(header.getHash()).get();
      final Hash blockHash = header.getHash();
      final MessageData messageData =
          GetReceiptsMessage.create(Arrays.asList(gen.hash(), blockHash, gen.hash()));

      // Define handler to validate response
      final PeerSendHandler onSend =
          (cap, message, conn) -> {
            if (message.getCode() == EthPV62.STATUS) {
              // Ignore status message
              return;
            }
            assertThat(message.getCode()).isEqualTo(EthPV63.RECEIPTS);
            final ReceiptsMessage receiptsMessage = ReceiptsMessage.readFrom(message);
            final List<List<TransactionReceipt>> receipts =
                Lists.newArrayList(receiptsMessage.receipts());
            assertThat(receipts.size()).isEqualTo(1);
            assertThat(expectedReceipts).isEqualTo(receipts.get(0));
            message.release();
            done.complete(null);
          };

      // Run test
      final PeerConnection peer = setupPeer(ethManager, onSend);
      ethManager.processMessage(EthProtocol.ETH63, new DefaultMessage(peer, messageData));
      done.get();
    }
  }

  @Test
  public void newBlockMinedSendsNewBlockMessageToAllPeers() {
    final EthProtocolManager ethManager = new EthProtocolManager(blockchain, 1, true, 1);

    // Define handler to validate response
    final PeerSendHandler onSend = mock(PeerSendHandler.class);
    final List<PeerConnection> peers = Lists.newArrayList();

    final int PEER_COUNT = 5;
    for (int i = 0; i < PEER_COUNT; i++) {
      peers.add(setupPeer(ethManager, onSend));
    }

    final Hash chainHeadHash = blockchain.getChainHeadHash();
    final Block minedBlock =
        new Block(
            blockchain.getBlockHeader(chainHeadHash).get(),
            blockchain.getBlockBody(chainHeadHash).get());

    final UInt256 expectedTotalDifficulty = blockchain.getChainHead().getTotalDifficulty();

    reset(onSend);

    ethManager.blockMined(minedBlock);

    final ArgumentCaptor<NewBlockMessage> messageSentCaptor =
        ArgumentCaptor.forClass(NewBlockMessage.class);
    final ArgumentCaptor<PeerConnection> receivingPeerCaptor =
        ArgumentCaptor.forClass(PeerConnection.class);
    final ArgumentCaptor<Capability> capabilityCaptor = ArgumentCaptor.forClass(Capability.class);

    verify(onSend, times(PEER_COUNT))
        .exec(
            capabilityCaptor.capture(), messageSentCaptor.capture(), receivingPeerCaptor.capture());

    // assert that all entries in capability param were Eth63
    assertThat(capabilityCaptor.getAllValues().stream().distinct().collect(Collectors.toList()))
        .isEqualTo(Collections.singletonList(EthProtocol.ETH63));

    // assert that all messages transmitted contain the expected block & total difficulty.
    final ProtocolSchedule<Void> protocolSchdeule = MainnetProtocolSchedule.create();
    for (final NewBlockMessage msg : messageSentCaptor.getAllValues()) {
      assertThat(msg.block(protocolSchdeule)).isEqualTo(minedBlock);
      assertThat(msg.totalDifficulty(protocolSchdeule)).isEqualTo(expectedTotalDifficulty);
      msg.release();
    }

    assertThat(receivingPeerCaptor.getAllValues().containsAll(peers)).isTrue();
  }

  @Test
  public void shouldSuccessfullyRespondToGetHeadersRequestLessThanZero()
      throws ExecutionException, InterruptedException {
    final Block genesisBlock = gen.genesisBlock();
    final MutableBlockchain blockchain = InMemoryTestFixture.createInMemoryBlockchain(genesisBlock);

    final BlockDataGenerator.BlockOptions options =
        new BlockDataGenerator.BlockOptions()
            .setBlockNumber(1L)
            .setParentHash(blockchain.getBlockHashByNumber(0L).get());
    final Block block = gen.block(options);
    final List<TransactionReceipt> receipts = gen.receipts(block);
    blockchain.appendBlock(block, receipts);

    final CompletableFuture<Void> done = new CompletableFuture<>();
    try (final EthProtocolManager ethManager = new EthProtocolManager(blockchain, 1, true, 1)) {
      final long startBlock = 1L;
      final int requestedBlockCount = 13;
      final int receivedBlockCount = 2;
      final MessageData messageData =
          GetBlockHeadersMessage.create(startBlock, requestedBlockCount, 0, true);
      final MockPeerConnection.PeerSendHandler onSend =
          (cap, message, conn) -> {
            if (message.getCode() == EthPV62.STATUS) {
              // Ignore status message
              return;
            }
            assertThat(message.getCode()).isEqualTo(EthPV62.BLOCK_HEADERS);
            final BlockHeadersMessage headersMsg = BlockHeadersMessage.readFrom(message);
            final List<BlockHeader> headers =
                Lists.newArrayList(headersMsg.getHeaders(protocolSchedule));
            assertThat(headers.size()).isEqualTo(receivedBlockCount);
            for (int i = 0; i < receivedBlockCount; i++) {
              assertThat(headers.get(i).getNumber()).isEqualTo(receivedBlockCount - 1 - i);
            }
            message.release();
            done.complete(null);
          };

      final Set<Capability> caps = new HashSet<>(Arrays.asList(EthProtocol.ETH63));
      final MockPeerConnection peer = new MockPeerConnection(caps, onSend);
      ethManager.handleNewConnection(peer);
      final StatusMessage statusMessage =
          StatusMessage.create(
              EthProtocol.EthVersion.V63,
              1,
              blockchain.getChainHead().getTotalDifficulty(),
              blockchain.getChainHeadHash(),
              blockchain.getBlockHeader(BlockHeader.GENESIS_BLOCK_NUMBER).get().getHash());

      ethManager.processMessage(EthProtocol.ETH63, new DefaultMessage(peer, statusMessage));
      ethManager.processMessage(EthProtocol.ETH63, new DefaultMessage(peer, messageData));
      done.get();
    }
  }
}
