package net.postchain.eif.anomaly_detector

import okhttp3.internal.toImmutableList
import org.web3j.protocol.core.methods.response.Log
import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.schedule

enum class LogVerificationStatus {
    UNKNOWN,
    RETRY,
    ANOMALY
}

data class LogVerification(
        val log: Log,
        val height: Long,
        val brid: ByteArray,
        var status: LogVerificationStatus = LogVerificationStatus.UNKNOWN
)

data class LogVerificationTask(
        val logVerification: LogVerification,
        val timestamp: Long,
        val delay: Long,
        var task: TimerTask? = null
)

class AnomaliesCache {

    private var anomalies = mutableListOf<LogVerification>()
    private var anomalyTasks = mutableListOf<LogVerificationTask>()

    private var timer = Timer()

    fun schedule(logVerification: LogVerification, status: LogVerificationStatus, delay: Long, action: (LogVerification) -> Unit) {

        if (!anomalies.contains(logVerification)) {
            anomalies.add(logVerification)
        }
        logVerification.status = status

        val task = timer.schedule(delay) {
            anomalies.remove(logVerification)
            anomalyTasks.removeIf { it.logVerification == logVerification }
            action(logVerification)
        }
        anomalyTasks.add(LogVerificationTask(logVerification, System.currentTimeMillis(), delay, task))
    }

    fun cancelTimer() {
        timer.cancel()
    }

    fun getAnomalies(): List<LogVerificationTask> {
        return anomalyTasks.toImmutableList()
    }
}
