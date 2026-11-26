package net.postchain.eif.bad.config

import net.postchain.common.config.getEnvOrLongProperty
import org.apache.commons.configuration2.Configuration

class EvmClientConfig(
        val connectTimeoutSeconds: Long = 30,
        val readTimeoutSeconds: Long = 30,
        val writeTimeoutSeconds: Long = 30,
) {
    constructor(config: Configuration) : this(
            config.getEnvOrLongProperty("EVM_CLIENT_CONNECT_TIMEOUT_SECONDS", "evm_client.connect_timeout_seconds", 30),
            config.getEnvOrLongProperty("EVM_CLIENT_READ_TIMEOUT_SECONDS", "evm_client.read_timeout_seconds", 30),
            config.getEnvOrLongProperty("EVM_CLIENT_WRITE_TIMEOUT_SECONDS", "evm_client.write_timeout_seconds", 30)
    )

    init {
        require(connectTimeoutSeconds > 0) { "Connect timeout must be larger than 0" }
        require(readTimeoutSeconds > 0) { "Read timeout must be larger than 0" }
        require(writeTimeoutSeconds > 0) { "Write timeout must be larger than 0" }
    }
}
