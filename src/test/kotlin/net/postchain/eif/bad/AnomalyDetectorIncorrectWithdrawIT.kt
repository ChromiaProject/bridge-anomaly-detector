package net.postchain.eif.bad

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import mu.KotlinLogging
import net.postchain.base.snapshot.SimpleDigestSystem
import net.postchain.chain0.common.init.initOperation
import net.postchain.chain0.common.operations.addNodeToClusterOperation
import net.postchain.chain0.common.operations.registerNodeWithUnitsOperation
import net.postchain.chain0.common.operations.updateNodeWithUnitsOperation
import net.postchain.chain0.common.queries.getBlockchains
import net.postchain.chain0.common.queries.getNodeData
import net.postchain.chain0.common.queries.getSummary
import net.postchain.chain0.direct_cluster.createClusterOperation
import net.postchain.chain0.direct_container.createContainerOperation
import net.postchain.chain0.economy_chain_in_directory_chain.getEconomyChainRid
import net.postchain.chain0.economy_chain_in_directory_chain.initEconomyChainOperation
import net.postchain.chain0.evm_event_receiver.initEvmEventReceiverChainOperation
import net.postchain.chain0.lib.eif.evm.getAccountIdByEvmAddress
import net.postchain.chain0.lib.eif.evm.registerAccountOperation
import net.postchain.chain0.lib.eif.ft4.addNewEvmErc20Operation
import net.postchain.chain0.lib.eif.ft4.addNewTokenMappingOperation
import net.postchain.chain0.lib.ft4.accounts.AuthDescriptor
import net.postchain.chain0.lib.ft4.accounts.AuthType
import net.postchain.chain0.lib.ft4.admin.registerAssetOperation
import net.postchain.chain0.lib.ft4.assets.external.getAssetBalance
import net.postchain.chain0.lib.ft4.assets.external.getAssetsByName
import net.postchain.chain0.lib.ft4.auth.external.ftAuthOperation
import net.postchain.chain0.model.ProviderInfo
import net.postchain.chain0.model.ProviderTier
import net.postchain.chain0.proposal_provider.proposeProvidersOperation
import net.postchain.cm.cm_api.ClusterManagementImpl
import net.postchain.common.BlockchainRid
import net.postchain.common.data.KECCAK256
import net.postchain.common.hexStringToByteArray
import net.postchain.common.hexStringToWrappedByteArray
import net.postchain.common.toHex
import net.postchain.crypto.KeyPair
import net.postchain.dapp.PostchainContainer
import net.postchain.dapp.postTransactionUntilConfirmed
import net.postchain.eif.EventMerkleProof
import net.postchain.eif.SimpleGtvEncoder
import net.postchain.eif.bad.config.AppConfig
import net.postchain.eif.bad.config.EvmClientConfig
import net.postchain.eif.bad.config.EvmConfig
import net.postchain.eif.bad.config.LogProcessorConfig
import net.postchain.eif.bad.config.AnomalyConfig
import net.postchain.eif.bad.evm.Web3jClientsManager
import net.postchain.eif.contracts.TestToken
import net.postchain.eif.contracts.TokenBridge
import net.postchain.eif.contracts.Validator
import net.postchain.eif.getEthereumAddress
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvArray
import net.postchain.gtv.GtvEncoder
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvInteger
import net.postchain.gtv.GtvNull
import net.postchain.gtv.gtvml.GtvMLParser
import net.postchain.gtv.mapper.toObject
import net.postchain.gtv.merkle.GtvMerkleHashCalculator
import net.postchain.gtv.merkleHash
import net.postchain.images.common.ManagedModeBase
import net.postchain.images.directory1.awaitQueryResult
import org.awaitility.Awaitility
import org.awaitility.Duration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junitpioneer.jupiter.DisableIfTestFails
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.junit.jupiter.Testcontainers
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.crypto.Credentials
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.http.HttpService
import org.web3j.tx.Contract
import org.web3j.tx.FastRawTransactionManager
import org.web3j.tx.TransactionManager
import org.web3j.tx.Transfer
import org.web3j.tx.response.PollingTransactionReceiptProcessor
import org.web3j.utils.Convert
import java.math.BigDecimal
import java.math.BigInteger
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@DisableIfTestFails
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnomalyDetectorIncorrectWithdrawIT : AnomalyDetectorTest() {

    companion object : ManagedModeBase() {
        private val BRIDGE_CHAIN_REFRESH_INTERVAL_MS = TimeUnit.SECONDS.toMillis(1)

        private const val EVM_EVENT_RECEIVER_CHAIN_NAME = "evm_event_receiver_chain"
        private const val EVM_TOKEN_BRIDGE_CHAIN_NAME = "evm_token_bridge"

        private val evmContainerLogger = KotlinLogging.logger("EvmEventReceiver_EvmContainerLogger")
        private val node1Logger = KotlinLogging.logger("EvmEventReceiver_Node1Logger")
        private val node2Logger = KotlinLogging.logger("EvmEventReceiver_Node2Logger")
        private val node3Logger = KotlinLogging.logger("EvmEventReceiver_Node3Logger")
        private val provider1KeyPair = KeyPair.of(
                "03ECD350EEBC617CBBFBEF0A1B7AE553A748021FD65C7C50C5ABB4CA16D4EA5B05",
                "BBBDFE956021912512E14BB081B27A35A0EABC4098CB687E973C434006BCE114")

        lateinit var eventReceiverBrid: BlockchainRid
        lateinit var tokenBridgeBrid: BlockchainRid

        private val evmContainer: GethContainer
        private val web3j: Web3j
        private val transactionManager: TransactionManager

        // users
        // - admin
        private val adminKeyPair = KeyPair.of( // node1
                "0350fe40766bc0ce8d08b3f5b810e49a8352fdd458606bd5fafe5acdcdc8ff3f57",
                "3132333435363738393031323334353637383930313233343536373839303131"
        )

        // - Alice
        private val alicePubkey = "038f888dec563b5bc253e87abc90afd26c3287021d10236ea19d248043dc39e0b8".hexStringToByteArray()
        private val alicePrivkey = "71b5b7f8de0661af934a5e4612f3d0ba183e639bdf4e7452fb6457ed3cfbc825".hexStringToByteArray()
        private val aliceKeyPair = KeyPair(alicePubkey, alicePrivkey)
        private val aliceEvmAddressStr = "e105ba42b66d08ac7ca7fc48c583599044a6dab3"
        private val aliceEvmAddress = aliceEvmAddressStr.hexStringToByteArray()
        private lateinit var aliceAccountId: ByteArray

        init {

            // Initialize EVM container
            evmContainer = GethContainer(logger = Slf4jLogConsumer(evmContainerLogger.underlyingLogger, true))
                    .withNetwork(network)
                    .apply {
                        start()
                    }

            // Web3j

            web3j = Web3j.build(HttpService(evmContainer.getExternalGethUrl()))

            transactionManager = FastRawTransactionManager(
                    web3j,
                    Credentials.create("0x53914554952e5473a54b211a31303078abde83b8128995785901eed28df3f610"),
                    PollingTransactionReceiptProcessor(web3j, 1000, 30)
            )

            // Nodes
            chain0Config = this::class.java.getResource("/net/postchain/eif/bad/mainnet.xml")!!.readText()
            node1 = postchainServer("node1", Slf4jLogConsumer(node1Logger.underlyingLogger, true),
                    provider1KeyPair,
                    "/net/postchain/images/directory1/config-no-subnodes")
                    .withEifEnv()
            node2 = postchainServer("node2", Slf4jLogConsumer(node2Logger.underlyingLogger, true),
                    KeyPair.of("03F9ABC05F7D7639AEC97B18784D5C83CA82D1EAF8F96DC31E77A83F21DDE67F95", "FFC28105CFE2CC336624DCDFDEDB58157B37ED565C29F11A3B54B8F721DBA7C5"),
                    "/net/postchain/images/directory1/config-no-subnodes")
                    .withEifEnv()
            node3 = postchainServer("node3", Slf4jLogConsumer(node3Logger.underlyingLogger, true),
                    KeyPair.of("03D01591E5466B07AC1D1F77BEBE2164AB0BA31366FBF005907F28FD144D64B871", "AD329F5C4E4DDF226D1A4948D7A2CCB34E76F64D4972B934FDBBDBEF4CA7B905"),
                    "/net/postchain/images/directory1/config-no-subnodes")
                    .withEifEnv()

            startNodesAndChain0()
        }

        private fun PostchainContainer.withEifEnv(): PostchainContainer {
            withEnv("POSTCHAIN_EIF_ETHEREUM_URLS", evmContainer.getNetworkGethUrl())
            withEnv("POSTCHAIN_EIF_ETHEREUM_MAX_READ_AHEAD", 200.toString())
            withEnv("POSTCHAIN_EIF_ETHEREUM_MAX_QUEUE_SIZE", 100.toString())
            withEnv("POSTCHAIN_EIF_EVM_MAX_TRY_ERRORS", 1.toString())
            withEnv("POSTCHAIN_TEST_DOCKER_IMAGE_POSTCHAIN_SERVER", "registry.gitlab.com/chromaway/postchain-chromia/chromaway/chromia-server:3.15.3")
            return this
        }

        @JvmStatic
        @AfterAll
        fun tearDownManagedModeBase() {
            evmContainer.stop()
            super.breakdown()
        }
    }

    @BeforeAll
    fun setupBeforeAll() {
        ds = SimpleDigestSystem(MessageDigest.getInstance(KECCAK256))

        with(configOverrides) {
            setProperty("infrastructure", net.postchain.devtools.testinfra.BaseTestInfrastructureFactory::class.qualifiedName)
            setProperty("ethereum.maxReadAhead", 200)
            setProperty("ethereum.maxQueueSize", 100)
            setProperty("evm.maxTryErrors", 1)
        }
    }

    @AfterAll
    fun tearDownAfterAll() {

        logger.info { "tearDownAfterAll" }

        super.tearDown() // Calling @AfterEach IntegrationTestSetup.tearDown()
        web3j.shutdown()
        evmContainer.stop()
        anomalyDetectorsManager.stop()
        restApi.close()
    }

    @Test
    @Order(10)
    fun `prepare - deploy contracts`() {
        logger.info { "deploy contracts" }

        // Deploy validator contract
        val encodedConstructor = FunctionEncoder.encodeConstructor(listOf(DynamicArray(Address::class.java, Address(getEthereumAddress(node1.pubkey.data).toHex()))))
        validator = Contract.deployRemoteCall(Validator::class.java, web3j, transactionManager, gasProvider, validatorBinary, encodedConstructor).send()

        // Deploy token bridge contract
        bridge = Contract.deployRemoteCall(TokenBridge::class.java, web3j, transactionManager, gasProvider, tokenBridgeBinary, "").send().apply {
            initialize(Address(validator.contractAddress), Uint256(2)).send()
        }

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
            val clusterAnchoringGtvConfig = GtvMLParser.parseGtvML(this::class.java.getResource("/net/postchain/eif/bad/cluster_anchoring.xml")!!.readText())
            val systemAnchoringGtvConfig = GtvMLParser.parseGtvML(this::class.java.getResource("/net/postchain/eif/bad/system_anchoring.xml")!!.readText())
            transactionBuilder()
                    .initOperation(GtvEncoder.encodeGtv(systemAnchoringGtvConfig), GtvEncoder.encodeGtv(clusterAnchoringGtvConfig))
                    .postTransactionUntilConfirmed("init")
            assertThat(getSummary().providers).isEqualTo(1L)
            assertThat(getNodeData(node1.nodeKeyPair.pubKey).active).isTrue()
        }
        assertAnchoringChainProperties()

        // Adding provider 2 & 3 as system
        logger.info("Add system provider provider 2 & 3 and their nodes")
        val newProviders = listOf(
                ProviderInfo(node2.provider.pubKey.wData, "provider2", "http://provider2.com"),
                ProviderInfo(node3.provider.pubKey.wData, "provider3", "http://provider3.com")
        )
        node1.client(chain0Brid, listOf(node1.provider, node2.provider, node3.provider)).transactionBuilder().addNop()
                .proposeProvidersOperation(node1.providerPubkey, newProviders, ProviderTier.NODE_PROVIDER, system = true, active = true, description = "")
                .registerNodeWithUnitsOperation(node2.providerPubkey, node2.pubkey.data, node2.nodeHost, node2.nodePort.toLong(), node2.nodeApiPath(), listOf(systemCluster), 2)
                .registerNodeWithUnitsOperation(node3.providerPubkey, node3.pubkey.data, node3.nodeHost, node3.nodePort.toLong(), node3.nodeApiPath(), listOf(systemCluster), 2)
                .postTransactionUntilConfirmed("System provider 2 & 3 registered and node added")

        val providers = node1.c0.query("get_all_providers", gtv(mapOf()))
        assertThat(providers.asArray().size).isEqualTo(3)

        // Adding a dapp cluster
        node1.client(chain0Brid, listOf(node1.provider, node2.provider)).transactionBuilder().addNop()
                .createClusterOperation(node1.providerPubkey, "dapp_cluster", "SYSTEM_P", listOf(node1.providerPubkey))
                .createContainerOperation(node1.providerPubkey, "dapp_container", "dapp_cluster", 1, listOf(node1.providerPubkey))
                .updateNodeWithUnitsOperation(node1.providerPubkey, node1.pubkey.data, null, null, null, 3)
                .addNodeToClusterOperation(node1.providerPubkey, node1.pubkey.data, "dapp_cluster")
                .postTransactionUntilConfirmed("dapp_cluster and dapp_container created")

    }

    @Test
    @Order(30)
    fun `prepare - start chains`() {
        logger.info { "start chains" }

        logger.info("Deploy EVM Event Receiver Chain")
        val gtvConfig = GtvMLParser.parseGtvML(this::class.java.getResource("/directory1deployment/evm_event_receiver.xml")!!.readText())

        node1.c0.transactionBuilder()
                .initEvmEventReceiverChainOperation(node1.providerPubkey, GtvEncoder.encodeGtv(gtvConfig))
                .postTransactionUntilConfirmed("Add $EVM_EVENT_RECEIVER_CHAIN_NAME")

        val blockchains = node1.c0.getBlockchains(true)
        val tcRid = blockchains.firstOrNull { it.name == EVM_EVENT_RECEIVER_CHAIN_NAME }?.rid
        assertThat(tcRid).isNotNull()
        eventReceiverBrid = BlockchainRid(tcRid!!)

        logger.info { "$EVM_EVENT_RECEIVER_CHAIN_NAME deployed: $eventReceiverBrid" }


        logger.info("Deploy Mocked economy chain")
        val ecChainGtvConfig = loadMockedEconomyBlockchainConfig()
        node1.c0.transactionBuilder()
                .initEconomyChainOperation(node1.providerPubkey, GtvEncoder.encodeGtv(ecChainGtvConfig))
                .postTransactionUntilConfirmed("Mocked EC initialized")
        ecBrid = BlockchainRid(node1.c0.getEconomyChainRid()!!)

        logger.info { "Mocked EC deployed:  blockchainRid: $ecBrid" }

        logger.info("Deploy EVM Token Bridge dapp")
        deployDapp("evm_token_bridge", "dapp_container", assertSigners = arrayOf(node1), icmfReceiver = eventReceiverBrid.data)

        val brid = node1.c0.getBlockchains(true).firstOrNull { it.name == EVM_TOKEN_BRIDGE_CHAIN_NAME }?.rid
        assertThat(brid).isNotNull()
        tokenBridgeBrid = BlockchainRid(brid!!)
        bridge.setBlockchainRid(Bytes32(tokenBridgeBrid.data)).send()

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
                node1.apiPath(),
                ecBrid.toHex(),
                BRIDGE_CHAIN_REFRESH_INTERVAL_MS,
                blockchainSyncMargin,
                bypassBlockchainSyncCheck = false,

                // Timeouts disabled for tests to make it execute right away
                AnomalyConfig(
                        missingHeightRetryDelay = 0,
                        pauseDelay = 0,
                        0,
                        true
                )
        )

        web3jClientsManager = Web3jClientsManager(appConfig.evmConfig)
        anomalyDetectorsManager = AnomalyDetectorsManager(appConfig, web3jClientsManager) {
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

        logger.info { "add fake chain to monitor" }

        Awaitility.await()
                .atMost(Duration.TEN_SECONDS)
                .untilAsserted {
                    assertThat(anomalyDetectorsManager.getAnomalyDetectors().size).isEqualTo(0)
                }

        node1.ec.transactionBuilder()
                .addOperation("add_anomaly_detection", gtv(ecBrid), gtv(networkId), gtv(bridge.contractAddress))
                .postTransactionUntilConfirmed("added fake chain bridge")

        Awaitility.await()
                .atMost(Duration(BRIDGE_CHAIN_REFRESH_INTERVAL_MS * 2, TimeUnit.SECONDS))
                .untilAsserted {
                    assertThat(anomalyDetectorsManager.getAnomalyDetectors().size).isEqualTo(1)
                }

        val restStatus = restStatus(appConfig)
        assertThat(restStatus.size).isEqualTo(1)
        assertThat(restStatus[0].blockchainRid).isEqualTo(ecBrid.toHex())
    }

    @Test
    @Order(70)
    fun `prepare - register ft accounts`() {
        logger.info { "Register FT accounts" }

        val tokenName = "Chromia"
        val tokenSymbol = "CHR"
        val tokenDecimal = 18L
        val tokenIconUrl = "https://chromaway.com/chr"

        node1.client(tokenBridgeBrid, signers = listOf(adminKeyPair)).transactionBuilder()
                .registerAssetOperation(tokenName, tokenSymbol, tokenDecimal, tokenIconUrl)
                .postTransactionUntilConfirmed("Register asset")

        assetId = awaitQueryResult {
            node1.client(tokenBridgeBrid).getAssetsByName(tokenName, 1L, null).data[0]["id"]?.asByteArray()
        }!!

        // Register evm account
        val aliceSig = net.postchain.chain0.lib.ft4.auth.Signature(
                "39b0c8c44a10d0fd70c0ed0e833cf6d93818ae1b10777857eb868516932796dc".hexStringToWrappedByteArray(),
                "44de8f297cce55c3da8401dd77269d0baf978f60e97ebc5717d4c8eeaed3bea9".hexStringToWrappedByteArray(),
                28L
        )
        val aliceAuth = AuthDescriptor(
                AuthType.S,
                listOf(
                        GtvArray(arrayOf(gtv("A"), gtv("T"))),
                        gtv(alicePubkey)
                ),
                GtvNull
        )

        node1.client(tokenBridgeBrid, signers = listOf(adminKeyPair)).transactionBuilder()
                .addNewEvmErc20Operation(networkId, testTokenAddress, tokenName, tokenSymbol, tokenDecimal, true)
                .addNewTokenMappingOperation(networkId, testTokenAddress, assetId)
                .postTransactionUntilConfirmed("Add ERC-20 token")
        node1.client(tokenBridgeBrid, signers = listOf(aliceKeyPair)).transactionBuilder()
                .registerAccountOperation(aliceEvmAddress, aliceAuth, aliceSig)
                .postTransactionUntilConfirmed("Register Alice account")

        awaitQueryResult {
            node1.client(tokenBridgeBrid).getAccountIdByEvmAddress(aliceEvmAddress)?.also {
                assertThat(it).isNotNull()
                aliceAccountId = it
            }
        }
    }

    @Test
    @Order(80)
    fun `prepare - deposit token on evm`() {

        logger.info { "deposit token on evm" }

        // Deposit token on EVM smart contract to bridge it to postchain
        for (i in 1..depositNum) {
            bridge.deposit(Address(testToken.contractAddress), Uint256(depositAmount)).send()
        }
        // check the balance on EVM
        userBalance = testToken.balanceOf(Address(aliceEvmAddressStr)).send()
        assertEquals(userBalance.value, initialMint - totalDepositedAmount)

        // check the asset balance on Chromia

        awaitQueryResult {
            val balance = node1.client(tokenBridgeBrid).getAssetBalance(aliceAccountId, assetId)
            assertThat(balance?.amount).isEqualTo(totalDepositedAmount)
        }
        snapshotHeights.add(node1.client(tokenBridgeBrid).currentBlockHeight())

        // Check eif state for account as well
        val expectedState = SimpleGtvEncoder.encodeGtv(gtv(
                gtv(to32Bytes(aliceEvmAddressStr)), // encode gtv array with assumption that the data contains only byte32 and uint256
                gtv(1 * 2 * 32), // 2 * 32 bytes per entry
                gtv(to32Bytes(testToken.contractAddress.substring(2))), // encode gtv array with assumption that the data contains only byte32 and uint256
                gtv(totalDepositedAmount)
        ))
        val accounts = node1.client(tokenBridgeBrid).query("eif.data.get_network_accounts",
                gtv("network_id" to gtv(networkId)))
        accountNumber = accounts[0].asDict()["state_n"]!!

        val args = gtv(
                "blockHeight" to gtv(node1.client(tokenBridgeBrid).currentBlockHeight()),
                "accountNumber" to gtv(accountNumber.asInteger())
        )
        awaitQueryResult {
            val accountState = node1.client(tokenBridgeBrid).query("get_account_state_merkle_proof", args).asDict()

            val stateData = accountState["stateData"]!!
            assertEquals(expectedState.toHex(), stateData.asByteArray().toHex())
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

        node1.ec.transactionBuilder()
                .addOperation("remove_anomaly_detection", gtv(ecBrid))
                .postTransactionUntilConfirmed("removed fake chain")

        Awaitility.await()
                .atMost(Duration(BRIDGE_CHAIN_REFRESH_INTERVAL_MS * 2, TimeUnit.SECONDS))
                .untilAsserted {
                    assertThat(anomalyDetectorsManager.getAnomalyDetectors().size).isEqualTo(0)
                }

        val restStatus = restStatus(appConfig)
        assertThat(restStatus.size).isEqualTo(0)

        // Make sure the client is shutdown
        assertThat(web3jClientsManager.hasNetworkClient(networkId)).isFalse()
    }

    private fun withdrawRequest() {
        // Bridge some ft token to evm
        val gtvAuthDescriptorId = node1.client(tokenBridgeBrid).query(
                "ft4.get_account_auth_descriptors",
                gtv("id" to gtv(aliceAccountId))
        )[0]["id"]!!

        val auth = gtv(
                gtv(AuthType.S.ordinal.toLong()),
                gtv(GtvArray(arrayOf(gtv("A"), gtv("T"))), gtv(alicePubkey)),
                GtvNull
        )

        val authDescriptorId = auth.merkleHash(GtvMerkleHashCalculator(myCS))
        assertEquals(gtv(authDescriptorId), gtvAuthDescriptorId)
        authId = gtv(gtv(aliceAccountId), gtvAuthDescriptorId)

        withdrawAmount = BigInteger("1", 16)

        node1.client(tokenBridgeBrid, signers = listOf(aliceKeyPair)).transactionBuilder()
                .ftAuthOperation(aliceAccountId, authDescriptorId)
                .addOperation(
                        "eif.ft4.bridge_ft_token_to_evm",
                        gtv(networkId),
                        gtv(testTokenAddress),
                        gtv(aliceEvmAddress),
                        gtv(withdrawAmount))
                .addOperation("nop", GtvInteger(System.currentTimeMillis()))
                .postTransactionUntilConfirmed("withdrawOnPostchain")
        totalDepositedAmount -= withdrawAmount
        snapshotHeights.add(node1.client(tokenBridgeBrid).currentBlockHeight())

        // Check eif state for account after withdraw as well
        val expectedState1 = SimpleGtvEncoder.encodeGtv(gtv(
                gtv(to32Bytes(aliceEvmAddressStr)), // encode gtv array with assumption that the data contains only byte32 and uint256
                gtv(1 * 2 * 32), // 2 * 32 bytes per entry
                gtv(to32Bytes(testToken.contractAddress.substring(2))), // encode gtv array with assumption that the data contains only byte32 and uint256
                gtv(totalDepositedAmount)
        ))
        val arg1 = gtv(
                "blockHeight" to gtv(node1.client(tokenBridgeBrid).currentBlockHeight()),
                "accountNumber" to gtv(accountNumber.asInteger())
        )

        val accountState1 = node1.client(tokenBridgeBrid).query("get_account_state_merkle_proof", arg1).asDict()

        val stateData1 = accountState1["stateData"]!!
        assertEquals(expectedState1.toHex(), stateData1.asByteArray().toHex())

        val balance = node1.client(tokenBridgeBrid).query("ft4.get_asset_balance",
                gtv("account_id" to gtv(aliceAccountId), "asset_id" to gtv(assetId)))["amount"]!!.asBigInteger()
        assertEquals(totalDepositedAmount, balance)

        // Get and verify the withdrawal data
        val withdrawInfo = getLastWithdrawal(aliceEvmAddress)
        assertEquals(withdrawInfo["amount"]!!.asBigInteger(), withdrawAmount)
        val serial = withdrawInfo["serial"]!!.asInteger()

        // Query to get the event proof to withdraw fund on evm
        val eventData = gtv(
                gtv(serial),
                gtv(networkId),
                gtv(to32Bytes(testToken.contractAddress.substring(2))),
                gtv(to32Bytes(aliceEvmAddressStr)),
                gtv(withdrawAmount)
        )
        val encodedEventData = SimpleGtvEncoder.encodeGtv(eventData)
        val eventHash = ds.digest(encodedEventData)
        val eventProof = node1.client(tokenBridgeBrid).query("get_event_merkle_proof",
                gtv("eventHash" to gtv(eventHash.toHex()))
        ).toObject<EventMerkleProof>()
        assertArrayEquals(encodedEventData, eventProof.eventData)

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

    private fun getLastWithdrawal(beneficiary: ByteArray): Map<String, Gtv> {
        val all = node1.client(tokenBridgeBrid).query("eif.ft4.get_erc20_withdrawal", gtv(
                "network_id" to gtv(networkId),
                "token_address" to gtv(testTokenAddress),
                "beneficiary" to gtv(beneficiary)
        )).asArray()

        return all.map { it.asDict() }.maxByOrNull { it["serial"]!!.asInteger() }!!
    }
}
