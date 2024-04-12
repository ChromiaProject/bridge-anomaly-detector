package net.postchain.eif.anomaly_detector

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import mu.KotlinLogging
import net.postchain.base.BaseBlockWitness
import net.postchain.base.configuration.KEY_SIGNERS
import net.postchain.base.snapshot.SimpleDigestSystem
import net.postchain.client.config.PostchainClientConfig
import net.postchain.client.core.PostchainClient
import net.postchain.client.impl.PostchainClientProviderImpl
import net.postchain.client.request.EndpointPool
import net.postchain.common.BlockchainRid
import net.postchain.common.data.Hash
import net.postchain.common.data.KECCAK256
import net.postchain.common.hexStringToByteArray
import net.postchain.common.toHex
import net.postchain.concurrent.util.get
import net.postchain.core.BlockRid
import net.postchain.core.block.BlockQueries
import net.postchain.crypto.KeyPair
import net.postchain.crypto.Signature
import net.postchain.crypto.devtools.KeyPairHelper
import net.postchain.devtools.PostchainTestNode
import net.postchain.devtools.PostchainTestNode.Companion.DEFAULT_CHAIN_IID
import net.postchain.eif.EifSignature
import net.postchain.eif.EventMerkleProof
import net.postchain.eif.SimpleGtvEncoder
import net.postchain.eif.anomaly_detector.config.AppConfig
import net.postchain.eif.anomaly_detector.config.EvmConfig
import net.postchain.eif.anomaly_detector.config.LogProcessorConfig
import net.postchain.eif.anomaly_detector.config.TimeoutConfig
import net.postchain.eif.anomaly_detector.evm.Web3jClientsManager
import net.postchain.eif.anomaly_detector.rest.AnomalyDetectorStatusResponse
import net.postchain.eif.anomaly_detector.rest.statusBody
import net.postchain.eif.contracts.TestToken
import net.postchain.eif.contracts.TokenBridge
import net.postchain.eif.contracts.Validator
import net.postchain.eif.encodeSignatureWithV
import net.postchain.eif.getEthereumAddress
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvArray
import net.postchain.gtv.GtvEncoder
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvNull
import net.postchain.gtv.gtvml.GtvMLParser
import net.postchain.gtv.mapper.toObject
import net.postchain.gtv.merkle.GtvMerkleHashCalculator
import net.postchain.gtv.merkleHash
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.cookie.StandardCookieSpec
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.core5.util.Timeout
import org.awaitility.Awaitility
import org.awaitility.Duration
import org.http4k.client.ApacheClient
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.filter.ClientFilters
import org.http4k.filter.GzipCompressionMode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.assertThrows
import org.junitpioneer.jupiter.DisableIfTestFails
import org.testcontainers.junit.jupiter.Testcontainers
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.crypto.Credentials
import org.web3j.crypto.Sign
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.exceptions.TransactionException
import org.web3j.tx.Contract
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val BRIDGE_CHAIN_REFRESH_INTERVAL_SECONDS = 5L

