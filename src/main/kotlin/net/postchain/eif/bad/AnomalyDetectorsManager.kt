package net.postchain.eif.bad

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.slf4j.MDCContext
import net.postchain.chain0.common.queries.getClusterBlockchains
import net.postchain.chain0.common.queries.getNodeData
import net.postchain.chain0.common.queries.listClustersOfNode
import net.postchain.chain0.economy_chain.getBlockchainsWithBridgeAndAnomalyDetection
import net.postchain.chain0.economy_chain_in_directory_chain.getEconomyChainRid
import net.postchain.chain0.lib.hbridge.erc20.getBridgeContracts
import net.postchain.chain0.token_chain_in_directory_chain.getTokenChainRid
import net.postchain.client.config.PostchainClientConfig
import net.postchain.client.core.PostchainQuery
import net.postchain.client.impl.PostchainClientImpl.Companion.logger
import net.postchain.client.impl.PostchainClientProviderImpl
import net.postchain.client.impl.TryNextOnErrorRequestStrategyFactory
import net.postchain.client.request.EndpointPool
import net.postchain.common.BlockchainRid
import net.postchain.eif.bad.config.AppConfig
import net.postchain.eif.bad.config.EvmClientConfig
import net.postchain.eif.bad.evm.TokenBridgeClientsManager
import net.postchain.eif.bad.evm.Web3jRequestHandler
import net.postchain.eif.bad.evm.Web3jServiceFactory.buildServices
import okhttp3.internal.toImmutableMap
import kotlin.coroutines.cancellation.CancellationException
import net.postchain.cm.cm_api.ClusterManagementImpl
import net.postchain.common.exception.UserMistake
import net.postchain.common.toHex
import net.postchain.d1.client.ChromiaClientProvider
import net.postchain.d1.cluster.ClusterManagement
import net.postchain.eif.bad.evm.EvmLogProcessor
import net.postchain.eif.contracts.TokenBridge
import org.web3j.abi.EventEncoder

