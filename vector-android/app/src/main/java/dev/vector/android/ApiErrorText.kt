package dev.vector.android

/**
 * Turn a backend failure into something a driver can act on.
 *
 * §24 asks every error to say three things: what happened, what Vector can still
 * do, and what the driver can do next. The network cases were the gap — they all
 * fell through to `"Could not plan a route (java.net.UnknownHostException:
 * ...)"`, which is a stack trace with a sentence in front of it and tells a
 * driver nothing they can act on.
 *
 * The distinction that matters to the driver is **whose fault it is**:
 * unreachable means check the phone, a 5xx means the server is unwell and
 * waiting will help, and a 422 means the request was answerable but wrong.
 * Those three suggest three different actions.
 *
 * ## Why this is a pure object and not a private method on the Activity
 *
 * It was a private method, and that is how it stayed wrong: **a status code was
 * being reported as a network fault.** `VectorApi.getJson` raises every non-2xx
 * as `IOException("HTTP $code from $url: ...")`, and the class-first `when`
 * matched `is java.io.IOException` before ever looking at that message, so a
 * 401, a 403, a 404 and a 500 all read back *"Lost the connection to Vector
 * while planning the route"*. Measured on the emulator on 2026-09-21: a route
 * that failed for a reason the server stated sent the driver to check their
 * signal.
 *
 * Nothing here touches Android, so the mapping is asserted directly by
 * `ApiErrorTextTest` — including the regression above, which is a failing test
 * against the private-method version.
 */
object ApiErrorText {

    /**
     * The sentence for [t].
     *
     * Order is the whole design: **a status code beats the exception class**,
     * because the class says how the failure arrived and the code says what the
     * server decided.
     */
    fun friendly(t: Throwable): String {
        val msg = t.message.orEmpty()
        // 1. A status code the server stated beats the exception class: the
        //    class says how the failure arrived, the code says what the server
        //    decided.
        httpStatus(msg)?.let { return statusSentence(it, msg) }
        // 2. A REASON the server stated beats it too, and this is the half that
        //    the first version of this fix missed: `outside routable area`
        //    arrives on a 422 body, and for an IOException the class check below
        //    swallowed it exactly as the status code was swallowed before.
        if (mentionsServerReason(msg)) return statusSentence(null, msg)
        return when (t) {
            is java.net.UnknownHostException ->
                "Cannot reach Vector — check the phone's connection"
            is java.net.SocketTimeoutException ->
                "Vector did not answer in time — it may be busy. Try again."
            is java.net.ConnectException ->
                "Cannot reach Vector — the server may be down"
            // An IOException with neither a status nor a reason really is a
            // transport failure, and its message is an implementation detail
            // that varies by Android version, so matching on the class is the
            // stable test. Vector is self-hosted, so "unreachable" is genuinely
            // common — the stack is on the driver's own network or behind a
            // tunnel.
            is java.io.IOException ->
                "Lost the connection to Vector while planning the route"
            else -> statusSentence(null, msg)
        }
    }

    /** The code `VectorApi.getJson` puts in its message, or null. */
    private fun httpStatus(message: String): Int? =
        Regex("\\bHTTP (\\d{3})\\b").find(message)
            ?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Whether the message carries a refusal the server worded.
     *
     * Deliberately a short list of the phrases the backend actually emits, not a
     * guess at JSON: a body is truncated to 200 characters by `getJson`, so
     * parsing it here would be a second, partial parser of the same payload.
     */
    private fun mentionsServerReason(message: String): Boolean =
        "outside routable area" in message

    /**
     * What a status means to a driver.
     *
     * Takes the code when it was parsed and falls back to the raw message,
     * because the same codes arrive both inside an [java.io.IOException]'s
     * message and as a bare exception from other paths, and the sentence must
     * not depend on which.
     */
    private fun statusSentence(code: Int?, message: String): String = when {
        code == 422 || "outside routable area" in message ->
            "That point is outside the mapped area"
        code == 404 || "404" in message -> "No road connects those two points"
        code == 401 || code == 403 ||
            "401" in message || "403" in message ->
            "Vector refused the request — this build's access token may be wrong"
        code != null && code >= 500 || Regex("\\b5\\d\\d\\b").containsMatchIn(message) ->
            "Vector is unwell — try again in a moment"
        else -> "Could not plan a route"
    }
}
