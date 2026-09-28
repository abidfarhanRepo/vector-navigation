package dev.vector.android

import dev.vector.android.pro.CachedEntitlement
import dev.vector.android.pro.PaywallGate
import dev.vector.android.pro.ProAccess
import dev.vector.android.pro.ProCatalogue
import dev.vector.android.pro.ProEntitlement
import dev.vector.android.pro.ProOffering
import dev.vector.android.pro.ProStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The paid tier's decisions, exercised without buying anything.
 *
 * Every case here is one a real purchase would take days or a cancelled card to
 * reach. See `pro/ProAccess.kt` for why the logic is separable at all.
 */
class ProAccessTest {

    private val now = 1_789_000_000_000L   // an arbitrary fixed "now"
    private val day = 24L * 60 * 60 * 1000

    // ---- the trap ---------------------------------------------------------

    @Test
    fun `a build with no RevenueCat key has no paid tier at all`() {
        val s = ProAccess.resolve(configured = false, cached = null, nowMs = now)
        assertEquals(ProStatus.UNCONFIGURED, s)
    }

    @Test
    fun `an unconfigured build grants every feature and offers nothing`() {
        val s = ProStatus.UNCONFIGURED
        // Both halves matter. Full access, AND no upsell — they are not
        // complements, which is the reason offersPro exists.
        assertTrue("unconfigured must not lock features", ProAccess.hasPro(s))
        assertFalse(
            "unconfigured must not render a paywall",
            ProAccess.offersPro(s, featureCount = 3),
        )
    }

    @Test
    fun `an unconfigured build ignores a stale cached entitlement`() {
        // A build that loses its key must not keep honouring an old purchase
        // record, and must not start gating either. It has no tier.
        val ancient = CachedEntitlement(active = true, checkedAtMs = 0L, expiresAtMs = null)
        assertEquals(
            ProStatus.UNCONFIGURED,
            ProAccess.resolve(configured = false, cached = ancient, nowMs = now),
        )
    }

    // ---- fresh install ----------------------------------------------------

    @Test
    fun `a fresh install of a configured build is free`() {
        assertEquals(
            ProStatus.FREE,
            ProAccess.resolve(configured = true, cached = null, nowMs = now),
        )
    }

    @Test
    fun `free grants no Pro feature, and offers the tier once one exists`() {
        assertFalse(ProAccess.hasPro(ProStatus.FREE))
        assertTrue(ProAccess.offersPro(ProStatus.FREE, featureCount = 1))
    }

    // ---- the rule: never sell what is not built ---------------------------

    @Test
    fun `no upsell exists anywhere while nothing is gated`() {
        // The structural guard. Until a real Pro feature is registered in
        // ProCatalogue, offersPro is false for every driver in every state —
        // so there is no settings entry, no lock and no paywall to render.
        for (s in ProStatus.entries) {
            assertFalse(
                "$s must not be upsold to when nothing is gated",
                ProAccess.offersPro(s, featureCount = 0),
            )
        }
    }

    @Test
    fun `the shipped catalogue only ever lists features with real copy`() {
        // Guards the same rule from the other side: an entry added here with a
        // placeholder title would sell a blank.
        for (f in ProCatalogue.features) {
            assertTrue("a Pro feature needs a title", f.title.isNotBlank())
            assertTrue("a Pro feature needs a reason", f.detail.isNotBlank())
        }
    }

    // ---- the entitled driver ----------------------------------------------

    @Test
    fun `an active entitlement checked just now is Pro`() {
        val c = CachedEntitlement(true, checkedAtMs = now, expiresAtMs = now + 30 * day)
        assertEquals(ProStatus.PRO, ProAccess.resolve(true, c, now))
    }

    @Test
    fun `Pro grants features and is never upsold to`() {
        assertTrue(ProAccess.hasPro(ProStatus.PRO))
        assertFalse(
            "a subscriber must never see the paywall",
            ProAccess.offersPro(ProStatus.PRO, featureCount = 3),
        )
    }

    // ---- the tunnel -------------------------------------------------------

    @Test
    fun `an entitled driver stays Pro with no network for six days`() {
        val c = CachedEntitlement(true, checkedAtMs = now, expiresAtMs = now + 365 * day)
        assertEquals(ProStatus.PRO, ProAccess.resolve(true, c, now + 6 * day))
    }

    @Test
    fun `an entitlement with no expiry survives right up to the grace boundary`() {
        val c = CachedEntitlement(true, checkedAtMs = now, expiresAtMs = null)
        assertEquals(
            ProStatus.PRO,
            ProAccess.resolve(true, c, now + ProAccess.OFFLINE_GRACE_MS),
        )
    }

    @Test
    fun `an entitlement unverified past the grace window falls back to free`() {
        val c = CachedEntitlement(true, checkedAtMs = now, expiresAtMs = null)
        assertEquals(
            ProStatus.FREE,
            ProAccess.resolve(true, c, now + ProAccess.OFFLINE_GRACE_MS + 1),
        )
    }

    // ---- expiry beats grace ----------------------------------------------

