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

import tech.pegasys.pantheon.ethereum.chain.Blockchain;
import tech.pegasys.pantheon.ethereum.core.BlockBody;
import tech.pegasys.pantheon.ethereum.core.BlockHeader;
import tech.pegasys.pantheon.ethereum.core.Hash;
import tech.pegasys.pantheon.ethereum.core.TransactionReceipt;
import tech.pegasys.pantheon.ethereum.eth.messages.BlockBodiesMessage;
import tech.pegasys.pantheon.ethereum.eth.messages.BlockHeadersMessage;
import tech.pegasys.pantheon.ethereum.eth.messages.EthPV62;
import tech.pegasys.pantheon.ethereum.eth.messages.EthPV63;
import tech.pegasys.pantheon.ethereum.eth.messages.GetBlockBodiesMessage;
import tech.pegasys.pantheon.ethereum.eth.messages.GetBlockHeadersMessage;
import tech.pegasys.pantheon.ethereum.eth.messages.GetNodeDataMessage;
import tech.pegasys.pantheon.ethereum.eth.messages.GetReceiptsMessage;
import tech.pegasys.pantheon.ethereum.eth.messages.NodeDataMessage;
import tech.pegasys.pantheon.ethereum.eth.messages.ReceiptsMessage;
import tech.pegasys.pantheon.ethereum.p2p.api.MessageData;
import tech.pegasys.pantheon.ethereum.p2p.api.PeerConnection.PeerNotConnected;
import tech.pegasys.pantheon.ethereum.p2p.wire.messages.DisconnectMessage.DisconnectReason;
import tech.pegasys.pantheon.ethereum.rlp.RLPException;
import tech.pegasys.pantheon.util.bytes.BytesValue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.google.common.collect.Lists;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

class EthServer {
  private static final Logger LOG = LogManager.getLogger();

  private final Blockchain blockchain;
  private final EthMessages ethMessages;
  private final int requestLimit;

  EthServer(final Blockchain blockchain, final EthMessages ethMessages, final int requestLimit) {
    this.blockchain = blockchain;
    this.ethMessages = ethMessages;
    this.requestLimit = requestLimit;
    this.setupListeners();
  }

  private void setupListeners() {
    ethMessages.subscribe(EthPV62.GET_BLOCK_HEADERS, this::handleGetBlockHeaders);
    ethMessages.subscribe(EthPV62.GET_BLOCK_BODIES, this::handleGetBlockBodies);
    ethMessages.subscribe(EthPV63.GET_RECEIPTS, this::handleGetReceipts);
    ethMessages.subscribe(EthPV63.GET_NODE_DATA, this::handleGetNodeData);
  }

  private void handleGetBlockHeaders(final EthMessage message) {
    LOG.trace("Responding to GET_BLOCK_HEADERS request");
    try {
      final MessageData response =
          constructGetHeadersResponse(blockchain, message.getData(), requestLimit);
      message.getPeer().send(response);
    } catch (final RLPException e) {
      message.getPeer().disconnect(DisconnectReason.BREACH_OF_PROTOCOL);
    } catch (final PeerNotConnected peerNotConnected) {
      // Peer disconnected before we could respond - nothing to do
    }
  }

  private void handleGetBlockBodies(final EthMessage message) {
    LOG.trace("Responding to GET_BLOCK_BODIES request");
    try {
      final MessageData response =
          constructGetBodiesResponse(blockchain, message.getData(), requestLimit);
      message.getPeer().send(response);
    } catch (final RLPException e) {
      message.getPeer().disconnect(DisconnectReason.BREACH_OF_PROTOCOL);
    } catch (final PeerNotConnected peerNotConnected) {
      // Peer disconnected before we could respond - nothing to do
    }
  }

  private void handleGetReceipts(final EthMessage message) {
    LOG.trace("Responding to GET_RECEIPTS request");
    try {
      final MessageData response =
          constructGetReceiptsResponse(blockchain, message.getData(), requestLimit);
      message.getPeer().send(response);
    } catch (final RLPException e) {
      message.getPeer().disconnect(DisconnectReason.BREACH_OF_PROTOCOL);
    } catch (final PeerNotConnected peerNotConnected) {
      // Peer disconnected before we could respond - nothing to do
    }
  }

