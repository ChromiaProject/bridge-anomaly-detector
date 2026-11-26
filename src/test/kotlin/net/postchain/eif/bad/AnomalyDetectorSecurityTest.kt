package net.postchain.eif.bad

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
import net.postchain.client.config.PostchainClientConfig
import net.postchain.client.core.PostchainClient
import net.postchain.common.BlockchainRid
import net.postchain.eif.bad.config.AnomalyConfig
import net.postchain.eif.bad.config.EvmClientConfig
import net.postchain.eif.bad.config.LogProcessorConfig
import net.postchain.eif.bad.evm.DynamicEIP1559GasProvider
import net.postchain.eif.bad.evm.EvmLogProcessor
import net.postchain.eif.bad.evm.TokenBridgeClient
import net.postchain.eif.bad.evm.Web3jRequestHandler
import org.apache.commons.configuration2.MapConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.web3j.abi.datatypes.Bool
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.methods.response.EthFeeHistory
import org.web3j.protocol.core.methods.response.EthGasPrice
import org.web3j.protocol.core.methods.response.Log
import org.web3j.protocol.core.methods.response.TransactionReceipt
import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AnomalyDetectorSecurityTest {

    @Test
    fun `EvmLogProcessor fails closed and does not advance lastReadBlockNumber on log processing error`() {
        val web3jHandler = mock<Web3jRequestHandler>()
        val processor = EvmLogProcessor(
                LogProcessorConfig(readOffset = 0, maxBlockRangePerRequest = 100, delayWhenNoNewBlock = 100),
                emptyArray(),
                web3jHandler,
        )

        val log = Log().apply {
            address = "0x1234567890abcdef1234567890abcdef12345678"
            setBlockNumber("0x64") // 100
        }

        // Inject subscription directly without starting coroutine
        val subsField = EvmLogProcessor::class.java.getDeclaredField("contractSubscriptions")
        subsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val subs = subsField.get(processor) as MutableMap<String, (Log) -> Unit>
        subs[log.address.lowercase()] = { throw RuntimeException("Postchain node unavailable") }

        assertThrows<RuntimeException> {
            processor.processLogEventsAndUpdateOffsets(listOf(log), 100L)
        }
        assertThat(processor.lastReadBlockNumber).isEqualTo(-1L)

        // Once node recovers, offset advances cleanly
        subs[log.address.lowercase()] = { /* ok */ }
        processor.processLogEventsAndUpdateOffsets(listOf(log), 100L)
        assertThat(processor.lastReadBlockNumber).isEqualTo(100L)
    }

    @Test
    fun `AnomaliesCache survives uncaught exception in one task and still executes pending pause tasks`() {
        val cache = AnomaliesCache()
        try {
            val log1 = Log().apply { transactionHash = "0xaaa" }
            val log2 = Log().apply { transactionHash = "0xbbb" }
            val lv1 = LogVerification(log1, 10L, ByteArray(32) { 1 })
            val lv2 = LogVerification(log2, 11L, ByteArray(32) { 2 })

            val secondTaskRan = CountDownLatch(1)

            cache.schedule(lv1, LogVerificationStatus.RETRY, 10L) {
                throw RuntimeException("Simulated blockAtHeight failure")
            }
            cache.schedule(lv2, LogVerificationStatus.ANOMALY, 50L) {
                secondTaskRan.countDown()
            }

            assertThat(secondTaskRan.await(5, TimeUnit.SECONDS)).isTrue()
        } finally {
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
    fun `DynamicEIP1559GasProvider computes dynamic EIP-1559 fees from eth_feeHistory and falls back to eth_gasPrice`() {
        val web3j = mock<Web3j>()
        val feeHistoryResp = EthFeeHistory().apply {
            result = EthFeeHistory.FeeHistory().apply {
                setBaseFeePerGas(listOf("0x3b9aca00", "0x77359400")) // 1 gwei, 2 gwei
                setReward(listOf(listOf("0x77359400"), listOf("0xb2d05e00"))) // 2 gwei, 3 gwei
            }
        }
        val feeReq = mock<Request<Any, EthFeeHistory>>()
        whenever(feeReq.send()).thenReturn(feeHistoryResp)
        @Suppress("UNCHECKED_CAST")
        whenever(web3j.ethFeeHistory(5, DefaultBlockParameterName.LATEST, listOf(50.0)))
                .thenReturn(feeReq as Request<*, EthFeeHistory>)

        val provider = DynamicEIP1559GasProvider(web3j, chainId = 1L)
        // baseFee = 2 gwei, reward median = 3 gwei -> maxFee = 2*2 + 3 = 7 gwei
        assertThat(provider.getMaxPriorityFeePerGas()).isEqualTo(BigInteger.valueOf(3_000_000_000L))
        assertThat(provider.getMaxFeePerGas()).isEqualTo(BigInteger.valueOf(7_000_000_000L))

        // Fallback when eth_feeHistory throws
        whenever(feeReq.send()).thenThrow(RuntimeException("method not supported"))
        val gasPriceResp = EthGasPrice().apply { result = "0x4a817c800" } // 20 gwei
        val gasReq = mock<Request<Any, EthGasPrice>>()
        whenever(gasReq.send()).thenReturn(gasPriceResp)
        @Suppress("UNCHECKED_CAST")
        whenever(web3j.ethGasPrice()).thenReturn(gasReq as Request<*, EthGasPrice>)

        // 20 gwei * 1.25 = 25 gwei
        assertThat(provider.getGasPrice()).isEqualTo(BigInteger.valueOf(25_000_000_000L))
        assertThat(provider.getGasLimit()).isGreaterThan(BigInteger.ZERO)
    }

    @Test
    fun `AnomalyDetector retries pauseTokenBridge with backoff when initial pause call fails`() {
        val postchainClient = mock<PostchainClient>()
        whenever(postchainClient.config).thenReturn(
                PostchainClientConfig(BlockchainRid.ZERO_RID, endpointPool = net.postchain.client.request.EndpointPool.singleUrl("http://localhost:7740"))
        )
        val tokenBridgeClient = mock<TokenBridgeClient>()
        val callCount = AtomicInteger(0)
        val pausedLatch = CountDownLatch(1)

        // First call: initial queryIsTokenBridgePaused() -> false
        // Second call: pauseTokenBridge attempt 1 queryIsTokenBridgePaused() -> false
        // Third call: pauseTokenBridge attempt 1 pause() -> throws
        // Fourth call: pauseTokenBridge attempt 2 queryIsTokenBridgePaused() -> false
        // Fifth call: pauseTokenBridge attempt 2 pause() -> succeeds
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
            // Allow status assignment right after withTokenBridge returns
            Thread.sleep(50)
            assertThat(detector.anomalyDetectorStatus).isEqualTo(AnomalyDetectorStatus.PAUSE_TRANSACTION_SENT)
        } finally {
            detector.stop()
        }
    }
}
