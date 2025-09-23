package net.postchain.eif.bad

import net.postchain.client.impl.PostchainClientImpl.Companion.logger
import net.postchain.common.BlockchainRid
import net.postchain.eif.bad.config.AppConfig
import net.postchain.eif.bad.config.RestApiConfig
import net.postchain.eif.bad.evm.TokenBridgeClientsManager
import net.postchain.eif.bad.rest.RestApi
import java.io.File

data class BlockchainBridge(
        val blockchainRid: BlockchainRid,
        val evmNetworkId: Long,
        val bridgeContract: String
)

fun main(args: Array<String>) {

    require(args.isNotEmpty()) { "Provider configuration file as input argument" }

    val configFile = File(args[0])
    require(configFile.exists()) { "No such file: $configFile" }

    val appConfig = AppConfig.fromPropertiesFile(configFile)

    if (!appConfig.anomalyConfig.pauseOnAnomaly) {
        logger.warn { "!!! Bridge will never be paused since pauseOnAnomaly is set to false !!!" }
    }

    val tokenBridgeClientsManager = TokenBridgeClientsManager(appConfig.evmConfig)
    val anomalyDetectorsManager = AnomalyDetectorsManager(appConfig, tokenBridgeClientsManager)
    anomalyDetectorsManager.start()

    startRestApi(appConfig.restApiConfig, anomalyDetectorsManager)

    logger.info { "Bridge anomaly detector is running" }
}

fun startRestApi(restApiConfig: RestApiConfig, anomalyDetectorsManager: AnomalyDetectorsManager): RestApi? {

    val restApi: RestApi? = with(restApiConfig) {
        if (port != -1) {
            try {
                RestApi(
                        listenPort = port,
                        basePath = basePath,
                        anomalyDetectorsManager = anomalyDetectorsManager
                )
            } catch (e: Exception) {
                net.postchain.client.impl.PostchainClientImpl.logger.error("Unable to start REST API on port $port", e)
                throw e
            }
        } else {
            null
        }
    }
    return restApi
}