/**
 * This is based on EifIntegrationTest in postchain-chromia repository.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@DisableIfTestFails
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnomalyDetectorIT : EifBaseIntegrationTest(
//        prependUrls = listOf("http://127.0.0.1:8888", "http://127.0.0.1:9999")
) {

    val logger = KotlinLogging.logger("test_logger")

    private lateinit var ds: SimpleDigestSystem

    private val accountNum = 15
    private val accountBalance = 1L

    // user
    private val evmAddress = "e105ba42b66d08ac7ca7fc48c583599044a6dab3"
    private val userEvmAddress = evmAddress.hexStringToByteArray()
    private val userPubkey = "038f888dec563b5bc253e87abc90afd26c3287021d10236ea19d248043dc39e0b8".hexStringToByteArray()
    private val userPriKey = "71b5b7f8de0661af934a5e4612f3d0ba183e639bdf4e7452fb6457ed3cfbc825".hexStringToByteArray()

    // TODO: use getEvmAddress
    private val node0EvmAddress = Address("659e4a3726275edFD125F52338ECe0d54d15BD99")
    private val node1EvmAddress = Address("2c3fA9C9FC3C5CB2f9C09aF6f7214f64382eA086")

    // other
    private val otherEvmAddressString = "661683e5d36E83B38B1a20247ba6F5c410dC165d"
    private val otherEvmAddress = otherEvmAddressString.hexStringToByteArray()

    private val initialMint = BigInteger("FF".repeat(32), 16)
    private val depositNum = 5
    private val depositAmount = BigInteger("AA".repeat(16), 16)
    private val totalDepositedAmount = depositNum.toBigInteger() * depositAmount
    private lateinit var validator: Validator
    private lateinit var bridge: TokenBridge
    private lateinit var testToken: TestToken
    private lateinit var testTokenAddress: ByteArray
    private lateinit var userBalance: Uint256
    private lateinit var withdrawAmount: BigInteger
    private lateinit var accountId: Gtv
    private lateinit var accountNumber: Gtv
    private lateinit var authDescriptorId: Hash
    private lateinit var authId: Gtv
    private lateinit var otherAccountId: Gtv
    private lateinit var assetId: Gtv
    private lateinit var node: PostchainTestNode
    private lateinit var blockQuery: BlockQueries
    private var eifChainId = -1L
    private var ecChainId = -1L
    private lateinit var eifBcRid: BlockchainRid
    private lateinit var ecBcRid: BlockchainRid
    private var currentBlockHeight = 0L
    private lateinit var ecClient: PostchainClient
    private lateinit var appConfig: AppConfig
    private lateinit var web3jClientsManager: Web3jClientsManager
    private lateinit var anomalyDetectorsManager: AnomalyDetectorsManager
    private val restApiHttpHandler = restApiHttpHandler()

    @BeforeAll
    fun setupBeforeAll() {
        super.setup()

        ds = SimpleDigestSystem(MessageDigest.getInstance(KECCAK256))

        with(configOverrides) {
            setProperty("infrastructure", net.postchain.devtools.testinfra.BaseTestInfrastructureFactory::class.qualifiedName)
            setProperty("ethereum.maxReadAhead", 200)
            setProperty("ethereum.maxQueueSize", 100)
            setProperty("evm.maxTryErrors", 1)
        }
    }

    @BeforeEach
    override fun setup() {
        // This method blocks @BeforeEach in EifBaseIntegrationTest.setup()
    }

    @AfterAll
    fun tearDownAfterAll() {
        super.tearDown() // Calling @AfterEach IntegrationTestSetup.tearDown()
        web3j.shutdown()
        evmContainer.stop()
    }

    @AfterEach
    override fun tearDown() {
        // This method blocks @AfterEach EifBaseIntegrationTest.tearDown()
    }

    @Test
    @Order(10)
    fun `prepare - deploy contracts`() {
        logger.info { "deploy contracts" }

        // Deploy validator contract
        val encodedConstructor = FunctionEncoder.encodeConstructor(listOf(DynamicArray(Address::class.java, node0EvmAddress)))
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
    fun `prepare - start nodes`() {
        logger.info { "start nodes" }

        // c0
        startManagedSystem(1, 1, restApi = true)

        // c1
        val chainGtvConfig = loadEifBlockchainConfig()
        eifChainId = startNewBlockchain(
                setOf(0), setOf(1), rawBlockchainConfiguration = GtvEncoder.encodeGtv(chainGtvConfig)
        )
        buildBlock(eifChainId)
        node = nodes[0]
        eifBcRid = node.getBlockchainInstance(eifChainId).blockchainEngine.blockchainRid
        logger.info { "EIF chain deployed: chainId: $eifChainId, blockchainRid: $eifBcRid" }

        // c2 - Mocked economy chain
        val ecChainGtvConfig = loadMockedEconomyBlockchainConfig(eifBcRid, bridge.contractAddress)
        ecChainId = startNewBlockchain(
                setOf(0), setOf(1), rawBlockchainConfiguration = GtvEncoder.encodeGtv(ecChainGtvConfig)
        )
        buildBlock(ecChainId)
        ecBcRid = node.getBlockchainInstance(ecChainId).blockchainEngine.blockchainRid
        logger.info { "Mocked EC deployed: chainId: $ecChainId, blockchainRid: $ecBcRid" }

        ecClient = PostchainClientProviderImpl().createClient(
                PostchainClientConfig(
                        ecBcRid,
                        EndpointPool.singleUrl("http://127.0.0.1:${node.getRestApiHttpPort()}"),
                        listOf()
                ))
    }

    @Test
    @Order(30)
    fun `start anomaly detector`() {

        appConfig = AppConfig(

                // Evm rpc
                mapOf(networkId to EvmConfig(
                        listOf("http://localhost:1", evmRpcUrl),
                        Credentials.create("0x53914554952e5473a54b211a31303078abde83b8128995785901eed28df3f610"),
                        LogProcessorConfig(2, 10, 50)
                )),

                // Node and postchain
                "http://127.0.0.1:${node.getRestApiHttpPort()}",
                ecBcRid.toHex(),
                BRIDGE_CHAIN_REFRESH_INTERVAL_SECONDS,

                // Timeouts disabled for tests to make it execute right away
                TimeoutConfig(
                        missingHeightRetryDelay = 0,
                        pauseDelay = 0,
                )
        )

        web3jClientsManager = Web3jClientsManager(appConfig.evmConfig)
        anomalyDetectorsManager = AnomalyDetectorsManager(appConfig, web3jClientsManager)
        anomalyDetectorsManager.start()
    }

    @Test
    @Order(40)
    fun `start anomaly detector api`() {

        val restApi = startRestApi(appConfig.restApiConfig, anomalyDetectorsManager)
        assertThat(restApi).isNotNull()

        val restStatus = restStatus()
        assertThat(restStatus.size).isEqualTo(0)
    }

    @Test
    @Order(50)
    fun `add chain to monitor`() {

        Awaitility.await()
                .atMost(Duration.TEN_SECONDS)
                .untilAsserted {
                    assertThat(anomalyDetectorsManager.getAnomalyDetectors().size).isEqualTo(0)
                }

        awaitTransaction(ecClient, ecChainId) { it.addOperation("add_anomaly_detection", gtv(eifBcRid), gtv(networkId), gtv(bridge.contractAddress)) }

        Awaitility.await()
                .atMost(Duration(BRIDGE_CHAIN_REFRESH_INTERVAL_SECONDS * 2, TimeUnit.SECONDS))
                .untilAsserted {
                    assertThat(anomalyDetectorsManager.getAnomalyDetectors().size).isEqualTo(1)
                }

        val restStatus = restStatus()
        assertThat(restStatus.size).isEqualTo(1)
        assertThat(restStatus[0].blockchainRid).isEqualTo(eifBcRid.toHex())
    }

    @Test
    @Order(60)
    fun `prepare - register ft accounts`() {
        logger.info { "register ft accounts" }

        val sigMaker = cryptoSystem.buildSigMaker(KeyPair(KeyPairHelper.pubKey(0), KeyPairHelper.privKey(0)))
        val tokenName = "Chromia"
        val tokenSymbol = "CHR"
        val tokenDecimal = 18L
        val tokenIconUrl = "https://chromaway.com/chr"

        enqueueTx(registerAsset(tokenName, tokenSymbol, tokenDecimal, tokenIconUrl, eifBcRid, sigMaker))
        sealBlock()

        val value = node.getBlockchainInstance().blockchainEngine.getBlockQueries()
                .query("ft4.get_assets_by_name", gtv(
                        "name" to gtv(tokenName),
                        "page_size" to gtv(1L),
                        "page_cursor" to GtvNull
                )).get()
        assetId = value["data"]?.get(0)?.get("id")!!

        // Register evm account
        val otherPubkey = "02E0A8A3C79C9F18B7CEAD2493435AC926B4A527EF670B873F5F1410084EFF9C80".hexStringToByteArray()
        val otherPrivkey = "B31AB878C62B0E940B345C659A456D3573CF25960823C34C7BEEB5D1F813BEFD".hexStringToByteArray()
        val sig = gtv(
                gtv("39b0c8c44a10d0fd70c0ed0e833cf6d93818ae1b10777857eb868516932796dc".hexStringToByteArray()),
                gtv("44de8f297cce55c3da8401dd77269d0baf978f60e97ebc5717d4c8eeaed3bea9".hexStringToByteArray()),
                gtv(28L))

        val otherSig = gtv(
                gtv("8fa4216cd5979efdeb109e10f87225ea9579fd21289fac7f1410278554e79aff".hexStringToByteArray()),
                gtv("442017757e4e627a98d40c89cdbdde4612251cc86acabba27cf1683cd1d7cb4c".hexStringToByteArray()),
                gtv(28L))

        enqueueTx(addNewEvmErc20(testTokenAddress, tokenName, tokenSymbol, tokenDecimal, eifBcRid, sigMaker))
        enqueueTx(addTokenMapping(testTokenAddress, assetId, eifBcRid, sigMaker))

        // Register accounts
        enqueueTx(registerAccount(userPubkey, userPriKey, userEvmAddress, sig, eifBcRid))
        enqueueTx(registerAccount(otherPubkey, otherPrivkey, otherEvmAddress, otherSig, eifBcRid))

        for (i in 1..accountNum) {
            val acc = AccountRegister(
                    ByteArray(32),
                    KeyPairHelper.privKey(i),
                    KeyPairHelper.pubKey(i),
                    getEthereumAddress(KeyPairHelper.pubKey(i)),
                    accountBalance
            )
            registerAccounts.add(acc)
            val registerMessage = getRegisterMessage(acc.evmAddress.toHex().lowercase(), acc.pubkey.toHex().lowercase())
            val evmSig = Sign.signPrefixedMessage(
                    registerMessage.toByteArray(StandardCharsets.UTF_8),
                    Credentials.create(acc.privKey.toHex()).ecKeyPair
            )
            val gtvEvmSig = gtv(
                    gtv(evmSig.r),
                    gtv(evmSig.s),
                    gtv(BigInteger(evmSig.v).longValueExact())
            )
            enqueueTx(registerAccount(acc.pubkey, acc.privKey, acc.evmAddress, gtvEvmSig, eifBcRid))
        }
        sealBlock()

        // query ft account id by evm address
        blockQuery = node.getBlockchainInstance().blockchainEngine.getBlockQueries()
        accountId = blockQuery.query("eif.evm.get_account_id_by_evm_address",
                gtv("acc" to gtv(userEvmAddress))).get()
        otherAccountId = blockQuery.query("eif.evm.get_account_id_by_evm_address",
                gtv("acc" to gtv(otherEvmAddress))).get()
        registerAccounts.forEach {
            it.accountId = blockQuery.query("eif.evm.get_account_id_by_evm_address", gtv("acc" to gtv(it.evmAddress))).get().asByteArray()
        }
    }


    @Test
    @Order(70)
    fun `prepare - deposit token on evm`() {
        logger.info { "deposit token on evm" }

        // Deposit token on EVM smart contract to bridge it to postchain
        for (i in 1..depositNum) {
            bridge.deposit(Address(testToken.contractAddress), Uint256(depositAmount)).send()
        }

        userBalance = testToken.balanceOf(Address(evmAddress)).send()
        assertEquals(userBalance.value, initialMint - totalDepositedAmount)

        // Check the asset balance
        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            sealBlock() // keep postchain mine new blocks to ensure that all evm deposits are recorded
            val balance = blockQuery.query("ft4.get_asset_balance", gtv("account_id" to accountId, "asset_id" to assetId)).get()["amount"]!!.asBigInteger()
            assertEquals(totalDepositedAmount, balance)
        }
        snapshotHeights.add(currentBlockHeight)

        // Check eif state for account as well
        val expectedState = SimpleGtvEncoder.encodeGtv(gtv(
                gtv(to32Bytes(evmAddress)), // encode gtv array with assumption that the data contains only byte32 and uint256
                gtv(1 * 2 * 32), // 2 * 32 bytes per entry
                gtv(to32Bytes(testToken.contractAddress.substring(2))), // encode gtv array with assumption that the data contains only byte32 and uint256
                gtv(totalDepositedAmount)
        ))
        val accounts = blockQuery.query("eif.data.get_network_accounts",
                gtv("network_id" to gtv(networkId))).get()
        accountNumber = accounts[0].asDict()["state_n"]!!

        val args = gtv(
                "blockHeight" to gtv(currentBlockHeight),
                "accountNumber" to gtv(accountNumber.asInteger())
        )
        val accountState = blockQuery.query("get_account_state_merkle_proof", args).get().asDict()

        val stateData = accountState["stateData"]!!
        assertEquals(expectedState.toHex(), stateData.asByteArray().toHex())
    }

    @Test
    @Order(80)
    fun `prepare - withdraw token to evm`() {
        logger.info { "withdraw token to evm" }

        // Bridge some ft token to evm
        val gtvAuthDescriptorId = blockQuery.query(
                "ft4.get_account_auth_descriptors",
                gtv("id" to accountId)
        ).get()[0]["id"]!!

        val auth = gtv(
                gtv(AuthType.S.ordinal.toLong()),
                gtv(GtvArray(arrayOf(gtv("A"), gtv("T"))), gtv(userPubkey)),
                GtvNull
        )

        authDescriptorId = auth.merkleHash(GtvMerkleHashCalculator(myCS))
        assertEquals(gtv(authDescriptorId), gtvAuthDescriptorId)
        authId = gtv(accountId, gtvAuthDescriptorId)

        withdrawAmount = BigInteger("1234567890", 16)
        enqueueTx(withdrawOnPostchain(userPubkey, userPriKey, authId, testTokenAddress, userEvmAddress, withdrawAmount, eifBcRid))
        sealBlock()
        snapshotHeights.add(currentBlockHeight)

        // Check eif state for account after withdraw as well
        val expectedState1 = SimpleGtvEncoder.encodeGtv(gtv(
                gtv(to32Bytes(evmAddress)), // encode gtv array with assumption that the data contains only byte32 and uint256
                gtv(1 * 2 * 32), // 2 * 32 bytes per entry
                gtv(to32Bytes(testToken.contractAddress.substring(2))), // encode gtv array with assumption that the data contains only byte32 and uint256
                gtv(totalDepositedAmount - withdrawAmount)
        ))
        val arg1 = gtv(
                "blockHeight" to gtv(currentBlockHeight),
                "accountNumber" to gtv(accountNumber.asInteger())
        )
        val accountState1 = blockQuery.query("get_account_state_merkle_proof", arg1).get().asDict()

        val stateData1 = accountState1["stateData"]!!
        assertEquals(expectedState1.toHex(), stateData1.asByteArray().toHex())

        val balance = blockQuery.query("ft4.get_asset_balance",
                gtv("account_id" to accountId, "asset_id" to assetId)).get()["amount"]!!.asBigInteger()
        assertEquals(totalDepositedAmount - withdrawAmount, balance)

        // Get and verify the withdrawal data
        val withdrawInfo = getLastWithdrawal(userEvmAddress)
        assertEquals(withdrawInfo["amount"]!!.asBigInteger(), withdrawAmount)
        val serial = withdrawInfo["serial"]!!.asInteger()

        // Query to get the event proof to withdraw fund on evm
        val eventData = gtv(
                gtv(serial),
                gtv(networkId),
                gtv(to32Bytes(testToken.contractAddress.substring(2))),
                gtv(to32Bytes(evmAddress)),
                gtv(withdrawAmount)
        )
        val encodedEventData = SimpleGtvEncoder.encodeGtv(eventData)
        val eventHash = ds.digest(encodedEventData)
        val eventProof = blockQuery.query("get_event_merkle_proof",
                gtv("eventHash" to gtv(eventHash.toHex()))
        ).get().toObject<EventMerkleProof>()
        assertArrayEquals(encodedEventData, eventProof.eventData)

        // Trying to send withdrawRequest without setting blockchain RID
        logger.info { "\tcan't withdraw without setting blockchain RID" }
        val exception = assertThrows<TransactionException> {
            bridge.withdrawRequest(
                    eventProof.web3EventData(),
                    eventProof.web3EventProof(),
                    eventProof.web3BlockHeader(),
                    eventProof.web3Signatures(),
                    eventProof.web3Signers(),
                    eventProof.web3ExtraProofData()
            ).send()
        }
        assertEquals(exception.message!!.contains("TokenBridge: blockchain rid is not set"), true)
        bridge.setBlockchainRid(Bytes32(eifBcRid.data)).send()

        // Updating validators
        logger.info { "\tcan't withdraw using the confirmation proof built before the validator list was changed" }
        updateValidatorsInPostchain()
        updateValidatorsInValidatorContract()
        val exception2 = assertThrows<TransactionException> {
            bridge.withdrawRequest(
                    eventProof.web3EventData(),
                    eventProof.web3EventProof(),
                    eventProof.web3BlockHeader(),
                    eventProof.web3Signatures(),
                    eventProof.web3Signers(),
                    eventProof.web3ExtraProofData()
            ).send()
        }
        assertEquals(exception2.message!!.contains("TokenBridge: block signature is invalid"), true)

        // Building a new withdrawal confirmation proof
        logger.info { "\tbuilding a new withdrawal confirmation proof using the new validator list" }
        val eventBlockHeight = blockQuery.query("get_event_block_height",
                gtv("eventHash" to gtv(eventHash.toHex()))
        ).get().asInteger()

        val blockRid = nodes[0].getRestApiModel(eifBcRid)?.getBlock(eventBlockHeight, true)!!.rid
        val signature0 = nodes[0].getRestApiModel(eifBcRid)?.confirmBlock(BlockRid(blockRid))!!
        assertThat(cryptoSystem.verifyDigest(blockRid, signature0.toSignature())).isEqualTo(true)
        val signature1 = nodes[1].getRestApiModel(eifBcRid)?.confirmBlock(BlockRid(blockRid))!!
        assertThat(cryptoSystem.verifyDigest(blockRid, signature1.toSignature())).isEqualTo(true)
        val signatures = listOf(
                EifSignature(
                        encodeSignatureWithV(blockRid, Signature(signature0.subjectID, signature0.data)),
                        getEthereumAddress(signature0.subjectID)),
                EifSignature(
                        encodeSignatureWithV(blockRid, Signature(signature1.subjectID, signature1.data)),
                        getEthereumAddress(signature1.subjectID))
        ).sortedBy { it.pubkey.toHex() }
        val eventProof2 = eventProof.copy(blockWitness = signatures)

        logger.info { "\trequesting withdrawal using the new confirmation proof" }
        val receipt = bridge.withdrawRequest(
                eventProof2.web3EventData(),
                eventProof2.web3EventProof(),
                eventProof2.web3BlockHeader(),
                eventProof2.web3Signatures(),
                eventProof2.web3Signers(),
                eventProof2.web3ExtraProofData()
        ).send()
        // wait some seconds to allow evm node to mine some new blocks
        // that mature enough to withdraw requesting fund
        Awaitility.await().atMost(Duration.TEN_SECONDS).until {
            val block = web3j.ethGetBlockByNumber(DefaultBlockParameter.valueOf(receipt.blockNumber.add(BigInteger.TWO)), false).send()
            block.block != null
        }
        bridge.withdraw(Bytes32(eventHash), Address(evmAddress)).send()
        userBalance = testToken.balanceOf(Address(evmAddress)).send()
        assertEquals(userBalance.value, initialMint - totalDepositedAmount + withdrawAmount)
    }

    @Test
    @Order(90)
    fun `verify correct withdraw request`() {

        var anomalyDetectors = mapOf<String, AnomalyDetector>()

        anomalyDetectorsManager.start()

        Awaitility.await()
                .atMost(Duration.TEN_SECONDS)
                .untilAsserted {
                    anomalyDetectors = anomalyDetectorsManager.getAnomalyDetectors()
                    assertThat(anomalyDetectors.size).isEqualTo(1)
                }
        val anomalyDetector = anomalyDetectors.values.first()

        Awaitility.await()
                .atMost(Duration.ONE_MINUTE)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted {

                    logger.info { "Waiting for anomaly detector..." }

                    assertThat(anomalyDetector.logsProcessed).isEqualTo(1L)
                    assertThat(anomalyDetector.logsVerified).isEqualTo(1L)
                    assertThat(anomalyDetector.anomalyDetectorStatus).isEqualTo(AnomalyDetectorStatus.NO_ANOMALIES)
                }
    }

    @Test
    @Order(120)
    fun `detect paused bridge`() {

        // It will of course be unpaused to start with
        assertThat(bridge.paused().send().value).isFalse()

        // Pause it
        val pauseResponse = bridge.pause().send()
        assertThat(pauseResponse.isStatusOK).isTrue()

        // Verify it being paused
        assertThat(bridge.paused().send().value).isTrue()

        // Verify the anomaly detector understand it is paused
        val anomalyDetector = anomalyDetectorsManager.getAnomalyDetectors().values.first()
        Awaitility.await()
                .atMost(Duration.TEN_SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted {
                    assertThat(anomalyDetector.anomalyDetectorStatus).isEqualTo(AnomalyDetectorStatus.PAUSED)
                }
    }

    @Test
    @Order(121)
    fun `detect unpaused bridge`() {

        // It will of course be paused to start with
        assertThat(bridge.paused().send().value).isTrue()

        // Unpause it
        val unpauseResponse = bridge.unpause().send()
        assertThat(unpauseResponse.isStatusOK).isTrue()

        // Verify it being unpaused
        assertThat(bridge.paused().send().value).isFalse()

        // Verify the anomaly detector understand it is unpaused
        val anomalyDetector = anomalyDetectorsManager.getAnomalyDetectors().values.first()
        Awaitility.await()
                .atMost(Duration.TEN_SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted {
                    assertThat(anomalyDetector.anomalyDetectorStatus).isEqualTo(AnomalyDetectorStatus.NO_ANOMALIES)
                }
    }

    @Test
    @Order(150)
    fun `remove chain from monitor`() {

        Awaitility.await()
                .atMost(Duration.TEN_SECONDS)
                .untilAsserted {
                    assertThat(anomalyDetectorsManager.getAnomalyDetectors().size).isEqualTo(1)
                }

        awaitTransaction(ecClient, ecChainId) { it.addOperation("remove_anomaly_detection", gtv(eifBcRid)) }

        Awaitility.await()
                .atMost(Duration(BRIDGE_CHAIN_REFRESH_INTERVAL_SECONDS * 2, TimeUnit.SECONDS))
                .untilAsserted {
                    assertThat(anomalyDetectorsManager.getAnomalyDetectors().size).isEqualTo(0)
                }

        val restStatus = restStatus()
        assertThat(restStatus.size).isEqualTo(0)

        // Make sure the client is shutdown
        assertThat(web3jClientsManager.hasNetworkClient(networkId)).isFalse()
    }

//    @Test
//    @Order(900)
//    fun `keep intances alive`() {
//        Awaitility.await().atMost(Duration.FOREVER).pollInterval(5, TimeUnit.SECONDS).untilAsserted {
//            logger.info { "Keeping alive...." }
//            fail("keep alive")
//        }
//    }

    private fun restApiHttpHandler(): HttpHandler {
        return ClientFilters.AcceptGZip(GzipCompressionMode.Streaming()).then(ApacheClient(HttpClients.custom()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setRedirectsEnabled(false)
                        .setCookieSpec(StandardCookieSpec.IGNORE)
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(5_000L))
                        .setResponseTimeout(Timeout.ofMilliseconds(10_000))
                        .build()).build()))
    }

    private fun sealBlock() {
        currentBlockHeight += 1
        buildBlock(DEFAULT_CHAIN_IID)
        assertEquals(currentBlockHeight, getLastHeight(node))
    }

    private fun enqueueTx(data: ByteArray) {
        try {
            // In a multi-node environment, we need to add tx to each node's txQueue
            // to ensure that the tx will be included in the next block.
            nodes.forEach {
                val engine = it.getBlockchainInstance(DEFAULT_CHAIN_IID).blockchainEngine
                val tx = engine.getConfiguration().getTransactionFactory().decodeTransaction(data)
                engine.getTransactionQueue().enqueue(tx)
            }
        } catch (e: Exception) {
            logger.error(e) { "Can't enqueue tx" }
        }
    }

    protected fun updateValidatorsInPostchain() {
        val lastBlockHeight = getLastHeight(node)
        // replica node[1] becomes a validator
        val newSigners = listOf(0, 1).associateWith { nodes[it].pubKey.hexStringToByteArray() }
        val newConfig = loadEifBlockchainConfig().asDict().toMutableMap()
        newConfig[KEY_SIGNERS] = gtv(newSigners.values.map { gtv(it) })

        // adding a new config at height (last + 2)
        // (last + 1) will not work because (last + 1) config already loaded by afterCommit handler
        val newConfigHeight = lastBlockHeight + 2
        val newRawConfig = GtvEncoder.encodeGtv(gtv(newConfig))
        addDappBlockchainConfiguration(eifChainId, newRawConfig, newConfigHeight)

        // building at least two blocks to build a block with new signers
        sealBlock()
        sealBlock()

        // asserting that new config is loaded
        val witness = node.blockQueries().getBlockAtHeight(newConfigHeight).get()!!.witness as BaseBlockWitness
        assertArrayEquals(
                listOf(nodes[0].pubKey, nodes[1].pubKey).sorted().toTypedArray(),
                witness.getSignatures().map { it.subjectID.toHex() }.sorted().toTypedArray()
        )
        blockQuery = node.getBlockchainInstance().blockchainEngine.getBlockQueries()
    }

    // This function emulates validator list updating by TransactionSubmitter
    protected fun updateValidatorsInValidatorContract() {
        // getting the current validator list
        val currentValidators = getContractValidatorList()

        // asserting that node0 is the only validator
        assertArrayEquals(arrayOf(node0EvmAddress), currentValidators.toTypedArray())

        // setting the new validator list: [node0, node1]
        val newValidators = listOf(node0EvmAddress, node1EvmAddress).sortedBy { it.value }.toTypedArray()
        val validatorsArg = DynamicArray(Address::class.java, *newValidators)
        validator.updateValidators(validatorsArg).send()

        // asserting that new validator list is set
        assertArrayEquals(newValidators, getContractValidatorList().toTypedArray())
    }

    private fun getContractValidatorList(): List<Address> {
        val count = validator.validatorCount.send().value.toLong()
        val validators = mutableListOf<Address>()
        (0 until count).forEach {
            validators.add(validator.validators(Uint256(it)).send())
        }
        return validators
    }

    private fun getLastWithdrawal(beneficiary: ByteArray): Map<String, Gtv> {
        val all = blockQuery.query("eif.ft4.get_erc20_withdrawal", gtv(
                "network_id" to gtv(networkId),
                "token_address" to gtv(testTokenAddress),
                "beneficiary" to gtv(beneficiary)
        )).get().asArray()

        return all.map { it.asDict() }.maxByOrNull { it["serial"]!!.asInteger() }!!
    }

    private fun loadEifBlockchainConfig(): Gtv = GtvMLParser.parseGtvML(
            javaClass.getResource("/net/postchain/eif/blockchain_eif_it.xml")!!.readText()
    )

    private fun loadMockedEconomyBlockchainConfig(
            eifBcRid: BlockchainRid,
            contractAddress: String): Gtv {

        val config = javaClass.getResource("/net/postchain/eif/blockchain_mocked_ec_it.xml")!!.readText()
                .replace("EIF_BCRID", eifBcRid.toHex())
                .replace("EVM_NETWORK_ID", networkId.toString())
                .replace("BRIDGE_CONTRACT", contractAddress)

        return GtvMLParser.parseGtvML(config)
    }

    private fun restStatus(): List<AnomalyDetectorStatusResponse> {
        val response = restApiHttpHandler.invoke(Request(Method.GET, "http://localhost:${appConfig.restApiConfig.port}/status"))
        assertThat(response.status).isEqualTo(Status.OK)
        return statusBody(response)
    }
}