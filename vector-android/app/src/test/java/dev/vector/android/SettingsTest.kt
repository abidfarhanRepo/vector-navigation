package dev.vector.android

import androidx.test.core.app.ApplicationProvider
import dev.vector.geo.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Preferences, and the fact that they survive.
 *
 * Nothing was persisted before except the collection consent flag, so every
 * choice a driver expressed lasted exactly as long as the process did — voice
 * came back ON at every launch, and theme, units and camera had nowhere to be
 * stored at all.
 *
 * The `load` tests care most about the FALLBACK path. A preferences file
 * written by an older build, or holding an enum name that has since been
 * renamed, must not stop the app launching: a navigator that will not start is
 * a worse failure than one that starts with the wrong theme.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsTest {

    private lateinit var prefs: android.content.SharedPreferences

    @Before
    fun setUp() {
        prefs = Settings.prefs(ApplicationProvider.getApplicationContext())
        prefs.edit().clear().commit()
    }

    // ---- defaults ----------------------------------------------------------

    @Test
    fun `a fresh install follows the system theme`() {
        assertEquals(VectorStyle.MapTheme.SYSTEM, Settings.load(prefs).theme)
    }

    @Test
    fun `collection is off by default`() {
        // adr-0068 makes collection opt-in, and a default-on switch is not
        // consent.
        assertFalse(Settings.load(prefs).contributing)
    }

    @Test
    fun `traffic before setting off is on by default`() {
        // The one thing that costs a request and is still default-on: traffic
        // before you leave is the reason to look at a map in a city, and the
        // previous behaviour made the layer invisible at exactly that moment.
        assertTrue(Settings.load(prefs).trafficInExplore)
    }

    // ---- round trip --------------------------------------------------------

    @Test
    fun `every field survives a save and reload`() {
        val s = Settings(
            theme = VectorStyle.MapTheme.LIGHT,
            orientation = MapOrientation.NORTH_UP,
            perspective = MapPerspective.FLAT,
            units = Units.IMPERIAL,
            voice = VoiceMode.ALERTS,
            trafficInExplore = false,
            autoZoom = false,
            contributing = true,
        )
        Settings.save(prefs, s)
        assertEquals(s, Settings.load(prefs))
    }

    @Test
    fun `muting persists across launches`() {
        // The specific regression: voice was a loose UiState flag, so it reset
        // to ON every time the process restarted.
        Settings.save(prefs, Settings(voice = VoiceMode.OFF))
        assertEquals(VoiceMode.OFF, Settings.load(prefs).voice)
    }

    // ---- V4: the boolean -> four-mode migration --------------------------

    @Test
    fun `a V3 preferences file with voice off loads as OFF`() {
        // Every existing install has the old boolean and no `voice_mode`.
        // Reading the new key with a plain default would silently un-mute
        // every driver who had muted — reintroducing, while replacing it, the
        // exact bug V3 fixed.
        prefs.edit().clear().putBoolean("voice", false).commit()
        assertEquals(VoiceMode.OFF, Settings.load(prefs).voice)
    }

    @Test
    fun `a V3 preferences file with voice on loads as FULL`() {
        prefs.edit().clear().putBoolean("voice", true).commit()
        assertEquals(VoiceMode.FULL, Settings.load(prefs).voice)
    }

    @Test
    fun `the new key wins over the old boolean once both are present`() {
        prefs.edit().clear()
            .putBoolean("voice", true)
            .putString("voice_mode", "ALERTS")
            .commit()
        assertEquals(VoiceMode.ALERTS, Settings.load(prefs).voice)
    }

    @Test
    fun `saving keeps the old boolean in step`() {
        // So that downgrading to a V3 build does not un-mute a driver who
        // muted in V4.
        Settings.save(prefs, Settings(voice = VoiceMode.OFF))
        assertFalse(prefs.getBoolean("voice", true))
        Settings.save(prefs, Settings(voice = VoiceMode.ALERTS))
        assertTrue(prefs.getBoolean("voice", false))
    }

    @Test
    fun `an unknown voice mode name falls back to the default`() {
        prefs.edit().clear().putString("voice_mode", "SHOUTING").commit()
        assertEquals(VoiceMode.FULL, Settings.load(prefs).voice)
    }

    // ---- tolerating a bad file --------------------------------------------

    @Test
    fun `an unknown enum name falls back instead of throwing`() {
        prefs.edit().putString("theme", "SEPIA").putString("units", "FURLONGS").commit()
        val s = Settings.load(prefs)
        assertEquals(VectorStyle.MapTheme.SYSTEM, s.theme)
        assertEquals(Units.METRIC, s.units)
    }

    @Test
    fun `a partially written file keeps the defaults for what is missing`() {
        prefs.edit().putString("units", "IMPERIAL").commit()
        val s = Settings.load(prefs)
        assertEquals(Units.IMPERIAL, s.units)
        assertEquals(VectorStyle.MapTheme.SYSTEM, s.theme)
        assertEquals(VoiceMode.FULL, s.voice)
    }

    // ---- theme resolution --------------------------------------------------

    @Test
    fun `system resolves against the device`() {
        val s = Settings(theme = VectorStyle.MapTheme.SYSTEM)
        assertEquals(VectorStyle.MapTheme.DARK, s.resolvedTheme(systemInDark = true))
        assertEquals(VectorStyle.MapTheme.LIGHT, s.resolvedTheme(systemInDark = false))
    }

    @Test
    fun `an explicit theme ignores the device`() {
        val s = Settings(theme = VectorStyle.MapTheme.LIGHT)
        assertEquals(VectorStyle.MapTheme.LIGHT, s.resolvedTheme(systemInDark = true))
    }

    @Test
    fun `a resolved theme is never SYSTEM`() {
        // VectorStyle.palette must never be handed an unresolved preference.
        for (t in VectorStyle.MapTheme.entries) {
            for (dark in listOf(true, false)) {
                assertTrue(
                    Settings(theme = t).resolvedTheme(dark) != VectorStyle.MapTheme.SYSTEM,
                )
            }
        }
    }
}

