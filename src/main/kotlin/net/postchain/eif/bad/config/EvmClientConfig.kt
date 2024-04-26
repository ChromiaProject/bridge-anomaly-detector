package net.postchain.eif.bad.config

import net.postchain.common.config.getEnvOrLongProperty
import org.apache.commons.configuration2.Configuration
import java.util.concurrent.TimeUnit

class EvmClientConfig(
        val connectTimeoutSeconds: Long = 10,
        val readTimeoutSeconds: Long = 10,
        val writeTimeoutSeconds: Long = 10,
) {
    constructor(config: Configuration) : this(
            TimeUnit.MINUTES.toMillis(config.getEnvOrLongProperty("EVM_CLIENT_CONNECT_TIMEOUT_SECONDS", "evm_client.connect_timeout_seconds", 10)),
            TimeUnit.MINUTES.toMillis(config.getEnvOrLongProperty("EVM_CLIENT_READ_TIMEOUT_SECONDS", "evm_client.read_timeout_seconds", 10)),
            TimeUnit.MINUTES.toMillis(config.getEnvOrLongProperty("EVM_CLIENT_WRITE_TIMEOUT_SECONDS", "evm_client.write_timeout_seconds", 10))
    )
    init {
        require(connectTimeoutSeconds > 0) { "Connect timeout must be larger than 0" }
        require(readTimeoutSeconds > 0) { "Connect timeout must be larger than 0" }
        require(writeTimeoutSeconds > 0) { "Connect timeout must be larger than 0" }
    }
}