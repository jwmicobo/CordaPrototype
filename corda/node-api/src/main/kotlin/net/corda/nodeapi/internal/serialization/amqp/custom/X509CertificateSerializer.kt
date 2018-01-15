package net.corda.nodeapi.internal.serialization.amqp.custom

import net.corda.nodeapi.internal.crypto.X509CertificateFactory
import net.corda.nodeapi.internal.serialization.amqp.*
import org.apache.qpid.proton.codec.Data
import java.lang.reflect.Type
import java.security.cert.X509Certificate

object X509CertificateSerializer : CustomSerializer.Implements<X509Certificate>(X509Certificate::class.java) {
    override val schemaForDocumentation = Schema(listOf(RestrictedType(
            type.toString(),
            "",
            listOf(type.toString()),
            SerializerFactory.primitiveTypeName(ByteArray::class.java)!!,
            descriptor,
            emptyList()
    )))

    override fun writeDescribedObject(obj: X509Certificate, data: Data, type: Type, output: SerializationOutput) {
        output.writeObject(obj.encoded, data, clazz)
    }

    override fun readObject(obj: Any, schemas: SerializationSchemas, input: DeserializationInput): X509Certificate {
        val bits = input.readObject(obj, schemas, ByteArray::class.java) as ByteArray
        return X509CertificateFactory().generateCertificate(bits.inputStream())
    }
}
