package dev.vector.android

import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import dev.vector.geo.SpeechArbiter.Kind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.Locale

/**
 * The voice, against a real (shadowed) TextToSpeech engine.
 *
 * [dev.vector.geo.SpeechArbiter] holds the rules and is tested on the JVM;
 * this pins that [VoiceGuide] actually carries them out — that it knows what is
 * playing from the engine's own progress callbacks, and that the queue mode it
 * hands the engine is the one the rule asked for.
 *
 * Robolectric's engine posts `onStart`/`onDone` to the main looper, so an
 * utterance counts as PLAYING until the looper is idled — which is exactly the
 * window in which, on the 2026-09-24 drive, a second sentence arrived and cut
 * the first off.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceGuideArbitrationTest {

    private lateinit var voice: VoiceGuide
    private lateinit var engine: ShadowTextToSpeech

    @Before
    fun setUp() {
        ShadowTextToSpeech.addLanguageAvailability(Locale.getDefault())
        ShadowTextToSpeech.addLanguageAvailability(Locale.ENGLISH)
        voice = VoiceGuide(ApplicationProvider.getApplicationContext())
        engine = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        engine.onInitListener.onInit(TextToSpeech.SUCCESS)
    }

    @After
    fun tearDown() {
        voice.shutdown()
        ShadowTextToSpeech.reset()
    }

    private fun finishSpeaking() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `an alert arriving mid-alert waits for it instead of cutting it off`() {
        // 0:17 on the 2026-09-24 drive: "Speed limit 50" cut off by the camera.
        voice.say("Speed limit 50", Kind.ALERT)
        voice.say("Speed camera ahead", Kind.ALERT)
        assertEquals(listOf("Speed limit 50"), engine.spokenTextList)
        finishSpeaking()
        assertEquals(listOf("Speed limit 50", "Speed camera ahead"), engine.spokenTextList)
    }

    @Test
    fun `the NOW stage does not restart the TURN sentence still playing`() {
        val s = "Slight left onto Al Mirqab Al Jadeed Street"
        voice.say(s)
        voice.say(s)
        finishSpeaking()
        assertEquals(listOf(s), engine.spokenTextList)
    }

    @Test
    fun `once it has finished, the same sentence may be said again`() {
        // The arbiter must only drop a repeat of what is PLAYING; one that
        // ended a moment ago is a new announcement.
        voice.say("Turn right onto Al Amir Street")
        finishSpeaking()
        voice.say("Turn right onto Al Amir Street")
        assertEquals(2, engine.spokenTextList.size)
    }

    @Test
    fun `a pending sentence is spoken when the playing one is done`() {
        // Drive I: the service-road line cut the slip-road line at 2.6 s.
        voice.say("Continue on the slip road, then continue")
        voice.say("Continue on the service road")
        assertEquals(listOf("Continue on the slip road, then continue"), engine.spokenTextList)
        finishSpeaking()
        assertEquals(
            listOf("Continue on the slip road, then continue", "Continue on the service road"),
            engine.spokenTextList,
        )
    }

    @Test
    fun `a newer pending sentence replaces the older one`() {
        // Drive E: the 150 m countdown is stale by the time the maneuver line
        // arrives, so it is never said at all.
        voice.say("Continue on Qalat Al Askar Street")
        voice.say("In 150 metres, continue on the slip road", Kind.PREPARE)
        voice.say("Continue on the slip road")
        finishSpeaking()
        assertEquals(
            listOf("Continue on Qalat Al Askar Street", "Continue on the slip road"),
            engine.spokenTextList,
        )
    }

    @Test
    fun `starting navigation is heard whole before the first instruction`() {
        voice.say("Starting navigation", Kind.COURTESY)
        voice.say("Continue on Qalat Al Askar Street")
        assertEquals(listOf("Starting navigation"), engine.spokenTextList)
        finishSpeaking()
        assertEquals(listOf("Starting navigation", "Continue on Qalat Al Askar Street"), engine.spokenTextList)
    }

    @Test
    fun `the maneuver still interrupts its own stale countdown`() {
        voice.say("In 150 metres, turn right onto Al Amir Street", Kind.PREPARE)
        voice.say("Turn right onto Al Amir Street")
        assertEquals(TextToSpeech.QUEUE_FLUSH, engine.queueMode)
        assertEquals("Turn right onto Al Amir Street", engine.lastSpokenText)
    }
}
