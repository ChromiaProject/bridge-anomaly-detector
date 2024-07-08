package net.postchain.eif.bad

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import mu.KotlinLogging
import net.postchain.base.snapshot.SimpleDigestSystem
import net.postchain.common.BlockchainRid
import net.postchain.common.hexStringToByteArray
import net.postchain.crypto.Secp256K1CryptoSystem
import net.postchain.d1.cluster.ClusterManagement
import net.postchain.devtools.ManagedModeTest
import net.postchain.eif.SimpleGtvEncoder
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
import net.postchain.gtv.Gtv
import net.postchain.gtv.gtvml.GtvMLParser
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
import org.web3j.tx.gas.DefaultGasProvider
import java.math.BigInteger

abstract class AnomalyDetectorTest : ManagedModeTest() {

    val logger = KotlinLogging.logger("test_logger")

    val networkId = 1337L
    val myCS = Secp256K1CryptoSystem()
    val blockchainSyncMargin: Int = 2

    lateinit var ds: SimpleDigestSystem

    val gasProvider = DefaultGasProvider()
    val snapshotHeights = mutableListOf<Long>()
    val tokenBridgeBinary = getBinaryFromArtifactResource("/artifacts/contracts/TokenBridge.sol/TokenBridge.json")
    val testTokenBinary = getBinaryFromArtifactResource("/artifacts/contracts/token/TestToken.sol/TestToken.json")
    val validatorBinary = getBinaryFromArtifactResource("/artifacts/contracts/Validator.sol/Validator.json")

    val initialMint = BigInteger("FF".repeat(32), 16)
    val depositNum = 5
    val depositAmount = BigInteger("AA".repeat(16), 16)
    var totalDepositedAmount = depositNum.toBigInteger() * depositAmount
    lateinit var validator: Validator
    lateinit var bridge: TokenBridge
    lateinit var testToken: TestToken
    lateinit var testTokenAddress: ByteArray
    lateinit var userBalance: Uint256
    lateinit var withdrawAmount: BigInteger
    lateinit var accountNumber: Gtv
    lateinit var authId: Gtv
    lateinit var assetId: ByteArray
    lateinit var appConfig: AppConfig
    lateinit var web3jClientsManager: Web3jClientsManager
    lateinit var anomalyDetectorsManager: AnomalyDetectorsManager
    lateinit var restApi: RestApi

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

    fun restAnomalies(appConfig: AppConfig, bcRid: BlockchainRid): AnomaliesResponse {
        val response = restApiHttpHandler().invoke(Request(Method.GET, "http://localhost:${appConfig.restApiConfig.port}/anomalies/${bcRid.toHex()}"))
        assertThat(response.status).isEqualTo(Status.OK)
        return anomaliesBody(response)
    }

    // get smart contract binary from resource
    private fun getBinaryFromArtifactResource(resourcePath: String): String {
        val artifactFile = javaClass.getResource(resourcePath)?.readText()
        val artifactJson = GsonBuilder().create().fromJson(artifactFile, JsonObject::class.java)
        return artifactJson.get("bytecode").asString
    }

    /**
     * convert evm address to 32 bytes to compliance with EIF simple gtv encoder
     * @see SimpleGtvEncoder.encodeGtv
     */
    fun to32Bytes(address: String) = "000000000000000000000000$address".hexStringToByteArray()


    fun loadMockedEconomyBlockchainConfig(): Gtv =
            GtvMLParser.parseGtvML(javaClass.getResource("/net/postchain/eif/blockchain_mocked_ec_it.xml")!!.readText())

}

// Test helper class to override api urls
class AnomalyContainerClusterManagement(private val delegate: ClusterManagement, private val restApiUrls: List<String>)
    : ClusterManagement by delegate {

    override fun getBlockchainApiUrls(blockchainRid: BlockchainRid): Collection<String> {
        return restApiUrls
    }
}

