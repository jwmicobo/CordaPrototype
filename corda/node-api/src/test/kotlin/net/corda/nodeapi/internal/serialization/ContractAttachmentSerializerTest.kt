package net.corda.nodeapi.internal.serialization

import net.corda.core.contracts.ContractAttachment
import net.corda.core.identity.CordaX500Name
import net.corda.core.serialization.*
import net.corda.testing.*
import net.corda.testing.contracts.DummyContract
import net.corda.testing.internal.rigorousMock
import net.corda.testing.node.MockServices
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Assert.assertArrayEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class ContractAttachmentSerializerTest {

    @Rule
    @JvmField
    val testSerialization = SerializationEnvironmentRule()

    private lateinit var factory: SerializationFactory
    private lateinit var context: SerializationContext
    private lateinit var contextWithToken: SerializationContext
    private val mockServices = MockServices(emptyList(), rigorousMock(), CordaX500Name("MegaCorp", "London", "GB"))
    @Before
    fun setup() {
        factory = testSerialization.env.serializationFactory
        context = testSerialization.env.checkpointContext
        contextWithToken = context.withTokenContext(SerializeAsTokenContextImpl(Any(), factory, context, mockServices))
    }

    @Test
    fun `write contract attachment and read it back`() {
        val contractAttachment = ContractAttachment(GeneratedAttachment(ByteArray(0)), DummyContract.PROGRAM_ID)
        // no token context so will serialize the whole attachment
        val serialized = contractAttachment.serialize(factory, context)
        val deserialized = serialized.deserialize(factory, context)

        assertEquals(contractAttachment.id, deserialized.attachment.id)
        assertEquals(contractAttachment.contract, deserialized.contract)
        assertArrayEquals(contractAttachment.open().readBytes(), deserialized.open().readBytes())
    }

    @Test
    fun `write contract attachment and read it back using token context`() {
        val attachment = GeneratedAttachment("test".toByteArray())

        mockServices.attachments.importAttachment(attachment.open())

        val contractAttachment = ContractAttachment(attachment, DummyContract.PROGRAM_ID)
        val serialized = contractAttachment.serialize(factory, contextWithToken)
        val deserialized = serialized.deserialize(factory, contextWithToken)

        assertEquals(contractAttachment.id, deserialized.attachment.id)
        assertEquals(contractAttachment.contract, deserialized.contract)
        assertArrayEquals(contractAttachment.open().readBytes(), deserialized.open().readBytes())
    }

    @Test
    fun `check only serialize attachment id and contract class name when using token context`() {
        val largeAttachmentSize = 1024 * 1024
        val attachment = GeneratedAttachment(ByteArray(largeAttachmentSize))

        mockServices.attachments.importAttachment(attachment.open())

        val contractAttachment = ContractAttachment(attachment, DummyContract.PROGRAM_ID)
        val serialized = contractAttachment.serialize(factory, contextWithToken)

        assertThat(serialized.size).isLessThan(largeAttachmentSize)
    }

    @Test
    fun `throws when missing attachment when using token context`() {
        val attachment = GeneratedAttachment("test".toByteArray())

        // don't importAttachment in mockService

        val contractAttachment = ContractAttachment(attachment, DummyContract.PROGRAM_ID)
        val serialized = contractAttachment.serialize(factory, contextWithToken)
        val deserialized = serialized.deserialize(factory, contextWithToken)

        assertThatThrownBy { deserialized.attachment.open() }.isInstanceOf(MissingAttachmentsException::class.java)
    }

    @Test
    fun `check attachment in deserialize is lazy loaded when using token context`() {
        val attachment = GeneratedAttachment(ByteArray(0))

        // don't importAttachment in mockService

        val contractAttachment = ContractAttachment(attachment, DummyContract.PROGRAM_ID)
        val serialized = contractAttachment.serialize(factory, contextWithToken)
        serialized.deserialize(factory, contextWithToken)

        // MissingAttachmentsException thrown if we try to open attachment
    }
}


