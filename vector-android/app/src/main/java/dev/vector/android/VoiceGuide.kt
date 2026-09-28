package dev.vector.android

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Speaks maneuvers.
 *
 * A thin wrapper only: WHEN to speak and WHAT to say is
 * [dev.vector.geo.ManeuverAnnouncer], which is pure and unit-tested. This class
 * owns nothing but the platform engine, so the interesting logic never needs a
 * device to verify — and the one piece of judgement it does own, deciding which
 * voice a sentence needs, is [segment], which is pure and tested too.
 *
 * ## Language
 *
 * This used to set the engine to Arabic once, at startup, on the reasoning that
 * Doha's street names are Arabic. Two things were wrong with that.
 *
 * The first is that it is no longer true of what Vector says. The client asks
 * the router for English (`&lang=en`) and 48,427 of Qatar's 48,680 named roads
 * carry `name:en`, so the guidance text is overwhelmingly Latin — "Turn left
 * onto Al Urouba Street". An Arabic voice reading that either mangles it or
 * skips it. So does the speed readout, which is the pure-ASCII string
 * "Speed limit 80", and the fixed lines "Starting navigation" and "You have
 * arrived". On a phone set to en-GB, every one of those was being read by an
 * Arabic voice.
 *
 * The second is that one language per session cannot be right for a sentence
 * carrying two. The remaining 253 roads have no `name:en`, so the instruction
 * really is mixed — "Turn left onto شارع الخليج" — and whichever single voice
 * is chosen gets half the sentence wrong.
 *
 * So: the voice follows the device, an Arabic voice is resolved separately and
 * used only for the Arabic runs of a sentence, and a sentence containing both
 * is spoken as consecutive utterances in the right voices. A missing voice
 * degrades to the other one, and a missing engine degrades to silence — never
 * to a crash mid-drive.
 */
