package dev.vector.android

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.revenuecat.purchases.Store
import dev.vector.android.pro.ProFeature
import dev.vector.android.pro.ProOffering
import dev.vector.android.pro.ProStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Vector Pro, actually composed.
 *
 * Separate from [NavUiTest] because these are about ONE question — what is on
 * screen in each of the four tier states — and because the most important
 * assertion is a negative one, which is easy to lose in a file of 106 cases.
 *
 * The negative: **while nothing is gated, no Pro surface exists anywhere.** The
 * previous attempt at a paid tier in this project shipped four features gated
 * against a constant `false` and a paywall nobody could unlock; this file is
 * where that cannot happen again without a test going red.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class ProUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val state = mutableStateOf(UiState())
    private var mounted = false
    private val bought = mutableListOf<String>()
    private var restores = 0
    private var paywallOpens = 0

    private val ANIMATION_SETTLE_MS = 600L

    /** The Last Mile copy, as Phase 4 will register it. */
    private val features = listOf(
        ProFeature("Cooler walking route", "Shade-scored walk from the car to the door."),
        ProFeature("Leave-by advisory", "When to set off to arrive while it is still shaded."),
    )

    private fun show(s: UiState) {
        state.value = s
        if (!mounted) {
            mounted = true
            compose.setContent {
                VectorChrome(
                    ui = state.value,
                    onQueryChange = {}, onSearch = {}, onPick = {}, onStart = {},
                    onCancel = {}, onRecenter = {}, onToggleVoice = {},
                    onToggleSteps = {}, onToggleContributing = {},
                    onOpenSearch = {}, onCloseSearch = {},
                    onOpenPaywall = { paywallOpens++ },
                    onBuy = { bought.add(it.identifier) },
                    onRestore = { restores++ },
                )
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(ANIMATION_SETTLE_MS)
        compose.waitForIdle()
    }

    /** Every string on screen, flattened. */
    private fun texts(): List<String> {
        val out = mutableListOf<String>()
        fun walk(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.Text)
                ?.forEach { out.add(it.text) }
            n.children.forEach { walk(it) }
        }
        walk(compose.onRoot().fetchSemanticsNode())
        return out
    }

    private fun screenSays(fragment: String): Boolean =
        texts().any { it.contains(fragment, ignoreCase = true) }

    // ---- the negative, which is the point of this file --------------------

    @Test
    fun `a build that gates nothing shows no Pro surface anywhere`() {
        // The negative this file exists for. Expressed against an EXPLICITLY
        // empty catalogue rather than against the real one: V7 Phase 4 landed
        // the first real Pro feature, so the real catalogue is no longer empty
        // and the guard has to be stated as the rule it always was — a build
        // with nothing gated sells nothing — rather than as a fact about the
        // current contents of a list.
        show(UiState(pro = ProStatus.FREE, proFeatures = emptyList(), showSettings = true))
        assertFalse("no Pro group in settings", screenSays("Vector Pro"))
        assertFalse("no purchase entry point", screenSays("See what Pro includes"))
        assertFalse("no restore entry point", screenSays("Restore"))
    }

    @Test
    fun `the shipped catalogue sells only what this binary actually contains`() {
        // Vector never sells a feature it has not built. Every entry is
        // enumerated here BY NAME rather than counted, so the list cannot grow
        // by accident: adding a ProFeature without the code behind it turns
        // this red, and admitting a real one is a deliberate edit to this test
        // in the same commit.
        //
        //  * the cooler walking route — `CoolerRoute` + `ShadeEstimator` + the
        //    journey card's toggle (V7 Phase 4).
        //  * the walk after the car — `JourneyCard`, the `/foot` leg off the
        //    pedestrian graph, and the arrival model that continues past the
        //    parking space (101ee4c), covered by `JourneyAcceptanceTest`.
        //
        // Deliberately NOT here: offline maps. `vector-offline-maps` is a
        // backend repo with no client surface — the only "offline" in this
        // binary is `ProAccess.OFFLINE_GRACE_MS`, which is the entitlement's
        // grace period and not a map the driver can take anywhere.
        val shipped = UiState().proFeatures
        assertEquals(
            "the shipped catalogue is exactly the features built",
            listOf("Cooler walking routes", "The walk after the car"),
            shipped.map { it.title },
        )
        // ...and because it IS gated, a free driver is now offered the tier.
        show(UiState(pro = ProStatus.FREE, proFeatures = shipped, showSettings = true))
        assertTrue(screenSays("Vector Pro"))
    }

    @Test
    fun `what Pro sells is described as an estimate, not as measured shade`() {
        // The thing being sold is a model with an assumed facade height and no
        // building data. Selling it as measured shade would be the same
        // dishonesty as a paywall with nothing behind it, one layer in.
        val shipped = UiState().proFeatures
        show(UiState(pro = ProStatus.FREE, proFeatures = shipped, showPaywall = true))
        val shade = shipped.first { it.title.contains("Cooler", ignoreCase = true) }
        assertTrue(
            "the paywall copy must not promise measured shade",
            shade.detail.contains("estimates", ignoreCase = true),
        )
    }

    @Test
    fun `a build with no RevenueCat key shows nothing even with features gated`() {
        show(UiState(pro = ProStatus.UNCONFIGURED, proFeatures = features, showSettings = true))
        assertFalse(screenSays("Vector Pro"))
        assertFalse(screenSays("See what Pro includes"))
    }

    @Test
    fun `an unconfigured build grants every Pro feature`() {
        val ui = UiState(pro = ProStatus.UNCONFIGURED, proFeatures = features)
        assertTrue("a self-hosted build must not lock its own features", ui.hasPro)
        assertFalse("...and must not be sold anything", ui.offersPro)
    }

    // ---- the store the money actually goes to ------------------------------

    @Test
    fun `the paywall names the store this build sells through`() {
        show(UiState(pro = ProStatus.FREE, proFeatures = features,
                     showPaywall = true, proStore = Store.PLAY_STORE))
        assertTrue(screenSays("Billed through Google Play"))
        assertTrue("and where to cancel", screenSays("Play Store subscriptions"))
    }

    @Test
    fun `a test build says so instead of claiming Google Play`() {
        // The whole development loop runs on the Test Store. The one screen
        // that makes a commercial claim was making the wrong one for all of it,
        // and telling the driver to cancel somewhere they could not go.
        show(UiState(pro = ProStatus.FREE, proFeatures = features,
                     showPaywall = true, proStore = Store.TEST_STORE))
        assertFalse("no build on the Test Store bills through Play",
                    screenSays("Google Play"))
        assertTrue("a simulated purchase must say it is one",
                   screenSays("Purchases are simulated"))
    }

    @Test
    fun `the price placeholder names the store it is waiting on`() {
        show(UiState(pro = ProStatus.FREE, proFeatures = features,
                     showPaywall = true, proOffer = null, proStore = Store.TEST_STORE))
        assertTrue(screenSays("Checking prices with the Test Store"))
    }

    @Test
    fun `manage subscription is offered only where it leads somewhere`() {
        // Vector's deep link goes to Play, and RevenueCat returns no management
        // URL for the Test Store at all.
        show(UiState(pro = ProStatus.PRO, proFeatures = features,
                     showSettings = true, proStore = Store.PLAY_STORE))
        assertTrue(screenSays("Manage subscription"))

        show(UiState(pro = ProStatus.PRO, proFeatures = features,
                     showSettings = true, proStore = Store.TEST_STORE))
        assertFalse("a control that opens the wrong store is worse than none",
                    screenSays("Manage subscription"))
        assertTrue("restore still works everywhere", screenSays("Restore a purchase"))
    }

    @Test
    fun `an unconfigured build names no store at all`() {
        // `proStore` is null before RevenueCat is configured and forever in a
        // build with no key. Nothing rendered may guess at a storefront.
        assertEquals("the app store",
                     dev.vector.android.pro.ProStoreCopy.name(null))
        assertFalse(dev.vector.android.pro.ProStoreCopy.managesSubscriptions(null))
    }

    // ---- the entry point that is not in Settings ---------------------------

    @Test
    fun `a free driver can find the tier without opening settings`() {
        // Pro used to be reachable from the settings sheet and from one row
        // inside the journey card, which only exists after a destination has
        // been chosen AND a walk was found at the other end. A driver who did
        // neither was never told the tier existed.
        show(UiState(pro = ProStatus.FREE, proFeatures = features))
        assertTrue("the map carries an entry point", screenSays("PRO"))
    }

    @Test
    fun `the map entry point opens the paywall every time it is tapped`() {
        // DELIBERATE, in `PaywallGate`'s sense: the driver asked for it, so it
        // is not rationed the way the contextual presentation is.
        show(UiState(pro = ProStatus.FREE, proFeatures = features))
        repeat(2) {
            compose.onAllNodesWithText("PRO")[0]
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            compose.waitForIdle()
        }
        assertEquals("a deliberate ask is never rationed", 2, paywallOpens)
    }

    @Test
    fun `a subscriber is sold nothing on the map`() {
        show(UiState(pro = ProStatus.PRO, proFeatures = features))
        assertFalse(screenSays("PRO"))
    }

    @Test
    fun `nothing is sold over a live maneuver`() {
        // The same rule that keeps the settings gear off the navigating HUD.
        // An upsell over a road is the thing this layout exists to prevent.
        show(UiState(pro = ProStatus.FREE, proFeatures = features,
                     phase = Phase.NAVIGATING))
        assertFalse(screenSays("PRO"))
    }

    @Test
    fun `a self-hosted build has no entry point to sell from`() {
        show(UiState(pro = ProStatus.UNCONFIGURED, proFeatures = features))
        assertFalse(screenSays("PRO"))
    }

    // ---- the free driver ---------------------------------------------------

    @Test
    fun `a free driver with a gated feature is offered the tier in settings`() {
        show(UiState(pro = ProStatus.FREE, proFeatures = features, showSettings = true))
        assertTrue(screenSays("Vector Pro"))
        assertTrue(screenSays("See what Pro includes"))
    }

    @Test
    fun `the paywall lists exactly what is gated, and claims nothing else`() {
        show(UiState(pro = ProStatus.FREE, proFeatures = features, showPaywall = true))
        for (f in features) {
            assertTrue("paywall must name '${f.title}'", screenSays(f.title))
            assertTrue("paywall must justify '${f.title}'", screenSays(f.detail))
        }
    }

    @Test
    fun `a paywall with no prices yet says so instead of drawing a dead button`() {
        // A purchase control that does nothing when tapped is how a driver
        // decides the payment is broken and never tries again.
        show(UiState(pro = ProStatus.FREE, proFeatures = features,
                     showPaywall = true, proOffer = null))
        assertTrue(screenSays("Checking prices"))
    }

    @Test
    fun `a paywall whose store returned nothing says so and offers a retry`() {
        // The state this replaces: the offering request failed, `proOffer`
        // stayed null exactly as it is a half-second after launch, and the
        // sheet sat on "Checking prices…" forever. A driver cannot tell that
        // from a hung app, and the reasonable conclusion from a payment screen
        // that never resolves is that the payment is broken.
        show(UiState(pro = ProStatus.FREE, proFeatures = features,
                     showPaywall = true, proOffer = null,
                     proStore = Store.TEST_STORE,
                     proOffering = ProOffering.UNAVAILABLE))
        assertTrue("the sheet must name what happened",
                   screenSays("no product"))
        assertTrue("the sheet must offer the one useful action",
                   screenSays("Try again"))
        assertTrue("the spinner must be gone",
                   !screenSays("Checking prices"))
    }

    @Test
    fun `Pro is not advertised on the map while the store cannot sell`() {
        // Advertising is `proSellable`; access is `hasPro`. This is the
        // advertising half — a free driver whose store is unreachable sees no
        // Pro pill, so they are not led to a purchase that cannot complete.
        show(UiState(pro = ProStatus.FREE, proFeatures = features,
                     proOffering = ProOffering.UNAVAILABLE))
        assertFalse(
            "no map entry point while there is nothing to buy",
            texts().any { it.trim() == "PRO" },
        )
    }

    @Test
    fun `a store problem is reported on the paywall, not in the navigation banner`() {
        val notice = "Purchase did not complete. Nothing was charged."
        show(UiState(pro = ProStatus.FREE, proFeatures = features,
                     showPaywall = true, proNotice = notice))
        assertTrue(screenSays(notice))
    }

    @Test
    fun `the settings entry point reaches the paywall callback`() {
        show(UiState(pro = ProStatus.FREE, proFeatures = features, showSettings = true))
        compose.onAllNodesWithText("See what Pro includes", substring = true)
            .fetchSemanticsNodes().let { assertTrue("button must exist", it.isNotEmpty()) }
        compose.onAllNodesWithText("See what Pro includes", substring = true)[0]
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        assertEquals("the settings button must open the paywall", 1, paywallOpens)
    }

    // ---- the subscriber ----------------------------------------------------

    @Test
    fun `a subscriber is never upsold to`() {
        // With a store, because a subscriber always has one: `proStore` is set
        // from `pro.store.value` in the same `copy` that seeds `pro`, before
        // anything composes. PRO with no store is not a state this app reaches
        // — a build with no key cannot resolve an entitlement to begin with.
        show(UiState(pro = ProStatus.PRO, proFeatures = features,
                     showSettings = true, proStore = Store.PLAY_STORE))
        assertTrue("their status is stated", screenSays("Subscription active"))
        assertFalse("but they are not sold to", screenSays("See what Pro includes"))
        assertTrue("and they can still manage it", screenSays("Manage subscription"))
    }

    @Test
    fun `a subscriber has every gated feature`() {
        val ui = UiState(pro = ProStatus.PRO, proFeatures = features)
        assertTrue(ui.hasPro)
        assertFalse(ui.offersPro)
    }

    @Test
    fun `a free driver has no gated feature but is not locked out of navigating`() {
        val ui = UiState(pro = ProStatus.FREE, proFeatures = features, phase = Phase.NAVIGATING)
        assertFalse(ui.hasPro)
        assertTrue(ui.offersPro)
        // The whole point of failing closed to FREE rather than to an error.
        assertEquals(Phase.NAVIGATING, ui.phase)
    }
}
