package dev.vector.android.pro

/**
 * Whether this build and this driver have Vector Pro — decided by pure
 * arithmetic, with no SDK, no network and no Android in the room.
 *
 * ## Why this is a separate file from the RevenueCat call
 *
 * Every interesting question about a paid tier is a question about *state over
 * time*: does a subscription that verified on Tuesday still count on Friday in
 * a tunnel? Does an entitlement whose expiry has passed survive because the
 * phone has not been online to hear about it? Those are decisions, and a
 * decision that can only be exercised by buying a real subscription on a real
 * handset is a decision nobody ever tests.
 *
 * So the decision lives here, takes its inputs as values, and is covered by
 * `app/src/test/.../ProAccessTest.kt`. [ProEntitlement] is the adapter that
 * fetches those values from RevenueCat and the preference store. This is the
 * same division `NavSession` already uses against `MainActivity`.
 *
 * ## The trap this file exists to make impossible
 *
 * `.scratch/vector-product/GOALS.md` recorded the previous attempt at a paid
 * tier: `app/www/vector-revenuecat.js` was 41 lines that hardcoded
 * `window.__VECTOR_PRO__ = false`, with four features already gated against it.
 * The app shipped **a paywall no one could ever unlock** — and the note on it
 * is the right one: for a judge or an early user that is *strictly worse than
 * having no Pro tier at all*.
 *
 * [ProStatus.UNCONFIGURED] is the structural answer. A build with no RevenueCat
 * key does not have a locked Pro tier; it has **no Pro tier**. Nothing is
 * gated, no paywall exists, and every feature is simply present. That is also
 * the correct behaviour for the self-hosted case this project is built for —
 * someone who compiles Vector for their own phone has nobody to buy from.
 */
enum class ProStatus {
    /**
     * This binary was built without a RevenueCat key.
     *
     * Not "locked" and not "free": there is no paid tier in this build at all.
     * Callers MUST treat this as full access — see [hasPro] — and MUST NOT
     * render a paywall, an upsell, or a lock. See the class docstring.
     */
    UNCONFIGURED,

    /** A configured build, and this driver has not subscribed. */
    FREE,

    /** A configured build, and the `vector_pro` entitlement is active. */
    PRO;
}

/**
 * Whether there is anything to sell *right now*.
 *
 * [ProStatus] answers "does this driver have the tier". This answers a
 * different question that the tier's own state cannot: "if I put a price in
 * front of them, is there a price to put". The two are independent, and
 * collapsing them is how the product shipped a paywall nobody could unlock —
 * four features gated against a constant `false` (see the class docstring of
 * [ProAccess]).
 *
 * It is deliberately a separate axis rather than extra states on [ProStatus]:
 * a driver's entitlement is a fact about them, and the store's answer is a fact
 * about the network. `offersPro` still decides *whether this build has a tier
 * to advertise*; [ProAccess.sellable] decides whether the surfaces that carry a
 * price may be drawn.
 */
enum class ProOffering {
    /**
     * The store has not answered yet — the offering is being fetched.
     *
     * Not an error, and not an answer. A paywall may be reachable from Settings
     * (which is a deliberate open, see [PaywallGate]) but no Pro surface may
     * *advertise* itself on the strength of a question still in flight.
     */
    LOADING,

    /** RevenueCat returned a current offering with at least one package. */
    READY,

    /**
     * The store answered and there is nothing purchasable: either the request
     * failed, or the current offering exists with no packages in it.
     *
     * The distinction from [LOADING] is the whole point. A sheet that says
     * "Checking prices…" forever is indistinguishable from a hung app, and a
     * driver reads that as a broken payment — so this state renders an apology
     * and a way to try again, and the surfaces that merely *advertise* Pro stay
     * off the screen.
     */
    UNAVAILABLE,
}

/**
 * The last entitlement answer RevenueCat gave, as stored on the device.
 *
 * @property active whether the `pro` entitlement was active when last checked.
 * @property checkedAtMs when that answer was received. Used for [ProAccess.OFFLINE_GRACE_MS].
 * @property expiresAtMs when the subscription period ends, when RevenueCat said
 *   so. Null for a lifetime purchase, or when the answer carried no expiry.
 */
