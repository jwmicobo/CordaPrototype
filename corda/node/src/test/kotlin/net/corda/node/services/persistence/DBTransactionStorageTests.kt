package net.corda.node.services.persistence

import net.corda.core.contracts.StateRef
import net.corda.core.crypto.Crypto
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.SignatureMetadata
import net.corda.core.crypto.TransactionSignature
import net.corda.core.toFuture
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.WireTransaction
import net.corda.node.services.transactions.PersistentUniquenessProvider
import net.corda.node.internal.configureDatabase
import net.corda.node.services.config.NodeConfiguration
import net.corda.nodeapi.internal.persistence.CordaPersistence
import net.corda.nodeapi.internal.persistence.DatabaseConfig
import net.corda.testing.*
import net.corda.testing.internal.LogHelper
import net.corda.testing.internal.rigorousMock
import net.corda.testing.node.MockServices.Companion.makeTestDataSourceProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

class DBTransactionStorageTests {
    private companion object {
        val ALICE_PUBKEY = TestIdentity(ALICE_NAME, 70).publicKey
        val DUMMY_NOTARY = TestIdentity(DUMMY_NOTARY_NAME, 20).party
    }

    @Rule
    @JvmField
    val testSerialization = SerializationEnvironmentRule()

    private lateinit var database: CordaPersistence
    private lateinit var transactionStorage: DBTransactionStorage
    @Before
    fun setUp() {
        LogHelper.setLevel(PersistentUniquenessProvider::class)
        val dataSourceProps = makeTestDataSourceProperties()
        database = configureDatabase(dataSourceProps, DatabaseConfig(), rigorousMock())
        newTransactionStorage()
    }

    @After
    fun cleanUp() {
        database.close()
        LogHelper.reset(PersistentUniquenessProvider::class)
    }

    @Test
    fun `empty store`() {
        database.transaction {
            assertThat(transactionStorage.getTransaction(newTransaction().id)).isNull()
        }
        database.transaction {
            assertThat(transactionStorage.transactions).isEmpty()
        }
        newTransactionStorage()
        database.transaction {
            assertThat(transactionStorage.transactions).isEmpty()
        }
    }

    @Test
    fun `one transaction`() {
        val transaction = newTransaction()
        database.transaction {
            transactionStorage.addTransaction(transaction)
        }
        assertTransactionIsRetrievable(transaction)
        database.transaction {
            assertThat(transactionStorage.transactions).containsExactly(transaction)
        }
        newTransactionStorage()
        assertTransactionIsRetrievable(transaction)
        database.transaction {
            assertThat(transactionStorage.transactions).containsExactly(transaction)
        }
    }

    @Test
    fun `two transactions across restart`() {
        val firstTransaction = newTransaction()
        val secondTransaction = newTransaction()
        database.transaction {
            transactionStorage.addTransaction(firstTransaction)
        }
        newTransactionStorage()
        database.transaction {
            transactionStorage.addTransaction(secondTransaction)
        }
        assertTransactionIsRetrievable(firstTransaction)
        assertTransactionIsRetrievable(secondTransaction)
        database.transaction {
            assertThat(transactionStorage.transactions).containsOnly(firstTransaction, secondTransaction)
        }
    }

    @Test
    fun `two transactions with rollback`() {
        val firstTransaction = newTransaction()
        val secondTransaction = newTransaction()
        database.transaction {
            transactionStorage.addTransaction(firstTransaction)
            transactionStorage.addTransaction(secondTransaction)
            rollback()
        }

        database.transaction {
            assertThat(transactionStorage.transactions).isEmpty()
        }
    }

    @Test
    fun `two transactions in same DB transaction scope`() {
        val firstTransaction = newTransaction()
        val secondTransaction = newTransaction()
        database.transaction {
            transactionStorage.addTransaction(firstTransaction)
            transactionStorage.addTransaction(secondTransaction)
        }
        assertTransactionIsRetrievable(firstTransaction)
        assertTransactionIsRetrievable(secondTransaction)
        database.transaction {
            assertThat(transactionStorage.transactions).containsOnly(firstTransaction, secondTransaction)
        }
    }

    @Test
    fun `transaction saved twice in same DB transaction scope`() {
        val firstTransaction = newTransaction()
        database.transaction {
            transactionStorage.addTransaction(firstTransaction)
            transactionStorage.addTransaction(firstTransaction)
        }
        assertTransactionIsRetrievable(firstTransaction)
        database.transaction {
            assertThat(transactionStorage.transactions).containsOnly(firstTransaction)
        }
    }

    @Test
    fun `transaction saved twice in two DB transaction scopes`() {
        val firstTransaction = newTransaction()
        val secondTransaction = newTransaction()
        database.transaction {
            transactionStorage.addTransaction(firstTransaction)
        }

        database.transaction {
            transactionStorage.addTransaction(secondTransaction)
            transactionStorage.addTransaction(firstTransaction)
        }
        assertTransactionIsRetrievable(firstTransaction)
        database.transaction {
            assertThat(transactionStorage.transactions).containsOnly(firstTransaction, secondTransaction)
        }
    }

    @Test
    fun `updates are fired`() {
        val future = transactionStorage.updates.toFuture()
        val expected = newTransaction()
        database.transaction {
            transactionStorage.addTransaction(expected)
        }
        val actual = future.get(1, TimeUnit.SECONDS)
        assertEquals(expected, actual)
    }

    private fun newTransactionStorage() {
        database.transaction {
            transactionStorage = DBTransactionStorage(NodeConfiguration.defaultTransactionCacheSize)
        }
    }

    private fun assertTransactionIsRetrievable(transaction: SignedTransaction) {
        database.transaction {
            assertThat(transactionStorage.getTransaction(transaction.id)).isEqualTo(transaction)
        }
    }

    private fun newTransaction(): SignedTransaction {
        val wtx = WireTransaction(
                inputs = listOf(StateRef(SecureHash.randomSHA256(), 0)),
                attachments = emptyList(),
                outputs = emptyList(),
                commands = listOf(dummyCommand()),
                notary = DUMMY_NOTARY,
                timeWindow = null
        )
        return SignedTransaction(wtx, listOf(TransactionSignature(ByteArray(1), ALICE_PUBKEY, SignatureMetadata(1, Crypto.findSignatureScheme(ALICE_PUBKEY).schemeNumberID))))
    }
}
