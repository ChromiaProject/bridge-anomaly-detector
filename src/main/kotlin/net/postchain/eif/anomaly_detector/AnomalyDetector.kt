package net.postchain.eif.anomaly_detector

import io.reactivex.disposables.Disposable
import net.postchain.client.core.BlockDetail
import net.postchain.client.core.PostchainClient
import net.postchain.client.impl.PostchainClientImpl.Companion.logger
import net.postchain.common.toHex
import net.postchain.eif.anomaly_detector.config.TimeoutConfig
import net.postchain.eif.anomaly_detector.evm.Web3jRequestHandler
import net.postchain.eif.contracts.TokenBridge
import org.web3j.abi.EventEncoder
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.EthFilter
import org.web3j.protocol.core.methods.response.Log
import org.web3j.tx.Contract
import java.util.Timer
import java.util.concurrent.TimeUnit
import kotlin.concurrent.timerTask

class AnomalyDetector(
        private val timeoutConfig: TimeoutConfig,
        private val web3jRequestHandler: Web3jRequestHandler,
        private val postchainClient: PostchainClient,
        private val tokenBridgeContractAddresses: String
) {

    private var timer = Timer()
    private var logSubscription: Disposable? = null
    var logsProcessed = 0L
        private set
    var anomaliesDetected = 0L
        private set
    var brigedPaused = false
        private set

    fun start() {

        logger.info { "Starting anomaly detector" }

        val blockNumber = web3jRequestHandler.sendWeb3jRequest { it.ethBlockNumber() }.blockNumber

        logger.info { "Block number: $blockNumber" }

        val eventsToRead = listOf(TokenBridge.WITHDRAWREQUEST_EVENT)
        val eventMap = eventsToRead.associateBy(EventEncoder::encode)
        val eventSignatures = eventMap.keys.toTypedArray()

        val filter = EthFilter(
                DefaultBlockParameterName.EARLIEST, // DefaultBlockParameter.valueOf(blockNumber),
                DefaultBlockParameterName.LATEST,
                tokenBridgeContractAddresses)
                .addOptionalTopics(*eventSignatures)

        logSubscription = web3jRequestHandler.getClient().ethLogFlowable(filter).subscribe(::onLog)
    }

    private fun onLog(log: Log) {

        logger.info { "Log: ${log.logIndex}" }
        logsProcessed++

        val parameters = Contract.staticExtractEventParameters(TokenBridge.WITHDRAWREQUEST_EVENT, log)

        // TODO extract brid and height
//        val brid = parameters.nonIndexedValues[1].
//        val height = parameters.nonIndexedValues[2].
        val brid = "0x00".toByteArray()
        val height = 1L

        verifyHeight(brid, height, true)
    }

    private fun verifyHeight(brid: ByteArray, height: Long, retry: Boolean) {

        val blockAtHeight = postchainClient.blockAtHeight(height)

        if (blockAtHeight == null) { // TODO or will blockAtHeight throw an exception?
            // TODO queue this for reverification in 24h (configured)

            logger.warn { "Height $height not found in node" }

            if (retry) {
                logger.warn { "Retry in ${getLogTime(timeoutConfig.missingHeightTimeoutInHours)}" }

                timer.schedule(timerTask {
                    verifyHeight(brid, height, false)
                }, timeoutConfig.missingHeightTimeoutInHours)
            } else {

                pauseTokenBridge()
            }
        } else {

            checkAnomaly(brid, blockAtHeight)
        }
    }

    private fun checkAnomaly(brid: ByteArray, blockAtHeight: BlockDetail) {

        if (!brid.contentEquals(blockAtHeight.rid.data)) {

            logger.error { "Anomaly detected - log XXX referees to nonexistent block, brid: ${brid.toHex()}, height: TODO - token bridge contract will be paused in ${getLogTime(timeoutConfig.delayPauseInMinutes)}" }

            timer.schedule(timerTask {
                pauseTokenBridge()
            }, timeoutConfig.delayPauseInMinutes)

            anomaliesDetected++
        }
    }

    private fun pauseTokenBridge() {

        logger.error { "Pausing token bridge..." }

        if (isTokenBridgeActive()) {
            // TODO pause the contract

            brigedPaused = true
        } else {
            // TODO already paused
        }
    }

    private fun isTokenBridgeActive(): Boolean {
        // TODO check if the bridge is active or paused
        return true
    }

    private fun getLogTime(milliseconds: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(milliseconds)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(milliseconds) -
                TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(milliseconds))
        return "${hours}h ${minutes}m"
    }

    fun stop() {
        logSubscription?.apply { dispose() }
        timer.cancel()
    }
}