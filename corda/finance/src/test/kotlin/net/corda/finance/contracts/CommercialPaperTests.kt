package net.corda.finance.contracts

import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.whenever
import net.corda.core.contracts.*
import net.corda.core.crypto.generateKeyPair
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.node.services.Vault
import net.corda.core.node.services.VaultService
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.days
import net.corda.core.utilities.seconds
import net.corda.finance.DOLLARS
import net.corda.finance.`issued by`
import net.corda.finance.contracts.asset.*
import net.corda.node.services.api.IdentityServiceInternal
import net.corda.testing.*
import net.corda.testing.dsl.EnforceVerifyOrFail
import net.corda.testing.dsl.TransactionDSL
import net.corda.testing.dsl.TransactionDSLInterpreter
import net.corda.testing.internal.rigorousMock
import net.corda.testing.internal.vault.VaultFiller
import net.corda.testing.node.MockServices
import net.corda.testing.node.MockServices.Companion.makeTestDatabaseAndMockServices
import net.corda.testing.node.ledger
import net.corda.testing.node.makeTestIdentityService
import net.corda.testing.node.transaction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.time.Instant
import java.util.*
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// TODO: The generate functions aren't tested by these tests: add them.

interface ICommercialPaperTestTemplate {
    fun getPaper(): ICommercialPaperState
    fun getIssueCommand(notary: Party): CommandData
    fun getRedeemCommand(notary: Party): CommandData
    fun getMoveCommand(): CommandData
    fun getContract(): ContractClassName
}

private val megaCorp = TestIdentity(CordaX500Name("MegaCorp", "London", "GB"))
private val MEGA_CORP get() = megaCorp.party
private val MEGA_CORP_IDENTITY get() = megaCorp.identity
private val MEGA_CORP_PUBKEY get() = megaCorp.publicKey

class JavaCommercialPaperTest : ICommercialPaperTestTemplate {
    override fun getPaper(): ICommercialPaperState = JavaCommercialPaper.State(
            MEGA_CORP.ref(123),
            MEGA_CORP,
            1000.DOLLARS `issued by` MEGA_CORP.ref(123),
            TEST_TX_TIME + 7.days
    )

    override fun getIssueCommand(notary: Party): CommandData = JavaCommercialPaper.Commands.Issue()
    override fun getRedeemCommand(notary: Party): CommandData = JavaCommercialPaper.Commands.Redeem()
    override fun getMoveCommand(): CommandData = JavaCommercialPaper.Commands.Move()
    override fun getContract() = JavaCommercialPaper.JCP_PROGRAM_ID
}

class KotlinCommercialPaperTest : ICommercialPaperTestTemplate {
    override fun getPaper(): ICommercialPaperState = CommercialPaper.State(
            issuance = MEGA_CORP.ref(123),
            owner = MEGA_CORP,
            faceValue = 1000.DOLLARS `issued by` MEGA_CORP.ref(123),
            maturityDate = TEST_TX_TIME + 7.days
    )

    override fun getIssueCommand(notary: Party): CommandData = CommercialPaper.Commands.Issue()
    override fun getRedeemCommand(notary: Party): CommandData = CommercialPaper.Commands.Redeem()
    override fun getMoveCommand(): CommandData = CommercialPaper.Commands.Move()
    override fun getContract() = CommercialPaper.CP_PROGRAM_ID
}

class KotlinCommercialPaperLegacyTest : ICommercialPaperTestTemplate {
    override fun getPaper(): ICommercialPaperState = CommercialPaper.State(
            issuance = MEGA_CORP.ref(123),
            owner = MEGA_CORP,
            faceValue = 1000.DOLLARS `issued by` MEGA_CORP.ref(123),
            maturityDate = TEST_TX_TIME + 7.days
    )

    override fun getIssueCommand(notary: Party): CommandData = CommercialPaper.Commands.Issue()
    override fun getRedeemCommand(notary: Party): CommandData = CommercialPaper.Commands.Redeem()
    override fun getMoveCommand(): CommandData = CommercialPaper.Commands.Move()
    override fun getContract() = CommercialPaper.CP_PROGRAM_ID
}

