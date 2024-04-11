package net.postchain.eif.anomaly_detector.config

import java.util.concurrent.TimeUnit

data class TimeoutConfig(
        val missingHeightRetryDelay: Long = TimeUnit.HOURS.toMillis(24),
        val pauseDelay: Long = TimeUnit.MINUTES.toMillis(120),
)