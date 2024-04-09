package net.postchain.eif.anomaly_detector

import net.postchain.client.request.EndpointPool
import net.postchain.eif.anomaly_detector.config.AppConfig
import net.postchain.eif.anomaly_detector.config.TimeoutConfig

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

    AnomalyDetectorsManager(appConfig).start()
}
