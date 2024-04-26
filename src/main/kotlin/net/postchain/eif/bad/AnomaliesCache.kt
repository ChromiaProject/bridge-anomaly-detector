package net.postchain.eif.bad

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
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as LogVerification

        if (log != other.log) return false
        if (height != other.height) return false
        if (!brid.contentEquals(other.brid)) return false
        if (status != other.status) return false

        return true
    }

    override fun hashCode(): Int {
        var result = log.hashCode()
        result = 31 * result + height.hashCode()
        result = 31 * result + brid.contentHashCode()
        result = 31 * result + status.hashCode()
        return result
    }
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
