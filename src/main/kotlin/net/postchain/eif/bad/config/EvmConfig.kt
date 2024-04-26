package net.postchain.eif.bad.config

import net.postchain.common.config.getEnvOrListProperty
import net.postchain.common.config.getEnvOrStringProperty
import net.postchain.common.exception.UserMistake
import net.postchain.eif.bad.config.AppConfig.Companion.EVM_PREFIX
import org.apache.commons.configuration2.PropertiesConfiguration
import org.apache.commons.configuration2.builder.FileBasedConfigurationBuilder
import org.apache.commons.configuration2.builder.fluent.Parameters
import org.web3j.crypto.Credentials
import java.io.File

class EvmConfig(
        val rpcUrls: List<String>,
        val credentials: Credentials,
        val logProcessorConfig: LogProcessorConfig,
) {
    companion object {

        const val PROPERTY_NAME_NODE_PRIVATE_KEY = "node_private_key"
        const val PROPERTY_NAME_NODE_CONFIG_FILE = "node_config_file"
        const val PROPERTY_NAME_MESSAGING_PRIVKEY = "messaging.privkey"

        fun fromConfiguration(networkId: Long, config: PropertiesConfiguration): EvmConfig {

            try {
                val rpcUrls = config.getEnvOrListProperty("EVM_${networkId}_RPC_URLS", "${EVM_PREFIX}.${networkId}.rpc_urls", listOf())
                var nodePrivateKey = config.getEnvOrStringProperty("EVM_${networkId}_NODE_PRIVATE_KEY", "${EVM_PREFIX}.${networkId}.$PROPERTY_NAME_NODE_PRIVATE_KEY", "")

                if (nodePrivateKey.isEmpty()) {
                    val nodeConfigFile = config.getEnvOrStringProperty("EVM_${networkId}_NODE_CONFIG_FILE", "${EVM_PREFIX}.${networkId}.$PROPERTY_NAME_NODE_CONFIG_FILE", "")

                    require(nodeConfigFile.isNotEmpty()) { "Either $PROPERTY_NAME_NODE_PRIVATE_KEY or $PROPERTY_NAME_NODE_CONFIG_FILE must be specified to import evm private key for network $networkId" }

                    val file = File(nodeConfigFile)
                    require(file.exists()) { "Node configuration file $file specified for network $networkId does not exist" }

                    val params = Parameters().properties()
                            .setFile(file)
                            .setIncludesAllowed(false)

                    val nodeConfig = FileBasedConfigurationBuilder(PropertiesConfiguration::class.java)
                            .configure(params)
                            .configuration
                    try {
                        nodePrivateKey = nodeConfig.getString(PROPERTY_NAME_MESSAGING_PRIVKEY)
                    } catch (e: Exception) {
                        throw UserMistake("Node configuration file for network $networkId does not contain $PROPERTY_NAME_MESSAGING_PRIVKEY")
                    }
                }

                // Validate
                rpcUrls.ifEmpty { throw UserMistake("No rpc urls specified") }
                val credentials = Credentials.create(nodePrivateKey)
                return EvmConfig(
                        rpcUrls,
                        credentials,
                        LogProcessorConfig(0, 0, 0)
                )
            } catch (e: Exception) {
                throw UserMistake("Failed to for configuration for evm network $networkId: ${e.message}", e)
            }
        }
    }
}
