package net.corda.finance.flows

import co.paralleluniverse.fibers.Suspendable
import com.typesafe.config.ConfigFactory
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StartableByRPC
import net.corda.core.internal.declaredField
import net.corda.core.internal.div
import net.corda.core.internal.read
import net.corda.core.node.AppServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.serialization.CordaSerializable
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.finance.CHF
import net.corda.finance.EUR
import net.corda.finance.GBP
import net.corda.finance.USD
import net.corda.finance.flows.ConfigHolder.Companion.supportedCurrencies
import java.nio.file.Path
import java.util.*

// TODO Until apps have access to their own config, we'll hack things by first getting the baseDirectory, read the node.conf
// again to get our config and store it here for access by our flow
@CordaService
class ConfigHolder(services: AppServiceHub) : SingletonSerializeAsToken() {
    companion object {
        val supportedCurrencies = listOf(USD, GBP, CHF, EUR)
    }

    val issuableCurrencies: List<Currency>

    init {
        // Warning!! You are about to see a major hack!
        val baseDirectory = services.declaredField<Any>("serviceHub").value
                .let { it.javaClass.getMethod("getConfiguration").apply { isAccessible = true }.invoke(it) }
                .let { it.javaClass.getMethod("getBaseDirectory").apply { isAccessible = true }.invoke(it)}
                .let { it.javaClass.getMethod("toString").apply { isAccessible = true }.invoke(it) as String }
        val config = (baseDirectory / "node.conf").read { ConfigFactory.parseReader(it.reader()) }
        if (config.hasPath("issuableCurrencies")) {
            issuableCurrencies = config.getStringList("issuableCurrencies").map { Currency.getInstance(it) }
            require(supportedCurrencies.containsAll(issuableCurrencies))
        } else {
            issuableCurrencies = emptyList()
        }
    }
}


/**
 * Flow to obtain cash cordapp app configuration.
 */
@StartableByRPC
class CashConfigDataFlow : FlowLogic<CashConfiguration>() {
    @Suspendable
    override fun call(): CashConfiguration {
        val configHolder = serviceHub.cordaService(ConfigHolder::class.java)
        return CashConfiguration(configHolder.issuableCurrencies, supportedCurrencies)
    }
}

@CordaSerializable
data class CashConfiguration(val issuableCurrencies: List<Currency>, val supportedCurrencies: List<Currency>)