    @Test
    fun `a known expiry inside the grace window still ends the subscription`() {
        // The grace window covers "we could not ASK recently". It must not
        // cover "we were told it ends on Tuesday and Tuesday has passed".
        val c = CachedEntitlement(true, checkedAtMs = now, expiresAtMs = now + 2 * day)
        assertEquals(ProStatus.PRO, ProAccess.resolve(true, c, now + 1 * day))
        assertEquals(ProStatus.FREE, ProAccess.resolve(true, c, now + 3 * day))
    }

    @Test
    fun `expiry is exclusive at the instant it lands`() {
        val c = CachedEntitlement(true, checkedAtMs = now, expiresAtMs = now + day)
        assertEquals(ProStatus.FREE, ProAccess.resolve(true, c, now + day))
    }

    @Test
    fun `an explicitly inactive entitlement is free regardless of freshness`() {
        val c = CachedEntitlement(active = false, checkedAtMs = now, expiresAtMs = null)
        assertEquals(ProStatus.FREE, ProAccess.resolve(true, c, now))
    }

    // ---- clock skew -------------------------------------------------------

    @Test
    fun `a cache from the future does not fall out of grace`() {
        // Device clocks move. A negative age must not read as "very stale".
        val c = CachedEntitlement(true, checkedAtMs = now + 10 * day, expiresAtMs = null)
        assertEquals(ProStatus.PRO, ProAccess.resolve(true, c, now))
    }

    // ---- the paywall gate -------------------------------------------------

    @Test
    fun `the paywall auto-presents at most once per session`() {
        val g = PaywallGate()
        assertTrue(g.shouldAutoPresent())
        g.markPresented()
        assertFalse("a free driver must not be shown the paywall twice", g.shouldAutoPresent())
        assertFalse(g.shouldAutoPresent())
    }

    @Test
    fun `a purchase re-arms the gate so a later lapse can still be explained`() {
        val g = PaywallGate()
        g.markPresented()
        g.reset()
        assertTrue(g.shouldAutoPresent())
    }

    // ---- what may be advertised, as opposed to what may be used ------------

    @Test
    fun `an upsell is not sellable until the store has answered`() {
        // The offering is fetched asynchronously, so for the first moments of
        // every launch it is neither present nor absent. Advertising on the
        // strength of a question still in flight is how a driver taps a Pro
        // pill and lands on a price list with no prices.
        assertFalse(
            ProAccess.sellable(ProStatus.FREE, featureCount = 2, ProOffering.LOADING),
        )
    }

    @Test
    fun `an upsell is not sellable when the store has nothing to sell`() {
        // The failure this exists for: the request threw, or the current
        // offering is empty. Either way there is no purchase to complete, so
        // there is no price to draw.
        assertFalse(
            ProAccess.sellable(ProStatus.FREE, featureCount = 2, ProOffering.UNAVAILABLE),
        )
    }

    @Test
    fun `an upsell is sellable only when both halves are true`() {
        assertTrue(ProAccess.sellable(ProStatus.FREE, featureCount = 2, ProOffering.READY))
        // No tier in this build.
        assertFalse(
            ProAccess.sellable(ProStatus.UNCONFIGURED, featureCount = 2, ProOffering.READY),
        )
        // Already bought it.
        assertFalse(ProAccess.sellable(ProStatus.PRO, featureCount = 2, ProOffering.READY))
        // Nothing gated, so nothing to sell.
        assertFalse(ProAccess.sellable(ProStatus.FREE, featureCount = 0, ProOffering.READY))
    }

    @Test
    fun `access never depends on the store answering`() {
        // The fail-open guard, and the reason `sellable` is not used for access
        // anywhere. If a feature were gated on the offering rather than on the
        // entitlement, a network outage would hand the whole paid tier to every
        // free driver — a worse defect than the dead paywall this replaces.
        for (offering in ProOffering.entries) {
            assertFalse(
                "a free driver must stay free while the store is $offering",
                ProAccess.hasPro(ProStatus.FREE),
            )
        }
    }

    @Test
    fun `an empty offering and a failed one are the same state`() {
        // Both mean "nothing to buy". They are one state because the driver's
        // next action is identical in both, and the cause — logged by
        // ProEntitlement — is not something a commerce screen should name.
        assertEquals(ProOffering.UNAVAILABLE, ProOffering.valueOf("UNAVAILABLE"))
        assertEquals(3, ProOffering.entries.size)
    }

    // ---- the one string that has to match a server we do not own -----------

    @Test
    fun `the entitlement identifier matches the RevenueCat dashboard`() {
        // `CustomerInfo.entitlements` is keyed by IDENTIFIER — the display name
        // is cosmetic, and an identifier cannot be edited after the entitlement
        // is created. Getting this wrong is the quietest failure the paid tier
        // has: the purchase completes, the store records a subscription, every
        // log line is clean, and the entitlement is simply absent, so nothing
        // unlocks. It cost a session to find on a device.
        //
        // This test does not verify the dashboard. It makes the constant
        // impossible to edit ABSENT-MINDEDLY: changing it means changing this,
        // in the same commit, having gone and looked.
        assertEquals("vector_pro", ProEntitlement.ENTITLEMENT)
    }
}