data class CachedEntitlement(
    val active: Boolean,
    val checkedAtMs: Long,
    val expiresAtMs: Long?,
)

object ProAccess {

    /**
     * How long a verified entitlement keeps working with no network.
     *
     * Seven days. The requirement is not hypothetical politeness: Vector is a
     * navigation application, and the moments it is most needed are the
     * moments a phone has no signal — the Salwa Road, the tunnels under the
     * Corniche, the drive to Mesaieed. A paying driver losing their paid
     * routing because they entered a tunnel would be the single worst bug this
     * tier could have.
     *
     * Seven days rather than "forever" because the cache is the only thing
     * standing between a cancelled subscription and permanent free Pro, and
     * rather than "one day" because a week of travelling without opening the
     * app is ordinary. [resolve] still honours a KNOWN expiry inside the grace
     * window — see below — so this only extends an entitlement whose end date
     * we were never told.
     */
    const val OFFLINE_GRACE_MS: Long = 7L * 24 * 60 * 60 * 1000

    /**
     * The answer.
     *
     * Deliberately total: every combination of inputs produces a status and
     * none of them throws. A paid tier that can crash the app it is bolted to
     * is a worse outcome than a paid tier that quietly stops charging.
     *
     * @param configured whether this build carries a RevenueCat key.
     * @param cached the stored answer, or null if RevenueCat has never
     *   successfully answered on this device.
     */
    fun resolve(configured: Boolean, cached: CachedEntitlement?, nowMs: Long): ProStatus {
        if (!configured) return ProStatus.UNCONFIGURED
        if (cached == null) return ProStatus.FREE
        if (!cached.active) return ProStatus.FREE

        // A known expiry beats the offline grace, always.
        //
        // The grace window exists to cover "we have not been able to ASK
        // recently". It must not cover "we already know the answer and the
        // answer is that the subscription ended on Tuesday". Without this
        // ordering, a subscription that expires while the phone is offline
        // would keep working for another week — which is not generosity, it is
        // the cache silently overriding a fact we were told.
        val expiry = cached.expiresAtMs
        if (expiry != null && nowMs >= expiry) return ProStatus.FREE

        // Stale beyond the grace window. Fail CLOSED to free.
        //
        // Closed means free, and free means every navigation feature still
        // works — see the class docstring. Nothing in this file can lock a
        // driver out of getting where they are going.
        if (nowMs - cached.checkedAtMs > OFFLINE_GRACE_MS) return ProStatus.FREE

        return ProStatus.PRO
    }

    /**
     * May this driver use a Pro feature?
     *
     * [ProStatus.UNCONFIGURED] answers **true**, and that is the whole point of
     * it existing. A build with no paid tier has no locked features.
     */
    fun hasPro(status: ProStatus): Boolean =
        status == ProStatus.PRO || status == ProStatus.UNCONFIGURED

    /**
     * Should a Pro *upsell* be rendered anywhere in this build?
     *
     * Three things must all be true, and each rules out a different way of
     * shipping a dishonest paywall:
     *
     *  1. [ProStatus.FREE] — not [ProStatus.UNCONFIGURED] (no tier exists in
     *     this build) and not [ProStatus.PRO] (they already bought it). Note
     *     that this is NOT the complement of [hasPro]: an unconfigured build
     *     has full access *and* no upsell.
     *  2. `featureCount > 0` — there is at least one Pro feature actually
     *     built. **This is the structural guard.** Until Phase 4 registers the
     *     cooler walking route in [ProCatalogue], this returns false
     *     everywhere, so the settings entry, the paywall and every lock are
     *     absent from the build rather than merely unreachable. A paywall that
     *     sells nothing cannot be rendered because there is nothing to render
     *     it from.
     */
    fun offersPro(
        status: ProStatus,
        featureCount: Int = ProCatalogue.features.size,
    ): Boolean = status == ProStatus.FREE && featureCount > 0

    /**
     * Can a purchase be completed right now?
     *
     * True only when the store has answered *and* the answer is that there is
     * something to buy. This is the predicate every surface carrying a price
     * must consult; [offersPro] alone is not enough, because it is a statement
     * about the build and says nothing about the network.
     *
     * An unconfigured or already-subscribed driver answers false through
     * [offersPro], so this never has to reason about them — see
     * [sellable], which is the two of them together and the one callers should
     * normally use.
     */
    fun canBuy(offering: ProOffering): Boolean = offering == ProOffering.READY

