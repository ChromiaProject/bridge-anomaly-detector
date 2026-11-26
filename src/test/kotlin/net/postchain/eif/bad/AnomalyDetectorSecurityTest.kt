package net.postchain.eif.bad

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import net.postchain.client.config.PostchainClientConfig
import net.postchain.client.core.PostchainClient
import net.postchain.common.BlockchainRid
import net.postchain.eif.bad.config.AnomalyConfig
import net.postchain.eif.bad.config.EvmClientConfig
import net.postchain.eif.bad.config.EvmConfig
import net.postchain.eif.bad.config.LogProcessorConfig
import net.postchain.eif.bad.evm.DynamicEIP1559GasProvider
import net.postchain.eif.bad.evm.EvmLogProcessor
import net.postchain.eif.bad.evm.TokenBridgeClient
import net.postchain.eif.bad.evm.Web3jRequestHandler
import net.postchain.eif.contracts.TokenBridge
import okhttp3.OkHttpClient
import org.apache.commons.configuration2.MapConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.web3j.abi.EventEncoder
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Bool
import org.web3j.abi.datatypes.DynamicBytes
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.crypto.Credentials
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.JsonRpc2_0Web3j
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.Response
import org.web3j.protocol.core.methods.response.EthFeeHistory
import org.web3j.protocol.core.methods.response.EthGasPrice
import org.web3j.protocol.core.methods.response.Log
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.protocol.http.HttpService
import org.web3j.tx.gas.DefaultGasProvider
import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AnomalyDetectorSecurityTest {

    private fun newWithdrawRequestLog(
            blockHex: String = "0x64",
            height: Long = 42L,
            brid: ByteArray = ByteArray(32) { 0x11 },
            contractAddress: String = "0x1234567890abcdef1234567890abcdef12345678",
    ): Log {
        val eventSig = EventEncoder.encode(TokenBridge.WITHDRAWREQUEST_EVENT)
        val beneficiaryTopic = "0x0000000000000000000000001111111111111111111111111111111111111111"
        val tokenTopic = "0x0000000000000000000000002222222222222222222222222222222222222222"
        // Non-indexed params of WithdrawRequest(address indexed beneficiary, address indexed token, uint256 value, uint256 networkId/height, bytes32 blockRid, ...)
        // Match TokenBridge.WITHDRAWREQUEST_EVENT non-indexed types dynamically
        val nonIndexedParams = TokenBridge.WITHDRAWREQUEST_EVENT.nonIndexedParameters.mapIndexed { idx, typeRef ->
            when {
                idx == 1 -> Uint256(BigInteger.valueOf(height))
                idx == 2 -> Bytes32(brid)
                typeRef.type == Uint256::class.java -> Uint256(BigInteger.valueOf(1000L))
                typeRef.type == Bytes32::class.java -> Bytes32(ByteArray(32) { 0x22 })
                typeRef.type == Address::class.java -> Address("0x3333333333333333333333333333333333333333")
                typeRef.type == DynamicBytes::class.java -> DynamicBytes(ByteArray(0))
                else -> Uint256(BigInteger.ZERO)
            }
        }
        val encoded = FunctionEncoder.encodeConstructor(nonIndexedParams)
        return Log().apply {
            address = contractAddress
            setBlockNumber(blockHex)
            setLogIndex("0x0")
            transactionHash = "0xdeadbeef"
            topics = listOf(eventSig, beneficiaryTopic, tokenTopic).take(1 + TokenBridge.WITHDRAWREQUEST_EVENT.indexedParameters.size)
            data = "0x$encoded"
        }
    }

    private fun mockPostchainClient(): PostchainClient {
        val postchainClient = mock<PostchainClient>()
        whenever(postchainClient.config).thenReturn(
                PostchainClientConfig(BlockchainRid.ZERO_RID, endpointPool = net.postchain.client.request.EndpointPool.singleUrl("http://localhost:7740"))
        )
        return postchainClient
    }

    @Test
    fun `EvmLogProcessor fails closed and preserves offset across repeated RPC and Postchain failures`() {
        val web3jHandler = mock<Web3jRequestHandler>()
        val processor = EvmLogProcessor(
                LogProcessorConfig(readOffset = 0, maxBlockRangePerRequest = 100, delayWhenNoNewBlock = 100),
                emptyArray(),
                web3jHandler,
        )

        val addrA = "0x1234567890abcdef1234567890abcdef12345678"
        val addrB = "0xabcdef1234567890abcdef1234567890abcdef12"
        val logA = Log().apply {
            address = addrA
            setBlockNumber("0x64") // 100
        }
        val logB = Log().apply {
            address = addrB
            setBlockNumber("0x65") // 101
        }

        val subsField = EvmLogProcessor::class.java.getDeclaredField("contractSubscriptions")
        subsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val subs = subsField.get(processor) as MutableMap<String, (Log) -> Unit>

        // Contract A succeeds, Contract B fails repeatedly -> offset must NOT advance on any failure
        val attempts = AtomicInteger(0)
        subs[addrA] = { /* ok */ }
        subs[addrB] = {
            attempts.incrementAndGet()
            throw RuntimeException("Postchain node unavailable")
        }

        repeat(3) {
            assertThrows<RuntimeException> {
                processor.processLogEventsAndUpdateOffsets(listOf(logA, logB), 101L)
            }
            assertThat(processor.lastReadBlockNumber).isEqualTo(-1L)
        }
        assertThat(attempts.get()).isEqualTo(3)

        // Once node recovers, offset advances cleanly to 101
        subs[addrB] = { /* ok */ }
        processor.processLogEventsAndUpdateOffsets(listOf(logA, logB), 101L)
        assertThat(processor.lastReadBlockNumber).isEqualTo(101L)

        // Subsequent failure at block 105 keeps lastReadBlockNumber pinned at 101
        val logFail = Log().apply {
            address = addrB
            setBlockNumber("0x69") // 105
        }
        subs[addrB] = { throw IllegalStateException("Transient RPC timeout") }
        assertThrows<IllegalStateException> {
            processor.processLogEventsAndUpdateOffsets(listOf(logFail), 105L)
        }
        assertThat(processor.lastReadBlockNumber).isEqualTo(101L)
    }

    @Test
    fun `AnomaliesCache survives uncaught exceptions and handles concurrent scheduling safely`() {
        val cache = AnomaliesCache()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val totalGoodTasks = 16
            val completedLatch = CountDownLatch(totalGoodTasks)
            val scheduledLatch = CountDownLatch(24)

            for (i in 0 until 24) {
                pool.submit {
                    val log = Log().apply { transactionHash = "0x%04x".format(i) }
                    val lv = LogVerification(log, 100L + i, ByteArray(32) { i.toByte() })
                    val shouldFail = (i % 3 == 0) // 8 throwing tasks, 16 good tasks
                    val status = if (i % 2 == 0) LogVerificationStatus.RETRY else LogVerificationStatus.ANOMALY
                    cache.schedule(lv, status, (10L + (i % 5) * 10L)) {
                        if (shouldFail) {
                            if (i % 6 == 0) {
                                throw AssertionError("Simulated fatal error in task $i")
                            } else {
                                throw RuntimeException("Simulated runtime exception in task $i")
                            }
                        } else {
                            completedLatch.countDown()
                        }
                    }
                    // Concurrent reads while scheduling/executing must never throw ConcurrentModificationException
                    cache.getAnomalies()
                    cache.getAnomalyTasks()
                    scheduledLatch.countDown()
                }
            }

            assertThat(scheduledLatch.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(completedLatch.await(5, TimeUnit.SECONDS)).isTrue()

            // Also verify scheduleAction survives an exception and runs subsequent actions
            val actionLatch = CountDownLatch(1)
            cache.scheduleAction(5L) { throw RuntimeException("action failure") }
            cache.scheduleAction(20L) { actionLatch.countDown() }
            assertThat(actionLatch.await(5, TimeUnit.SECONDS)).isTrue()

            // Give the single-thread executor a moment to finish cleanup
            Thread.sleep(50)
            assertThat(cache.getAnomalies()).isEmpty()
            assertThat(cache.getAnomalyTasks()).isEmpty()
        } finally {
            pool.shutdownNow()
            cache.cancelTimer()
        }
    }

    @Test
    fun `EvmClientConfig uses seconds directly with sane 30s defaults`() {
        val defaultConfig = EvmClientConfig(MapConfiguration(emptyMap<String, Any>()))
        assertThat(defaultConfig.connectTimeoutSeconds).isEqualTo(30L)
        assertThat(defaultConfig.readTimeoutSeconds).isEqualTo(30L)
        assertThat(defaultConfig.writeTimeoutSeconds).isEqualTo(30L)

        val customConfig = EvmClientConfig(MapConfiguration(mapOf(
                "evm_client.connect_timeout_seconds" to 15L,
                "evm_client.read_timeout_seconds" to 25L,
                "evm_client.write_timeout_seconds" to 20L,
        )))
        assertThat(customConfig.connectTimeoutSeconds).isEqualTo(15L)
        assertThat(customConfig.readTimeoutSeconds).isEqualTo(25L)
        assertThat(customConfig.writeTimeoutSeconds).isEqualTo(20L)
    }

    @Test
    fun `TokenBridgeClient wires 30s default and custom EvmClientConfig timeouts into OkHttpClient`() {
        val dummyCreds = Credentials.create("0x0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
        val evmConfig = mock<EvmConfig>()
        whenever(evmConfig.rpcUrls).thenReturn(listOf("http://127.0.0.1:8545"))
        whenever(evmConfig.credentials).thenReturn(dummyCreds)

        fun extractOkHttp(client: TokenBridgeClient): OkHttpClient {
            val web3jField = TokenBridgeClient::class.java.getDeclaredField("web3jClients")
            web3jField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val web3js = web3jField.get(client) as List<Web3j>
            val rpc = web3js.first() as JsonRpc2_0Web3j
            val svcField = JsonRpc2_0Web3j::class.java.getDeclaredField("web3jService")
            svcField.isAccessible = true
            val httpService = svcField.get(rpc) as HttpService
            val httpClientField = HttpService::class.java.getDeclaredField("httpClient")
            httpClientField.isAccessible = true
            return httpClientField.get(httpService) as OkHttpClient
        }

        val defaultClient = TokenBridgeClient(1L, evmConfig)
        try {
            val ok = extractOkHttp(defaultClient)
            assertThat(ok.connectTimeoutMillis).isEqualTo(30_000)
            assertThat(ok.readTimeoutMillis).isEqualTo(30_000)
            assertThat(ok.writeTimeoutMillis).isEqualTo(30_000)
        } finally {
            defaultClient.close()
        }

        val customCfg = EvmClientConfig(MapConfiguration(mapOf(
                "evm_client.connect_timeout_seconds" to 12L,
                "evm_client.read_timeout_seconds" to 18L,
                "evm_client.write_timeout_seconds" to 24L,
        )))
        val customClient = TokenBridgeClient(1L, evmConfig, customCfg)
        try {
            val ok = extractOkHttp(customClient)
            assertThat(ok.connectTimeoutMillis).isEqualTo(12_000)
            assertThat(ok.readTimeoutMillis).isEqualTo(18_000)
            assertThat(ok.writeTimeoutMillis).isEqualTo(24_000)
        } finally {
            customClient.close()
        }
    }

    @Test
    fun `DynamicEIP1559GasProvider handles normal fees, gas spikes, priority floor, and full fallback chain`() {
        val web3j = mock<Web3j>()
        val feeReq = mock<Request<Any, EthFeeHistory>>()
        @Suppress("UNCHECKED_CAST")
        whenever(web3j.ethFeeHistory(5, DefaultBlockParameterName.LATEST, listOf(50.0)))
                .thenReturn(feeReq as Request<*, EthFeeHistory>)

        // 1. Normal fees: baseFee = 2 gwei, reward median = 3 gwei -> maxFee = 2*2 + 3 = 7 gwei
        val normalResp = EthFeeHistory().apply {
            result = EthFeeHistory.FeeHistory().apply {
                setBaseFeePerGas(listOf("0x3b9aca00", "0x77359400")) // 1 gwei, 2 gwei
                setReward(listOf(listOf("0x77359400"), listOf("0xb2d05e00"))) // 2 gwei, 3 gwei
            }
        }
        whenever(feeReq.send()).thenReturn(normalResp)
        val provider = DynamicEIP1559GasProvider(web3j, chainId = 1L)
        assertThat(provider.getMaxPriorityFeePerGas()).isEqualTo(BigInteger.valueOf(3_000_000_000L))
        assertThat(provider.getMaxFeePerGas()).isEqualTo(BigInteger.valueOf(7_000_000_000L))

        // 2. Congestion gas spike: baseFee = 200 gwei, reward = 50 gwei -> maxFee = 450 gwei
        val spikeResp = EthFeeHistory().apply {
            result = EthFeeHistory.FeeHistory().apply {
                setBaseFeePerGas(listOf("0x2e90edd000")) // 200 gwei
                setReward(listOf(listOf("0xba43b7400"))) // 50 gwei
            }
        }
        whenever(feeReq.send()).thenReturn(spikeResp)
        assertThat(provider.getMaxPriorityFeePerGas()).isEqualTo(BigInteger.valueOf(50_000_000_000L))
        assertThat(provider.getMaxFeePerGas()).isEqualTo(BigInteger.valueOf(450_000_000_000L))

        // 3. Tiny reward below floor (0.1 gwei) -> clamped up to MIN_PRIORITY_FEE_WEI (2 gwei)
        val lowRewardResp = EthFeeHistory().apply {
            result = EthFeeHistory.FeeHistory().apply {
                setBaseFeePerGas(listOf("0x3b9aca00")) // 1 gwei
                setReward(listOf(listOf("0x5f5e100"))) // 0.1 gwei
            }
        }
        whenever(feeReq.send()).thenReturn(lowRewardResp)
        assertThat(provider.getMaxPriorityFeePerGas()).isEqualTo(DynamicEIP1559GasProvider.MIN_PRIORITY_FEE_WEI)
        assertThat(provider.getMaxFeePerGas()).isEqualTo(BigInteger.valueOf(4_000_000_000L))

        // 4. eth_feeHistory RPC error -> falls back to eth_gasPrice with 1.25x boost (20 gwei -> 25 gwei)
        val rpcErrResp = EthFeeHistory().apply {
            error = Response.Error(-32601, "Method not found")
        }
        whenever(feeReq.send()).thenReturn(rpcErrResp)
        val gasPriceResp = EthGasPrice().apply { result = "0x4a817c800" } // 20 gwei
        val gasReq = mock<Request<Any, EthGasPrice>>()
        whenever(gasReq.send()).thenReturn(gasPriceResp)
        @Suppress("UNCHECKED_CAST")
        whenever(web3j.ethGasPrice()).thenReturn(gasReq as Request<*, EthGasPrice>)
        assertThat(provider.getGasPrice()).isEqualTo(BigInteger.valueOf(25_000_000_000L))

        // 5. Both eth_feeHistory and eth_gasPrice throw -> falls back safely to DefaultGasProvider.GAS_PRICE
        whenever(feeReq.send()).thenThrow(RuntimeException("feeHistory failed"))
        whenever(gasReq.send()).thenThrow(RuntimeException("gasPrice failed"))
        assertThat(provider.getMaxFeePerGas()).isEqualTo(DefaultGasProvider.GAS_PRICE)
        assertThat(provider.getGasPrice()).isEqualTo(DefaultGasProvider.GAS_PRICE)
        assertThat(provider.getGasLimit()).isGreaterThan(BigInteger.ZERO)
    }

    @Test
    fun `AnomalyDetector fails closed on initial and retry Postchain errors`() {
        val postchainClient = mockPostchainClient()
        val tokenBridgeClient = mock<TokenBridgeClient>()
        val pauseSentLatch = CountDownLatch(1)

        val callCount = AtomicInteger(0)
        whenever(tokenBridgeClient.withTokenBridge<Any>(any(), any())).thenAnswer {
            when (callCount.incrementAndGet()) {
                1, 2 -> Bool(false) // initial status check & pre-pause check
                3 -> {
                    pauseSentLatch.countDown()
                    TransactionReceipt()
                }
                else -> Bool(true)
            }
        }

        val eventSig = EventEncoder.encode(TokenBridge.WITHDRAWREQUEST_EVENT)
        val detector = AnomalyDetector(
                AnomalyConfig(pauseOnAnomaly = true, pauseDelay = 0, missingHeightRetryDelay = 20, maxRandomDelay = 0),
                mapOf(eventSig to TokenBridge.WITHDRAWREQUEST_EVENT),
                tokenBridgeClient,
                postchainClient,
                "0x1234567890abcdef1234567890abcdef12345678",
        )
        try {
            // Case A: Initial blockAtHeight throws -> propagates so EvmLogProcessor does NOT advance offset
            whenever(postchainClient.blockAtHeight(42L)).thenThrow(RuntimeException("Postchain unreachable"))
            assertThrows<RuntimeException> {
                detector.onLog(newWithdrawRequestLog(height = 42L))
            }

            // Case B: Initial blockAtHeight returns null (schedules retry), retry throws -> fail-closed pauses bridge
            whenever(postchainClient.blockAtHeight(99L))
                    .thenReturn(null)
                    .thenThrow(RuntimeException("Postchain still unreachable on retry"))

            detector.onLog(newWithdrawRequestLog(height = 99L))
            assertThat(pauseSentLatch.await(5, TimeUnit.SECONDS)).isTrue()
            Thread.sleep(50)
            assertThat(detector.anomalyDetectorStatus).isEqualTo(AnomalyDetectorStatus.PAUSE_TRANSACTION_SENT)
            assertThat(detector.anomaliesCache.getAnomalies()).isNotEmpty()
        } finally {
            detector.stop()
        }
    }

    @Test
    fun `AnomalyDetector retries pauseTokenBridge with backoff and handles exhaustion and already-paused`() {
        val postchainClient = mockPostchainClient()
        val tokenBridgeClient = mock<TokenBridgeClient>()
        val callCount = AtomicInteger(0)
        val pausedLatch = CountDownLatch(1)

        whenever(tokenBridgeClient.withTokenBridge<Any>(any(), any())).thenAnswer {
            when (callCount.incrementAndGet()) {
                1, 2, 4 -> Bool(false)
                3 -> throw RuntimeException("underpriced tx")
                5 -> {
                    pausedLatch.countDown()
                    TransactionReceipt()
                }
                else -> Bool(true)
            }
        }

        val detector = AnomalyDetector(
                AnomalyConfig(pauseOnAnomaly = true, pauseDelay = 0, missingHeightRetryDelay = 0, maxRandomDelay = 0),
                emptyMap(),
                tokenBridgeClient,
                postchainClient,
                "0x1234567890abcdef1234567890abcdef12345678",
        )
        try {
            detector.pauseTokenBridge(attempt = 1, retryBackoffMs = 20L, maxAttempts = 3)
            assertThat(pausedLatch.await(5, TimeUnit.SECONDS)).isTrue()
            Thread.sleep(50)
            assertThat(detector.anomalyDetectorStatus).isEqualTo(AnomalyDetectorStatus.PAUSE_TRANSACTION_SENT)
        } finally {
            detector.stop()
        }

        // Exhaustion case: all 3 attempts fail -> remains ANOMALY_FOUND_NOT_PAUSED and stops retrying
        val failingBridgeClient = mock<TokenBridgeClient>()
        val failCalls = AtomicInteger(0)
        val exhaustedLatch = CountDownLatch(3)
        whenever(failingBridgeClient.withTokenBridge<Any>(any(), any())).thenAnswer {
            val n = failCalls.incrementAndGet()
            if (n == 1 || n % 2 == 0) {
                Bool(false) // initial check + each attempt's paused() query
            } else {
                exhaustedLatch.countDown()
                throw RuntimeException("RPC down")
            }
        }
        val exhaustDetector = AnomalyDetector(
                AnomalyConfig(pauseOnAnomaly = true, pauseDelay = 0, missingHeightRetryDelay = 0, maxRandomDelay = 0),
                emptyMap(),
                failingBridgeClient,
                postchainClient,
                "0x1234567890abcdef1234567890abcdef12345678",
        )
        try {
            exhaustDetector.pauseTokenBridge(attempt = 1, retryBackoffMs = 15L, maxAttempts = 3)
            assertThat(exhaustedLatch.await(5, TimeUnit.SECONDS)).isTrue()
            Thread.sleep(100)
            assertThat(exhaustDetector.anomalyDetectorStatus).isEqualTo(AnomalyDetectorStatus.ANOMALY_FOUND_NOT_PAUSED)
            // 1 initial + 3 * (paused query + pause call) = 7 total calls
            assertThat(failCalls.get()).isEqualTo(7)
        } finally {
            exhaustDetector.stop()
        }

        // Already paused on-chain: skips sending pause() tx and marks PAUSED
        val alreadyPausedClient = mock<TokenBridgeClient>()
        val alreadyPausedCalls = AtomicInteger(0)
        whenever(alreadyPausedClient.withTokenBridge<Any>(any(), any())).thenAnswer {
            val n = alreadyPausedCalls.incrementAndGet()
            if (n == 1) Bool(false) else Bool(true)
        }
        val alreadyPausedDetector = AnomalyDetector(
                AnomalyConfig(pauseOnAnomaly = true, pauseDelay = 0, missingHeightRetryDelay = 0, maxRandomDelay = 0),
                emptyMap(),
                alreadyPausedClient,
                postchainClient,
                "0x1234567890abcdef1234567890abcdef12345678",
        )
        try {
            alreadyPausedDetector.pauseTokenBridge(attempt = 1, retryBackoffMs = 10L, maxAttempts = 3)
            assertThat(alreadyPausedDetector.anomalyDetectorStatus).isEqualTo(AnomalyDetectorStatus.PAUSED)
            assertThat(alreadyPausedCalls.get()).isEqualTo(2)
        } finally {
            alreadyPausedDetector.stop()
        }
    }
}
