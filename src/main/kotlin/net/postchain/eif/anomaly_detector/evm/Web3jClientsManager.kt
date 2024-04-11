package net.postchain.eif.anomaly_detector.evm

import net.postchain.client.impl.PostchainClientImpl.Companion.logger
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.eif.anomaly_detector.config.EvmConfig

class Web3jClientsManager(
        val evmConfigs: Map<Long, EvmConfig>
) {

    private val clientMap = mutableMapOf<Long, Web3jClient>()

    fun getClient(networkId: Long): Web3jClient {

        return clientMap.getOrPut(networkId) {

            val evmConfig = evmConfigs[networkId]
            if (evmConfig?.rpcUrls == null || evmConfig.rpcUrls.isEmpty()) {
                val message = "No rpc urls set for network ${networkId}"

                logger.error { message }
                throw ProgrammerMistake(message)
            }

            Web3jClient(networkId, evmConfig)
        }
    }
}
