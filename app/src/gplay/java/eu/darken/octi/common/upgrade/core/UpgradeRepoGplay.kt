package eu.darken.octi.common.upgrade.core

import android.app.Activity
import eu.darken.octi.common.coroutine.AppScope
import eu.darken.octi.common.coroutine.DispatcherProvider
import eu.darken.octi.common.datastore.value
import eu.darken.octi.common.debug.logging.Logging.Priority.ERROR
import eu.darken.octi.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.octi.common.debug.logging.asLog
import eu.darken.octi.common.debug.logging.log
import eu.darken.octi.common.debug.logging.logTag
import eu.darken.octi.common.error.asErrorDialogBuilder
import eu.darken.octi.common.flow.setupCommonEventHandlers
import eu.darken.octi.common.upgrade.UpgradeRepo
import eu.darken.octi.common.upgrade.core.billing.BillingData
import eu.darken.octi.common.upgrade.core.billing.BillingManager
import eu.darken.octi.common.upgrade.core.billing.PurchasedSku
import eu.darken.octi.common.upgrade.core.billing.Sku
import eu.darken.octi.common.upgrade.core.billing.SkuDetails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpgradeRepoGplay @Inject constructor(
    @AppScope private val scope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
    private val billingManager: BillingManager,
    private val billingCache: BillingCache,
) : UpgradeRepo {

    override val mainWebsite: String = SITE

    override val upgradeInfo: Flow<Info> = billingManager.billingData
        .map<BillingData, BillingData?> { it }
        .onStart { emit(null) }
        .setupCommonEventHandlers(TAG) { "upgradeInfo1" }
        .map { data: BillingData? -> // Only relinquish pro state if we haven't had it for a while
            val now = System.currentTimeMillis()
            val lastProStateAt = billingCache.lastProStateAt.value()
            log(TAG) { "Map: now=$now, lastProStateAt=$lastProStateAt, data=${data}" }

            when {
                data?.purchases?.isNotEmpty() == true -> {
                    // If we are pro refresh timestamp
                    billingCache.lastProStateAt.value(now)
                    Info(billingData = data)
                }

                (now - lastProStateAt) < 7 * 24 * 60 * 1000L -> { // 7 days
                    log(TAG, VERBOSE) { "We are not pro, but were recently, did GPlay try annoy us again?" }
                    Info(gracePeriod = true, billingData = null)
                }

                else -> {
                    Info(billingData = data)
                }
            }
        }
        .distinctUntilChanged()
        .catch {
            // Ignore Google Play errors if the last pro state was recent
            val now = System.currentTimeMillis()
            val lastProStateAt = billingCache.lastProStateAt.value()
            log(TAG) { "Catch: now=$now, lastProStateAt=$lastProStateAt, error=$it" }
            if ((now - lastProStateAt) < 7 * 24 * 60 * 1000L) { // 7 days
                log(TAG, VERBOSE) { "We are not pro, but were recently, and just and an error, what is GPlay doing???" }
                emit(Info(gracePeriod = true, billingData = null))
            } else {
                throw it
            }
        }
        .setupCommonEventHandlers(TAG) { "upgradeInfo2" }
        .shareIn(scope, SharingStarted.WhileSubscribed(3000L, 0L), replay = 1)

    fun launchBillingFlow(activity: Activity, sku: Sku, offer: Sku.Subscription.Offer?) {
        log(TAG) { "launchBillingFlow($activity,$sku)" }
        scope.launch {
            try {
                billingManager.startIapFlow(activity, sku, offer)
            } catch (e: Exception) {
                log(TAG) { "startIapFlow failed:${e.asLog()}" }
                withContext(dispatcherProvider.Main) {
                    e.asErrorDialogBuilder(activity).show()
                }
            }
        }
    }

    suspend fun querySkus(vararg skus: Sku): Collection<SkuDetails> = billingManager.querySkus(*skus)

    override suspend fun refresh() {
        log(TAG) { "refresh()" }
        billingManager.refresh()
    }

    data class Info(
        private val gracePeriod: Boolean = false,
        private val billingData: BillingData?,
    ) : UpgradeRepo.Info {

        override val type: UpgradeRepo.Type = UpgradeRepo.Type.GPLAY

        val upgrades: Collection<PurchasedSku> = billingData?.purchases
            ?.map { purchase ->
                purchase.products.mapNotNull { productId ->
                    val sku = OurSku.PRO_SKUS.singleOrNull { it.id == productId }
                    if (sku == null) {
                        log(TAG, ERROR) { "Unknown product: $productId ($purchase)" }
                        return@mapNotNull null
                    } else {
                        log(TAG) { "Mapped $productId to $sku ($purchase)" }
                    }
                    PurchasedSku(sku, purchase)
                }
            }
            ?.flatten()
            ?: emptySet()

        override val isPro: Boolean = upgrades.isNotEmpty() || gracePeriod

        override val upgradedAt: Instant? = upgrades
            .maxByOrNull { it.purchase.purchaseTime }
            ?.let { Instant.ofEpochMilli(it.purchase.purchaseTime) }
    }


    companion object {
        private const val SITE = "https://play.google.com/store/apps/details?id=eu.darken.octi"
        val TAG: String = logTag("Upgrade", "Gplay", "Repo")
    }
}
