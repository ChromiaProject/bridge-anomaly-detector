package net.postchain.eif.anomaly_detector

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import net.postchain.client.core.PostchainClient
import net.postchain.client.transaction.TransactionBuilder
import net.postchain.common.BlockchainRid
import net.postchain.common.data.Hash
import net.postchain.common.hexStringToByteArray
import net.postchain.common.tx.TransactionStatus
import net.postchain.crypto.KeyPair
import net.postchain.crypto.Secp256K1CryptoSystem
import net.postchain.crypto.SigMaker
import net.postchain.crypto.devtools.KeyPairHelper
import net.postchain.devtools.ManagedModeTest
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvArray
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvInteger
import net.postchain.gtv.GtvNull
import net.postchain.gtx.GtxBuilder
import org.awaitility.Awaitility.await
import org.awaitility.Duration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.testcontainers.containers.DockerComposeContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.web3j.crypto.Credentials
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import org.web3j.tx.FastRawTransactionManager
import org.web3j.tx.TransactionManager
import org.web3j.tx.gas.DefaultGasProvider
import org.web3j.tx.response.PollingTransactionReceiptProcessor
import java.io.File
import java.math.BigInteger

enum class EvmType {
    GETH, BSC
}

data class AccountRegister(
        var accountId: ByteArray = ByteArray(32),
        val privKey: ByteArray,
        val pubkey: ByteArray,
        val evmAddress: ByteArray,
        val balance: Long
)

enum class AuthType {
    S, M
}

val myCS = Secp256K1CryptoSystem()

class GethContainer : DockerComposeContainer<GethContainer>(File("src/test/resources/geth-compose/docker-compose.yml"))

class BscContainer : DockerComposeContainer<BscContainer>(File("src/test/resources/bsc-compose/docker-compose.yml"))

abstract class EifBaseIntegrationTest(evmType: EvmType, private val prependUrls: List<String> = listOf()) : ManagedModeTest() {

    val networkId = 1337L
    val gasProvider = DefaultGasProvider()
    protected val evmContainer: DockerComposeContainer<*> = when (evmType) {
        EvmType.GETH -> {
            GethContainer().withExposedService(
                    "geth", 8545,
                    Wait.forLogMessage(".*HTTP server started.*\\s", 1)
            )
        }

        EvmType.BSC -> {
            BscContainer().withExposedService(
                    "geth", 8545,
                    Wait.forLogMessage(".*HTTP server started.*\\s", 1)
            )
        }
    }
    val credentials = Credentials
            .create("0x53914554952e5473a54b211a31303078abde83b8128995785901eed28df3f610")
    val registerAccounts = mutableListOf<AccountRegister>()
    val snapshotHeights = mutableListOf<Long>()
    val tokenBridgeBinary = getBinaryFromArtifactResource("/artifacts/contracts/TokenBridge.sol/TokenBridge.json")
    val testTokenBinary = getBinaryFromArtifactResource("/artifacts/contracts/token/TestToken.sol/TestToken.json")
    val validatorBinary = getBinaryFromArtifactResource("/artifacts/contracts/Validator.sol/Validator.json")

    lateinit var web3j: Web3j
    lateinit var transactionManager: TransactionManager
    lateinit var evmRpcUrl: String

    @BeforeEach
    open fun setup() {

        evmContainer.start()

        evmRpcUrl = getRpcUrl()
        web3j = Web3j.build(HttpService(evmRpcUrl))

        transactionManager = FastRawTransactionManager(
                web3j,
                credentials,
                PollingTransactionReceiptProcessor(
                        web3j,
                        1000,
                        30
                )
        )

        var urls = mutableListOf(evmRpcUrl)
        urls.addAll(prependUrls)
        with(configOverrides) {
            setProperty("ethereum.urls", urls.joinToString(","))
        }
    }

    @AfterEach
    override fun tearDown() {
        super.tearDown() // Calling @AfterEach IntegrationTestSetup.tearDown()
        if (::web3j.isInitialized) web3j.shutdown()
        evmContainer.stop()
    }

    // get smart contract binary from resource
    fun getBinaryFromArtifactResource(resourcePath: String): String {
        val artifactFile = javaClass.getResource(resourcePath)?.readText()
        val artifactJson = GsonBuilder().create().fromJson(artifactFile, JsonObject::class.java)
        return artifactJson.get("bytecode").asString
    }

    /**
     * convert evm address to 32 bytes to compliance with EIF simple gtv encoder
     * @see SimpleGtvEncoder.encodeGtv
     */
    fun to32Bytes(address: String) = "000000000000000000000000$address".hexStringToByteArray()

    // Register asset on postchain
    fun registerAsset(
            tokenName: String,
            tokenSymbol: String,
            tokenDecimal: Long,
            tokenIconUrl: String,
            bcRid: BlockchainRid,
            sigMaker: SigMaker
    ): ByteArray {
        val b = GtxBuilder(bcRid, listOf(KeyPairHelper.pubKey(0)), myCS)
        b.addOperation(
                "ft4.admin.register_asset",
                gtv(tokenName), gtv(tokenSymbol), gtv(tokenDecimal), gtv(tokenIconUrl)
        )
        return b.finish()
                .sign(sigMaker)
                .buildGtx()
                .encode()
    }

