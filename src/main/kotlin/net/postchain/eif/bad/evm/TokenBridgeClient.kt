package net.postchain.eif.bad.evm

import net.postchain.client.impl.PostchainClientImpl.Companion.logger
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.eif.bad.config.EvmClientConfig
import net.postchain.eif.bad.config.EvmConfig
import net.postchain.eif.bad.evm.Web3jServiceFactory.buildServices
import net.postchain.eif.contracts.TokenBridge
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.RemoteFunctionCall
import org.web3j.tx.RawTransactionManager
import java.util.concurrent.ConcurrentHashMap

class TokenBridgeClient(
        private val networkId: Long,
        private val evmConfig: EvmConfig,
        evmClientConfig: EvmClientConfig = EvmClientConfig(),
) {

    private val web3jClients: List<Web3j> = buildServices(
            evmConfig.rpcUrls,
            evmClientConfig.connectTimeoutSeconds,
            evmClientConfig.readTimeoutSeconds,
            evmClientConfig.writeTimeoutSeconds,
    )
    private val tokenBridgeMap = ConcurrentHashMap<String, List<TokenBridge>>()

    fun <T> withTokenBridge(tokenBridgeContractAddresses: String, call: (TokenBridge) -> RemoteFunctionCall<T>): T {

        for (tokenBridge in getTokenBridge(tokenBridgeContractAddresses)) {
            try {
                val functionCall = call(tokenBridge)
                return functionCall.send()
            } catch (e: Exception) {
                logger.warn(e) { "Failed to call rpc endpoint for network ${networkId}: ${e.message}" }
            }
        }

        throw ProgrammerMistake("Failed to call all rpc endpoints for network $networkId")
    }

    fun close() {
        web3jClients.forEach(Web3j::shutdown)
    }

    private fun getTokenBridge(tokenBridgeContractAddresses: String): List<TokenBridge> {
        return tokenBridgeMap.getOrPut(tokenBridgeContractAddresses) {
            web3jClients.map {
                val transactionManager = RawTransactionManager(it, evmConfig.credentials, networkId)
                val gasProvider = DynamicEIP1559GasProvider(it, networkId)
                TokenBridge.load(tokenBridgeContractAddresses, it, transactionManager, gasProvider)
            }
        }
    }
}