/**
 * Recent destinations.
 *
 * A navigator with no memory makes the driver retype their commute every
 * morning, and Android had none.
 */
@RunWith(RobolectricTestRunner::class)
class RecentsTest {

    private lateinit var prefs: android.content.SharedPreferences

    @Before
    fun setUp() {
        prefs = Settings.prefs(ApplicationProvider.getApplicationContext())
        prefs.edit().clear().commit()
    }

    @Test
    fun `a fresh install has no recents`() {
        assertEquals(emptyList<Recents.Entry>(), Recents.load(prefs))
    }

    @Test
    fun `the newest destination is first`() {
        Recents.add(prefs, Recents.Entry("West Bay", 51.52, 25.32))
        Recents.add(prefs, Recents.Entry("Souq Waqif", 51.53, 25.28))
        assertEquals(listOf("Souq Waqif", "West Bay"), Recents.load(prefs).map { it.name })
    }

    @Test
    fun `the same place twice is one entry, moved to the front`() {
        Recents.add(prefs, Recents.Entry("West Bay", 51.52, 25.32))
        Recents.add(prefs, Recents.Entry("Souq Waqif", 51.53, 25.28))
        Recents.add(prefs, Recents.Entry("West Bay", 51.52, 25.32))
        assertEquals(listOf("West Bay", "Souq Waqif"), Recents.load(prefs).map { it.name })
    }

    @Test
    fun `de-duplication is by position, not by name`() {
        // The same place reached once from a search result and once from a
        // dropped pin has two different names and is one destination.
        Recents.add(prefs, Recents.Entry("Dropped pin", 51.52, 25.32))
        Recents.add(prefs, Recents.Entry("Villaggio Mall", 51.52, 25.32))
        val out = Recents.load(prefs)
        assertEquals(1, out.size)
        // The newer name wins, so a pin that later gets reverse-geocoded
        // replaces its own placeholder.
        assertEquals("Villaggio Mall", out[0].name)
    }

    @Test
    fun `the list is bounded`() {
        for (i in 0 until Recents.LIMIT + 5) {
            Recents.add(prefs, Recents.Entry("place $i", 51.0 + i * 0.01, 25.0 + i * 0.01))
        }
        assertEquals(Recents.LIMIT, Recents.load(prefs).size)
    }

