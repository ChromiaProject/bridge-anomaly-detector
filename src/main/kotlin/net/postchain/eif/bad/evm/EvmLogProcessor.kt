package net.postchain.eif.bad.evm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.slf4j.MDCContext
import mu.KLogging
import net.postchain.eif.bad.config.LogProcessorConfig
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.core.methods.request.EthFilter
import org.web3j.protocol.core.methods.response.EthLog
import org.web3j.protocol.core.methods.response.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Reads logs from evm chain and calls the given onLog function on each log entry.
 */
class EvmLogProcessor(
        private val logProcessorConfig: LogProcessorConfig,
        private val eventSignatures: Array<String>,
        private val web3jClient: Web3jRequestHandler,
) {

    private var job: Job? = null

    companion object : KLogging()

    private val contractSubscriptions = ConcurrentHashMap<String, (Log) -> Unit>()

    var lastReadBlockNumber = -1L
        private set

    fun addContractSubscription(contractAddress: String, onLog: (Log) -> Unit) {
        contractSubscriptions[contractAddress.lowercase()] = onLog

        if (job == null) {
            start()
        }
    }

    fun removeContractSubscription(contractAddress: String) {
        if (contractSubscriptions.containsKey(contractAddress) && contractSubscriptions.size == 1) {
            stop()
        }

        contractSubscriptions.remove(contractAddress.lowercase())
    }

    private fun start() {
        lastReadBlockNumber = web3jClient.sendWeb3jRequest { it.ethBlockNumber() }.blockNumber.toLong() - logProcessorConfig.readOffset

        logger.info { "Starting log processor for network id ${web3jClient.networkId} with last read block number: $lastReadBlockNumber" }

        job = CoroutineScope(Dispatchers.IO).launch(CoroutineName("network-${web3jClient.networkId}-log-processor") + MDCContext()) {
            var backoffMs = 500L
            val maxBackoffMs = 60_000L
            while (isActive) {
                try {
                    fetchEvents()
                    // Reset backoff after a successful iteration
                    backoffMs = 500L
                } catch (_: CancellationException) {
                    break
                } catch (e: Exception) {
                    logger.error("Parsing of EVM logs unexpectedly failed: $e", e)
                    logger.info { "Retrying failed log fetching after ${backoffMs}ms..." }
                    delay(backoffMs)
                    backoffMs = min(maxBackoffMs, backoffMs * 2)
                }
            }
        }
    }

    private suspend fun fetchEvents() {

        val from = lastReadBlockNumber + 1

        val blockNumberReply = web3jClient.sendWeb3jRequest { it.ethBlockNumber() }
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
                ArrayList(contractSubscriptions.keys)
        )
        filter.addOptionalTopics(*eventSignatures)

        val logs = web3jClient
                .sendWeb3jRequest { it.ethGetLogs(filter) }
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
                contractSubscriptions[log.address.lowercase()]?.invoke(log)
            } catch (e: Exception) {
                logger.error(e) { "Failed ot process log event: ${e.message}" }
            }
        }
        lastReadBlockNumber = newLastReadLogBlockHeight
    }

    fun stop() {
        logger.info { "Stopping log processor for network id ${web3jClient.networkId}" }

        runBlocking {
            job?.cancelAndJoin()
        }
        job = null
    }
}
