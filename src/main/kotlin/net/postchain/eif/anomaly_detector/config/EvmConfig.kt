package net.postchain.eif.anomaly_detector.config

import org.web3j.crypto.Credentials

data class EvmConfig(
        val rpcUrls: List<String>,
        val credentials: Credentials
)