  private void handleGetNodeData(final EthMessage message) {
    LOG.trace("Responding to GET_NODE_DATA request");
    try {
      final MessageData response = constructGetNodeDataResponse(message.getData(), requestLimit);
      message.getPeer().send(response);
    } catch (final RLPException e) {
      message.getPeer().disconnect(DisconnectReason.BREACH_OF_PROTOCOL);
    } catch (final PeerNotConnected peerNotConnected) {
      // Peer disconnected before we could respond - nothing to do
    }
  }

  static MessageData constructGetHeadersResponse(
      final Blockchain blockchain, final MessageData message, final int requestLimit) {
    final GetBlockHeadersMessage getHeaders = GetBlockHeadersMessage.readFrom(message);
    try {
      final Optional<Hash> hash = getHeaders.hash();
      final int skip = getHeaders.skip();
      final int maxHeaders = Math.min(requestLimit, getHeaders.maxHeaders());
      final boolean reversed = getHeaders.reverse();
      final BlockHeader firstHeader;
      if (hash.isPresent()) {
        final Hash startHash = hash.get();
        firstHeader = blockchain.getBlockHeader(startHash).orElse(null);
      } else {
        final long firstNumber = getHeaders.blockNumber().getAsLong();
        firstHeader = blockchain.getBlockHeader(firstNumber).orElse(null);
      }
      final Collection<BlockHeader> resp;
      if (firstHeader == null) {
        resp = Collections.emptyList();
      } else {
        resp = Lists.newArrayList(firstHeader);
        final long numberDelta = reversed ? -(skip + 1) : (skip + 1);
        for (int i = 1; i < maxHeaders; i++) {
          final long blockNumber = firstHeader.getNumber() + i * numberDelta;
          if (blockNumber < BlockHeader.GENESIS_BLOCK_NUMBER) {
            break;
          }
          final Optional<BlockHeader> maybeHeader = blockchain.getBlockHeader(blockNumber);
          if (maybeHeader.isPresent()) {
            resp.add(maybeHeader.get());
          } else {
            break;
          }
        }
      }
      return BlockHeadersMessage.create(resp);
    } finally {
      getHeaders.release();
    }
  }

  static MessageData constructGetBodiesResponse(
      final Blockchain blockchain, final MessageData message, final int requestLimit) {
    final GetBlockBodiesMessage getBlockBodiesMessage = GetBlockBodiesMessage.readFrom(message);
    try {
      final Iterable<Hash> hashes = getBlockBodiesMessage.hashes();

      final Collection<BlockBody> bodies = new ArrayList<>();
      int count = 0;
      for (final Hash hash : hashes) {
        if (count >= requestLimit) {
          break;
        }
        count++;
        final Optional<BlockBody> maybeBody = blockchain.getBlockBody(hash);
        if (!maybeBody.isPresent()) {
          continue;
        }
        bodies.add(maybeBody.get());
      }
      return BlockBodiesMessage.create(bodies);
    } finally {
      getBlockBodiesMessage.release();
    }
  }

  static MessageData constructGetReceiptsResponse(
      final Blockchain blockchain, final MessageData message, final int requestLimit) {
    final GetReceiptsMessage getReceipts = GetReceiptsMessage.readFrom(message);
    try {
      final Iterable<Hash> hashes = getReceipts.hashes();

      final List<List<TransactionReceipt>> receipts = new ArrayList<>();
      int count = 0;
      for (final Hash hash : hashes) {
        if (count >= requestLimit) {
          break;
        }
        count++;
        final Optional<List<TransactionReceipt>> maybeReceipts = blockchain.getTxReceipts(hash);
        if (!maybeReceipts.isPresent()) {
          continue;
        }
        receipts.add(maybeReceipts.get());
      }
      return ReceiptsMessage.create(receipts);
    } finally {
      getReceipts.release();
    }
  }

  static MessageData constructGetNodeDataResponse(
      final MessageData message, final int requestLimit) {
    final GetNodeDataMessage getNodeDataMessage = GetNodeDataMessage.readFrom(message);
    try {
      final Iterable<Hash> hashes = getNodeDataMessage.hashes();

      final List<BytesValue> nodeData = new ArrayList<>();
      int count = 0;
      for (final Hash hash : hashes) {
        if (count >= requestLimit) {
          break;
        }
        count++;
        // TODO: Lookup node data and add it to the list
      }
      return NodeDataMessage.create(nodeData);
    } finally {
      getNodeDataMessage.release();
    }
  }
}
