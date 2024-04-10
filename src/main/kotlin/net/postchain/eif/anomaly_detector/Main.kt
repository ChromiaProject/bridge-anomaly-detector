package net.postchain.eif.anomaly_detector

import net.postchain.client.impl.PostchainClientImpl.Companion.logger
import net.postchain.client.request.EndpointPool
import net.postchain.eif.anomaly_detector.config.AppConfig
import net.postchain.eif.anomaly_detector.config.RestApiConfig
import net.postchain.eif.anomaly_detector.config.TimeoutConfig
import net.postchain.eif.anomaly_detector.rest.RestApi

data class Blockchain (
        val blockchainRid: ByteArray,
        val evmNetworkId: Long,
        val bridgeContract: String
)

fun main(args: Array<String>) {

    // TODO read from config, preferable the same as node?
    val appConfig = AppConfig(
            mapOf(1337L to listOf("http://localhost:33253")),
            EndpointPool.singleUrl("http://127.0.0.1:7740"),
            "0000000000000000000000000000000000000000000000000000000000000002",
            10L,
            TimeoutConfig()
    )

    val anomalyDetectorsManager = AnomalyDetectorsManager(appConfig)
    anomalyDetectorsManager.start()

    startRestApi(appConfig.restApiConfig, anomalyDetectorsManager)
}

fun startRestApi(restApiConfig: RestApiConfig, anomalyDetectorsManager: AnomalyDetectorsManager): RestApi? {

    val restApi: RestApi? = with(restApiConfig) {
        if (port != -1) {
            net.postchain.client.impl.PostchainClientImpl.logger.info { "Starting REST API on port $port and path $basePath/" }
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
