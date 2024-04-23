package net.postchain.eif.anomaly_detector.evm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.slf4j.MDCContext
import mu.KLogging
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.eif.anomaly_detector.config.LogProcessorConfig
import org.web3j.abi.EventEncoder
import org.web3j.abi.datatypes.Event
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.core.methods.request.EthFilter
import org.web3j.protocol.core.methods.response.EthLog
import org.web3j.protocol.core.methods.response.Log

/**
 * Reads logs from evm chain and calls the given onLog function on each log entry.
 */
class EvmLogProcessor(
        private val logProcessorConfig: LogProcessorConfig,
        private val contractAddresses: String,
        events: List<Event>,
        skipToHeight: Long,
        private val web3jClient: Web3jClient,
        private val onLog: (Log) -> Unit,
) {

    private val job: Job

    companion object : KLogging()

    private val eventMap = events.associateBy(EventEncoder::encode)
    private val eventSignatures = eventMap.keys.toTypedArray()

    var lastReadBlockNumber = skipToHeight
        private set

    init {
        job = CoroutineScope(Dispatchers.IO).launch(CoroutineName("${contractAddresses}-log-processor") + MDCContext()) {
            while (isActive) {
                try {
                    fetchEvents()
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    logger.error("Parsing of EVM logs unexpectedly failed: $e", e)
                    delay(500) // Delay a bit and hope that we can recover
                }
            }
        }
    }

    private suspend fun fetchEvents() {

        val from = lastReadBlockNumber + 1

        val blockNumberReply = web3jClient.sendRequest { it.ethBlockNumber() }
        val currentBlockHeight = blockNumberReply.blockNumber.toLong() - logProcessorConfig.readOffset

        // Pacing the reading of logs
        val to = minOf(currentBlockHeight, from + logProcessorConfig.maxBlockRangePerRequest)

        if (to < from) {
            logger.debug { "No new blocks to read. We are at height: $to" }
            // Sleep a bit until next attempt
            delay(logProcessorConfig.delayWhenNoNewBlock)
            return
        }

        val filter = EthFilter(
                DefaultBlockParameter.valueOf(from.toBigInteger()),
                DefaultBlockParameter.valueOf(to.toBigInteger()),
                contractAddresses
        )
        filter.addOptionalTopics(*eventSignatures)

        val logs = web3jClient
                .sendRequest { it.ethGetLogs(filter) }
                .logs
                .map { (it as EthLog.LogObject).get() }

        processLogEventsAndUpdateOffsets(logs, to)
    }

    private fun processLogEventsAndUpdateOffsets(
            logs: List<Log>,
            newLastReadLogBlockHeight: Long
    ) {
        for (log in logs) {
            try {
                onLog(log)
            } catch (e: Exception) {
                logger.error(e) { "Failed ot process log event: ${e.message}" }
            }
        }
        lastReadBlockNumber = newLastReadLogBlockHeight
    }

    fun shutdown() {
        job.cancel()
    }

    fun getEventType(log: Log): Any {
        return eventMap[log.topics[0]] ?: throw ProgrammerMistake("No matching event for log: $log")
    }
}