    // Add new evm erc20 token
    fun addNewEvmErc20(
            tokenAddress: ByteArray,
            name: String,
            symbol: String,
            decimal: Long,
            bcRid: BlockchainRid,
            sigMaker: SigMaker
    ): ByteArray {
        val b = GtxBuilder(bcRid, listOf(KeyPairHelper.pubKey(0)), myCS)
        b.addOperation(
                "eif.ft4.add_new_evm_erc20",
                gtv(networkId),
                gtv(tokenAddress),
                gtv(name),
                gtv(symbol),
                gtv(decimal)
        )
        return b.finish()
                .sign(sigMaker)
                .buildGtx()
                .encode()
    }

    // Add new token mapping
    fun addTokenMapping(tokenAddress: ByteArray, assetId: Gtv, bcRid: BlockchainRid, sigMaker: SigMaker): ByteArray {
        val b = GtxBuilder(bcRid, listOf(KeyPairHelper.pubKey(0)), myCS)
        b.addOperation("eif.ft4.add_new_token_mapping", gtv(networkId), gtv(tokenAddress), assetId)
        return b.finish()
                .sign(sigMaker)
                .buildGtx()
                .encode()
    }

    // Register account on postchain
    fun registerAccount(
            userPubkey: ByteArray,
            userPriKey: ByteArray,
            userEVMAddress: ByteArray,
            sig: GtvArray,
            bcRid: BlockchainRid
    ): ByteArray {
        val auth = gtv(
                gtv(AuthType.S.ordinal.toLong()),
                gtv(GtvArray(arrayOf(gtv("A"), gtv("T"))), gtv(userPubkey)),
                GtvNull
        )

        val b = GtxBuilder(bcRid, listOf(userPubkey), myCS)
        b.addOperation("eif.evm.register_account", gtv(userEVMAddress), auth, sig)

        val signer = cryptoSystem.buildSigMaker(KeyPair(userPubkey, userPriKey))
        return b.finish()
                .sign(signer)
                .buildGtx()
                .encode()
    }

    // Withdraw ft3 token on postchain
    fun withdrawOnPostchain(
            userPubkey: ByteArray, userPriKey: ByteArray,
            authId: Gtv, tokenAddress: ByteArray,
            userEvmAddress: ByteArray, withdrawAmount: BigInteger, bcRid: BlockchainRid
    ): ByteArray {
        val b = GtxBuilder(bcRid, listOf(userPubkey), myCS)
        b.addOperation(
                "eif.ft4.bridge_ft_token_to_evm",
                authId,
                gtv(networkId),
                gtv(tokenAddress),
                gtv(userEvmAddress),
                gtv(withdrawAmount)
        )
        b.addOperation("nop", GtvInteger(System.currentTimeMillis()))
        val signer = cryptoSystem.buildSigMaker(KeyPair(userPubkey, userPriKey))
        return b.finish()
                .sign(signer)
                .buildGtx()
                .encode()
    }

    // Transfer ft3 token to another account
    fun transfer(
            userPubkey: ByteArray, userPriKey: ByteArray,
            accountId: Gtv, authDescriptorId: Hash, otherAccountId: Gtv,
            assetId: Gtv, transferAmount: BigInteger, bcRid: BlockchainRid
    ): ByteArray {
        val b = GtxBuilder(bcRid, listOf(userPubkey), myCS)
        b.addOperation("ft4.ft_auth", accountId, gtv(authDescriptorId))
        b.addOperation("ft4.transfer", otherAccountId, assetId, gtv(transferAmount))

        val signer = cryptoSystem.buildSigMaker(KeyPair(userPubkey, userPriKey))
        return b.finish()
                .sign(signer)
                .buildGtx()
                .encode()
    }

    fun getRegisterMessage(evmAddress: String, disposableKey: String) =
            "Create account for EVM wallet:\n${evmAddress}\n\nDisposable key:\n${disposableKey}"

    fun getRpcUrl(): String {
        val evmHost = evmContainer.getServiceHost("geth", 8545)
        val evmPort = evmContainer.getServicePort("geth", 8545)
        return "http://$evmHost:$evmPort"
    }

    fun awaitTransaction(client: PostchainClient, chainId: Long, ops: (TransactionBuilder) -> Unit) {

        val transactionBuilder = client.transactionBuilder()
        ops(transactionBuilder)
        val tx = transactionBuilder.post()

        await().atMost(Duration.TEN_SECONDS)
                .untilAsserted {
                    buildBlock(chainId)

                    val checkTxStatus = client.checkTxStatus(tx.txRid)
                    assertThat(checkTxStatus.status).isEqualTo(TransactionStatus.CONFIRMED)
                }

    }
}