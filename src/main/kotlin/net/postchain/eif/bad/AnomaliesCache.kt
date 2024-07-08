package net.postchain.eif.bad

import okhttp3.internal.toImmutableList
import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.schedule

enum class LogVerificationStatus {
    UNKNOWN,
    RETRY,      // Height not found on node, will retry again
    ANOMALY     // Confirmed anomaly
}

data class LogVerificationTask(
        val logVerification: LogVerification,
        val timestamp: Long,
        val delay: Long,
        var task: TimerTask? = null
)

class AnomaliesCache {

    private var anomalies = mutableSetOf<LogVerification>()
    private var anomalyTasks = mutableListOf<LogVerificationTask>()

    private var timer = Timer()

    fun schedule(logVerification: LogVerification, status: LogVerificationStatus, delay: Long, action: (LogVerification) -> Unit) {
        addAnomaly(logVerification)
        logVerification.status = status

        val task = timer.schedule(delay) {
            anomalies.removeIf { it == logVerification }
            anomalyTasks.removeIf { it.logVerification == logVerification }
            action(logVerification)
        }
        anomalyTasks.add(LogVerificationTask(logVerification, System.currentTimeMillis(), delay, task))
    }

    fun addAnomaly(anomaly: LogVerification) {
        if (!anomalies.contains(anomaly)) {
            anomalies.add(anomaly)
        }
    }

    fun cancelTimer() {
        timer.cancel()
    }

    fun getAnomalies(): List<LogVerification> {
        return anomalies.toList()
    }

    fun getAnomalyTasks(): List<LogVerificationTask> {
        return anomalyTasks.toImmutableList()
    }
}
