package net.postchain.eif.anomaly_detector.evm

import net.postchain.client.impl.PostchainClientImpl.Companion.logger
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.eif.anomaly_detector.config.EvmConfig
import net.postchain.eif.anomaly_detector.evm.Web3jServiceFactory.buildServices
import net.postchain.eif.contracts.TokenBridge
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.RemoteFunctionCall
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.Response
import org.web3j.tx.RawTransactionManager
import org.web3j.tx.gas.DefaultGasProvider

class Web3jClient(
        private val networkId: Long,
        private val evmConfig: EvmConfig
) {

    private val web3jClients: List<Web3j> = buildServices(evmConfig.rpcUrls, 10_000, 10_000, 10_000)
    private val tokenBridgeMap = mutableMapOf<String, List<TokenBridge>>()

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

    fun <T : Response<*>> sendRequest(
            requestFactory: (Web3j) -> Request<*, T>
    ): T {

        return withAnyClient {
            val request = requestFactory(it)
            val response = request.send()

            if (response.hasError()) {
                val errorMessage = "Web3J error code: ${response.error.code} and message: ${response.error.message}"
                throw ProgrammerMistake(errorMessage)
            }

            response
        }
    }

    fun <T> withAnyClient(action: (Web3j) -> T?): T {

        for (web3jClient in web3jClients) {

            try {
                val result = action(web3jClient)

                if (result != null) {
                    return result
                }

                logger.warn{ "Failed to call rpc endpoint for network ${networkId}:" }
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
                val transactionManager = RawTransactionManager(it, evmConfig.credentials)
                TokenBridge.load(tokenBridgeContractAddresses, it, transactionManager, DefaultGasProvider())
            }
        }
    }
}

