package net.postchain.eif.bad.evm

import mu.KLogging
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.tx.gas.ContractEIP1559GasProvider
import org.web3j.tx.gas.DefaultGasProvider
import java.math.BigInteger

/**
 * Dynamic EIP-1559 gas provider that queries eth_feeHistory (with fallback to eth_gasPrice)
 * so emergency pause() transactions are competitively priced during network congestion.
 */
class DynamicEIP1559GasProvider(
        private val web3j: Web3j,
        private val chainId: Long,
        private val gasLimit: BigInteger = DEFAULT_PAUSE_GAS_LIMIT,
        private val minPriorityFeeWei: BigInteger = MIN_PRIORITY_FEE_WEI,
        private val feeMultiplier: BigInteger = BigInteger.TWO,
) : ContractEIP1559GasProvider {

    companion object : KLogging() {
        val DEFAULT_PAUSE_GAS_LIMIT: BigInteger = BigInteger.valueOf(500_000L)
        val MIN_PRIORITY_FEE_WEI: BigInteger = BigInteger.valueOf(2_000_000_000L) // 2 gwei
    }

    private data class FeeEstimate(
            val maxFeePerGas: BigInteger,
            val maxPriorityFeePerGas: BigInteger,
            val legacyGasPrice: BigInteger,
    )

    private fun estimateFees(): FeeEstimate {
        try {
            val feeHistory = web3j.ethFeeHistory(5, DefaultBlockParameterName.LATEST, listOf(50.0)).send()
            val baseFees = feeHistory.feeHistory?.baseFeePerGas
            if (feeHistory.error == null && !baseFees.isNullOrEmpty()) {
                val latestBaseFee = baseFees.last()
                val rewards = feeHistory.feeHistory.reward
                        ?.mapNotNull { it.firstOrNull() }
                        ?.sorted()
                val medianPriorityFee = if (!rewards.isNullOrEmpty()) {
                    rewards[rewards.size / 2]
                } else {
                    minPriorityFeeWei
                }
                val priorityFee = medianPriorityFee.max(minPriorityFeeWei)
                val maxFee = latestBaseFee.multiply(feeMultiplier).add(priorityFee)
                return FeeEstimate(maxFee, priorityFee, maxFee)
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch eth_feeHistory on chain $chainId, falling back to eth_gasPrice: ${e.message}" }
        }

        return try {
            val gasPrice = web3j.ethGasPrice().send().gasPrice
            val boosted = gasPrice.multiply(BigInteger.valueOf(125)).divide(BigInteger.valueOf(100))
                    .max(DefaultGasProvider.GAS_PRICE)
            FeeEstimate(boosted, minPriorityFeeWei.min(boosted), boosted)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch eth_gasPrice on chain $chainId, falling back to DefaultGasProvider: ${e.message}" }
            FeeEstimate(DefaultGasProvider.GAS_PRICE, minPriorityFeeWei, DefaultGasProvider.GAS_PRICE)
        }
    }

    override fun getChainId(): Long = chainId

    override fun getMaxFeePerGas(): BigInteger = estimateFees().maxFeePerGas

    override fun getMaxPriorityFeePerGas(): BigInteger = estimateFees().maxPriorityFeePerGas

    override fun getGasPrice(): BigInteger = estimateFees().legacyGasPrice

    override fun getGasLimit(transaction: Transaction): BigInteger = gasLimit

    override fun getGasLimit(): BigInteger = gasLimit
}
