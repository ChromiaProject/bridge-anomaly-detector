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
import net.postchain.eif.anomaly_detector.evm.Web3jClientsManager
import net.postchain.eif.anomaly_detector.evm.Web3jRequestHandler
import net.postchain.eif.anomaly_detector.evm.Web3jServiceFactory.buildServices
import okhttp3.internal.toImmutableMap
import java.util.concurrent.TimeUnit
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
                            delay(TimeUnit.SECONDS.toMillis(appConfig.bridgeChainRefreshIntervalSeconds))
                        } catch (e: CancellationException) {
                            break
                        }
                    }
                }
    }

    fun getAnomalyDetectors(): Map<String, AnomalyDetector> = anomalyDetectors.toImmutableMap()

    private fun setupAndStopDetectors() {

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

                val web3jRequestHandler = createWeb3jRequestHandler(evmConfig.rpcUrls)
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
        }
    }

    private fun getBlockchainsToStop(blockchainsToMonitor: List<Blockchain>) =
            anomalyDetectors.filter { (key, _) -> blockchainsToMonitor.none { it.blockchainRid.toHex() == key } }

    private fun getBlockchainsToStart(blockchainsToMonitor: List<Blockchain>) =
        blockchainsToMonitor.filter { !anomalyDetectors.containsKey(it.blockchainRid.toHex()) }

    fun stop() {

        bridgeMonitorJob.cancel()
    }

    private fun getBlockchainsToMonitor(appConfig: AppConfig): List<Blockchain> {


        // TODO either EC or TXSC

        val postchainClient = createPostchainClient(appConfig.nodeUrl, appConfig.bcRid.hexStringToByteArray())
        return postchainClient.getBlockchainsWithBridgeAndAnomalyDetection()
                .map { Blockchain(it.blockchainRid.data, it.evmNetworkId, it.bridgeContract) }
    }

    private fun createPostchainClient(nodeUrl: EndpointPool, bcRid: ByteArray) =
            PostchainClientProviderImpl().createClient(
                    PostchainClientConfig(
                            BlockchainRid(bcRid),
                            nodeUrl,
                            listOf()
                    ))

    private fun createWeb3jRequestHandler(rpcUrls: List<String>): Web3jRequestHandler {

        val web3jServices = buildServices(rpcUrls, 10_000L, 10_000L, 10_000L)
        val web3jRequestHandler = Web3jRequestHandler(web3jServices)

        return web3jRequestHandler
    }
}