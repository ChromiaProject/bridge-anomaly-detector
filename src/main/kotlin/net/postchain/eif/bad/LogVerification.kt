package net.postchain.eif.bad

import org.web3j.protocol.core.methods.response.Log

data class LogVerification(
        val log: Log,
        val height: Long,
        val brid: ByteArray,
        var status: LogVerificationStatus = LogVerificationStatus.UNKNOWN
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as LogVerification

        if (log != other.log) return false
        if (height != other.height) return false
        if (!brid.contentEquals(other.brid)) return false
        if (status != other.status) return false

        return true
    }

    override fun hashCode(): Int {
        var result = log.hashCode()
        result = 31 * result + height.hashCode()
        result = 31 * result + brid.contentHashCode()
        result = 31 * result + status.hashCode()
        return result
    }
}
