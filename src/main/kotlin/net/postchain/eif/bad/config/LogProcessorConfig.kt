package net.postchain.eif.bad.config

data class LogProcessorConfig(
        val readOffset: Long,
        val maxBlockRangePerRequest: Long,
        val delayWhenNoNewBlock: Long,
)
