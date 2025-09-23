package net.postchain.eif.bad.evm

import net.postchain.client.impl.PostchainClientImpl.Companion.logger
import net.postchain.common.exception.ProgrammerMistake
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.Response
import java.io.Closeable

open class Web3jRequestHandler(
        private val web3jServices: List<Web3j>,
        val networkId: Long,
) : Closeable {

    open fun <T : Response<*>> sendWeb3jRequest(
            requestFactory: (Web3j) -> Request<*, T>
    ): T {
        val requests = web3jServices.map(requestFactory)
        for (request in requests) {

            try {
                val response = request.send()

                if (response.hasError()) {
                    val errorMessage = "Web3J error code: ${response.error.code} and message: ${response.error.message}"
                    throw ProgrammerMistake(errorMessage)
                }

                return response
            } catch (e: Exception) {
                logger.error("Web3j request failed: ${e.message}", e)
            }
        }

        throw ProgrammerMistake("Failed to send web3j request to all ${web3jServices.size} nodes")
    }

    override fun close() {
        web3jServices.forEach { it.shutdown() }
    }
}