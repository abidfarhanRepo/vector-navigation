package dev.vector.android.car

import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Vector's entry point on the car screen.
 *
 * Android Auto binds this service when the driver taps Vector in the car's
 * launcher. It is declared in the manifest with the NAVIGATION category, which
 * is what makes the head unit list Vector among the navigation apps rather
 * than under media or messaging, and what unlocks the navigation templates.
 *
 * The service itself does almost nothing: it vouches for the host and hands
 * back a [VectorCarSession]. All of the behaviour is in the session and its
 * screens, and all of the navigation is in the phone's engine — see
 * [dev.vector.android.NavBridge].
 */
class VectorCarAppService : CarAppService() {

    /**
     * Who is allowed to drive this app's screens.
     *
     * This is a real security boundary, not boilerplate. The car host can
     * read every template Vector renders — destinations, the drive in
     * progress, the driver's saved places — so an unvalidated host is an app
     * that will hand a journey to anything on the device that binds it.
     *
     * `ALLOW_ALL_HOSTS_VALIDATOR` is therefore restricted to debuggable
     * builds, where it is what lets the Desktop Head Unit connect at all.
     * Release builds check the caller against the library's shipped allowlist
     * of signed Google hosts. The check is on the debuggable FLAG rather than
     * on `BuildConfig.DEBUG` so that a release APK cannot pick up the
     * permissive branch through a build-variant mistake — the flag is a
     * property of the binary the system installed.
     */
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = VectorCarSession()
}
