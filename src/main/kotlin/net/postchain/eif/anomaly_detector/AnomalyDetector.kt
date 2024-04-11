package net.postchain.eif.anomaly_detector

import io.reactivex.disposables.Disposable
import mu.withLoggingContext
import net.postchain.client.core.BlockDetail
import net.postchain.client.core.PostchainClient
import net.postchain.client.impl.PostchainClientImpl.Companion.logger
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.toHex
import net.postchain.eif.anomaly_detector.config.TimeoutConfig
import net.postchain.eif.anomaly_detector.evm.Web3jClient
import net.postchain.eif.anomaly_detector.evm.Web3jRequestHandler
import net.postchain.eif.contracts.TokenBridge
import org.web3j.abi.EventEncoder
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Bool
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.EthFilter
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.core.methods.response.Log
import org.web3j.tx.Contract
import java.math.BigInteger
import java.util.Timer
import java.util.concurrent.TimeUnit
import kotlin.concurrent.schedule
import kotlin.concurrent.timerTask


enum class AnomalyDetectorStatus {
    NO_ANOMALIES,
    ANOMALY_FOUND,
    PAUSE_TRANSACTION_SENT,
    PAUSED,
}

class AnomalyDetector(
        private val timeoutConfig: TimeoutConfig,
        private val web3jRequestHandler: Web3jRequestHandler,
        private val web3jClient: Web3jClient,
        private val postchainClient: PostchainClient,
        val tokenBridgeContractAddresses: String
) {

    private var timer = Timer()
    private var logSubscription: Disposable? = null
    private var blockchainRid = postchainClient.config.blockchainRid

    // State
    var anomalyDetectorStatus = AnomalyDetectorStatus.NO_ANOMALIES
        private set
    var logsProcessed = 0L
        private set
    var anomaliesDetected = 0L
        private set
    var logsVerified = 0L
        private set

    // Log subscriptions
    private val eventsToRead = listOf(
            TokenBridge.PAUSED_EVENT,
            TokenBridge.UNPAUSED_EVENT,
            TokenBridge.WITHDRAWREQUEST_EVENT
    )
    private val eventMap = eventsToRead.associateBy(EventEncoder::encode)

    fun start() {

        val blockNumber = web3jRequestHandler.sendWeb3jRequest { it.ethBlockNumber() }.blockNumber

        logInfo { "Starting anomaly detector for bcRid ${blockchainRid.toHex()} monitoring bridge contract ${tokenBridgeContractAddresses} from block number $blockNumber" }

        anomalyDetectorStatus = setInitialDetectorStatus()

        setupLogSubscription(blockNumber)
    }

    private fun queryIsTokenBridgePaused(): Boolean {

        val function = org.web3j.abi.datatypes.Function(
                "paused",
                listOf(),
                listOf(TypeReference.create(Bool::class.java))
        )

        val encodedFunction = FunctionEncoder.encode(function)
        val sendWeb3jRequest = web3jClient.sendWeb3jRequest {
            it.ethCall(
                    Transaction.createEthCallTransaction(tokenBridgeContractAddresses, tokenBridgeContractAddresses, encodedFunction),
                    DefaultBlockParameterName.LATEST
            )
        }

        return web3jClient.withTokenBridge(tokenBridgeContractAddresses) {
            it.paused()
        }.value
    }

    private fun setupLogSubscription(blockNumber: BigInteger?) {
        val eventSignatures = eventMap.keys.toTypedArray()

        val filter = EthFilter(
                DefaultBlockParameter.valueOf(blockNumber),
                DefaultBlockParameterName.LATEST,
                tokenBridgeContractAddresses)
                .addOptionalTopics(*eventSignatures)

        logSubscription = web3jRequestHandler.getClient().ethLogFlowable(filter).subscribe(::onLog)
    }

    private fun onLog(log: Log) {

        val matchingEvent = eventMap[log.topics[0]] ?: throw ProgrammerMistake("No matching event for log: $log")

        when (matchingEvent) {
            TokenBridge.PAUSED_EVENT -> bridgePaused()
            TokenBridge.UNPAUSED_EVENT -> {}
            TokenBridge.WITHDRAWREQUEST_EVENT -> {}
        }
//        val parameters = Contract.staticExtractEventParameters(matchingEvent, event)

        logInfo { "Log: ${log.logIndex}" }
        logsProcessed++

        val parameters = Contract.staticExtractEventParameters(TokenBridge.WITHDRAWREQUEST_EVENT, log)

        val heightParam = parameters.nonIndexedValues[1]
        val bridParam = parameters.nonIndexedValues[2]
        if (heightParam is Uint256 && bridParam is Bytes32) {

            verifyHeight(heightParam.value.toLong(), bridParam.value, true)
        } else {
            logError { "Unexpected log parameters: ${parameters.indexedValues} and ${parameters.nonIndexedValues}" }
            throw ProgrammerMistake("Unexpected log parameters: ${parameters.indexedValues} and ${parameters.nonIndexedValues}")
        }
    }

    private fun bridgePaused() {

        logWarn { "Bridge was paused" }

        anomalyDetectorStatus = AnomalyDetectorStatus.PAUSED
    }

    private fun verifyHeight(height: Long, brid: ByteArray, retry: Boolean) {

        val blockAtHeight = postchainClient.blockAtHeight(height)

        if (blockAtHeight == null) {

            logWarn { "Height $height not found in node" }

            if (retry) {
                logWarn { "Retry in ${getLogTime(timeoutConfig.missingHeightTimeoutInHours)}" }

                val schedule = timer.schedule(timeoutConfig.missingHeightTimeoutInHours + 1000) {
                    verifyHeight(height, brid, false)
                }
                schedule.cancel()
                timer.schedule(timerTask {
                    verifyHeight(height, brid, false)
                }, timeoutConfig.missingHeightTimeoutInHours)
            } else {

                pauseTokenBridge()
            }
        } else {

            checkAnomaly(brid, blockAtHeight)
        }
    }

    private fun checkAnomaly(brid: ByteArray, blockAtHeight: BlockDetail) {

        if (brid.contentEquals(blockAtHeight.rid.data)) {

            logsVerified++

        } else {

            logError { "Anomaly detected - log XXX referees to nonexistent block, brid: ${brid.toHex()}, height: TODO - token bridge contract will be paused in ${getLogTime(timeoutConfig.delayPauseInMinutes)}" }

            timer.schedule(timerTask {
                pauseTokenBridge()
            }, timeoutConfig.delayPauseInMinutes)

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
        timer.cancel()
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