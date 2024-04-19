package net.postchain.eif.anomaly_detector.config

import net.postchain.common.config.Config
import net.postchain.common.config.getEnvOrLongProperty
import org.apache.commons.configuration2.Configuration
import java.util.concurrent.TimeUnit

data class RestApiConfig(
        val basePath: String = "/",
        val port: Int = REST_API_DEFAULT_PORT,
) {

    constructor(config: Configuration) : this(
            port = config.getEnvOrLongProperty("REST_API_PORT", "rest_api.port", REST_API_DEFAULT_PORT.toLong()).toInt(),
    )

    init {
        require(port in -1 .. 49151) { "API port has to be between -1 (disabled) and 49151 (ephemeral)" }
    }

    companion object {

        const val REST_API_DEFAULT_PORT = 7790
    }
}
