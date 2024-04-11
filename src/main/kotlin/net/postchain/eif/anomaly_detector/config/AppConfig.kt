package net.postchain.eif.anomaly_detector.config

import net.postchain.client.request.EndpointPool
import java.util.concurrent.TimeUnit

data class AppConfig(

        // EVM
        val evmConfig: Map<Long, EvmConfig>,

        // Postchain
        val nodeUrl: EndpointPool,
        val bcRid: String,
        val bridgeChainRefreshIntervalSeconds: Long = TimeUnit.MINUTES.toSeconds(30), // Change to MS for tests?

        // Anomaly detector
        val timeoutConfig: TimeoutConfig,

        // REST
        val restApiConfig: RestApiConfig = RestApiConfig()
)