class AnomalyDetectorsManager(
        private val appConfig: AppConfig,
        private val tokenBridgeClientsManager: TokenBridgeClientsManager,
        private val clusterManagementProvider: (PostchainQuery) -> ClusterManagement
) {

    constructor(
            appConfig: AppConfig,
            tokenBridgeClientsManager: TokenBridgeClientsManager,
    ) : this(appConfig, tokenBridgeClientsManager, ::ClusterManagementImpl)

    companion object {
        const val SYSTEM_CLUSTER = "system"
    }

    private val anomalyDetectors = mutableMapOf<BlockchainBridge, AnomalyDetector>()
    private val logProcessors = mutableMapOf<Long, EvmLogProcessor>() // network id -> log processor
    private lateinit var bridgeMonitorJob: Job
    private lateinit var directoryChainBrid: BlockchainRid
    private lateinit var economyChainBrid: BlockchainRid
    private var tokenChainBrid: BlockchainRid? = null
    private val eventMap = listOf(
            TokenBridge.PAUSED_EVENT,
            TokenBridge.UNPAUSED_EVENT,
            TokenBridge.WITHDRAWREQUEST_EVENT
    ).associateBy { EventEncoder.encode(it) }

    fun start() {
        initializeBrids()

        bridgeMonitorJob =
                CoroutineScope(Dispatchers.IO).launch(CoroutineName("anomaly-detectors-manager") + MDCContext()) {

                    logger.info { "Monitoring blockchains bridge updates..." }

                    while (isActive) {
                        try {
                            setupAndStopDetectors()
                        } catch (e: CancellationException) {
                            break
                        } catch (e: Exception) {
                            logger.error(e) { "Failed to process start/stop bridge anomaly detectors: ${e.message}" }
                        }

                        delay(appConfig.bridgeChainRefreshInterval)
                    }
                }
    }

    private fun initializeBrids() {
        val client = createPostchainClient(appConfig.nodeUrl, BlockchainRid.ZERO_RID)
        directoryChainBrid = client.getBlockchainRID(0)
        val directoryChainClient = createPostchainClient(appConfig.nodeUrl, directoryChainBrid)
        economyChainBrid = directoryChainClient.getEconomyChainRid()?.let { BlockchainRid(it) }
                ?: throw UserMistake("Network does not have any economy chain running")
        tokenChainBrid = directoryChainClient.getTokenChainRid().let { if (it.size == 32) BlockchainRid(it) else null }
    }

    fun getAnomalyDetectors(): Map<BlockchainBridge, AnomalyDetector> = anomalyDetectors.toImmutableMap()

    private fun setupAndStopDetectors() {

        logger.debug { "Read bridges to monitor from blockchain" }

        val blockchainsToMonitor = getBlockchainsToMonitor(appConfig)
        val detectorsToStop = getBlockchainsToStop(blockchainsToMonitor)
        val blockchainsToStart = getBlockchainsToStart(blockchainsToMonitor).filter { appConfig.bypassBlockchainSyncCheck || blockchainSyncCheck(it) }

        stopDetectors(detectorsToStop)
        startDetectors(blockchainsToStart)
    }

    private fun blockchainSyncCheck(blockchain: BlockchainBridge): Boolean {
        try {
            val blockchainPostchainClient = createPostchainClient(appConfig.nodeUrl, blockchain.blockchainRid)
            val currentBlockHeight = blockchainPostchainClient.currentBlockHeight()
            val directoryChainPostchainClient = createPostchainClient(appConfig.nodeUrl, directoryChainBrid)
            val clusterManagement = clusterManagementProvider(directoryChainPostchainClient)
            val ourApiUrl = directoryChainPostchainClient.getNodeData(appConfig.nodePubKey).apiUrl
            val blockchainApiUrls = clusterManagement.getBlockchainApiUrls(blockchain.blockchainRid)
                    .filter { it != ourApiUrl }
            val highestBlockheightOverNodes = blockchainApiUrls
                    .map { createPostchainClient(it, blockchain.blockchainRid) }
                    .map { it.currentBlockHeight() }
                    .max()

            if (highestBlockheightOverNodes - currentBlockHeight > appConfig.blockchainSyncMargin) {
                logger.info { "Anomaly detector won't start for brid: ${blockchain.blockchainRid.toHex()} because blockchain is syncing." }
                return false
            }
            return true
        } catch (e: Exception) {
            logger.error(e) { "Failed to sync check blockchain: ${blockchain.blockchainRid.toHex()} with error: ${e.message}" }
            return false
        }
    }

    private fun startDetectors(blockchainsToStart: List<BlockchainBridge>) {

        for (blockchainToMonitor in blockchainsToStart) {

            logger.info { "Setting up anomaly detector for bcRid ${blockchainToMonitor.blockchainRid.toHex()}, network ${blockchainToMonitor.evmNetworkId} and bridge contract ${blockchainToMonitor.bridgeContract}" }

            val client = tokenBridgeClientsManager.getClient(blockchainToMonitor.evmNetworkId)

            val evmLogProcessor = try {
                getOrCreateLogProcessor(blockchainToMonitor.evmNetworkId)
            } catch (e: UserMistake) {
                logger.error("Failed to set up detector for bcRid ${blockchainToMonitor.blockchainRid.toHex()}, network ${blockchainToMonitor.evmNetworkId} and bridge contract ${blockchainToMonitor.bridgeContract}: ${e.message}")
                continue
            }
            val postchainClient = createPostchainClient(appConfig.nodeUrl, blockchainToMonitor.blockchainRid)

            val anomalyDetector = AnomalyDetector(
                    appConfig.anomalyConfig,
                    eventMap,
                    client,
                    postchainClient,
                    blockchainToMonitor.bridgeContract,
            )
            anomalyDetectors[blockchainToMonitor] = anomalyDetector
            evmLogProcessor.addContractSubscription(blockchainToMonitor.bridgeContract, anomalyDetector::onLog)
        }
    }

    private fun getOrCreateLogProcessor(networkId: Long): EvmLogProcessor = logProcessors.getOrPut(networkId) {
        val evmConfig = appConfig.evmConfig[networkId]
        if (evmConfig?.rpcUrls == null || evmConfig.rpcUrls.isEmpty()) {
            throw UserMistake("No rpc urls set for network $networkId")
        }
        EvmLogProcessor(
                evmConfig.logProcessorConfig,
                eventMap.keys.toTypedArray(),
                createWeb3jRequestHandler(appConfig.evmClientConfig, evmConfig.rpcUrls, networkId)
        )
    }

    private fun stopDetectors(detectorsToStop: Map<BlockchainBridge, AnomalyDetector>) {

        detectorsToStop.forEach{ (blockchainBridge, detector) ->

            logger.info { "Stopping detector for bridge: $blockchainBridge" }

            detector.stop()
            anomalyDetectors.remove(blockchainBridge)
            logProcessors[blockchainBridge.evmNetworkId]?.removeContractSubscription(blockchainBridge.bridgeContract)

            // Close client if this was the last detector for this network
            if (anomalyDetectors.keys.none { it.evmNetworkId == blockchainBridge.evmNetworkId }) {
                tokenBridgeClientsManager.closeClient(blockchainBridge.evmNetworkId)
            }
        }
    }

    private fun getBlockchainsToStop(blockchainsToMonitor: List<BlockchainBridge>) =
            anomalyDetectors.filterKeys { it !in blockchainsToMonitor }

    private fun getBlockchainsToStart(blockchainsToMonitor: List<BlockchainBridge>) =
            blockchainsToMonitor.filter { !anomalyDetectors.containsKey(it) }

    fun stop() {

        bridgeMonitorJob.cancel()
        anomalyDetectors.values.forEach { it.stop() }
    }

    private fun getBlockchainsToMonitor(appConfig: AppConfig): List<BlockchainBridge> {
        val directoryChainClient = createPostchainClient(appConfig.nodeUrl, directoryChainBrid)
        val nodeClusters = directoryChainClient.listClustersOfNode(appConfig.nodePubKey)

        val chainsToMonitor = mutableListOf<BlockchainBridge>()
        if (nodeClusters.contains(SYSTEM_CLUSTER)) {
            val ecClient = createPostchainClient(appConfig.nodeUrl, economyChainBrid)
            val supportedNetworks = appConfig.evmConfig.keys.toList()
            chainsToMonitor += supportedNetworks.flatMap { networkId ->
                ecClient.getBridgeContracts(networkId)
                        .map { BlockchainBridge(economyChainBrid, networkId, it.contractAddress.data.toHex().prefixedHex()) }
            }
            if (tokenChainBrid != null) {
                val tokenChainClient = createPostchainClient(appConfig.nodeUrl, tokenChainBrid!!)
                chainsToMonitor += supportedNetworks.flatMap { networkId ->
                    tokenChainClient.getBridgeContracts(networkId)
                            .map { BlockchainBridge(tokenChainBrid!!, networkId, it.contractAddress.data.toHex().prefixedHex()) }
                }
            }
        }

        val runningDappChains = nodeClusters.filter { it != SYSTEM_CLUSTER }
                .flatMap { directoryChainClient.getClusterBlockchains(it) }
                .map { BlockchainRid(it) }

        if (runningDappChains.isNotEmpty()) {
            val ecClient = ChromiaClientProvider(clusterManagementProvider(directoryChainClient)).blockchain(economyChainBrid)
            chainsToMonitor += ecClient.getBlockchainsWithBridgeAndAnomalyDetection(includeExpired = false)
                    .map { BlockchainBridge(BlockchainRid(it.blockchainRid), it.evmNetworkId, it.bridgeContract.prefixedHex()) }
                    .filter { runningDappChains.contains(it.blockchainRid) }
        }

        return chainsToMonitor
    }

    private fun createPostchainClient(nodeUrl: String, bcRid: BlockchainRid) =
            PostchainClientProviderImpl().createClient(
                    PostchainClientConfig(
                            bcRid,
                            EndpointPool.singleUrl(nodeUrl),
                            listOf(),
                            requestStrategy = TryNextOnErrorRequestStrategyFactory()
                    ))

    private fun createWeb3jRequestHandler(evmClientConfig: EvmClientConfig, rpcUrls: List<String>, networkId: Long): Web3jRequestHandler {

        val web3jServices = buildServices(rpcUrls, evmClientConfig.connectTimeoutSeconds, evmClientConfig.readTimeoutSeconds, evmClientConfig.writeTimeoutSeconds)
        val web3jRequestHandler = Web3jRequestHandler(web3jServices, networkId)

        return web3jRequestHandler
    }

    private fun String.prefixedHex(): String {
        if (!this.startsWith("0x")) {
            return "0x$this"
        }
        return this
    }
}
