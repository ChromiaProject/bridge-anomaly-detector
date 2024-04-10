package net.postchain.eif.anomaly_detector.config

import net.postchain.common.config.Config

data class RestApiConfig(
        val basePath: String = "/",
        val port: Int = 7790,
) : Config {

    init {
        require(port in -1 .. 49151) { "API port has to be between -1 (disabled) and 49151 (ephemeral)" }
    }

//    companion object {
//
//        const val DEFAULT_REST_API_PORT = 7740
//        const val DEFAULT_DEBUG_API_PORT = 7750
//
//        @JvmStatic
//        fun fromAppConfig(config: AppConfig): RestApiConfig {
//            return RestApiConfig(
//                    config.getEnvOrString("POSTCHAIN_API_BASEPATH", "api.basepath", ""),
//                    config.getEnvOrInt("POSTCHAIN_API_PORT", "api.port", DEFAULT_REST_API_PORT),
//                    config.getEnvOrInt("POSTCHAIN_DEBUG_PORT", "debug.port", DEFAULT_DEBUG_API_PORT),
//                    config.getBoolean("api.graceful-shutdown", true),
//                    config.getEnvOrInt("POSTCHAIN_API_REQUEST_CONCURRENCY", "api.request-concurrency", 0),
//                    config.getEnvOrInt("POSTCHAIN_API_CHAIN_REQUEST_CONCURRENCY", "api.chain-request-concurrency", -1),
//                    config.getEnvOrBoolean("POSTCHAIN_API_SUBNODE_HTTP_REDIRECT", "api.subnode-http-redirect", false)
//            )
//        }
//    }
}
