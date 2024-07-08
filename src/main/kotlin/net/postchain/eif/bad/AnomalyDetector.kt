package net.postchain.eif.bad

import io.reactivex.disposables.Disposable
import mu.withLoggingContext
import net.postchain.client.core.BlockDetail
import net.postchain.client.core.PostchainClient
import net.postchain.client.impl.PostchainClientImpl.Companion.logger
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.toHex
import net.postchain.eif.bad.config.LogProcessorConfig
import net.postchain.eif.bad.config.AnomalyConfig
import net.postchain.eif.bad.evm.EvmLogProcessor
import net.postchain.eif.bad.evm.Web3jClient
import net.postchain.eif.bad.evm.Web3jRequestHandler
import net.postchain.eif.contracts.TokenBridge
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.protocol.core.methods.response.Log
import org.web3j.tx.Contract
import java.math.BigInteger
import java.util.concurrent.TimeUnit
import kotlin.random.Random


enum class AnomalyDetectorStatus {
    NO_ANOMALIES,
    ANOMALY_FOUND,
    PAUSE_TRANSACTION_SENT,
    PAUSED,
    ANOMALY_FOUND_NOT_PAUSED,
}

class AnomalyDetector(
        private val anomalyConfig: AnomalyConfig,
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

        anomalyDetectorStatus =
                if (anomaliesCache.getAnomalyTasks().isEmpty())
                    AnomalyDetectorStatus.NO_ANOMALIES
                else
                    AnomalyDetectorStatus.ANOMALY_FOUND
    }

    private fun verifyHeight(logVerification: LogVerification, retry: Boolean) {

        val blockAtHeight = postchainClient.blockAtHeight(logVerification.height)

        if (blockAtHeight == null) {

            logWarn { "Height ${logVerification.height} not found in node" }

            if (retry) {
                val delay = anomalyConfig.missingHeightRetryDelay + getRandomDelay()
                logWarn { "Retry in ${getLogTime(delay)}" }

                anomaliesCache.schedule(logVerification, LogVerificationStatus.RETRY, delay) {
                    verifyHeight(logVerification, false)
                }
            } else {

                pauseTokenBridge()
                anomaliesCache.addAnomaly(logVerification)
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

            val delay = anomalyConfig.pauseDelay + getRandomDelay()
            logError { "Anomaly detected - log index ${logVerification.log.logIndex} referees to block at height ${logVerification.height} with brid ${logVerification.brid.toHex()} but local brid is ${blockAtHeight.rid.toHex()} - token bridge contract will be paused in ${getLogTime(delay)}" }

            anomaliesCache.schedule(logVerification, LogVerificationStatus.ANOMALY, delay) {
                pauseTokenBridge()
                anomaliesCache.addAnomaly(logVerification)
            }

            anomalyDetectorStatus = AnomalyDetectorStatus.ANOMALY_FOUND
            anomaliesDetected++
        }
    }

    private fun pauseTokenBridge() {

        logError { "Pausing token bridge..." }

        if (isTokenBridgePaused()) {

            logInfo { "Bridge already paused" }
        } else if (!anomalyConfig.pauseOnAnomaly) {

            logWarn { "Bridge contract is NOT paused since it is disabled" }
            anomalyDetectorStatus = AnomalyDetectorStatus.ANOMALY_FOUND_NOT_PAUSED

        } else {
            web3jClient.withTokenBridge(tokenBridgeContractAddresses) {
                it.pause()
            }
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

    private fun getRandomDelay(): Long {
        if (anomalyConfig.maxRandomDelay > 0) {
            return Random.nextLong(0, anomalyConfig.maxRandomDelay)
        }
        return 0
    }
}
