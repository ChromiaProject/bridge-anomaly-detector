package net.postchain.eif.bad

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import net.postchain.chain0.common.init.initOperation
import net.postchain.chain0.common.operations.addNodeToClusterOperation
import net.postchain.chain0.common.operations.registerNodeWithUnitsOperation
import net.postchain.chain0.common.operations.updateNodeWithUnitsOperation
import net.postchain.chain0.common.queries.getBlockchains
import net.postchain.chain0.common.queries.getNodeData
import net.postchain.chain0.common.queries.getSummary
import net.postchain.chain0.economy_chain.ClusterCreationStatus
import net.postchain.chain0.economy_chain.TagData
import net.postchain.chain0.economy_chain.TicketState
import net.postchain.chain0.economy_chain.addBridgeLeaseOperation
import net.postchain.chain0.economy_chain.createClusterOperation
import net.postchain.chain0.economy_chain.createContainerOperation
import net.postchain.chain0.economy_chain.createTagOperation
import net.postchain.chain0.economy_chain.getBalance
import net.postchain.chain0.economy_chain.getClusterCreationStatus
import net.postchain.chain0.economy_chain.getCreateContainerTicketByTransaction
import net.postchain.chain0.economy_chain.getLeasesByAccount
import net.postchain.chain0.economy_chain.getTagByName
import net.postchain.chain0.economy_chain.initOperation
import net.postchain.chain0.economy_chain.removeBridgeLeaseOperation
import net.postchain.chain0.economy_chain_in_directory_chain.initEconomyChainOperation
import net.postchain.chain0.lib.ft4.core.accounts.AuthDescriptor
import net.postchain.chain0.lib.ft4.core.accounts.AuthType
import net.postchain.chain0.lib.ft4.external.assets.getAssetBalance
import net.postchain.chain0.lib.ft4.external.assets.getAssetsByName
import net.postchain.chain0.lib.hbridge.bridgeFtAssetToEvmOperation
import net.postchain.chain0.lib.hbridge.getErc20WithdrawalByTx
import net.postchain.chain0.model.ProviderInfo
import net.postchain.chain0.model.ProviderTier
import net.postchain.chain0.proposal.voting.createVoterSetOperation
import net.postchain.chain0.proposal_blockchain.proposeBlockchainOperation
import net.postchain.chain0.proposal_provider.proposeProviderIsSystemOperation
import net.postchain.chain0.proposal_provider.proposeProvidersOperation
import net.postchain.cm.cm_api.ClusterManagementImpl
import net.postchain.common.BlockchainRid
import net.postchain.common.hexStringToByteArray
import net.postchain.common.toHex
import net.postchain.dapp.postTransactionUntilConfirmed
import net.postchain.eif.EventMerkleProof
import net.postchain.eif.bad.config.AnomalyConfig
import net.postchain.eif.bad.config.AppConfig
import net.postchain.eif.bad.config.EvmClientConfig
import net.postchain.eif.bad.config.EvmConfig
import net.postchain.eif.bad.config.LogProcessorConfig
import net.postchain.eif.bad.evm.TokenBridgeClientsManager
import net.postchain.eif.contracts.TestToken
import net.postchain.eif.contracts.TokenBridge
import net.postchain.eif.contracts.Validator
import net.postchain.eif.getEthereumAddress
import net.postchain.gtv.GtvArray
import net.postchain.gtv.GtvEncoder
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvNull
import net.postchain.gtv.gtvml.GtvMLParser
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtv.mapper.toObject
import net.postchain.images.directory1.FTAuthenticator
import net.postchain.images.directory1.awaitQueryResult
import net.postchain.images.directory1.awaitUntilAsserted
import net.postchain.images.directory1.createAccount
import org.awaitility.Awaitility
import org.awaitility.Duration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junitpioneer.jupiter.DisableIfTestFails
import org.testcontainers.junit.jupiter.Testcontainers
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.crypto.Credentials
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.tx.Contract
import org.web3j.tx.Transfer
import org.web3j.utils.Convert
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.TimeUnit

