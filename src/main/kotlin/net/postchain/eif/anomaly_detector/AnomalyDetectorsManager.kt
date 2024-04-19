package net.postchain.eif.anomaly_detector

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.slf4j.MDCContext
import net.postchain.client.config.PostchainClientConfig
import net.postchain.client.impl.PostchainClientImpl.Companion.logger
import net.postchain.client.impl.PostchainClientProviderImpl
import net.postchain.client.request.EndpointPool
import net.postchain.common.BlockchainRid
import net.postchain.common.hexStringToByteArray
import net.postchain.common.toHex
import net.postchain.economy.economy_chain.getBlockchainsWithBridgeAndAnomalyDetection
import net.postchain.eif.anomaly_detector.config.AppConfig
import net.postchain.eif.anomaly_detector.config.EvmClientConfig
import net.postchain.eif.anomaly_detector.evm.Web3jClientsManager
import net.postchain.eif.anomaly_detector.evm.Web3jRequestHandler
import net.postchain.eif.anomaly_detector.evm.Web3jServiceFactory.buildServices
import okhttp3.internal.toImmutableMap
import kotlin.coroutines.cancellation.CancellationException

class AnomalyDetectorsManager(
        private val appConfig: AppConfig,
        private val web3jClientsManager: Web3jClientsManager
) {

    private val anomalyDetectors = mutableMapOf<String, AnomalyDetector>()
    private lateinit var bridgeMonitorJob: Job

    fun start() {

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

    fun getAnomalyDetectors(): Map<String, AnomalyDetector> = anomalyDetectors.toImmutableMap()

    private fun setupAndStopDetectors() {

        logger.debug { "Read bridges to monitor from blockchain" }

        val blockchainsToMonitor = getBlockchainsToMonitor(appConfig)
        val detectorsToStop = getBlockchainsToStop(blockchainsToMonitor)
        val blockchainsToStart = getBlockchainsToStart(blockchainsToMonitor)

        stopDetectors(detectorsToStop)
        startDetectors(blockchainsToStart)
    }

    private fun startDetectors(blockchainsToStart: List<Blockchain>) {

        for (blockchainToMonitor in blockchainsToStart) {

            logger.info { "Setting up anomaly detector for bcRid ${blockchainToMonitor.blockchainRid.toHex()}, network ${blockchainToMonitor.evmNetworkId} and bridge contract ${blockchainToMonitor.bridgeContract}" }

            val client = web3jClientsManager.getClient(blockchainToMonitor.evmNetworkId)

            val evmConfig = appConfig.evmConfig[blockchainToMonitor.evmNetworkId]
            if (evmConfig?.rpcUrls == null || evmConfig.rpcUrls.isEmpty()) {
                logger.error { "No rpc urls set for network ${blockchainToMonitor.evmNetworkId}" }
            } else {

                val web3jRequestHandler = createWeb3jRequestHandler(appConfig.evmClientConfig, evmConfig.rpcUrls)
                val postchainClient = createPostchainClient(appConfig.nodeUrl, blockchainToMonitor.blockchainRid)

                val anomalyDetector = AnomalyDetector(
                        appConfig.timeoutConfig,
                        evmConfig.logProcessorConfig,
                        web3jRequestHandler,
                        client,
                        postchainClient,
                        blockchainToMonitor.bridgeContract,
                        blockchainToMonitor.evmNetworkId
                )
                anomalyDetectors[blockchainToMonitor.blockchainRid.toHex()] = anomalyDetector
                anomalyDetector.start()
            }
        }
    }

    private fun stopDetectors(detectorsToStop: Map<String, AnomalyDetector>) {

        detectorsToStop.forEach{ (bcRid, detector) ->

            logger.info { "Stopping detector for bcrid $bcRid" }

            detector.stop()
            anomalyDetectors.remove(bcRid)

            // Close client if this was the last detector for this network
            if (anomalyDetectors.values.none { it.networkId == detector.networkId }) {
                web3jClientsManager.closeClient(detector.networkId)
            }
        }
    }

    private fun getBlockchainsToStop(blockchainsToMonitor: List<Blockchain>) =
            anomalyDetectors.filter { (key, _) -> blockchainsToMonitor.none { it.blockchainRid.toHex() == key } }

    private fun getBlockchainsToStart(blockchainsToMonitor: List<Blockchain>) =
        blockchainsToMonitor.filter { !anomalyDetectors.containsKey(it.blockchainRid.toHex()) }

    fun stop() {

        bridgeMonitorJob.cancel()
        anomalyDetectors.values.forEach { it.stop() }
    }

    private fun getBlockchainsToMonitor(appConfig: AppConfig): List<Blockchain> {
        val postchainClient = createPostchainClient(appConfig.nodeUrl, appConfig.blockchainRid.hexStringToByteArray())
        return postchainClient.getBlockchainsWithBridgeAndAnomalyDetection()
                .map { Blockchain(it.blockchainRid.data, it.evmNetworkId, it.bridgeContract) }
    }

    private fun createPostchainClient(nodeUrl: String, bcRid: ByteArray) =
            PostchainClientProviderImpl().createClient(
                    PostchainClientConfig(
                            BlockchainRid(bcRid),
                            EndpointPool.singleUrl(nodeUrl),
                            listOf()
                    ))

    private fun createWeb3jRequestHandler(evmClientConfig: EvmClientConfig, rpcUrls: List<String>): Web3jRequestHandler {

        val web3jServices = buildServices(rpcUrls, evmClientConfig.connectTimeoutSeconds, evmClientConfig.readTimeoutSeconds, evmClientConfig.writeTimeoutSeconds)
        val web3jRequestHandler = Web3jRequestHandler(web3jServices)

        return web3jRequestHandler
    }
}