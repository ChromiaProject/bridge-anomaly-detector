package net.postchain.eif.bad

import org.testcontainers.containers.BindMode
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.InternetProtocol
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy
import org.testcontainers.utility.DockerImageName
import java.time.Duration

class GethContainer(logger: Slf4jLogConsumer, private val gethHost: String = "evm-host", private val gethPort: Int = 8545) : GenericContainer<GethContainer>(
        DockerImageName.parse("ethereum/client-go:v1.13.5")
) {

    init {
        super.addFixedExposedPort(gethPort, gethPort, InternetProtocol.TCP)
        super.withNetworkAliases(gethHost)
        super.withLogConsumer(logger)
        super.withClasspathResourceMapping(this::class.java.getResource("evm/geth")!!.path.substringAfter("test-classes/"), "/geth", BindMode.READ_ONLY)
        super.withCreateContainerCmdModifier { cmd -> cmd.withEntrypoint("./geth/start.sh") }
        waitStrategy = LogMessageWaitStrategy()
                .withRegEx(".*HTTP server started.*\\s")
                .withTimes(1).withStartupTimeout(Duration.ofMinutes(2))
    }

    fun getNetworkGethUrl(): String = "http://$gethHost:$gethPort"

    fun getExternalGethUrl(): String = "http://$host:${getMappedPort(gethPort)}"
}