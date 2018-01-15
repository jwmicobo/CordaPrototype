package net.corda.testing.node.internal.network

import net.corda.core.crypto.SecureHash
import net.corda.core.internal.signWithCert
import net.corda.core.node.NodeInfo
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.serialize
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.nodeapi.internal.SignedNodeInfo
import net.corda.nodeapi.internal.createDevNetworkMapCa
import net.corda.nodeapi.internal.crypto.CertificateAndKeyPair
import net.corda.nodeapi.internal.network.NetworkMap
import net.corda.nodeapi.internal.network.NetworkParameters
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.server.handler.HandlerCollection
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.servlet.ServletHolder
import org.glassfish.jersey.server.ResourceConfig
import org.glassfish.jersey.servlet.ServletContainer
import java.io.Closeable
import java.io.InputStream
import java.net.InetSocketAddress
import java.security.SignatureException
import java.time.Duration
import java.time.Instant
import javax.ws.rs.*
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response
import javax.ws.rs.core.Response.ok
import javax.ws.rs.core.Response.status

class NetworkMapServer(private val cacheTimeout: Duration,
                       hostAndPort: NetworkHostAndPort,
                       private val networkMapCa: CertificateAndKeyPair = createDevNetworkMapCa(),
                       private val myHostNameValue: String = "test.host.name",
                       vararg additionalServices: Any) : Closeable {
    companion object {
        private val stubNetworkParameters = NetworkParameters(1, emptyList(), 10485760, 40000, Instant.now(), 10)
    }

    private val server: Server
    var networkParameters: NetworkParameters = stubNetworkParameters
        set(networkParameters) {
            check(field == stubNetworkParameters) { "Network parameters can be set only once" }
            field = networkParameters
        }
    private val service = InMemoryNetworkMapService()

    init {
        server = Server(InetSocketAddress(hostAndPort.host, hostAndPort.port)).apply {
            handler = HandlerCollection().apply {
                addHandler(ServletContextHandler().apply {
                    contextPath = "/"
                    val resourceConfig = ResourceConfig().apply {
                        // Add your API provider classes (annotated for JAX-RS) here
                        register(service)
                        additionalServices.forEach { register(it) }
                    }
                    val jerseyServlet = ServletHolder(ServletContainer(resourceConfig)).apply { initOrder = 0 } // Initialise at server start
                    addServlet(jerseyServlet, "/*")
                })
            }
        }
    }

    fun start(): NetworkHostAndPort {
        server.start()
        // Wait until server is up to obtain the host and port.
        while (!server.isStarted) {
            Thread.sleep(500)
        }
        return server.connectors
                .mapNotNull { it as? ServerConnector }
                .first()
                .let { NetworkHostAndPort(it.host, it.localPort) }
    }

    fun removeNodeInfo(nodeInfo: NodeInfo) {
        service.removeNodeInfo(nodeInfo)
    }

    override fun close() {
        server.stop()
    }

    @Path("network-map")
    inner class InMemoryNetworkMapService {
        private val nodeInfoMap = mutableMapOf<SecureHash, SignedNodeInfo>()
        private val signedNetParams by lazy {
            networkParameters.signWithCert(networkMapCa.keyPair.private, networkMapCa.certificate)
        }

        @POST
        @Path("publish")
        @Consumes(MediaType.APPLICATION_OCTET_STREAM)
        fun publishNodeInfo(input: InputStream): Response {
            return try {
                val signedNodeInfo = input.readBytes().deserialize<SignedNodeInfo>()
                signedNodeInfo.verified()
                nodeInfoMap[signedNodeInfo.raw.hash] = signedNodeInfo
                ok()
            } catch (e: Exception) {
                when (e) {
                    is SignatureException -> status(Response.Status.FORBIDDEN).entity(e.message)
                    else -> status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.message)
                }
            }.build()
        }

        @GET
        @Produces(MediaType.APPLICATION_OCTET_STREAM)
        fun getNetworkMap(): Response {
            val networkMap = NetworkMap(nodeInfoMap.keys.toList(), signedNetParams.raw.hash)
            val signedNetworkMap = networkMap.signWithCert(networkMapCa.keyPair.private, networkMapCa.certificate)
            return Response.ok(signedNetworkMap.serialize().bytes).header("Cache-Control", "max-age=${cacheTimeout.seconds}").build()
        }

        // Remove nodeInfo for testing.
        fun removeNodeInfo(nodeInfo: NodeInfo) {
            nodeInfoMap.remove(nodeInfo.serialize().hash)
        }

        @GET
        @Path("node-info/{var}")
        @Produces(MediaType.APPLICATION_OCTET_STREAM)
        fun getNodeInfo(@PathParam("var") nodeInfoHash: String): Response {
            val signedNodeInfo = nodeInfoMap[SecureHash.parse(nodeInfoHash)]
            return if (signedNodeInfo != null) {
                Response.ok(signedNodeInfo.serialize().bytes)
            } else {
                Response.status(Response.Status.NOT_FOUND)
            }.build()
        }

        @GET
        @Path("network-parameters/{var}")
        @Produces(MediaType.APPLICATION_OCTET_STREAM)
        fun getNetworkParameter(@PathParam("var") hash: String): Response {
            require(signedNetParams.raw.hash == SecureHash.parse(hash))
            return Response.ok(signedNetParams.serialize().bytes).build()
        }

        @GET
        @Path("my-hostname")
        fun getHostName(): Response = Response.ok(myHostNameValue).build()
    }
}