    /**
     * May an upsell carrying a price be drawn for this driver?
     *
     * The conjunction of "this build has a tier they do not have" and "there is
     * something to sell". False is the safe default: fewer Pro surfaces, never
     * a dead one.
     *
     * Note what this is *not*. It is not an access check. Gating a feature on
     * this rather than on [hasPro] would hand a free driver the whole tier
     * whenever the store was briefly unreachable — a paywall that fails open is
     * a worse defect than a paywall that fails closed, and this file exists to
     * make the first one impossible. Access is [hasPro]; advertising is this.
     */
    fun sellable(
        status: ProStatus,
        featureCount: Int = ProCatalogue.features.size,
        offering: ProOffering,
    ): Boolean = offersPro(status, featureCount) && canBuy(offering)
}

/**
 * One thing Vector Pro buys.
 *
 * @property title what the driver gets, in their words.
 * @property detail one line on why it is worth money. Must describe something
 *   that exists in the binary — see [ProCatalogue].
 */
data class ProFeature(val title: String, val detail: String)

/**
 * Everything Vector Pro actually unlocks, in this binary.
 *
 * ## Why this list starts empty
 *
 * It is the enforcement point for one rule: **Vector never sells a feature it
 * has not built.** A `ProFeature` is added here in the same commit that lands
 * the code it names, never before. While the list is empty,
 * [ProAccess.offersPro] is false for every driver, so there is no settings
 * entry, no lock, and no paywall anywhere in the application.
 *
 * The alternative — authoring the paywall copy first and gating it on a flag —
 * is precisely the shape of the defect this project has already shipped once:
 * four features gated against a constant `false`, and a paywall that could not
 * be unlocked. See `pro/ProAccess.kt`'s class docstring.
 *
 * Phase 4 of the V7 plan adds the first entry here.
 */
object ProCatalogue {
    val features: List<ProFeature> = listOf(
        // Added by V7 Phase 4, in the commit that landed the code it names:
        // `CoolerRoute`, `ShadeEstimator` and the journey card's toggle. The
        // copy says "estimated" because the thing being sold is an estimate —
        // selling it as measured shade would be the same dishonesty as a
        // paywall with nothing behind it, one layer further in.
        ProFeature(
            title = "Cooler walking routes",
            detail = "Vector estimates how much of each walk is in direct sun, " +
                "and offers the shadier way to the door.",
        ),
        // Added by V7 Phase 4, against the code in 101ee4c: `JourneyCard`, the
        // `/foot` leg from the pedestrian graph, and the arrival model that
        // continues past the parking space. Shipped and covered by
        // `JourneyAcceptanceTest` / `JourneyUiTest` before being sold here.
        ProFeature(
            title = "The walk after the car",
            detail = "Where to park, then the way from the space to the door — " +
                "planned as one journey instead of ending at the kerb.",
        ),
    )
}

/**
 * Stops the paywall appearing more than once in a session.
 *
 * ## Why this is a class and not a boolean at the call site
 *
 * "Once per session" has two callers with different rights, and collapsing them
 * is how a navigation app becomes an advertisement. A *contextual* presentation
 * — the driver tapped something Pro and Vector explains why it did not happen —
 * gets exactly one chance. A *deliberate* presentation — the driver opened
 * Settings and tapped "Vector Pro" — is not an interruption and must always
 * work, however many times they do it.
 *
 * Encoding that as one object with two methods makes it impossible to reach for
 * the wrong one by accident, and makes both testable without a screen.
 */
class PaywallGate {
    private var autoPresented = false

    /** True at most once per session. Consumes nothing — call [markPresented]. */
    fun shouldAutoPresent(): Boolean = !autoPresented

    fun markPresented() { autoPresented = true }

    /**
     * A purchase completed, or the driver signed in on another device.
     *
     * Re-arms the gate: if the entitlement later lapses, the one contextual
     * explanation is available again. Without this a driver who subscribed and
     * then cancelled would never be told why the cooler route stopped working.
     */
    fun reset() { autoPresented = false }
}
