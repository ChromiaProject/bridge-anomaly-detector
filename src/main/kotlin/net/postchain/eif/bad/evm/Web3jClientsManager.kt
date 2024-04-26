package net.postchain.eif.bad.evm

import net.postchain.client.impl.PostchainClientImpl.Companion.logger
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.eif.bad.config.EvmConfig

class Web3jClientsManager(
        private val evmConfigs: Map<Long, EvmConfig>
) {

    private val clientMap = mutableMapOf<Long, Web3jClient>()

    fun getClient(networkId: Long): Web3jClient {

        return clientMap.getOrPut(networkId) {

            logger.info { "Creating clients for network $networkId" }

            val evmConfig = evmConfigs[networkId]
            if (evmConfig?.rpcUrls == null || evmConfig.rpcUrls.isEmpty()) {
                val message = "No rpc urls set for network $networkId"

                logger.error { message }
                throw ProgrammerMistake(message)
            }

            Web3jClient(networkId, evmConfig)
        }
    }

    fun closeClient(networkId: Long) {

        logger.info { "Closing clients for network $networkId" }

        clientMap.remove(networkId)?.close()
    }

    fun hasNetworkClient(networkId: Long): Boolean
        = clientMap.containsKey(networkId)
}