class VoiceGuide(context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false

    /** Whether the engine has a usable voice for each script. Resolved once. */
    private var latinOk = false
    private var arabicOk = false
    private var latinLocale: Locale = Locale.ENGLISH

    var enabled: Boolean = true

    /**
     * The sentence the engine is saying, or null when it is silent.
     *
     * Kept from the engine's own progress callbacks rather than from
     * `isSpeaking()`, because the question [dev.vector.geo.SpeechArbiter] asks
     * is not "is anything playing?" but "is THIS sentence playing, and what
     * kind is it?" — and a mixed-script sentence is several utterances that
     * must count as one.
     *
     * Set the moment a sentence is handed over, not at `onStart`: synthesis
     * takes a few hundred milliseconds, and a repeat arriving inside that gap
     * is exactly the restart the arbiter exists to drop.
     *
     * Guarded by [outstanding], like [pending]: the progress callbacks arrive
     * on the engine's binder thread, and `say` is called from the main one.
     */
    private var playing: dev.vector.geo.SpeechArbiter.Utterance? = null

    /**
     * The ONE sentence waiting for the playing one to end, or null.
     *
     * Held here rather than in the engine's queue on purpose. A `QUEUE_ADD`
     * cannot be taken back, so a queue would say every instruction offered —
     * including the "In 150 metres" line that is stale by the time its turn
     * comes. One slot, replaced by newer offers, says only the latest; see
     * [dev.vector.geo.SpeechArbiter] for which offers may replace which.
     */
    private var pending: dev.vector.geo.SpeechArbiter.Utterance? = null

    /** Utterance ids handed to the engine and not yet finished. Also the lock. */
    private val outstanding = HashMap<String, dev.vector.geo.SpeechArbiter.Utterance>()
    private var nextId = 0L

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            synchronized(outstanding) {
                outstanding[utteranceId]?.let { playing = it }
            }
        }

        override fun onDone(utteranceId: String?) = finished(utteranceId)

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = finished(utteranceId)

        override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
    }

    /**
     * An utterance ended. When it was the last of its sentence, the engine is
     * free, and the waiting sentence — if any — is said now.
     *
     * `onStop` for utterances a flush displaced can arrive after the new
     * sentence was handed over; their ids are already gone from
     * [outstanding], so they neither clear [playing] nor start [pending].
     */
    private fun finished(id: String?) {
        synchronized(outstanding) {
            if (outstanding.remove(id) == null || outstanding.isNotEmpty()) return
            playing = null
            val next = pending ?: return
            pending = null
            speakLocked(next)
        }
    }

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TextToSpeech unavailable ($status); guidance will be silent")
                return@TextToSpeech
            }
            val engine = tts ?: return@TextToSpeech
            // The device's own language first. It is the language the rest of
            // the product is already speaking: `VectorApi.langParam` derives
            // the router's `lang` from this same Locale, so the voice and the
            // banner cannot disagree about which language was asked for.
            val device = Locale.getDefault()
            latinOk = engine.usable(device)
            latinLocale = if (latinOk) device else Locale.ENGLISH
            if (!latinOk) latinOk = engine.usable(Locale.ENGLISH)
            arabicOk = engine.usable(ARABIC)
            if (!latinOk && !arabicOk) {
                Log.w(TAG, "no usable voice for $device or Arabic; guidance will be silent")
                return@TextToSpeech
            }
            engine.setSpeechRate(1.0f)
            engine.setOnUtteranceProgressListener(progress)
            ready = true
        }
    }

    /** Does the engine have this language, without leaving it selected? */
    private fun TextToSpeech.usable(locale: Locale): Boolean =
        runCatching {
            val r = isLanguageAvailable(locale)
            r == TextToSpeech.LANG_AVAILABLE ||
                r == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                r == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
        }.getOrDefault(false)

    /**
     * Offer a sentence, and let [dev.vector.geo.SpeechArbiter] decide whether
     * it is said now, waits for the one playing, or is not said at all.
     *
     * Every sentence used to flush the engine, and a second fix that kept
     * "guidance interrupts guidance" still measured 13 mid-sentence cuts on the
     * S24 drive run — "Continue on Qalat Al Askar Street" cut at 1.0 s by a
     * 150 m countdown, itself cut at 0.7 s by the maneuver. Now a sentence
     * never cuts another mid-word except where the countdown it replaces is
     * stale (a NOW over a PREPARE or an alert); everything else waits in
     * [pending] and is said the moment the engine is free.
     *
     * @param kind what the sentence is. Defaults to [dev.vector.geo.SpeechArbiter.Kind.NOW],
     *   the kind that neither interrupts another NOW nor is displaced by an
     *   alert — the safe reading of a caller that does not say.
     */
    fun say(
        text: String,
        kind: dev.vector.geo.SpeechArbiter.Kind = dev.vector.geo.SpeechArbiter.Kind.NOW,
    ) {
        if (!enabled || !ready) return
        if (tts == null) return
        synchronized(outstanding) {
            val decision = dev.vector.geo.SpeechArbiter.decide(
                playing, pending, dev.vector.geo.SpeechArbiter.Utterance(text, kind),
            )
            // Debug builds only: what the arbiter did with each sentence, for the
            // device drive checks (no audio capture over adb).
            if (BuildConfig.DEBUG) Log.i(TAG, "say priority=$kind playing=${playing != null} " +
                "decision=${decision::class.simpleName} text=\"$text\"")
            when (decision) {
                is dev.vector.geo.SpeechArbiter.Decision.Drop -> Unit
                is dev.vector.geo.SpeechArbiter.Decision.Queue -> pending = decision.pending
                is dev.vector.geo.SpeechArbiter.Decision.Interrupt -> {
                    pending = decision.pending
                    speakLocked(decision.now)
                }
            }
        }
    }

    /**
     * Hand a sentence to the engine, flushing whatever it is saying. Caller
     * holds [outstanding].
     *
     * Only the FIRST run of a mixed sentence flushes; the rest queue behind it,
     * so the sentence stays whole while still displacing the previous one.
     */
    private fun speakLocked(u: dev.vector.geo.SpeechArbiter.Utterance) {
        val engine = tts ?: return
        // A flush stops everything queued, whose onStop may or may not arrive;
        // forgetting them here keeps `playing` from outliving the sentence that
        // replaced it.
        outstanding.clear()
        playing = null
        var flushed = false
        for (run in segment(u.text)) {
            val locale = if (run.arabic) ARABIC else latinLocale
            // A run in a script we have no voice for is dropped rather than
            // handed to the wrong voice: silence is a smaller lie than a
            // street name read as gibberish.
            if (run.arabic && !arabicOk) continue
            if (!run.arabic && !latinOk) continue
            val id = "vector-guidance-${nextId++}"
            outstanding[id] = u
            playing = u
            val ok = runCatching {
                engine.setLanguage(locale)
                engine.speak(
                    run.text,
                    if (flushed) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH,
                    null,
                    id,
                ) == TextToSpeech.SUCCESS
            }.onFailure { Log.w(TAG, "speak failed", it) }.getOrDefault(false)
            // An utterance the engine refused will never report done, and a
            // `playing` that never clears would hold every later sentence in
            // the slot forever. Forget it now instead.
            if (ok) flushed = true else outstanding.remove(id)
        }
        if (outstanding.isEmpty()) playing = null
    }

    fun stop() {
        runCatching { tts?.stop() }
        synchronized(outstanding) { outstanding.clear(); playing = null; pending = null }
    }

    fun shutdown() {
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    /** One stretch of a sentence that wants a single voice. */
    data class Run(val text: String, val arabic: Boolean)

    companion object {
        private const val TAG = "VectorVoice"
        private val ARABIC = Locale("ar")

        /**
         * Split a sentence into consecutive runs, each in one script.
         *
         * "Turn left onto شارع الخليج" is two runs; "Speed limit 80" is one.
         *
         * Characters belonging to neither script — spaces, digits, punctuation
         * — join the run in progress rather than starting one of their own.
         * Otherwise "Turn left onto شارع الخليج, then keep right" would break
         * into five utterances, and the pauses between them would be audible.
         *
         * A run with no letter or digit in it is dropped: handing a lone comma
         * to a synthesiser buys a gap and nothing else.
         */
        fun segment(text: String): List<Run> {
            val runs = mutableListOf<Run>()
            val cur = StringBuilder()
            var curArabic: Boolean? = null
            for (ch in text) {
                val script = when {
                    isArabic(ch) -> true
                    ch.isLetter() -> false
                    else -> null          // neutral: stays with the current run
                }
                if (script != null && curArabic != null && script != curArabic) {
                    runs.addIfSpoken(cur.toString(), curArabic)
                    cur.setLength(0)
                }
                if (script != null) curArabic = script
                cur.append(ch)
            }
            runs.addIfSpoken(cur.toString(), curArabic ?: false)
            return runs
        }

        private fun MutableList<Run>.addIfSpoken(text: String, arabic: Boolean) {
            val t = text.trim()
            if (t.any { it.isLetterOrDigit() }) add(Run(t, arabic))
        }

        /**
         * Arabic script, including the presentation forms OSM names contain.
         *
         * Deliberately a range test rather than `Character.UnicodeScript`,
         * which is not available on every API level Vector supports.
         */
        fun isArabic(ch: Char): Boolean {
            val c = ch.code
            return (c in 0x0600..0x06FF) ||     // Arabic
                (c in 0x0750..0x077F) ||        // Arabic Supplement
                (c in 0x08A0..0x08FF) ||        // Arabic Extended-A
                (c in 0xFB50..0xFDFF) ||        // Presentation Forms-A
                (c in 0xFE70..0xFEFF)           // Presentation Forms-B
        }
    }
}
