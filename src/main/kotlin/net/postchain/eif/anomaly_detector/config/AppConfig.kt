package net.postchain.eif.anomaly_detector.config

import net.postchain.common.BlockchainRid
import net.postchain.common.config.getEnvOrLongProperty
import net.postchain.common.config.getEnvOrStringProperty
import org.apache.commons.configuration2.PropertiesConfiguration
import org.apache.commons.configuration2.builder.FileBasedConfigurationBuilder
import org.apache.commons.configuration2.builder.fluent.Parameters
import org.apache.commons.configuration2.convert.DefaultListDelimiterHandler
import java.io.File
import java.util.concurrent.TimeUnit

class AppConfig (

        // EVM
        val evmConfig: Map<Long, EvmConfig>,

        // Postchain
        val nodeUrl: String,
        val blockchainRid: String,
        val bridgeChainRefreshInterval: Long = TimeUnit.MINUTES.toSeconds(30), // Change to MS for tests?

        // Anomaly detector
        val timeoutConfig: TimeoutConfig,

        // REST
        val restApiConfig: RestApiConfig = RestApiConfig()
) {
    companion object {

        const val EVM_PREFIX = "evm"
        private val EVM_PROPERTY_PATTERN = "${EVM_PREFIX}.([0-9]+)[.](.*)".toRegex()

        fun fromPropertiesFile(configFile: File): AppConfig {

            // EVM
            val params = Parameters().properties()
                    .setFile(configFile)
                    .setListDelimiterHandler(DefaultListDelimiterHandler(','))

            val config = FileBasedConfigurationBuilder(PropertiesConfiguration::class.java)
                    .configure(params)
                    .configuration

            val evmProperties = config.getKeys(EVM_PREFIX)
            val evmNetworksInConfig = mutableSetOf<Long>()
            for (evmProperty in evmProperties) {

                EVM_PROPERTY_PATTERN.matchEntire(evmProperty)?.let {
                    evmNetworksInConfig.add(it.groupValues[1].toLong())
                }
            }

            val evmConfigs = evmNetworksInConfig.map {
                it to EvmConfig.fromConfiguration(it, config)
            }.toMap()

            val appConfig = AppConfig(
                    evmConfig = evmConfigs,

                    nodeUrl = config.getEnvOrStringProperty("POSTCHAIN_URL", "postchain.url", "http://localhost:7740"),
                    blockchainRid = config.getEnvOrStringProperty("POSTCHAIN_BLOCKCHAIN_RID", "postchain.blockchain_rid", ""),
                    bridgeChainRefreshInterval = config.getEnvOrLongProperty("POSTCHAIN_BRIDGE_CHAIN_REFRESH_INTERVAL", "postchain.bridge_chain_refresh_interval", TimeUnit.MINUTES.toMillis(20)),

                    TimeoutConfig(config),

                    RestApiConfig.fromConfiguration(config),
            )

            return appConfig
        }
    }

    init {
        require(evmConfig.isNotEmpty()) { "At least one evm network is required" }
        require(nodeUrl.isNotBlank()) { "Postchain node url is required" }
        require(blockchainRid.isNotBlank()) { "Postchain blockchain RID is required" }
        require(blockchainRid.length == 64) { "Postchain blockchain RID is invalid (expected length 64)" }
        require(bridgeChainRefreshInterval > 0) { "bridgeChainRefreshInterval must be positive" }
    }
}
