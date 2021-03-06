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
package tech.pegasys.pantheon.consensus.ibftlegacy.blockcreation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static tech.pegasys.pantheon.ethereum.core.InMemoryTestFixture.createInMemoryWorldStateArchive;

import tech.pegasys.pantheon.config.GenesisConfigFile;
import tech.pegasys.pantheon.consensus.common.VoteProposer;
import tech.pegasys.pantheon.consensus.common.VoteTally;
import tech.pegasys.pantheon.consensus.ibft.IbftContext;
import tech.pegasys.pantheon.consensus.ibftlegacy.IbftBlockHeaderValidationRulesetFactory;
import tech.pegasys.pantheon.consensus.ibftlegacy.IbftExtraData;
import tech.pegasys.pantheon.consensus.ibftlegacy.IbftProtocolSchedule;
import tech.pegasys.pantheon.crypto.SECP256K1.KeyPair;
import tech.pegasys.pantheon.ethereum.ProtocolContext;
import tech.pegasys.pantheon.ethereum.chain.MutableBlockchain;
import tech.pegasys.pantheon.ethereum.core.Address;
import tech.pegasys.pantheon.ethereum.core.Block;
import tech.pegasys.pantheon.ethereum.core.BlockHeader;
import tech.pegasys.pantheon.ethereum.core.BlockHeaderTestFixture;
import tech.pegasys.pantheon.ethereum.core.Hash;
import tech.pegasys.pantheon.ethereum.core.PendingTransactions;
import tech.pegasys.pantheon.ethereum.core.Wei;
import tech.pegasys.pantheon.ethereum.mainnet.BlockHeaderValidator;
import tech.pegasys.pantheon.ethereum.mainnet.HeaderValidationMode;
import tech.pegasys.pantheon.ethereum.mainnet.ProtocolSchedule;
import tech.pegasys.pantheon.util.bytes.BytesValue;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.google.common.collect.Lists;
import org.junit.Test;

public class IbftBlockCreatorTest {

  @Test
  public void headerProducedPassesValidationRules() {
    // Construct a parent block.
    final BlockHeaderTestFixture blockHeaderBuilder = new BlockHeaderTestFixture();
    blockHeaderBuilder.gasLimit(5000); // required to pass validation rule checks.
    final BlockHeader parentHeader = blockHeaderBuilder.buildHeader();
    final Optional<BlockHeader> optionalHeader = Optional.of(parentHeader);

    // Construct a block chain and world state
    final MutableBlockchain blockchain = mock(MutableBlockchain.class);
    when(blockchain.getChainHeadHash()).thenReturn(parentHeader.getHash());
    when(blockchain.getBlockHeader(any())).thenReturn(optionalHeader);

    final KeyPair nodeKeys = KeyPair.generate();
    // Add the local node as a validator (can't propose a block if node is not a validator).
    final Address localAddr = Address.extract(Hash.hash(nodeKeys.getPublicKey().getEncodedBytes()));
    final List<Address> initialValidatorList =
        Arrays.asList(
            Address.fromHexString(String.format("%020d", 1)),
            Address.fromHexString(String.format("%020d", 2)),
            Address.fromHexString(String.format("%020d", 3)),
            Address.fromHexString(String.format("%020d", 4)),
            localAddr);

    final VoteTally voteTally = new VoteTally(initialValidatorList);

    final ProtocolSchedule<IbftContext> protocolSchedule =
        IbftProtocolSchedule.create(
            GenesisConfigFile.fromConfig("{\"config\": {\"spuriousDragonBlock\":0}}")
                .getConfigOptions());
    final ProtocolContext<IbftContext> protContext =
        new ProtocolContext<>(
            blockchain,
            createInMemoryWorldStateArchive(),
            new IbftContext(voteTally, new VoteProposer()));

    final IbftBlockCreator blockCreator =
        new IbftBlockCreator(
            Address.fromHexString(String.format("%020d", 0)),
            parent ->
                new IbftExtraData(
                        BytesValue.wrap(new byte[32]),
                        Lists.newArrayList(),
                        null,
                        initialValidatorList)
                    .encode(),
            new PendingTransactions(1),
            protContext,
            protocolSchedule,
            parentGasLimit -> parentGasLimit,
            nodeKeys,
            Wei.ZERO,
            parentHeader);

    final Block block = blockCreator.createBlock(Instant.now().getEpochSecond());

    final BlockHeaderValidator<IbftContext> rules =
        IbftBlockHeaderValidationRulesetFactory.ibftProposedBlockValidator(0);

    final boolean validationResult =
        rules.validateHeader(
            block.getHeader(), parentHeader, protContext, HeaderValidationMode.FULL);

    assertThat(validationResult).isTrue();
  }
}
