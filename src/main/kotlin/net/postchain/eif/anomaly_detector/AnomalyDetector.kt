package net.postchain.eif.anomaly_detector

import io.reactivex.disposables.Disposable
import mu.withLoggingContext
import net.postchain.client.core.BlockDetail
import net.postchain.client.core.PostchainClient
import net.postchain.client.impl.PostchainClientImpl.Companion.logger
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.toHex
import net.postchain.eif.anomaly_detector.config.LogProcessorConfig
import net.postchain.eif.anomaly_detector.config.TimeoutConfig
import net.postchain.eif.anomaly_detector.evm.EvmLogProcessor
import net.postchain.eif.anomaly_detector.evm.Web3jClient
import net.postchain.eif.anomaly_detector.evm.Web3jRequestHandler
import net.postchain.eif.contracts.TokenBridge
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.protocol.core.methods.response.Log
import org.web3j.tx.Contract
import java.math.BigInteger
import java.util.concurrent.TimeUnit


enum class AnomalyDetectorStatus {
    NO_ANOMALIES,
    ANOMALY_FOUND,
    PAUSE_TRANSACTION_SENT,
    PAUSED,
}

class AnomalyDetector(
        private val timeoutConfig: TimeoutConfig,
        private val logProcessorConfig: LogProcessorConfig,
        private val web3jRequestHandler: Web3jRequestHandler,
        private val web3jClient: Web3jClient,
        private val postchainClient: PostchainClient,
        val tokenBridgeContractAddresses: String,
        val networkId: Long,
) {

    private var logSubscription: Disposable? = null
    private var blockchainRid = postchainClient.config.blockchainRid

    // State
    var anomaliesCache = AnomaliesCache()
    var anomalyDetectorStatus = AnomalyDetectorStatus.NO_ANOMALIES
        private set
    var logsProcessed = 0L
        private set
    var anomaliesDetected = 0L
        private set
    var logsVerified = 0L
        private set
    var lastBlockNumberProcessed = BigInteger.ZERO
        private set

    // Log subscriptions
    private val eventsToRead = listOf(
            TokenBridge.PAUSED_EVENT,
            TokenBridge.UNPAUSED_EVENT,
            TokenBridge.WITHDRAWREQUEST_EVENT
    )
    private lateinit var evmLogProcessor: EvmLogProcessor

    fun start() {

        val currentBlockNumber = web3jRequestHandler.sendWeb3jRequest { it.ethBlockNumber() }.blockNumber

        logInfo { "Starting anomaly detector for bcRid ${blockchainRid.toHex()} monitoring bridge contract ${tokenBridgeContractAddresses} from block number $currentBlockNumber" }

        anomalyDetectorStatus = setInitialDetectorStatus()

        setupLogProcessorJob(currentBlockNumber.toLong())
    }

    private fun setupLogProcessorJob(currentBlockNumber: Long) {
        evmLogProcessor = EvmLogProcessor(
                logProcessorConfig,
                tokenBridgeContractAddresses,
                eventsToRead,
                currentBlockNumber,
                web3jClient,
                ::onLog
        )
    }

    private fun queryIsTokenBridgePaused(): Boolean {

        return web3jClient.withTokenBridge(tokenBridgeContractAddresses) {
            it.paused()
        }.value
    }

    private fun onLog(log: Log) {

        val event = evmLogProcessor.getEventType(log)
        lastBlockNumberProcessed = log.blockNumber

        logger.info { "Received log event $log." }

        when (event) {
            TokenBridge.PAUSED_EVENT -> bridgePaused()
            TokenBridge.UNPAUSED_EVENT -> bridgeUnpaused()
            TokenBridge.WITHDRAWREQUEST_EVENT -> withdrawRequestEvent(log)
        }
    }

    private fun withdrawRequestEvent(log: Log) {

        logsProcessed++

        val parameters = Contract.staticExtractEventParameters(TokenBridge.WITHDRAWREQUEST_EVENT, log)

        val heightParam = parameters.nonIndexedValues[1]
        val bridParam = parameters.nonIndexedValues[2]
        if (heightParam is Uint256 && bridParam is Bytes32) {

            val logVerification = LogVerification(log, heightParam.value.toLong(), bridParam.value)

            verifyHeight(logVerification, true)
        } else {
            logError { "Unexpected log parameters: ${parameters.indexedValues} and ${parameters.nonIndexedValues}" }
            throw ProgrammerMistake("Unexpected log parameters: ${parameters.indexedValues} and ${parameters.nonIndexedValues}")
        }
    }

    private fun bridgePaused() {

        logWarn { "Bridge was paused" }

        anomalyDetectorStatus = AnomalyDetectorStatus.PAUSED
    }

    private fun bridgeUnpaused() {

        logWarn { "Bridge was UNpaused" }

        anomalyDetectorStatus = AnomalyDetectorStatus.NO_ANOMALIES // TODO what state? Do we have anomalies?
    }

    private fun verifyHeight(logVerification: LogVerification, retry: Boolean) {

        val blockAtHeight = postchainClient.blockAtHeight(logVerification.height)

        if (blockAtHeight == null) {

            logWarn { "Height ${logVerification.height} not found in node" }

            if (retry) {
                logWarn { "Retry in ${getLogTime(timeoutConfig.missingHeightRetryDelay)}" }

                anomaliesCache.schedule(logVerification, LogVerificationStatus.RETRY, timeoutConfig.missingHeightRetryDelay + 1000) {
                    verifyHeight(logVerification, false)
                }
            } else {

                pauseTokenBridge()
            }
        } else {

            checkAnomaly(logVerification, blockAtHeight)
        }
    }

    private fun checkAnomaly(logVerification: LogVerification, blockAtHeight: BlockDetail) {

        if (logVerification.brid.contentEquals(blockAtHeight.rid.data)) {

            logsVerified++

            logInfo { "Verified transaction on height ${logVerification.height} and brid ${logVerification.brid.toHex()}" }

        } else {

            logError { "Anomaly detected - log index ${logVerification.log.logIndex} referees to block at height ${logVerification.height} with brid ${logVerification.brid.toHex()} but local brid is ${blockAtHeight.rid.toHex()} - token bridge contract will be paused in ${getLogTime(timeoutConfig.pauseDelay)}" }

            anomaliesCache.schedule(logVerification, LogVerificationStatus.ANOMALY, timeoutConfig.pauseDelay) {
                pauseTokenBridge()
            }

            anomalyDetectorStatus = AnomalyDetectorStatus.ANOMALY_FOUND
            anomaliesDetected++
        }
    }

    private fun pauseTokenBridge() {

        logError { "Pausing token bridge..." }

        if (isTokenBridgePaused()) {
            // TODO already paused
        } else {
            // TODO pause the contract - not if we already sent a pause?

            anomalyDetectorStatus = AnomalyDetectorStatus.PAUSE_TRANSACTION_SENT
        }
    }

    private fun isTokenBridgePaused(): Boolean {
        return anomalyDetectorStatus == AnomalyDetectorStatus.PAUSED
    }

    private fun getLogTime(milliseconds: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(milliseconds)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(milliseconds) -
                TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(milliseconds))
        return "${hours}h ${minutes}m"
    }

    fun stop() {
        logSubscription?.apply { dispose() }
        anomaliesCache.cancelTimer()
        evmLogProcessor.shutdown()
    }

    private fun setInitialDetectorStatus() = if (queryIsTokenBridgePaused())
        AnomalyDetectorStatus.PAUSED
    else
        AnomalyDetectorStatus.NO_ANOMALIES

    private fun logInfo(msg: () -> String) = log(logger::info, msg)
    private fun logWarn(msg: () -> String) = log(logger::error, msg)
    private fun logError(msg: () -> String) = log(logger::error, msg)

    private fun log(levelFunction: (() -> Any?) -> Unit, msg: () -> Any?) {
        withLoggingContext("bcRid" to blockchainRid.toShortHex()) {
            levelFunction(msg)
        }
    }
}