@RunWith(Parameterized::class)
class CommercialPaperTestsGeneric {
    companion object {
        @Parameterized.Parameters
        @JvmStatic
        fun data() = listOf(JavaCommercialPaperTest(), KotlinCommercialPaperTest(), KotlinCommercialPaperLegacyTest())

        private val dummyCashIssuer = TestIdentity(CordaX500Name("Snake Oil Issuer", "London", "GB"), 10)
        private val DUMMY_CASH_ISSUER_IDENTITY get() = dummyCashIssuer.identity
        private val DUMMY_CASH_ISSUER = dummyCashIssuer.ref(1)
        private val alice = TestIdentity(ALICE_NAME, 70)
        private val BIG_CORP_KEY = generateKeyPair()
        private val dummyNotary = TestIdentity(DUMMY_NOTARY_NAME, 20)
        private val miniCorp = TestIdentity(CordaX500Name("MiniCorp", "London", "GB"))
        private val ALICE get() = alice.party
        private val ALICE_KEY get() = alice.keyPair
        private val ALICE_PUBKEY get() = alice.publicKey
        private val DUMMY_NOTARY get() = dummyNotary.party
        private val DUMMY_NOTARY_IDENTITY get() = dummyNotary.identity
        private val MINI_CORP get() = miniCorp.party
        private val MINI_CORP_IDENTITY get() = miniCorp.identity
        private val MINI_CORP_PUBKEY get() = miniCorp.publicKey
    }

    @Parameterized.Parameter
    lateinit var thisTest: ICommercialPaperTestTemplate
    @Rule
    @JvmField
    val testSerialization = SerializationEnvironmentRule()
    val issuer = MEGA_CORP.ref(123)
    private val ledgerServices = MockServices(emptyList(), rigorousMock<IdentityServiceInternal>().also {
        doReturn(MEGA_CORP).whenever(it).partyFromKey(MEGA_CORP_PUBKEY)
        doReturn(MINI_CORP).whenever(it).partyFromKey(MINI_CORP_PUBKEY)
        doReturn(null).whenever(it).partyFromKey(ALICE_PUBKEY)
    }, MEGA_CORP.name)

    @Test
    fun `trade lifecycle test`() {
        val someProfits = 1200.DOLLARS `issued by` issuer
        ledgerServices.ledger(DUMMY_NOTARY) {
            unverifiedTransaction {
                attachment(Cash.PROGRAM_ID)
                output(Cash.PROGRAM_ID, "alice's $900", 900.DOLLARS.CASH issuedBy issuer ownedBy ALICE)
                output(Cash.PROGRAM_ID, "some profits", someProfits.STATE ownedBy MEGA_CORP)
            }

            // Some CP is issued onto the ledger by MegaCorp.
            transaction("Issuance") {
                attachments(CP_PROGRAM_ID, JavaCommercialPaper.JCP_PROGRAM_ID)
                output(thisTest.getContract(), "paper", thisTest.getPaper())
                command(MEGA_CORP_PUBKEY, thisTest.getIssueCommand(DUMMY_NOTARY))
                timeWindow(TEST_TX_TIME)
                this.verifies()
            }

            // The CP is sold to alice for her $900, $100 less than the face value. At 10% interest after only 7 days,
            // that sounds a bit too good to be true!
            transaction("Trade") {
                attachments(Cash.PROGRAM_ID, JavaCommercialPaper.JCP_PROGRAM_ID)
                input("paper")
                input("alice's $900")
                output(Cash.PROGRAM_ID, "borrowed $900", 900.DOLLARS.CASH issuedBy issuer ownedBy MEGA_CORP)
                output(thisTest.getContract(), "alice's paper", "paper".output<ICommercialPaperState>().withOwner(ALICE))
                command(ALICE_PUBKEY, Cash.Commands.Move())
                command(MEGA_CORP_PUBKEY, thisTest.getMoveCommand())
                this.verifies()
            }

            // Time passes, and Alice redeem's her CP for $1000, netting a $100 profit. MegaCorp has received $1200
            // as a single payment from somewhere and uses it to pay Alice off, keeping the remaining $200 as change.
            transaction("Redemption") {
                attachments(CP_PROGRAM_ID, JavaCommercialPaper.JCP_PROGRAM_ID)
                input("alice's paper")
                input("some profits")

                fun TransactionDSL<TransactionDSLInterpreter>.outputs(aliceGetsBack: Amount<Issued<Currency>>) {
                    output(Cash.PROGRAM_ID, "Alice's profit", aliceGetsBack.STATE ownedBy ALICE)
                    output(Cash.PROGRAM_ID, "Change", (someProfits - aliceGetsBack).STATE ownedBy MEGA_CORP)
                }
                command(MEGA_CORP_PUBKEY, Cash.Commands.Move())
                command(ALICE_PUBKEY, thisTest.getRedeemCommand(DUMMY_NOTARY))
                tweak {
                    outputs(700.DOLLARS `issued by` issuer)
                    timeWindow(TEST_TX_TIME + 8.days)
                    this `fails with` "received amount equals the face value"
                }
                outputs(1000.DOLLARS `issued by` issuer)


                tweak {
                    timeWindow(TEST_TX_TIME + 2.days)
                    this `fails with` "must have matured"
                }
                timeWindow(TEST_TX_TIME + 8.days)

                tweak {
                    output(thisTest.getContract(), "paper".output<ICommercialPaperState>())
                    this `fails with` "must be destroyed"
                }

                this.verifies()
            }
        }
    }

