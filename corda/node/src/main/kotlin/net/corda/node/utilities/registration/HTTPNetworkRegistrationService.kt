package net.corda.node.utilities.registration

import com.google.common.net.MediaType
import net.corda.core.internal.openHttpConnection
import net.corda.nodeapi.internal.crypto.X509CertificateFactory
import org.apache.commons.io.IOUtils
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import java.io.IOException
import java.net.HttpURLConnection
import java.net.HttpURLConnection.*
import java.net.URL
import java.security.cert.Certificate
import java.util.*
import java.util.zip.ZipInputStream

class HTTPNetworkRegistrationService(compatibilityZoneURL: URL) : NetworkRegistrationService {
    private val registrationURL = URL("$compatibilityZoneURL/certificate")

    companion object {
        // TODO: Propagate version information from gradle
        val clientVersion = "1.0"
    }

    @Throws(CertificateRequestException::class)
    override fun retrieveCertificates(requestId: String): Array<Certificate>? {
        // Poll server to download the signed certificate once request has been approved.
        val conn = URL("$registrationURL/$requestId").openHttpConnection()
        conn.requestMethod = "GET"

        return when (conn.responseCode) {
            HTTP_OK -> ZipInputStream(conn.inputStream).use {
                val certificates = ArrayList<Certificate>()
                val factory = X509CertificateFactory()
                while (it.nextEntry != null) {
                    certificates += factory.generateCertificate(it)
                }
                certificates.toTypedArray()
            }
            HTTP_NO_CONTENT -> null
            HTTP_UNAUTHORIZED -> throw CertificateRequestException("Certificate signing request has been rejected: ${conn.errorMessage}")
            else -> throwUnexpectedResponseCode(conn)
        }
    }

    override fun submitRequest(request: PKCS10CertificationRequest): String {
        // Post request to certificate signing server via http.
        val conn = URL("$registrationURL").openHttpConnection()
        conn.doOutput = true
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        conn.setRequestProperty("Client-Version", clientVersion)
        conn.outputStream.write(request.encoded)

        return when (conn.responseCode) {
            HTTP_OK -> IOUtils.toString(conn.inputStream, conn.charset)
            HTTP_FORBIDDEN -> throw IOException("Client version $clientVersion is forbidden from accessing permissioning server, please upgrade to newer version.")
            else -> throwUnexpectedResponseCode(conn)
        }
    }

    private fun throwUnexpectedResponseCode(connection: HttpURLConnection): Nothing {
        throw IOException("Unexpected response code ${connection.responseCode} - ${connection.errorMessage}")
    }

    private val HttpURLConnection.charset: String get() = MediaType.parse(contentType).charset().or(Charsets.UTF_8).name()

    private val HttpURLConnection.errorMessage: String get() = IOUtils.toString(errorStream, charset)
}
