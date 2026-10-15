package com.evnsolution.ups.driver

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import kotlin.concurrent.thread

/**
 * Sends the phone's place while the app is on screen, at most once a minute, so 운행 현황 can show where the
 * driver is. Nothing runs in the background and nothing is kept on the phone. The permission is asked once the
 * driver is signed in; after the dialog the activity resumes and updates start.
 */
class PositionReporter(private val activity: Activity, private val api: UpsApi) :
    Application.ActivityLifecycleCallbacks, LocationListener {
    private val locations = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var signedIn = false
    private var sentAt = 0L

    /** The latest place the phone knows, for an arrival or a report. */
    var last: Location? = null
        private set

    fun signedIn() {
        signedIn = true
        if (!allowed()) activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST)
        start()
    }

    private fun allowed() =
        activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun start() {
        if (!signedIn || !allowed()) return
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { locations.isProviderEnabled(it) }
            .forEach { locations.requestLocationUpdates(it, INTERVAL_MS, DISTANCE_M, this) }
    }

    override fun onLocationChanged(location: Location) {
        last = location
        val now = System.currentTimeMillis()
        if (now - sentAt < INTERVAL_MS) return
        sentAt = now
        thread { runCatching { api.position(location.latitude, location.longitude, location.accuracy.toDouble()) } }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity === this.activity) start()
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity === this.activity) locations.removeUpdates(this)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        if (activity === this.activity) activity.application.unregisterActivityLifecycleCallbacks(this)
    }

    private companion object {
        const val REQUEST = 7
        const val INTERVAL_MS = 60_000L
        const val DISTANCE_M = 50f
    }
}
