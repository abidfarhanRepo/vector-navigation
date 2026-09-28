package dev.vector.android.pro

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.revenuecat.purchases.Store
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.awaitCustomerInfo
import com.revenuecat.purchases.awaitOfferings
import com.revenuecat.purchases.awaitPurchase
import com.revenuecat.purchases.awaitRestore
import com.revenuecat.purchases.interfaces.UpdatedCustomerInfoListener
import dev.vector.android.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.revenuecat.purchases.Package as RcPackage

/**
 * Vector Pro, as the application sees it.
 *
 * The adapter between RevenueCat and [ProAccess]. Everything that can be
 * decided without a network lives in `ProAccess.kt` and is unit-tested; this
 * file only fetches, caches and reports.
 *
 * ## The identity Vector does not send
 *
 * There is no login. RevenueCat is configured with **no `appUserID`**, so it
 * mints an anonymous one of its own and Vector never hands it a destination, a
 * route, a location, or anything derived from one. The subscription is attached
 * to a Play account, which the store already knows about, and to nothing else.
 * That is the same line the rest of the product draws (ADR-0065): the server
 * may know aggregates, never a person's journeys.
 *
 * ## Why the first frame is already correct
 *
 * [state] is seeded **synchronously from the preference store** in `init`,
 * before any network call. A subscriber opening the app must not see a free
 * screen for the half-second it takes RevenueCat to answer — that flash is how
 * a paying user learns to distrust a paid tier. The network answer then
 * confirms or corrects it.
 */
class ProEntitlement(context: Context) {

    private val app = context.applicationContext
    private val prefs: SharedPreferences =
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True when this binary was built with a RevenueCat key. See build.gradle.kts. */
    val configured: Boolean = BuildConfig.REVENUECAT_KEY.isNotBlank()

    private val _state = MutableStateFlow(
        ProAccess.resolve(configured, readCache(), System.currentTimeMillis())
    )
    val state: StateFlow<ProStatus> = _state.asStateFlow()

    private val _offer = MutableStateFlow<ProOffer?>(null)
    val offer: StateFlow<ProOffer?> = _offer.asStateFlow()

    /**
     * Whether the store has answered, and what the answer was.
     *
     * Kept beside [offer] rather than folded into it because `null` cannot say
     * which of three different things happened: nothing asked yet, asked and
     * failed, or asked and there is nothing in the offering. All three used to
     * render the same "Checking prices…" line forever. See [ProOffering].
     */
    private val _offering = MutableStateFlow(ProOffering.LOADING)
    val offering: StateFlow<ProOffering> = _offering.asStateFlow()

    /**
     * The store this binary is selling through, once [start] has run.
     *
     * Null before that, and null forever in a build with no key — which is the
     * honest answer in both cases, and the one `ProStoreCopy` is written to
     * handle without naming a store it cannot see.
     */
    private val _store = MutableStateFlow<Store?>(null)
    val store: StateFlow<Store?> = _store.asStateFlow()

    /** The one contextual paywall this session is allowed. */
    val gate = PaywallGate()

    /**
     * Bring the SDK up, if there is one to bring up.
     *
     * Idempotent and total: a failure here leaves [state] at whatever the cache
     * said and never throws. Configuration is the one call that cannot be
     * retried usefully, so it is guarded by `Purchases.isConfigured` rather
     * than by a flag of our own — process death and Activity recreation both
     * re-enter this.
     */
    fun start() {
        if (!configured) return
        runCatching {
            if (!Purchases.isConfigured) {
                if (BuildConfig.DEBUG) Purchases.logLevel = LogLevel.WARN
                Purchases.configure(
                    PurchasesConfiguration.Builder(app, BuildConfig.REVENUECAT_KEY).build()
                )
            }
            // Keeps [state] live when the entitlement changes underneath us:
            // a renewal, a refund, a cancellation, or a purchase made on
            // another device signed into the same Play account.
            Purchases.sharedInstance.updatedCustomerInfoListener =
                UpdatedCustomerInfoListener { info -> adopt(info) }
            // Which store the key selected. The SDK decides this from the key
            // prefix -- `test_` is the Test Store, `goog_` is Play -- so asking
            // it is the only way to be right, and the paywall's copy depends on
            // the answer. See `ProStoreCopy`.
            _store.value = Purchases.sharedInstance.store
        }.onFailure { warn("configure", it) }
    }

    /**
     * Ask RevenueCat where we stand.
     *
     * Fails CLOSED to whatever the cache already decided — which for an
     * unverified device is [ProStatus.FREE], and free is a fully working
     * navigation app. Nothing in this class can stop a driver getting
     * somewhere.
     */
    suspend fun refresh() {
        if (!configured) return
        runCatching { Purchases.sharedInstance.awaitCustomerInfo() }
            .onSuccess { adopt(it) }
            .onFailure { warn("refresh", it) }
    }

    /** Load the current offering. Null when RevenueCat has none configured. */
    suspend fun loadOffer() {
        if (!configured) return
        _offering.value = ProOffering.LOADING
        runCatching {
            val current = Purchases.sharedInstance.awaitOfferings().current
            _offer.value = current?.let {
                ProOffer(
                    monthly = it.monthly,
                    annual = it.annual,
                    // Anything the dashboard offers that is neither, so an
                    // offering built with custom package identifiers is not
                    // silently invisible.
                    others = it.availablePackages.filter { p ->
                        p !== it.monthly && p !== it.annual
                    },
                )
            }
            // An empty offering is an ANSWER, not a failure: the dashboard has
            // no current offering, or it has one with no packages attached to
            // the entitlement. Either way there is nothing to sell, and the
            // paywall must say so rather than spin.
            _offering.value = if (_offer.value?.isEmpty == false) ProOffering.READY
                              else ProOffering.UNAVAILABLE
        }.onFailure {
            _offering.value = ProOffering.UNAVAILABLE
            warn("offerings", it)
        }
    }

