package net.postchain.eif.bad.rest

import mu.KLogging
import net.postchain.common.toHex
import net.postchain.eif.bad.AnomalyDetector
import net.postchain.eif.bad.AnomalyDetectorStatus
import net.postchain.eif.bad.AnomalyDetectorsManager
import net.postchain.eif.bad.LogVerificationStatus
import org.http4k.core.Body
import org.http4k.core.Filter
import org.http4k.core.Method.GET
import org.http4k.core.Method.OPTIONS
import org.http4k.core.Method.POST
import org.http4k.core.Request
import org.http4k.core.RequestContexts
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.Status.Companion.BAD_REQUEST
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Status.Companion.SERVICE_UNAVAILABLE
import org.http4k.core.then
import org.http4k.core.with
import org.http4k.filter.AllowAll
import org.http4k.filter.CorsPolicy
import org.http4k.filter.OriginPolicy
import org.http4k.filter.ServerFilters
import org.http4k.format.Gson.auto
import org.http4k.format.auto
import org.http4k.lens.ContentNegotiation
import org.http4k.routing.ResourceLoader
import org.http4k.routing.bind
import org.http4k.routing.path
import org.http4k.routing.routes
import org.http4k.routing.static
import org.http4k.server.SunHttp
import org.http4k.server.asServer
import java.io.Closeable

data class ErrorBody(val error: String = "")
data class Version(val version: Int)
data class AnomalyDetectorStatusResponse(
        val blockchainRid: String,
        val status: AnomalyDetectorStatus,
        val networkId: Long,
        val tokenBridgeContractAddresses: String,
        val logsProcessed: Long,
        val anomaliesDetected: Long,
        val logsVerified: Long,
        val lastBlockNumberProcessed: Long,
)

data class AnomalyResponse(
        var height: Long,
        var brid: String,
        var lastUpdatedTimestamp: Long,
        var nextActionTimestamp: Long,
)

data class AnomaliesResponse(
        var anomalies: List<AnomalyResponse> = mutableListOf(),
        var potentialAnomalies: List<AnomalyResponse> = mutableListOf()
)

val anomaliesBody = Body.auto<AnomaliesResponse>().toLens()
val statusBody = Body.auto<List<AnomalyDetectorStatusResponse>>().toLens()
val versionBody = Body.auto<Version>().toLens()
val errorJsonBody = Body.auto<ErrorBody>().toLens()
val errorBody = ContentNegotiation.auto(errorJsonBody)

class RestApi(
        private val listenPort: Int,
        val basePath: String,
        private val anomalyDetectorsManager: AnomalyDetectorsManager
) : Closeable {

    companion object : KLogging() {
        const val REST_API_VERSION = 1
    }

    private val app = routes(
            "/" bind static(ResourceLoader.Classpath("/restapi-root")),

            "/status" bind GET to ::getStatus,
            "/anomalies/{blockchainRid}" bind GET to ::getAnomalies,

            "/version" bind GET to ::getVersion,
    )

    private fun getAnomalies(request: Request): Response {

        val bcRid = request.path("blockchainRid")
        if (bcRid.isNullOrBlank()) {
            return errorResponse(request, BAD_REQUEST, "Missing blockchainRid")
        }

        val anomalyDetector = anomalyDetectorsManager.getAnomalyDetectors()[bcRid]
        if (anomalyDetector == null) {
            return errorResponse(request, BAD_REQUEST, "No anomaly detector for blockchainRid $bcRid[0]")
        }

        return Response(OK).with(anomaliesBody of AnomaliesResponse(
                anomalies = mapAnomalies(anomalyDetector, LogVerificationStatus.ANOMALY),
                potentialAnomalies = mapAnomalies(anomalyDetector, LogVerificationStatus.RETRY)
        ))
    }

    @Suppress("UNUSED_PARAMETER")
    private fun getStatus(request: Request): Response {

        val statuses = anomalyDetectorsManager.getAnomalyDetectors()
                .map {
                    AnomalyDetectorStatusResponse(
                            it.key,
                            it.value.anomalyDetectorStatus,
                            it.value.networkId,
                            it.value.tokenBridgeContractAddresses,
                            it.value.logsProcessed,
                            it.value.anomaliesDetected,
                            it.value.logsVerified,
                            it.value.lastBlockNumberProcessed.longValueExact(),
                    )
                }

        return Response(OK).with(
                statusBody of statuses
        )
    }

    @Suppress("UNUSED_PARAMETER")
    private fun getVersion(request: Request): Response = Response(OK).with(
            versionBody of Version(REST_API_VERSION)
    )

    private val handler = routes(
            basePath bind app
    )

    private val contexts = RequestContexts()

    private val server = ServerFilters.InitialiseRequestContext(contexts)
            .then(ServerFilters.Cors(
                    CorsPolicy(OriginPolicy.AllowAll(), listOf("Content-Type", "Accept"), listOf(GET, POST, OPTIONS), credentials = false)))
            .then(Filter { next ->
                { request ->
                    try {
                        next(request)
                    } catch (e: Exception) {
                        onError(e, request)
                    }
                }
            })
            .then(ServerFilters.CatchLensFailure { request, lensFailure ->
                logger.info { "Bad request: ${lensFailure.message}" }
                Response(BAD_REQUEST).with(
                        errorBody.outbound(request) of ErrorBody(lensFailure.failures.joinToString("; "))
                )
            })
            .then(handler)
            .asServer(SunHttp(listenPort))
            .start().also {
                logger.info { "Rest API listening on port ${it.port()} and were given $listenPort, attached on $basePath/" }
            }

    private fun onError(error: Exception, request: Request): Response {
        logger.warn(error) { "Unexpected exception: $error" }
        return errorResponse(request, SERVICE_UNAVAILABLE, error.message!!)
    }

    private fun errorResponse(request: Request, status: Status, errorMessage: String): Response =
            Response(status).with(
                    errorBody.outbound(request) of ErrorBody(errorMessage)
            )

    override fun close() {
        server.close()
        System.gc()
        System.runFinalization()
    }

    private fun mapAnomalies(anomalyDetector: AnomalyDetector, status: LogVerificationStatus): List<AnomalyResponse> {

        return anomalyDetector.anomaliesCache.getAnomalies()
                .filter { it.logVerification.status == status }
                .map { AnomalyResponse(it.logVerification.height, it.logVerification.brid.toHex(), it.timestamp, it.timestamp + it.delay) }
    }
}
