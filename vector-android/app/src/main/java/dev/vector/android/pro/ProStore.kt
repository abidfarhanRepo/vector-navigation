package dev.vector.android.pro

import com.revenuecat.purchases.Store

/**
 * The words Vector uses for the store it is actually selling through.
 *
 * ## Why this exists
 *
 * The paywall said "Billed through Google Play. Cancel any time in Play Store
 * subscriptions" unconditionally, and the price placeholder said "Checking
 * prices with Google Play…". Both were true of every shipping build and false
 * on the emulator, where the whole tier is exercised through RevenueCat's Test
 * Store — so the one screen that makes a commercial claim made the wrong one
 * for the entire development loop, and "cancel in Play Store subscriptions"
 * described a place the driver could not go.
 *
 * ## Why there is no Vector-side store enum
 *
 * There is nothing to model. [Store] is already the identifier — the SDK reads
 * the API key prefix, decides, and exposes the answer as
 * `Purchases.sharedInstance.store`. Wrapping that in a second enum would add a
 * mapping to maintain and a way for the two to disagree. This file holds only
 * the strings, which is the part that is Vector's and not RevenueCat's.
 *
 * `null` is the honest state before `Purchases` is configured, and in a build
 * with no key at all — where none of this copy is ever rendered, because
 * [ProAccess.offersPro] is false. The fallbacks say nothing specific rather
 * than guessing at a store.
 */
object ProStoreCopy {

    /** The store, named as a driver would name it. */
    fun name(store: Store?): String = when (store) {
        Store.PLAY_STORE -> "Google Play"
        Store.AMAZON -> "the Amazon Appstore"
        Store.GALAXY -> "the Galaxy Store"
        Store.TEST_STORE -> "the Test Store"
        else -> "the app store"
    }

    /** The line under the purchase controls. Where the money goes, and where it does not. */
    fun billing(store: Store?): String {
        val privacy = "Vector never sees your payment details, and your " +
            "subscription is not linked to anywhere you drive."
        return when (store) {
            Store.PLAY_STORE ->
                "Billed through Google Play. Cancel any time in Play Store " +
                    "subscriptions. $privacy"
            Store.AMAZON ->
                "Billed through the Amazon Appstore. Cancel any time in your " +
                    "Amazon subscriptions. $privacy"
            Store.GALAXY ->
                "Billed through the Galaxy Store. Cancel any time in your " +
                    "Galaxy Store subscriptions. $privacy"
            // Said plainly, because a simulated purchase that reads like a real
            // one is how a test build gets demonstrated as a shipping one.
            Store.TEST_STORE ->
                "This is RevenueCat's Test Store. Purchases are simulated, no " +
                    "money moves, and nothing bought here is a real subscription. " +
                    "A build that shows this is a development build."
            else -> privacy
        }
    }

    /** While the offering is still in flight. */
    fun checkingPrices(store: Store?): String = "Checking prices with ${name(store)}…"

    /**
     * When the store answered and there was nothing to sell.
     *
     * Not an error string and deliberately not a diagnosis: the driver cannot
     * act on "the offerings request returned no current offering", and naming
     * the cause accurately would mean putting account identifiers or SDK
     * detail on a commerce screen. [ProEntitlement] logs the cause; this says
     * only what the driver needs, which is that nothing is for sale right now,
     * that nothing was charged, and that the free app is unaffected.
     *
     * Named for the store, because "the store is not answering" and "this
     * account has no products" lead a driver to different next steps.
     */
    fun unavailable(store: Store?): String = when (store) {
        Store.TEST_STORE ->
            "The Test Store returned no product for this build."
        Store.PLAY_STORE ->
            "Google Play returned no Vector Pro product for this account."
        null -> "Vector Pro could not be loaded from the store."
        else -> "${name(store)} returned no Vector Pro product for this account."
    }

    /**
     * Whether this store has somewhere to send a driver to manage a purchase.
     *
     * The Test Store does not: RevenueCat returns `management_url: null` for it,
     * and Vector's own deep link goes to Play. A control that opens the wrong
     * store is worse than one that is not offered.
     */
    fun managesSubscriptions(store: Store?): Boolean = store == Store.PLAY_STORE

    /** When a restore finds nothing, named for the account it actually looked in. */
    fun noSubscriptionFound(store: Store?): String = when (store) {
        Store.PLAY_STORE -> "No Vector Pro subscription on this Google account."
        else -> "No Vector Pro subscription found in ${name(store)}."
    }
}