    private fun transaction(script: TransactionDSL<TransactionDSLInterpreter>.() -> EnforceVerifyOrFail) = run {
        ledgerServices.transaction(DUMMY_NOTARY, script)
    }

    @Test
    fun `key mismatch at issue`() {
        transaction {
            attachment(CP_PROGRAM_ID)
            attachment(JavaCommercialPaper.JCP_PROGRAM_ID)
            output(thisTest.getContract(), thisTest.getPaper())
            command(MINI_CORP_PUBKEY, thisTest.getIssueCommand(DUMMY_NOTARY))
            timeWindow(TEST_TX_TIME)
            this `fails with` "output states are issued by a command signer"
        }
    }

    @Test
    fun `face value is not zero`() {
        transaction {
            attachment(CP_PROGRAM_ID)
            attachment(JavaCommercialPaper.JCP_PROGRAM_ID)
            output(thisTest.getContract(), thisTest.getPaper().withFaceValue(0.DOLLARS `issued by` issuer))
            command(MEGA_CORP_PUBKEY, thisTest.getIssueCommand(DUMMY_NOTARY))
            timeWindow(TEST_TX_TIME)
            this `fails with` "output values sum to more than the inputs"
        }
    }

    @Test
    fun `maturity date not in the past`() {
        transaction {
            attachment(CP_PROGRAM_ID)
            attachment(JavaCommercialPaper.JCP_PROGRAM_ID)
            output(thisTest.getContract(), thisTest.getPaper().withMaturityDate(TEST_TX_TIME - 10.days))
            command(MEGA_CORP_PUBKEY, thisTest.getIssueCommand(DUMMY_NOTARY))
            timeWindow(TEST_TX_TIME)
            this `fails with` "maturity date is not in the past"
        }
    }

    @Test
    fun `issue cannot replace an existing state`() {
        transaction {
            attachment(CP_PROGRAM_ID)
            attachment(JavaCommercialPaper.JCP_PROGRAM_ID)
            input(thisTest.getContract(), thisTest.getPaper())
            output(thisTest.getContract(), thisTest.getPaper())
            command(MEGA_CORP_PUBKEY, thisTest.getIssueCommand(DUMMY_NOTARY))
            timeWindow(TEST_TX_TIME)
            this `fails with` "output values sum to more than the inputs"
        }
    }

    /**
     *  Unit test requires two separate Database instances to represent each of the two
     *  transaction participants (enforces uniqueness of vault content in lieu of partipant identity)
     */

    private lateinit var bigCorpServices: MockServices
    private lateinit var bigCorpVault: Vault<ContractState>
    private lateinit var bigCorpVaultService: VaultService

