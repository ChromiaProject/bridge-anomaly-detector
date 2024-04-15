package net.postchain.eif.anomaly_detector

import net.postchain.eif.anomaly_detector.config.AppConfig
import net.postchain.eif.anomaly_detector.config.RestApiConfig
import net.postchain.eif.anomaly_detector.evm.Web3jClientsManager
import net.postchain.eif.anomaly_detector.rest.RestApi
import java.io.File

data class Blockchain(
        val blockchainRid: ByteArray,
        val evmNetworkId: Long,
        val bridgeContract: String
)

fun main(args: Array<String>) {

    require(args.isNotEmpty()) { "Provider configuration file as input argument" }

    val configFile = File(args[0])
    require(configFile.exists()) { "No such file: $configFile" }

    val appConfig = AppConfig.fromPropertiesFile(configFile)

    val web3jClientsManager = Web3jClientsManager(appConfig.evmConfig)
    val anomalyDetectorsManager = AnomalyDetectorsManager(appConfig, web3jClientsManager)
    anomalyDetectorsManager.start()

    startRestApi(appConfig.restApiConfig, anomalyDetectorsManager)
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