/**
 * The way we fake anomalies in this test is by registering an incorrect blockchain RID as the bridge chain
 * instead of the actual bridge chain.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@DisableIfTestFails
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnomalyDetectorIncorrectWithdrawIT : AnomalyDetectorTest("bad-incorrect-withdraw") {

    init {
        // Nodes
        chain0Config = this::class.java.getResource("/directory1deployment/mainnet.xml")!!.readText()
        node1 = postchainServer("node1",
                provider1KeyPair,
                "/net/postchain/images/directory1/config-no-subnodes")
                .withEnv("EVM_${networkId}_READ_OFFSET", "0")
                .withEifEnv()
        node2 = postchainServer("node2",
                provider2KeyPair,
                "/net/postchain/images/directory1/config-no-subnodes")
                .withEnv("POSTCHAIN_GENESIS_PUBKEY", node1.pubkey.hex())
                .withEnv("POSTCHAIN_GENESIS_HOST", node1.nodeHost)
                .withEnv("POSTCHAIN_GENESIS_PORT", node1.nodePort.toString())
                .withEnv("EVM_${networkId}_READ_OFFSET", "0")
                .withEifEnv()
        node3 = postchainServer("node3",
                provider3KeyPair,
                "/net/postchain/images/directory1/config-no-subnodes")
                .withEnv("POSTCHAIN_GENESIS_PUBKEY", node1.pubkey.hex())
                .withEnv("POSTCHAIN_GENESIS_HOST", node1.nodeHost)
                .withEnv("POSTCHAIN_GENESIS_PORT", node1.nodePort.toString())
                .withEnv("EVM_${networkId}_READ_OFFSET", "0")
                .withEifEnv()

        startNodesAndChain0()
    }

    @AfterAll
    fun tearDownAfterAll() {
        logger.info { "tearDownAfterAll" }
        super.tearDown() // Calling @AfterEach EvmTestBase.tearDown()
        anomalyDetectorsManager.stop()
        restApi.close()
    }

    @Test
    @Order(10)
    fun `prepare - deploy contracts`() {
        logger.info { "deploy contracts" }

        // Deploy validator contract
        val encodedConstructor = FunctionEncoder.encodeConstructor(listOf(DynamicArray(Address::class.java,
                Address(getEthereumAddress(node1.pubkey.data).toHex()),
                Address(getEthereumAddress(node3.pubkey.data).toHex())
        )))
        validator = Contract.deployRemoteCall(Validator::class.java, web3j, transactionManager, gasProvider, validatorBinary, encodedConstructor).send()

        // Deploy token bridge contract
        bridge = Contract.deployRemoteCall(TokenBridge::class.java, web3j, transactionManager, gasProvider, tokenBridgeBinary, "").send().apply {
            initialize(Address(validator.contractAddress), Uint256(2)).send()
        }
        bridgeAddress = bridge.contractAddress.substring(2).hexStringToByteArray()

        // Deploy a test token that we mint and then approve transfer of coins to chrL2 contract
        testToken = Contract.deployRemoteCall(TestToken::class.java, web3j, transactionManager, gasProvider, testTokenBinary, "").send().apply {
            mint(Address(transactionManager.fromAddress), Uint256(initialMint)).send()
            approve(Address(bridge.contractAddress), Uint256(initialMint)).send()
        }
        testTokenAddress = testToken.contractAddress.substring(2).hexStringToByteArray()
        // Allow token
        bridge.allowToken(Address(testToken.contractAddress)).send()
    }

    @Test
    @Order(20)
    fun `Setup the network`() {
        logger.info("Setup the network")
        getDb(node1).awaitBlockHeight(0)
        with(node1.c0) {
            val clusterAnchoringGtvConfig = GtvMLParser.parseGtvML(this::class.java.getResource("/directory1deployment/cluster_anchoring.xml")!!.readText())
            val systemAnchoringGtvConfig = GtvMLParser.parseGtvML(this::class.java.getResource("/directory1deployment/system_anchoring.xml")!!.readText())
            transactionBuilder()
                    .initOperation(GtvEncoder.encodeGtv(systemAnchoringGtvConfig), GtvEncoder.encodeGtv(clusterAnchoringGtvConfig))
                    .postTransactionUntilConfirmed("init")
            assertThat(getSummary().providers).isEqualTo(1L)
            assertThat(getNodeData(node1.nodeKeyPair.pubKey).active).isTrue()
        }
        assertAnchoringChainProperties()

        // Adding provider 2 & 3
        logger.info("Add provider 2 & 3. Promote 2 to system provider")
        val newProviders = listOf(
                ProviderInfo(node2.provider.pubKey.wData, "provider2", "http://provider2.com"),
                ProviderInfo(node3.provider.pubKey.wData, "provider3", "http://provider3.com")
        )
        node1.client(chain0Brid, listOf(node1.provider, node2.provider, node3.provider)).transactionBuilder().addNop()
                .proposeProvidersOperation(node1.providerPubkey, newProviders, ProviderTier.NODE_PROVIDER, system = false, active = true, description = "")
                .proposeProviderIsSystemOperation(node1.providerPubkey, node2.providerPubkey, true, "")
                .registerNodeWithUnitsOperation(node2.providerPubkey, node2.pubkey.data, node2.nodeHost, node2.nodePort.toLong(), node2.nodeApiPath(), listOf(systemCluster), 2)
                .createVoterSetOperation(node3.providerPubkey, PROVIDER_1_AND_3_VS, 0, listOf(node1.providerPubkey, node3.providerPubkey), null)
                .postTransactionUntilConfirmed("System provider 2 & 3 registered and node added")

        val providers = node1.c0.query("get_all_providers", gtv(mapOf()))
        assertThat(providers.asArray().size).isEqualTo(3)
    }

    @Test
    @Order(25)
    fun `Setup economy chain with container lease`() {
        testLogger.info("Deploying Economy Chain")

        val economyChainGtvConfig = GtvMLParser.parseGtvML(this::class.java.getResource("/directory1deployment/economy_chain.xml")!!
                .readText()
                .replace(
                        "<string>$EIF_EC_EVENT_RECEIVER_BRID_PLACEHOLDER</string>",
                        "<bytea>${BlockchainRid.ZERO_RID.toHex()}</bytea>"
                )
        )

        node1.c0.transactionBuilder()
                .initEconomyChainOperation(node1.providerPubkey, GtvEncoder.encodeGtv(economyChainGtvConfig))
                .postTransactionUntilConfirmed("Add $EC_CHAIN_NAME")

        awaitUntilAsserted {
            val ecRid = node1.c0.getBlockchains(true).firstOrNull { it.name == EC_CHAIN_NAME }?.rid
            assertThat(ecRid).isNotNull()
            ecBrid = BlockchainRid(ecRid!!)
        }

        testLogger.info { "$EC_CHAIN_NAME deployed: $ecBrid" }

        node1.ec.transactionBuilder()
                .initOperation()
                .postTransactionUntilConfirmed("Init $EC_CHAIN_NAME")
        testLogger.info { "$EC_CHAIN_NAME initialized" }

        aliceECAuthDescriptor = createAccount(node1, accountCreatorKeyPair, ecBrid, aliceKeyPair, "Alice")
        linkAccount(aliceECAuthDescriptor, aliceEvmCredentials, ecBrid)

        node1.client(ecBrid, listOf(aliceKeyPair)).getBalance(aliceECAuthDescriptor.accountId)

        testLogger.info("Adding tag")
        with(node1.ec) {
            transactionBuilder()
                    .createTagOperation(node1.providerPubkey, APP_CLUSTER_TAG, 1, 1, 0)
                    .postTransactionUntilConfirmed("$APP_CLUSTER_TAG tag created")

            makeVoteOnLatestProposal(node2)

            assertThat(getTagByName(APP_CLUSTER_TAG))
                    .isEqualTo(TagData(APP_CLUSTER_TAG, 1, 1, 0))
        }

        testLogger.info("Adding cluster")

        with(node1.ec) {
            transactionBuilder()
                    .createClusterOperation(
                            node1.providerPubkey, APP_CLUSTER, "SYSTEM_P", PROVIDER_1_AND_3_VS, 1, 0, APP_CLUSTER_TAG,
                            containerUnitCpu = 1,
                            containerUnitRam = 1,
                            containerUnitIoRead = 1,
                            containerUnitIoWrite = 1,
                            containerUnitStorage = 17000,
                            systemContainerUnits = 1,
                            maxNodes = Long.MAX_VALUE
                    )
                    .postTransactionUntilConfirmed("$APP_CLUSTER created")

            // Approve APP_CLUSTER
            makeVoteOnLatestProposal(node2)

            awaitQueryResult {
                assertThat(getClusterCreationStatus(APP_CLUSTER))
                        .isEqualTo(ClusterCreationStatus.SUCCESS)
            }
        }

        node1.client(chain0Brid, listOf(node1.provider, node3.provider)).transactionBuilder().addNop()
                .updateNodeWithUnitsOperation(node1.providerPubkey, node1.pubkey.data, null, null, null, 3)
                .addNodeToClusterOperation(node1.providerPubkey, node1.pubkey.data, APP_CLUSTER)
                .registerNodeWithUnitsOperation(node3.providerPubkey, node3.pubkey.data, node3.nodeHost, node3.nodePort.toLong(), node3.nodeApiPath(), listOf(APP_CLUSTER), 1)
                .postTransactionUntilConfirmed("Add node 1 and 3 to dapp cluster")

        testLogger.info("Create container")
        aliceECAuthDescriptor.verifyOperationAuthFlags("create_container")
        val tcRid = aliceECAuthDescriptor.transactionBuilder()
                .createContainerOperation(
                        node1.provider.pubKey.data, 1, 1, 0, APP_CLUSTER, true)
                .postTransactionUntilConfirmed("Create Container")
                .txRid

        awaitUntilAsserted {
            val ticket = aliceECAuthDescriptor.client.getCreateContainerTicketByTransaction(tcRid.rid.hexStringToByteArray())
            assertThat(ticket).isNotNull()
            assertThat(ticket!!.state).isEqualTo(TicketState.SUCCESS)
        }

        val leaseDataList = aliceECAuthDescriptor.client.getLeasesByAccount(aliceECAuthDescriptor.accountId)
        assertThat(leaseDataList.size).isEqualTo(1)
        val leaseData = leaseDataList[0]

        aliceContainerName = leaseData.containerName
    }

    @Test
    @Order(30)
    fun `prepare - start chains`() {
        logger.info { "start chains" }

        testLogger.info("Deploying EIF Event Receiver Chain")
        val configGtv = GtvMLParser.parseGtvML(
                this::class.java.getResource("/directory1deployment/$EIF_EVENT_RECEIVER_CHAIN_NAME.xml")!!
                        .readText()
                        .replace(EIF_EVENT_RECEIVER_CONTRACT_PLACEHOLDER, bridge.contractAddress)
        )

        node1.c0.transactionBuilder().addNop()
                .proposeBlockchainOperation(node1.providerPubkey, GtvEncoder.encodeGtv(configGtv), EIF_EVENT_RECEIVER_CHAIN_NAME, aliceContainerName, "")
                .postTransactionUntilConfirmed("Propose dapp $EIF_EVENT_RECEIVER_CHAIN_NAME")

        awaitUntilAsserted {
            val erRid = node1.c0.getBlockchains(true).firstOrNull { it.name == EIF_EVENT_RECEIVER_CHAIN_NAME }?.rid
            assertThat(erRid).isNotNull()
            eventReceiverBrid = BlockchainRid(erRid!!)
        }

        testLogger.info { "$EIF_EVENT_RECEIVER_CHAIN_NAME deployed: $eventReceiverBrid" }

        logger.info("Deploy EVM Token Bridge dapp")
        deployDapp(EVM_TOKEN_BRIDGE_CHAIN_NAME, aliceContainerName, assertSigners = arrayOf(node1, node3), icmfReceiver = eventReceiverBrid.data)
        deployDapp(FAKE_EVM_TOKEN_BRIDGE_CHAIN_NAME, aliceContainerName, assertSigners = arrayOf(node1, node3), icmfReceiver = BlockchainRid.ZERO_RID.data)

        awaitUntilAsserted {
            val brid = node1.c0.getBlockchains(true).firstOrNull { it.name == EVM_TOKEN_BRIDGE_CHAIN_NAME }?.rid
            assertThat(brid).isNotNull()
            tokenBridgeBrid = BlockchainRid(brid!!)
        }
        bridge.setBlockchainRid(Bytes32(tokenBridgeBrid.data)).send()
        awaitUntilAsserted {
            val brid = node1.c0.getBlockchains(true).firstOrNull { it.name == FAKE_EVM_TOKEN_BRIDGE_CHAIN_NAME }?.rid
            assertThat(brid).isNotNull()
            fakeTokenBridgeBrid = BlockchainRid(brid!!)
        }

        node1.client(tokenBridgeBrid).transactionBuilder()
                .addOperation("init", gtv(testTokenAddress), gtv(bridgeAddress))
                .postTransactionUntilConfirmed("Register bridge asset")

        logger.info { "$EVM_TOKEN_BRIDGE_CHAIN_NAME deployed: $tokenBridgeBrid" }
    }

    @Test
    @Order(40)
    fun `start anomaly detector`() {

        logger.info { "start anomaly detector" }

        // Funds for pausing
        Transfer(web3j, transactionManager).sendFunds(
                getEthereumAddress(node1.pubkey.data).toHex(),
                BigDecimal.valueOf(400), Convert.Unit.ETHER).send()

        appConfig = AppConfig(
                EvmClientConfig(),

                // Evm rpc
                mapOf(networkId to EvmConfig(
                        listOf(evmContainer.getExternalGethUrl()),
                        Credentials.create(node1.appConfig.privKey),
                        LogProcessorConfig(2, 10, 50)
                )),

                // Node and postchain
                node3.apiPath(),
                node3.pubkey,
                BRIDGE_CHAIN_REFRESH_INTERVAL_MS,
                blockchainSyncMargin,
                bypassBlockchainSyncCheck = false,

                // Timeouts disabled for tests to make it execute right away
                AnomalyConfig(
                        missingHeightRetryDelay = 0,
                        pauseDelay = 0,
                        0,
                        true,
                )
        )

        tokenBridgeClientsManager = TokenBridgeClientsManager(appConfig.evmConfig)
        anomalyDetectorsManager = AnomalyDetectorsManager(appConfig, tokenBridgeClientsManager) {
            AnomalyContainerClusterManagement(
                    ClusterManagementImpl(it),
                    listOf(node1.apiPath())
            )
        }
        anomalyDetectorsManager.start()
    }

    @Test
    @Order(50)
    fun `start anomaly detector api`() {

        logger.info { "start anomaly detector api" }

        restApi = startRestApi(appConfig.restApiConfig, anomalyDetectorsManager)!!

        val restStatus = restStatus(appConfig)
        assertThat(restStatus.size).isEqualTo(0)
    }

    @Test
    @Order(60)
    fun `add chain to monitor`() {

        logger.info { "add bridge lease" }

        Awaitility.await()
                .atMost(Duration.TEN_SECONDS)
                .untilAsserted {
                    assertThat(anomalyDetectorsManager.getAnomalyDetectors().size).isEqualTo(0)
                }

        aliceECAuthDescriptor.transactionBuilder()
                .addBridgeLeaseOperation(aliceContainerName, fakeTokenBridgeBrid, networkId, validator.contractAddress, bridge.contractAddress, true)
                .postTransactionUntilConfirmed("added bridge lease")

        Awaitility.await()
                .atMost(Duration(BRIDGE_CHAIN_REFRESH_INTERVAL_MS * 2, TimeUnit.SECONDS))
                .untilAsserted {
                    assertThat(anomalyDetectorsManager.getAnomalyDetectors().size).isEqualTo(1)
                }

        val restStatus = restStatus(appConfig)
        assertThat(restStatus.size).isEqualTo(1)
        assertThat(restStatus[0].blockchainRid).isEqualTo(fakeTokenBridgeBrid.toHex())
    }

    @Test
    @Order(70)
    fun `prepare - register ft accounts`() {
        logger.info { "Register FT accounts" }

        val aliceAuth = AuthDescriptor(
                AuthType.S,
                listOf(
                        GtvArray(arrayOf(gtv("A"), gtv("T"))),
                        gtv(alicePubkey)
                ),
                GtvNull
        )

        node1.client(tokenBridgeBrid, signers = listOf(ft4AdminKeyPair)).transactionBuilder()
                .addOperation("ft4.admin.register_account", GtvObjectMapper.toGtvArray(aliceAuth))
                .postTransactionUntilConfirmed("Register Alice account")

        aliceBridgeAuthDescriptor = FTAuthenticator(aliceKeyPair, node1.client(tokenBridgeBrid, listOf(aliceKeyPair)))

        linkAccount(aliceBridgeAuthDescriptor, aliceEvmCredentials, tokenBridgeBrid)

        assetId = node1.client(tokenBridgeBrid).getAssetsByName("tCHR", null, null)
                .data[0]["id"]?.asByteArray()!!
    }

    @Test
    @Order(80)
    fun `prepare - deposit token on evm`() {

        logger.info { "deposit token on evm" }

        // Deposit token on EVM smart contract to bridge it to postchain
        (1..depositNum).forEach { i ->
            bridge.deposit(Address(testToken.contractAddress), Uint256(depositAmount)).send()
        }
        // check the balance on EVM
        userBalance = testToken.balanceOf(Address(aliceEvmAddressStr)).send()
        assertEquals(userBalance.value, initialMint - totalDepositedAmount)

        // check the asset balance on Chromia
        awaitQueryResult {
            val balance = node1.client(tokenBridgeBrid).getAssetBalance(aliceBridgeAuthDescriptor.accountId, assetId)
            assertThat(balance?.amount).isEqualTo(totalDepositedAmount)
        }
    }

    @Test
    @Order(90)
    fun `verify anomaly detected`() {

        logger.info { "verify anomaly detected" }
        withdrawRequest()

        Awaitility.await()
                .atMost(Duration.ONE_MINUTE)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted {

                    logger.info { "Waiting for anomaly detector to detect anomaly and pause bridge..." }

                    val anomalyDetectors = anomalyDetectorsManager.getAnomalyDetectors()
                    assertThat(anomalyDetectors.size).isEqualTo(1)
                    val anomalyDetector = anomalyDetectors.values.first()

                    assertThat(anomalyDetector.logsProcessed).isEqualTo(1L)
                    assertThat(anomalyDetector.logsVerified).isEqualTo(0L)
                    assertThat(anomalyDetector.anomalyDetectorStatus).isEqualTo(AnomalyDetectorStatus.PAUSED)
                    assertThat(bridge.paused().send().value).isTrue()
                }
    }

    @Test
    @Order(100)
    fun `remove fake chain bridge from monitor`() {

        logger.info { "remove fake chain bridge from monitor" }

        Awaitility.await()
                .atMost(Duration.TEN_SECONDS)
                .untilAsserted {
                    assertThat(anomalyDetectorsManager.getAnomalyDetectors().size).isEqualTo(1)
                }

        aliceECAuthDescriptor.transactionBuilder()
                .removeBridgeLeaseOperation(fakeTokenBridgeBrid, networkId)
                .postTransactionUntilConfirmed("removed fake chain")

        Awaitility.await()
                .atMost(Duration(BRIDGE_CHAIN_REFRESH_INTERVAL_MS * 2, TimeUnit.SECONDS))
                .untilAsserted {
                    assertThat(anomalyDetectorsManager.getAnomalyDetectors().size).isEqualTo(0)

                    val restStatus = restStatus(appConfig)
                    assertThat(restStatus.size).isEqualTo(0)

                    // Make sure the client is shutdown
                    assertThat(tokenBridgeClientsManager.hasNetworkClient(networkId)).isFalse()
                }
    }

    private fun withdrawRequest() {
        // Bridge some ft token to evm
        withdrawAmount = BigInteger("1", 16)

        val withdrawTxRid = aliceBridgeAuthDescriptor.transactionBuilder()
                .bridgeFtAssetToEvmOperation(
                        assetId,
                        withdrawAmount,
                        networkId,
                        aliceEvmAddress,
                        bridgeAddress
                )
                .postTransactionUntilConfirmed("withdrawOnPostchain")
                .txRid
        totalDepositedAmount -= withdrawAmount

        val balance = node1.client(tokenBridgeBrid).getAssetBalance(aliceBridgeAuthDescriptor.accountId, assetId)!!
        assertEquals(totalDepositedAmount, balance.amount)

        // Get and verify the withdrawal data
        val withdrawInfo = node1.client(tokenBridgeBrid)
                .getErc20WithdrawalByTx(withdrawTxRid.rid.hexStringToByteArray(), 1)!!
        val eventProof = node1.client(tokenBridgeBrid).query("get_event_merkle_proof",
                gtv("eventHash" to gtv(withdrawInfo.eventHash.toHex()))
        ).toObject<EventMerkleProof>()

        logger.info { "\trequesting withdrawal using the confirmation proof" }
        val withdrawRequestReceipt = bridge.withdrawRequest(
                eventProof.web3EventData(),
                eventProof.web3EventProof(),
                eventProof.web3BlockHeader(),
                eventProof.web3Signatures(),
                eventProof.web3Signers(),
                eventProof.web3ExtraProofData()
        ).send()

        // wait some seconds to allow evm node to mine some new blocks
        // that mature enough to withdraw requesting fund
        Awaitility.await().atMost(Duration.TEN_SECONDS).until {
            val block = web3j.ethGetBlockByNumber(DefaultBlockParameter.valueOf(withdrawRequestReceipt.blockNumber.add(blockchainSyncMargin.toBigInteger())), false).send()
            block.block != null
        }
    }
}