    private lateinit var aliceServices: MockServices
    private lateinit var aliceVaultService: VaultService
    private lateinit var alicesVault: Vault<ContractState>
    private val notaryServices = MockServices(emptyList(), rigorousMock(), MEGA_CORP.name, dummyNotary.keyPair)
    private val issuerServices = MockServices(listOf("net.corda.finance.contracts"), rigorousMock(), MEGA_CORP.name, dummyCashIssuer.keyPair)
    private lateinit var moveTX: SignedTransaction
    @Test
    fun `issue move and then redeem`() {
        val aliceDatabaseAndServices = makeTestDatabaseAndMockServices(
                listOf("net.corda.finance.contracts"),
                makeTestIdentityService(MEGA_CORP_IDENTITY, MINI_CORP_IDENTITY, DUMMY_CASH_ISSUER_IDENTITY, DUMMY_NOTARY_IDENTITY),
                TestIdentity(MEGA_CORP.name, ALICE_KEY))
        val databaseAlice = aliceDatabaseAndServices.first
        aliceServices = aliceDatabaseAndServices.second
        aliceVaultService = aliceServices.vaultService

        databaseAlice.transaction {
            alicesVault = VaultFiller(aliceServices, dummyNotary, rngFactory = ::Random).fillWithSomeTestCash(9000.DOLLARS, issuerServices, 1, DUMMY_CASH_ISSUER)
            aliceVaultService = aliceServices.vaultService
        }
        val bigCorpDatabaseAndServices = makeTestDatabaseAndMockServices(
                listOf("net.corda.finance.contracts"),
                makeTestIdentityService(MEGA_CORP_IDENTITY, MINI_CORP_IDENTITY, DUMMY_CASH_ISSUER_IDENTITY, DUMMY_NOTARY_IDENTITY),
                TestIdentity(MEGA_CORP.name, BIG_CORP_KEY))
        val databaseBigCorp = bigCorpDatabaseAndServices.first
        bigCorpServices = bigCorpDatabaseAndServices.second
        bigCorpVaultService = bigCorpServices.vaultService

        databaseBigCorp.transaction {
            bigCorpVault = VaultFiller(bigCorpServices, dummyNotary, rngFactory = ::Random).fillWithSomeTestCash(13000.DOLLARS, issuerServices, 1, DUMMY_CASH_ISSUER)
            bigCorpVaultService = bigCorpServices.vaultService
        }

        // Propagate the cash transactions to each side.
        aliceServices.recordTransactions(bigCorpVault.states.map { bigCorpServices.validatedTransactions.getTransaction(it.ref.txhash)!! })
        bigCorpServices.recordTransactions(alicesVault.states.map { aliceServices.validatedTransactions.getTransaction(it.ref.txhash)!! })

        // BigCorp™ issues $10,000 of commercial paper, to mature in 30 days, owned initially by itself.
        val faceValue = 10000.DOLLARS `issued by` DUMMY_CASH_ISSUER
        val issuance = bigCorpServices.myInfo.chooseIdentity().ref(1)
        val issueBuilder = CommercialPaper().generateIssue(issuance, faceValue, TEST_TX_TIME + 30.days, DUMMY_NOTARY)
        issueBuilder.setTimeWindow(TEST_TX_TIME, 30.seconds)
        val issuePtx = bigCorpServices.signInitialTransaction(issueBuilder)
        val issueTx = notaryServices.addSignature(issuePtx)

        databaseAlice.transaction {
            // Alice pays $9000 to BigCorp to own some of their debt.
            moveTX = run {
                val builder = TransactionBuilder(DUMMY_NOTARY)
                Cash.generateSpend(aliceServices, builder, 9000.DOLLARS, AnonymousParty(BIG_CORP_KEY.public))
                CommercialPaper().generateMove(builder, issueTx.tx.outRef(0), AnonymousParty(ALICE_KEY.public))
                val ptx = aliceServices.signInitialTransaction(builder)
                val ptx2 = bigCorpServices.addSignature(ptx)
                val stx = notaryServices.addSignature(ptx2)
                stx
            }
        }

        databaseBigCorp.transaction {
            // Verify the txns are valid and insert into both sides.
            listOf(issueTx, moveTX).forEach {
                it.toLedgerTransaction(aliceServices).verify()
                aliceServices.recordTransactions(it)
                bigCorpServices.recordTransactions(it)
            }
        }

        databaseBigCorp.transaction {
            fun makeRedeemTX(time: Instant): Pair<SignedTransaction, UUID> {
                val builder = TransactionBuilder(DUMMY_NOTARY)
                builder.setTimeWindow(time, 30.seconds)
                CommercialPaper().generateRedeem(builder, moveTX.tx.outRef(1), bigCorpServices, bigCorpServices.myInfo.chooseIdentityAndCert())
                val ptx = aliceServices.signInitialTransaction(builder)
                val ptx2 = bigCorpServices.addSignature(ptx)
                val stx = notaryServices.addSignature(ptx2)
                return Pair(stx, builder.lockId)
            }

            val redeemTX = makeRedeemTX(TEST_TX_TIME + 10.days)
            val tooEarlyRedemption = redeemTX.first
            val tooEarlyRedemptionLockId = redeemTX.second
            val e = assertFailsWith(TransactionVerificationException::class) {
                tooEarlyRedemption.toLedgerTransaction(aliceServices).verify()
            }
            // manually release locks held by this failing transaction
            aliceServices.vaultService.softLockRelease(tooEarlyRedemptionLockId)
            assertTrue(e.cause!!.message!!.contains("paper must have matured"))

            val validRedemption = makeRedeemTX(TEST_TX_TIME + 31.days).first
            validRedemption.toLedgerTransaction(aliceServices).verify()
            // soft lock not released after success either!!! (as transaction not recorded)
        }
    }
}
