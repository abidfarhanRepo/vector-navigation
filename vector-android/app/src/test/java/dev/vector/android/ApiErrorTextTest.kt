package dev.vector.android

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * What a driver is told when a route fails.
 *
 * The first test is the regression: a status code the server stated used to be
 * reported as a network fault, because the class-first `when` matched
 * `IOException` before reading the message. On the emulator that produced
 * *"Lost the connection to Vector while planning the route"* for a request the
 * backend had answered — and the driver's next move for a network fault is to
 * check their signal, which is the wrong move and wastes the one minute they had.
 *
 * `VectorApi.getJson` puts the code in the message as `HTTP $code from $url`, so
 * the fixtures below are that exact shape.
 */
class ApiErrorTextTest {

    private fun http(code: Int) = IOException("HTTP $code from https://your-host.example.com/navigate?x: body")

    @Test
    fun `a status the server stated is never reported as a lost connection`() {
        for (code in listOf(400, 401, 403, 404, 422, 500, 502, 503)) {
            val s = ApiErrorText.friendly(http(code))
            assertEquals(
                "HTTP $code was reported as a network fault",
                false,
                s == "Lost the connection to Vector while planning the route",
            )
        }
    }

    @Test
    fun `a refused request blames the token`() {
        for (code in listOf(401, 403)) {
            assertEquals(
                "Vector refused the request — this build's access token may be wrong",
                ApiErrorText.friendly(http(code)),
            )
        }
    }

    @Test
    fun `a server fault says the server is unwell`() {
        for (code in listOf(500, 502, 503, 504)) {
            assertEquals(
                "Vector is unwell — try again in a moment",
                ApiErrorText.friendly(http(code)),
            )
        }
    }

    @Test
    fun `a point outside the mapped area says so`() {
        assertEquals(
            "That point is outside the mapped area",
            ApiErrorText.friendly(http(422)),
        )
        // And when the code is not in the message but the reason is.
        assertEquals(
            "That point is outside the mapped area",
            ApiErrorText.friendly(IOException("destination is outside routable area")),
        )
    }

    @Test
    fun `no road between two points is its own sentence`() {
        assertEquals(
            "No road connects those two points",
            ApiErrorText.friendly(http(404)),
        )
    }

    @Test
    fun `a transport failure with no status still reads as a lost connection`() {
        // The case the old branch existed for, and it must survive: an
        // IOException the server never answered.
        assertEquals(
            "Lost the connection to Vector while planning the route",
            ApiErrorText.friendly(IOException("unexpected end of stream")),
        )
    }

    @Test
    fun `the three transport failures keep their three different actions`() {
        assertEquals(
            "Cannot reach Vector — check the phone's connection",
            ApiErrorText.friendly(UnknownHostException("your-host.example.com")),
        )
        assertEquals(
            "Vector did not answer in time — it may be busy. Try again.",
            ApiErrorText.friendly(SocketTimeoutException("timeout")),
        )
        assertEquals(
            "Cannot reach Vector — the server may be down",
            ApiErrorText.friendly(ConnectException("ECONNREFUSED")),
        )
    }

    @Test
    fun `an unclassifiable failure invents nothing`() {
        assertEquals("Could not plan a route", ApiErrorText.friendly(RuntimeException("?")))
        // An IOException with no message at all has not been answered by the
        // server, so it is still a transport failure — not an invented status.
        assertEquals(
            "Lost the connection to Vector while planning the route",
            ApiErrorText.friendly(IOException("")),
        )
    }
}
