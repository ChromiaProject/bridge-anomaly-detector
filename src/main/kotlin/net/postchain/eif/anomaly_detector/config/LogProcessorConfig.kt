package net.postchain.eif.anomaly_detector.config

data class LogProcessorConfig(
        val readOffset: Long,
        val maxBlockRangePerRequest: Long,
        val delayWhenNoNewBlock: Long,
)
