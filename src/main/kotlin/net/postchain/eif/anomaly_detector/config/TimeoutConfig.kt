package net.postchain.eif.anomaly_detector.config

import java.util.concurrent.TimeUnit

data class TimeoutConfig(
        val missingHeightTimeoutInHours: Long = TimeUnit.HOURS.toMillis(24),
        val delayPauseInMinutes: Long = TimeUnit.MINUTES.toMillis(120),
)