    @Test
    fun `the oldest entry is what falls off`() {
        for (i in 0 until Recents.LIMIT + 1) {
            Recents.add(prefs, Recents.Entry("place $i", 51.0 + i * 0.01, 25.0 + i * 0.01))
        }
        val names = Recents.load(prefs).map { it.name }
        assertEquals("place ${Recents.LIMIT}", names.first())
        assertFalse("place 0 should have aged out", names.contains("place 0"))
    }

    @Test
    fun `clearing removes everything`() {
        Recents.add(prefs, Recents.Entry("West Bay", 51.52, 25.32))
        Recents.clear(prefs)
        assertEquals(emptyList<Recents.Entry>(), Recents.load(prefs))
    }

    @Test
    fun `a name containing the separator does not corrupt the list`() {
        // The store is one delimited string, so a name carrying the delimiter
        // would otherwise split into a garbage record and silently drop the
        // rest of the list.
        Recents.add(prefs, Recents.Entry("Odd\u001Ename\u001Fhere", 51.52, 25.32))
        Recents.add(prefs, Recents.Entry("West Bay", 51.50, 25.30))
        val out = Recents.load(prefs)
        assertEquals(2, out.size)
        assertEquals("West Bay", out[0].name)
        assertEquals(51.52, out[1].lng, 1e-6)
    }

    @Test
    fun `coordinates round-trip to about a metre`() {
        Recents.add(prefs, Recents.Entry("West Bay", 51.523456789, 25.321987654))
        val e = Recents.load(prefs)[0]
        // 5 dp is ~1 m, which is enough to re-route to the same door without
        // keeping a higher-fidelity location history than a probe upload would
        // ever be allowed to carry (adr-0065).
        assertEquals(51.52346, e.lng, 1e-5)
        assertEquals(25.32199, e.lat, 1e-5)
    }

}

/**
 * The journey that survives process death (V5).
 *
 * Vector held the whole journey in `UiState` and nowhere else, so Android
 * killing a backgrounded process mid-drive lost it: the app came back to an
 * empty map and the driver, at speed, had to search again. It was on V4's own
 * list of known limitations.
 *
 * Its own class rather than a section of [RecentsTest], which is where these
 * first landed by accident — a file with two test classes in it and a helper
 * that inserts before the last brace. They ran, under a name that had nothing
 * to do with them.
 */
@RunWith(RobolectricTestRunner::class)
class JourneyTest {

    private lateinit var prefs: android.content.SharedPreferences

    @Before
    fun setUp() {
        prefs = Settings.prefs(ApplicationProvider.getApplicationContext())
        prefs.edit().clear().commit()
    }


    @Test
    fun `nothing to resume on a fresh install`() {
        assertNull(Journey.load(prefs, 1_000_000L))
    }

    @Test
    fun `a saved journey comes back`() {
        val p = prefs
        Journey.save(p, "Villaggio Mall", 51.4437, 25.2585, 1_000_000L)
        val j = Journey.load(p, 1_000_000L + 60_000L)!!
        assertEquals("Villaggio Mall", j.name)
        // Float precision: about a metre at this latitude, which is finer than
        // the router's own endpoint snapping.
        assertEquals(51.4437, j.lng, 0.00002)
        assertEquals(25.2585, j.lat, 0.00002)
    }

    @Test
    fun `a journey older than the window is not offered`() {
        val p = prefs
        Journey.save(p, "Villaggio Mall", 51.4437, 25.2585, 0L)
        assertNull(Journey.load(p, Journey.RESUME_WINDOW_MS + 1))
    }

    @Test
    fun `an expired journey is also forgotten, not just hidden`() {
        // Otherwise it sits in the preferences forever and comes back the
        // moment a clock adjustment brings it inside the window again.
        val p = prefs
        Journey.save(p, "Villaggio Mall", 51.4437, 25.2585, 0L)
        Journey.load(p, Journey.RESUME_WINDOW_MS + 1)
        assertNull(Journey.load(p, 1L))
    }

