package net.postchain.eif.bad

import assertk.assertThat
import assertk.assertions.isEqualTo
import mu.KotlinLogging
import net.postchain.common.BlockchainRid
import net.postchain.crypto.KeyPair
import net.postchain.d1.cluster.ClusterManagement
import net.postchain.eif.bad.config.AppConfig
import net.postchain.eif.bad.evm.Web3jClientsManager
import net.postchain.eif.bad.rest.AnomaliesResponse
import net.postchain.eif.bad.rest.AnomalyDetectorStatusResponse
import net.postchain.eif.bad.rest.RestApi
import net.postchain.eif.bad.rest.anomaliesBody
import net.postchain.eif.bad.rest.statusBody
import net.postchain.eif.contracts.TestToken
import net.postchain.eif.contracts.TokenBridge
import net.postchain.eif.contracts.Validator
import net.postchain.images.directory1.EvmTestBase
import net.postchain.images.directory1.FTAuthenticator
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.cookie.StandardCookieSpec
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.core5.util.Timeout
import org.http4k.client.ApacheClient
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.filter.ClientFilters
import org.http4k.filter.GzipCompressionMode
import org.web3j.abi.datatypes.generated.Uint256
import java.math.BigInteger
import java.util.concurrent.TimeUnit

abstract class AnomalyDetectorTest : EvmTestBase("EvmEventReceiver_EvmContainerLogger") {

    companion object {
        val BRIDGE_CHAIN_REFRESH_INTERVAL_MS = TimeUnit.SECONDS.toMillis(1)

        // We re-use the eif event receiver that we build in postchain chromia
        // If this causes issues in the future we can build a dedicated event receiver instead
        const val EIF_EVENT_RECEIVER_CHAIN_NAME = "eif_event_receiver"

        const val EVM_TOKEN_BRIDGE_CHAIN_NAME = "evm_token_bridge"
        const val FAKE_EVM_TOKEN_BRIDGE_CHAIN_NAME = "fake_evm_token_bridge"

        const val APP_CLUSTER = "app_cluster"
        const val APP_CLUSTER_TAG = "app_cluster_tag"
        const val PROVIDER_1_AND_3_VS = "provider_1_and_3_vs"

        // Could be nice to expose in a clean way from postchain-chromia
        const val EIF_EC_EVENT_RECEIVER_BRID_PLACEHOLDER = "EIF_EC_EVENT_RECEIVER_BRID_PLACEHOLDER"
    }

    val logger = KotlinLogging.logger("test_logger")

    val networkId = 1337L
    val blockchainSyncMargin: Int = 2

    val initialMint = BigInteger("FF".repeat(32), 16)
    val depositNum = 5
    val depositAmount = BigInteger("AA".repeat(16), 16)
    var totalDepositedAmount = depositNum.toBigInteger() * depositAmount
    lateinit var validator: Validator
    lateinit var bridge: TokenBridge
    lateinit var testToken: TestToken
    lateinit var testTokenAddress: ByteArray
    lateinit var bridgeAddress: ByteArray
    lateinit var userBalance: Uint256
    lateinit var withdrawAmount: BigInteger
    lateinit var assetId: ByteArray
    lateinit var appConfig: AppConfig
    lateinit var web3jClientsManager: Web3jClientsManager
    lateinit var anomalyDetectorsManager: AnomalyDetectorsManager
    lateinit var restApi: RestApi

    lateinit var eventReceiverBrid: BlockchainRid
    lateinit var tokenBridgeBrid: BlockchainRid
    lateinit var fakeTokenBridgeBrid: BlockchainRid

    lateinit var aliceContainerName: String
    lateinit var aliceECAuthDescriptor: FTAuthenticator
    lateinit var aliceBridgeAuthDescriptor: FTAuthenticator

    // users
    // - admin
    val ft4AdminKeyPair = KeyPair.of( // node1
            "0350fe40766bc0ce8d08b3f5b810e49a8352fdd458606bd5fafe5acdcdc8ff3f57",
            "3132333435363738393031323334353637383930313233343536373839303131"
    )

    private fun restApiHttpHandler(): HttpHandler {
        return ClientFilters.AcceptGZip(GzipCompressionMode.Streaming()).then(ApacheClient(HttpClients.custom()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setRedirectsEnabled(false)
                        .setCookieSpec(StandardCookieSpec.IGNORE)
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(5_000L))
                        .setResponseTimeout(Timeout.ofMilliseconds(10_000))
                        .build()).build()))
    }

    fun restStatus(appConfig: AppConfig): List<AnomalyDetectorStatusResponse> {
        val response = restApiHttpHandler().invoke(Request(Method.GET, "http://localhost:${appConfig.restApiConfig.port}/status"))
        assertThat(response.status).isEqualTo(Status.OK)
        return statusBody(response)
    }

    fun restAnomalies(appConfig: AppConfig, bcRid: BlockchainRid): List<AnomaliesResponse> {
        val response = restApiHttpHandler().invoke(Request(Method.GET, "http://localhost:${appConfig.restApiConfig.port}/anomalies/${bcRid.toHex()}"))
        assertThat(response.status).isEqualTo(Status.OK)
        return anomaliesBody(response)
    }
}

// Test helper class to override api urls
class AnomalyContainerClusterManagement(private val delegate: ClusterManagement, private val restApiUrls: List<String>)
    : ClusterManagement by delegate {

    override fun getBlockchainApiUrls(blockchainRid: BlockchainRid): Collection<String> {
        return restApiUrls
    }
}

