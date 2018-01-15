package net.corda.nodeapi.internal.serialization.amqp.custom

import net.corda.core.crypto.Crypto
import net.corda.nodeapi.internal.serialization.amqp.*
import org.apache.qpid.proton.codec.Data
import java.lang.reflect.Type
import java.security.PublicKey

/**
 * A serializer that writes out a public key in X.509 format.
 */
object PublicKeySerializer : CustomSerializer.Implements<PublicKey>(PublicKey::class.java) {
    override val schemaForDocumentation = Schema(listOf(RestrictedType(type.toString(), "", listOf(type.toString()), SerializerFactory.primitiveTypeName(ByteArray::class.java)!!, descriptor, emptyList())))

    override fun writeDescribedObject(obj: PublicKey, data: Data, type: Type, output: SerializationOutput) {
        // TODO: Instead of encoding to the default X509 format, we could have a custom per key type (space-efficient) serialiser.
        output.writeObject(obj.encoded, data, clazz)
    }

    override fun readObject(obj: Any, schemas: SerializationSchemas, input: DeserializationInput): PublicKey {
        val bits = input.readObject(obj, schemas, ByteArray::class.java) as ByteArray
        return Crypto.decodePublicKey(bits)
    }
}