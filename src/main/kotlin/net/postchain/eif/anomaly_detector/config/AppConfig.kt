package net.postchain.eif.anomaly_detector.config

import net.postchain.client.request.EndpointPool
import java.util.concurrent.TimeUnit

data class AppConfig(
        val rpcUrls: Map<Long, List<String>>,
        val nodeUrl: EndpointPool,
        val bcRid: String,
        val bridgeChainRefreshIntervalSeconds: Long = TimeUnit.MINUTES.toSeconds(30), // Change to MS for tests?

        val timeoutConfig: TimeoutConfig
)