    @Test
    fun `a clock that has gone backwards does not create an immortal journey`() {
        // A reboot can set the wall clock back. A negative age passed the
        // `age < WINDOW` test that a naive comparison would use, so the journey
        // could never expire.
        val p = prefs
        Journey.save(p, "Villaggio Mall", 51.4437, 25.2585, 5_000_000L)
        assertNull(Journey.load(p, 1_000L))
    }

    @Test
    fun `clearing forgets it`() {
        val p = prefs
        Journey.save(p, "Villaggio Mall", 51.4437, 25.2585, 1_000L)
        Journey.clear(p)
        assertNull(Journey.load(p, 2_000L))
    }

    @Test
    fun `a journey with no name is still resumable`() {
        // A long-press destination whose reverse geocode had not landed yet.
        val p = prefs
        Journey.save(p, "", 51.4437, 25.2585, 1_000L)
        val j = Journey.load(p, 2_000L)!!
        assertEquals("", j.name)
    }
}

/**
 * Home and Work (V5).
 *
 * V4's handover called this "the cheapest large win in the product" and put it
 * on the list of three features with a working backend and no client.
 */
@RunWith(RobolectricTestRunner::class)
class PlacesTest {

    private lateinit var prefs: android.content.SharedPreferences

    @Before
    fun setUp() {
        prefs = Settings.prefs(ApplicationProvider.getApplicationContext())
        prefs.edit().clear().commit()
    }


    @Test
    fun `nothing is saved on a fresh install`() {
        assertTrue(Places.load(prefs).isEmpty())
        assertNull(Places.get(prefs, Places.Slot.HOME))
    }

    @Test
    fun `a slot round-trips`() {
        Places.save(prefs, Places.Slot.HOME, "Villaggio Mall", 51.4437, 25.2585)
        val h = Places.get(prefs, Places.Slot.HOME)!!
        assertEquals("Villaggio Mall", h.name)
        assertEquals(51.4437, h.lng, 1e-9)
        assertEquals(25.2585, h.lat, 1e-9)
        assertEquals(Places.Slot.HOME, h.slot)
    }

    @Test
    fun `the two slots are independent`() {
        Places.save(prefs, Places.Slot.HOME, "Msheireb", 51.5238, 25.2867)
        Places.save(prefs, Places.Slot.WORK, "West Bay", 51.4986, 25.3208)
        assertEquals(2, Places.load(prefs).size)
        assertEquals("Msheireb", Places.get(prefs, Places.Slot.HOME)!!.name)
        assertEquals("West Bay", Places.get(prefs, Places.Slot.WORK)!!.name)
    }

    @Test
    fun `the order is the enum's, not the order they were set`() {
        // A list whose items move when one is re-set is a list a driver has to
        // READ before tapping, which defeats the point of having two fixed
        // rows.
        Places.save(prefs, Places.Slot.WORK, "West Bay", 51.4986, 25.3208)
        Places.save(prefs, Places.Slot.HOME, "Msheireb", 51.5238, 25.2867)
        assertEquals(
            listOf(Places.Slot.HOME, Places.Slot.WORK),
            Places.load(prefs).map { it.slot },
        )
    }

    @Test
    fun `re-setting a slot replaces it silently`() {
        // No confirmation, deliberately: the control shows which slots are
        // already set, so "overwrite Home" is a decision made before the tap.
        Places.save(prefs, Places.Slot.HOME, "Msheireb", 51.5238, 25.2867)
        Places.save(prefs, Places.Slot.HOME, "West Bay", 51.4986, 25.3208)
        assertEquals(1, Places.load(prefs).size)
        assertEquals("West Bay", Places.get(prefs, Places.Slot.HOME)!!.name)
    }

    @Test
    fun `a nameless destination falls back to the slot's own label`() {
        // A long-press whose reverse geocode has not landed. "Home · Home"
        // reads oddly but it is findable; a blank row is not.
        Places.save(prefs, Places.Slot.HOME, "   ", 51.5238, 25.2867)
        assertEquals("Home", Places.get(prefs, Places.Slot.HOME)!!.name)
    }