    /**
     * Buy.
     *
     * @return null on success, or a message fit to show a driver.
     */
    suspend fun purchase(activity: Activity, pkg: RcPackage): String? {
        if (!configured) return "This build of Vector has no paid tier."
        return runCatching {
            val result = Purchases.sharedInstance.awaitPurchase(
                PurchaseParams.Builder(activity, pkg).build()
            )
            adopt(result.customerInfo)
            // Re-arm: if this subscription later lapses, the driver is owed one
            // explanation of why the feature stopped.
            gate.reset()
            null
        }.getOrElse { e ->
            // A cancelled purchase is not an error to report. The driver
            // pressed back; telling them so is noise.
            if (isUserCancellation(e)) null
            else { warn("purchase", e); "Purchase did not complete. Nothing was charged." }
        }
    }

    /** Restore on a reinstall or a new device. @return null on success. */
    suspend fun restore(): String? {
        if (!configured) return "This build of Vector has no paid tier."
        return runCatching {
            adopt(Purchases.sharedInstance.awaitRestore())
            gate.reset()
            null
        }.getOrElse { e ->
            warn("restore", e)
            "Could not reach the store. Try again when you have a connection."
        }
    }

    // ---- internals --------------------------------------------------------

    private fun adopt(info: CustomerInfo) {
        val ent = info.entitlements[ENTITLEMENT]
        val cached = CachedEntitlement(
            active = ent?.isActive == true,
            checkedAtMs = System.currentTimeMillis(),
            expiresAtMs = ent?.expirationDate?.time,
        )
        writeCache(cached)
        _state.value = ProAccess.resolve(configured, cached, System.currentTimeMillis())
    }

    private fun readCache(): CachedEntitlement? {
        if (!prefs.contains(K_CHECKED_AT)) return null
        val checked = prefs.getLong(K_CHECKED_AT, 0L)
        if (checked <= 0L) return null
        val exp = prefs.getLong(K_EXPIRES_AT, 0L)
        return CachedEntitlement(
            active = prefs.getBoolean(K_ACTIVE, false),
            checkedAtMs = checked,
            expiresAtMs = if (exp > 0L) exp else null,
        )
    }

    private fun writeCache(c: CachedEntitlement) {
        prefs.edit()
            .putBoolean(K_ACTIVE, c.active)
            .putLong(K_CHECKED_AT, c.checkedAtMs)
            .putLong(K_EXPIRES_AT, c.expiresAtMs ?: 0L)
            .apply()
    }

    /**
     * Was this the driver backing out of the Play sheet?
     *
     * Matched on the SDK's own error code rather than on a message, and
     * defensively: the class hierarchy differs across SDK lines, and a
     * mis-detection here only costs one unnecessary toast, whereas letting the
     * detection throw would cost the purchase.
     */
    private fun isUserCancellation(e: Throwable): Boolean = runCatching {
        val code = e.javaClass.methods
            .firstOrNull { it.name == "getError" && it.parameterCount == 0 }
            ?.invoke(e)
            ?.let { err ->
                err.javaClass.methods
                    .firstOrNull { it.name == "getCode" && it.parameterCount == 0 }
                    ?.invoke(err)
            }
        code?.toString()?.contains("PurchaseCancelled", ignoreCase = true) == true
    }.getOrDefault(false)

    private fun warn(what: String, e: Throwable) {
        // Never the key, never the app user id. Just that it failed.
        Log.w(TAG, "pro/$what failed: ${e.javaClass.simpleName}")
    }

    companion object {
        private const val TAG = "VectorPro"

        /**
         * The single entitlement. Must match the RevenueCat dashboard exactly.
         *
         * `CustomerInfo.entitlements` is keyed by the entitlement's
         * **identifier**, not its display name, and RevenueCat does not allow
         * an identifier to be edited after the entitlement is created. So this
         * string moved rather than the dashboard: the entitlement there was
         * made as `vector_pro`, changing its display name to "pro" did nothing
         * to the key, and the only other route would have been a second
         * entitlement plus a detach of the first.
         *
         * Pinned by `ProAccessTest`, which is now true — the comment that used
         * to claim it was not. A silent edit here is a purchase that completes
         * and unlocks nothing, which is the hardest failure in this file to see
         * from the inside: every log line is clean and the entitlement is
         * simply absent.
         */
        const val ENTITLEMENT = "vector_pro"

        /**
         * A file of its own, not the `vector` settings file.
         *
         * `Settings.clear` paths and the "Your data" controls in the settings
         * sheet exist to let a driver erase what Vector remembers about them. A
         * subscription is not that — erasing it would silently revoke something
         * they paid for — so it must not live where a "forget my data" control
         * can reach it.
         */
        private const val PREFS = "vector_pro"
        private const val K_ACTIVE = "ent_active"
        private const val K_CHECKED_AT = "ent_checked_at"
        private const val K_EXPIRES_AT = "ent_expires_at"
    }
}

/** What the current RevenueCat offering contains. */
data class ProOffer(
    val monthly: RcPackage?,
    val annual: RcPackage?,
    val others: List<RcPackage> = emptyList(),
) {
    /** Every package worth drawing, cheapest cadence first. */
    val packages: List<RcPackage>
        get() = listOfNotNull(monthly, annual) + others

    val isEmpty: Boolean get() = packages.isEmpty()
}
