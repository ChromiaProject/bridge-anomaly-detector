package net.postchain.eif.anomaly_detector.config

import net.postchain.common.config.getEnvOrLongProperty
import org.apache.commons.configuration2.Configuration
import java.util.concurrent.TimeUnit

class TimeoutConfig(
        val missingHeightRetryDelay: Long,
        val pauseDelay: Long,
) {
    constructor(config: Configuration) : this(
            TimeUnit.MINUTES.toMillis(config.getEnvOrLongProperty("MISSING_HEIGHT_RETRY_DELAY", "missing_height_retry_delay", TimeUnit.HOURS.toMinutes(24))),
            TimeUnit.MINUTES.toMillis(config.getEnvOrLongProperty("PAUSE_DELAY", "pause_delay", TimeUnit.HOURS.toMinutes(2)))
    )

    init {
        require(missingHeightRetryDelay >= 0) { "Missing height retry delay cant be negative" }
        require(pauseDelay >= 0) { "Pause delay cant be negative" }
    }
}