    @Test
    fun `a separator in a place name cannot corrupt the record`() {
        // Same guard as Recents: the store is separator-delimited, so a name
        // containing one would split into the wrong number of fields and the
        // slot would silently vanish.
        Places.save(prefs, Places.Slot.WORK, "A\u001FB", 51.4986, 25.3208)
        val w = Places.get(prefs, Places.Slot.WORK)!!
        assertEquals("A B", w.name)
        assertEquals(51.4986, w.lng, 1e-9)
    }

    @Test
    fun `clearing one slot leaves the other`() {
        Places.save(prefs, Places.Slot.HOME, "Msheireb", 51.5238, 25.2867)
        Places.save(prefs, Places.Slot.WORK, "West Bay", 51.4986, 25.3208)
        Places.clear(prefs, Places.Slot.HOME)
        assertNull(Places.get(prefs, Places.Slot.HOME))
        assertEquals("West Bay", Places.get(prefs, Places.Slot.WORK)!!.name)
    }

    // ---- durability --------------------------------------------------------
    //
    // V6 §14 treats persistence as a reliability feature and names the cases
    // directly: "app killed immediately after changing a setting", "...after
    // saving Home/Work", "...after selecting a destination". Every store in
    // this file used `apply()`, which updates the in-memory map now and writes
    // the file on a background thread.
    //
    // Within one process the two are INDISTINGUISHABLE — every later `load`
    // sees the new value whether the bytes have landed or not — which is
    // precisely why the gap survived four releases and why an ordinary
    // round-trip test cannot see it. Android does flush pending `apply()` work
    // in onPause/onStop, so backgrounding was always safe; a force-stop, a
    // crash, or the OS reclaiming a foregrounded process was not.
    //
    // So these assertions reach past the map to the bytes. They are the JVM
    // half of the property; the device half is a force-stop one second after
    // the tap, which is what §16 asks to be physically verified.

    private fun prefsFileText(): String {
        val f = java.io.File(
            ApplicationProvider.getApplicationContext<android.content.Context>().dataDir,
            "shared_prefs/vector.xml",
        )
        assertTrue("preferences file should exist", f.exists())
        return f.readText()
    }

    @Test
    fun `a changed setting is on disk before the call returns`() {
        Settings.save(prefs, Settings(voice = VoiceMode.ALERTS, units = Units.IMPERIAL))
        val text = prefsFileText()
        assertTrue("voice mode should already be in the file", "ALERTS" in text)
        assertTrue("units should already be in the file", "IMPERIAL" in text)
    }

    @Test
    fun `a saved place is on disk before the call returns`() {
        Places.save(prefs, Places.Slot.HOME, "Msheireb", 51.5238, 25.2867)
        assertTrue("Msheireb" in prefsFileText())
    }

    @Test
    fun `a cleared place is gone from disk before the call returns`() {
        Places.save(prefs, Places.Slot.WORK, "West Bay", 51.4986, 25.3208)
        Places.clear(prefs, Places.Slot.WORK)
        // The one that matters most: a driver who removes their home address
        // and force-stops the app has asked for it to be gone, and an
        // asynchronous delete that had not landed would bring it back.
        assertTrue("West Bay" !in prefsFileText())
    }

    @Test
    fun `a chosen destination is on disk before the call returns`() {
        Recents.add(prefs, Recents.Entry("Villaggio Mall", 51.4433, 25.2586))
        assertTrue("Villaggio Mall" in prefsFileText())
    }

    @Test
    fun `the journey in progress is on disk before the call returns`() {
        // This store exists ONLY to survive the process dying, so an
        // asynchronous write is the one thing it cannot afford: a process
        // killed in the second after Start is exactly the case the feature is
        // for.
        Journey.save(prefs, "Hamad International", 51.6080, 25.2731, 1_700_000_000_000L)
        assertTrue("Hamad International" in prefsFileText())
    }

    @Test
    fun `a cleared journey is gone from disk before the call returns`() {
        Journey.save(prefs, "Hamad International", 51.6080, 25.2731, 1_700_000_000_000L)
        Journey.clear(prefs)
        assertTrue("Hamad International" !in prefsFileText())
    }
}
