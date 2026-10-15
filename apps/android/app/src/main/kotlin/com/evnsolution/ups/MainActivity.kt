package com.evnsolution.ups

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.MediaStore
import android.widget.TextView
import androidx.core.content.FileProvider
import com.evnsolution.ups.driver.ApiRefused
import com.evnsolution.ups.driver.DriverRoute
import com.evnsolution.ups.driver.DriverStop
import com.evnsolution.ups.driver.LoginRefused
import com.evnsolution.ups.driver.LoginScreen
import com.evnsolution.ups.driver.PositionReporter
import com.evnsolution.ups.driver.RouteScreen
import com.evnsolution.ups.driver.StopActions
import com.evnsolution.ups.driver.StopScreen
import com.evnsolution.ups.driver.UpsApi
import com.evnsolution.ups.driver.UpsCode
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.concurrent.thread

/**
 * The driver app: sign in, see today's route as a timeline, open a stop, and report arrival, delivery (with the
 * recipient's name or a photo) or an exception. Network calls run on worker threads; screens are drawn on the UI
 * thread. Photos are taken into the app's cache, sent, and deleted, so they never stay in the phone's gallery.
 */
class MainActivity : Activity(), StopActions {
    private val api = UpsApi(BuildConfig.API_BASE_URL)
    private val login: LoginScreen by lazy { LoginScreen(this) { id, password -> signIn(id, password) } }
    private val routeScreen: RouteScreen by lazy { RouteScreen(this, ::open) { load() } }
    private val stopScreen: StopScreen by lazy { StopScreen(this, this) }
    private var route: DriverRoute? = null
    private var codes: List<UpsCode> = emptyList()
    private var opened: String? = null
    private var pending: Pair<DriverStop, File>? = null
    private val signedOut: () -> Unit = { setContentView(login.view()) }
    private val positions: PositionReporter by lazy { PositionReporter(this, api) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        application.registerActivityLifecycleCallbacks(positions)
        setContentView(login.view())
    }

    private fun signIn(id: String, password: String): Unit = work(login.note, signedOut) {
        val role = api.login(id, password)
        if (role != "driver") throw ApiRefused(FORBIDDEN)
        codes = api.codes()
        runOnUiThread { positions.signedIn() }
        load()
    }

    private fun load(): Unit = work(login.note, signedOut) {
        val fresh = api.route()
        runOnUiThread {
            route = fresh
            val stop = fresh.stops.firstOrNull { it.orderId == opened }
            val photos = stop?.result?.photoCount ?: 0
            setContentView(if (stop == null) routeScreen.view(fresh) else stopScreen.view(stop, codes, photos))
        }
    }

    private fun open(stop: DriverStop) {
        opened = stop.orderId
        setContentView(stopScreen.view(stop, codes, stop.result?.photoCount ?: 0))
    }

    override fun back() {
        opened = null
        route?.let { setContentView(routeScreen.view(it)) }
    }

    override fun arrive(stop: DriverStop): Unit = act {
        api.arrive(it, stop.orderId, positions.last?.latitude, positions.last?.longitude)
    }

    override fun delivered(stop: DriverStop, density: String, recipientName: String?): Unit =
        act { api.delivered(it, stop.orderId, density, recipientName) }

    override fun exception(stop: DriverStop, code: String): Unit = act { api.exception(it, stop.orderId, code) }

    override fun takePhoto(stop: DriverStop) {
        val file = File(File(cacheDir, "photos").apply { mkdirs() }, "stop.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.photos", file)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        pending = stop to file
        @Suppress("DEPRECATION")
        startActivityForResult(intent, PHOTO)
    }

    @Deprecated("Activity result API needs AndroidX Activity; this app uses the platform Activity.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val (stop, file) = pending ?: return
        pending = null
        if (requestCode != PHOTO || resultCode != RESULT_OK || !file.exists()) return
        act {
            try {
                api.photo(it, stop.orderId, shrink(file))
            } finally {
                file.delete()
            }
        }
    }

    /** Runs a report on the open route, then reads the route again so the screen shows what the server kept. */
    private fun act(report: (String) -> Unit): Unit = work(stopScreen.note, signedOut) {
        val planId = route?.planId ?: return@work
        report(planId)
        load()
    }

    private companion object {
        const val PHOTO = 1
    }
}

private const val MAX_SIDE = 1600
private const val QUALITY = 80
private const val UNAUTHORIZED = 401
private const val FORBIDDEN = 403
private const val NOT_FOUND = 404
private const val TOO_MANY = 429

/** What a failed call means to the driver, in words to act on. */
private fun words(error: Throwable): String = if (error is LoginRefused) {
    if (error.status == TOO_MANY) "로그인 시도가 많아 잠시 잠겼습니다. 15분 뒤 다시 시도하세요." else "ID 또는 비밀번호가 맞지 않습니다."
} else when ((error as? ApiRefused)?.status) {
    UNAUTHORIZED -> "로그인이 필요합니다."
    FORBIDDEN -> "기사 계정이 아니거나 기사에 연결되지 않았습니다."
    NOT_FOUND -> "오늘 전송된 노선이 없습니다."
    null -> "서버에 연결하지 못했습니다."
    else -> "처리하지 못했습니다 (HTTP ${(error as ApiRefused).status})."
}

/** The photo at most 1600 px on its long side, as JPEG, so a door photo stays well under the 5 MB limit. */
private fun shrink(file: File): ByteArray {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
    val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    return ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        bitmap.recycle()
        out.toByteArray()
    }
}



/** Runs [task] off the UI thread; a failure goes into [note] in words the driver can act on. A 401 signs out. */
private fun Activity.work(note: TextView, onSignedOut: () -> Unit, task: () -> Unit) {
    note.text = ""
    thread {
        runCatching(task).onFailure { error ->
            runOnUiThread {
                note.text = words(error)
                if ((error as? ApiRefused)?.status == UNAUTHORIZED) onSignedOut()
            }
        }
    }
}
