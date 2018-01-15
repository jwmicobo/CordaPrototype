package net.corda.node.services.network

import com.google.common.util.concurrent.MoreExecutors
import net.corda.core.crypto.SecureHash
import net.corda.core.internal.SignedDataWithCert
import net.corda.core.internal.checkOkResponse
import net.corda.core.internal.openHttpConnection
import net.corda.core.internal.responseAs
import net.corda.core.node.NodeInfo
import net.corda.core.serialization.serialize
import net.corda.core.utilities.contextLogger
import net.corda.core.utilities.minutes
import net.corda.core.utilities.seconds
import net.corda.node.services.api.NetworkMapCacheInternal
import net.corda.node.utilities.NamedThreadFactory
import net.corda.nodeapi.internal.SignedNodeInfo
import net.corda.nodeapi.internal.network.NetworkMap
import net.corda.nodeapi.internal.network.NetworkParameters
import net.corda.nodeapi.internal.network.verifiedNetworkMapCert
import okhttp3.CacheControl
import okhttp3.Headers
import rx.Subscription
import java.io.BufferedReader
import java.io.Closeable
import java.net.URL
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NetworkMapClient(compatibilityZoneURL: URL, private val trustedRoot: X509Certificate) {
    private val networkMapUrl = URL("$compatibilityZoneURL/network-map")

    fun publish(signedNodeInfo: SignedNodeInfo) {
        val publishURL = URL("$networkMapUrl/publish")
        publishURL.openHttpConnection().apply {
            doOutput = true
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/octet-stream")
            outputStream.use { signedNodeInfo.serialize().open().copyTo(it) }
            checkOkResponse()
        }
    }

    fun getNetworkMap(): NetworkMapResponse {
        val connection = networkMapUrl.openHttpConnection()
        val signedNetworkMap = connection.responseAs<SignedDataWithCert<NetworkMap>>()
        val networkMap = signedNetworkMap.verifiedNetworkMapCert(trustedRoot)
        val timeout = CacheControl.parse(Headers.of(connection.headerFields.filterKeys { it != null }.mapValues { it.value[0] })).maxAgeSeconds().seconds
        return NetworkMapResponse(networkMap, timeout)
    }

    fun getNodeInfo(nodeInfoHash: SecureHash): NodeInfo {
        return URL("$networkMapUrl/node-info/$nodeInfoHash").openHttpConnection().responseAs<SignedNodeInfo>().verified()
    }

    fun getNetworkParameters(networkParameterHash: SecureHash): SignedDataWithCert<NetworkParameters> {
        return URL("$networkMapUrl/network-parameters/$networkParameterHash").openHttpConnection().responseAs()
    }

    fun myPublicHostname(): String {
        val connection = URL("$networkMapUrl/my-hostname").openHttpConnection()
        return connection.inputStream.bufferedReader().use(BufferedReader::readLine)
    }
}

data class NetworkMapResponse(val networkMap: NetworkMap, val cacheMaxAge: Duration)

class NetworkMapUpdater(private val networkMapCache: NetworkMapCacheInternal,
                        private val fileWatcher: NodeInfoWatcher,
                        private val networkMapClient: NetworkMapClient?,
                        private val currentParametersHash: SecureHash) : Closeable {
    companion object {
        private val logger = contextLogger()
        private val retryInterval = 1.minutes
    }

    private val executor = Executors.newSingleThreadScheduledExecutor(NamedThreadFactory("Network Map Updater Thread", Executors.defaultThreadFactory()))
    private var fileWatcherSubscription: Subscription? = null

    override fun close() {
        fileWatcherSubscription?.unsubscribe()
        MoreExecutors.shutdownAndAwaitTermination(executor, 50, TimeUnit.SECONDS)
    }

    fun updateNodeInfo(newInfo: NodeInfo, signNodeInfo: (NodeInfo) -> SignedNodeInfo) {
        val oldInfo = networkMapCache.getNodeByLegalIdentity(newInfo.legalIdentities.first())
        // Compare node info without timestamp.
        if (newInfo.copy(serial = 0L) == oldInfo?.copy(serial = 0L)) return

        // Only publish and write to disk if there are changes to the node info.
        val signedNodeInfo = signNodeInfo(newInfo)
        networkMapCache.addNode(newInfo)
        fileWatcher.saveToFile(signedNodeInfo)

        if (networkMapClient != null) {
            tryPublishNodeInfoAsync(signedNodeInfo, networkMapClient)
        }
    }

    fun subscribeToNetworkMap() {
        require(fileWatcherSubscription == null) { "Should not call this method twice." }
        // Subscribe to file based networkMap
        fileWatcherSubscription = fileWatcher.nodeInfoUpdates().subscribe(networkMapCache::addNode)

        if (networkMapClient == null) return
        // Subscribe to remote network map if configured.
        val task = object : Runnable {
            override fun run() {
                val nextScheduleDelay = try {
                    val (networkMap, cacheTimeout) = networkMapClient.getNetworkMap()
                    // TODO NetworkParameters updates are not implemented yet. Every mismatch should result in node shutdown.
                    if (currentParametersHash != networkMap.networkParameterHash) {
                        logger.error("Node is using parameters with hash: $currentParametersHash but network map is advertising: ${networkMap.networkParameterHash}.\n" +
                                "Please update node to use correct network parameters file.\"")
                        System.exit(1)
                    }
                    val currentNodeHashes = networkMapCache.allNodeHashes
                    val hashesFromNetworkMap = networkMap.nodeInfoHashes
                    (hashesFromNetworkMap - currentNodeHashes).mapNotNull {
                        // Download new node info from network map
                        try {
                            networkMapClient.getNodeInfo(it)
                        } catch (e: Exception) {
                            // Failure to retrieve one node info shouldn't stop the whole update, log and return null instead.
                            logger.warn("Error encountered when downloading node info '$it', skipping...", e)
                            null
                        }
                    }.forEach {
                        // Add new node info to the network map cache, these could be new node info or modification of node info for existing nodes.
                        networkMapCache.addNode(it)
                    }
                    // Remove node info from network map.
                    (currentNodeHashes - hashesFromNetworkMap - fileWatcher.processedNodeInfoHashes)
                            .mapNotNull(networkMapCache::getNodeByHash)
                            .forEach(networkMapCache::removeNode)
                    cacheTimeout
                } catch (t: Throwable) {
                    logger.warn("Error encountered while updating network map, will retry in ${retryInterval.seconds} seconds", t)
                    retryInterval
                }
                // Schedule the next update.
                executor.schedule(this, nextScheduleDelay.toMillis(), TimeUnit.MILLISECONDS)
            }
        }
        executor.submit(task) // The check may be expensive, so always run it in the background even the first time.
    }

    private fun tryPublishNodeInfoAsync(signedNodeInfo: SignedNodeInfo, networkMapClient: NetworkMapClient) {
        val task = object : Runnable {
            override fun run() {
                try {
                    networkMapClient.publish(signedNodeInfo)
                } catch (t: Throwable) {
                    logger.warn("Error encountered while publishing node info, will retry in ${retryInterval.seconds} seconds.", t)
                    // TODO: Exponential backoff?
                    executor.schedule(this, retryInterval.toMillis(), TimeUnit.MILLISECONDS)
                }
            }
        }
        executor.submit(task)
    }
}