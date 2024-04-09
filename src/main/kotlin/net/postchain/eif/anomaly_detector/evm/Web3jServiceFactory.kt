package net.postchain.eif.anomaly_detector.evm

import okhttp3.OkHttpClient
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import org.web3j.protocol.ipc.UnixIpcService
import org.web3j.protocol.ipc.WindowsIpcService
import java.util.concurrent.TimeUnit

object Web3jServiceFactory {

    fun buildServices(urls: List<String>, connectTimeout: Long, readTimeout: Long, writeTimeout: Long): List<Web3j> =
            buildServicesMap(urls, connectTimeout, readTimeout, writeTimeout).map { it.value }

    fun buildServicesMap(urls: List<String>, connectTimeout: Long, readTimeout: Long, writeTimeout: Long): Map<String, Web3j> =
            urls.map { url ->
                val web3jService = if (url == "") {
                    HttpService(createOkHttpClient(connectTimeout, readTimeout, writeTimeout))
                } else if (url.startsWith("http")) {
                    HttpService(url, createOkHttpClient(connectTimeout, readTimeout, writeTimeout), false)
                } else if (System.getProperty("os.name").lowercase().startsWith("win")) {
                    WindowsIpcService(url)
                } else {
                    UnixIpcService(url)
                }
                url to Web3j.build(web3jService)
            }.toMap()

    private fun createOkHttpClient(connectTimeout: Long, readTimeout: Long, writeTimeout: Long): OkHttpClient {
        val builder: OkHttpClient.Builder = OkHttpClient.Builder()
        builder.connectTimeout(connectTimeout, TimeUnit.SECONDS)
        builder.readTimeout(readTimeout, TimeUnit.SECONDS) // Sets the socket timeout too
        builder.writeTimeout(writeTimeout, TimeUnit.SECONDS)
        return builder.build()
    }
}