package net.postchain.eif.bad.config

import net.postchain.common.config.getEnvOrBooleanProperty
import net.postchain.common.config.getEnvOrLongProperty
import org.apache.commons.configuration2.Configuration
import java.util.concurrent.TimeUnit

class AnomalyConfig(
        val missingHeightRetryDelay: Long,
        val pauseDelay: Long,
        val maxRandomDelay: Long,
        val pauseOnAnomaly: Boolean
) {
    constructor(config: Configuration) : this(
            TimeUnit.MINUTES.toMillis(config.getEnvOrLongProperty("MISSING_HEIGHT_RETRY_DELAY_MINUTES", "missing_height_retry_delay_minutes", 24 * 60)),
            TimeUnit.MINUTES.toMillis(config.getEnvOrLongProperty("PAUSE_DELAY_MINUTES", "pause_delay_minutes", 2 * 60)),
            TimeUnit.SECONDS.toMillis(config.getEnvOrLongProperty("MAX_RANDOM_DELAY_SECONDS", "max_random_delay_seconds", 5 * 60)),
            config.getEnvOrBooleanProperty("PAUSE_ON_ANOMALY", "pause_on_anomaly", true)
    )

    init {
        require(missingHeightRetryDelay >= 0) { "Missing height retry delay cant be negative" }
        require(pauseDelay >= 0) { "Pause delay cant be negative" }
        require(maxRandomDelay >= 0) { "Max random delay cant be negative" }
    }
}
