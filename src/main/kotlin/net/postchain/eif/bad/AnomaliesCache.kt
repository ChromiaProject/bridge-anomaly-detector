package net.postchain.eif.bad

import mu.KLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

enum class LogVerificationStatus {
    UNKNOWN,
    RETRY,      // Height not found on node, will retry again
    ANOMALY     // Confirmed anomaly
}

data class LogVerificationTask(
        val logVerification: LogVerification,
        val timestamp: Long,
        val delay: Long,
        var task: ScheduledFuture<*>? = null
)

class AnomaliesCache(
        private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "anomalies-cache-scheduler").apply { isDaemon = true }
        }
) {
    companion object : KLogging()

    private val anomalies: MutableSet<LogVerification> = ConcurrentHashMap.newKeySet()
    private val anomalyTasks = CopyOnWriteArrayList<LogVerificationTask>()

    fun schedule(logVerification: LogVerification, status: LogVerificationStatus, delay: Long, action: (LogVerification) -> Unit) {
        addAnomaly(logVerification)
        logVerification.status = status

        val entry = LogVerificationTask(logVerification, System.currentTimeMillis(), delay)
        anomalyTasks.add(entry)

        val future = scheduler.schedule({
            anomalies.remove(logVerification)
            anomalyTasks.remove(entry)
            try {
                action(logVerification)
            } catch (t: Throwable) {
                logger.error(t) { "Scheduled anomaly task failed for height ${logVerification.height}: ${t.message}" }
            }
        }, delay.coerceAtLeast(0L), TimeUnit.MILLISECONDS)

        entry.task = future
    }

    fun scheduleAction(delayMs: Long, action: () -> Unit): ScheduledFuture<*> {
        return scheduler.schedule({
            try {
                action()
            } catch (t: Throwable) {
                logger.error(t) { "Scheduled action failed: ${t.message}" }
            }
        }, delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
    }

    fun addAnomaly(anomaly: LogVerification) {
        anomalies.add(anomaly)
    }

    fun cancelTimer() {
        scheduler.shutdownNow()
    }

    fun getAnomalies(): List<LogVerification> {
        return anomalies.toList()
    }

    fun getAnomalyTasks(): List<LogVerificationTask> {
        return anomalyTasks.toList()
    }
}
