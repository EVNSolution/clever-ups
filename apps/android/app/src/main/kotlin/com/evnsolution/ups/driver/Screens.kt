package com.evnsolution.ups.driver

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView

/** Sign-in: login ID and password, and a line that says what went wrong. */
class LoginScreen(private val context: Context, private val onLogin: (String, String) -> Unit) {
    val note = Ui.text(context, "", Ui.NOTE, Ui.MUTED)

    fun view(): View {
        val id = EditText(context).apply { hint = "로그인 ID"; inputType = InputType.TYPE_CLASS_TEXT }
        val password = EditText(context).apply {
            hint = "비밀번호"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        return Ui.column(context, Ui.PAGE).apply {
            addView(Ui.text(context, "CLEVER UPS 기사", Ui.TITLE, bold = true))
            addView(Ui.gap(context, Ui.PAGE))
            addView(id)
            addView(password)
            addView(Ui.button(context, "로그인") { onLogin(id.text.toString().trim(), password.text.toString()) })
            addView(Ui.detached(note))
        }
    }
}

/** The day's route from top to bottom: each stop with its number, planned time, what happened, and the address. */
class RouteScreen(
    private val context: Context,
    private val onOpen: (DriverStop) -> Unit,
    private val onReload: () -> Unit,
) {
    fun view(route: DriverRoute): View {
        val done = route.stops.count { it.result?.completedAt != null }
        val list = Ui.column(context).apply {
            val header = "${route.day} · ${route.routeCode ?: route.driverId} · ${Ui.clock(route.departAt)} 출발"
            addView(Ui.text(context, header, Ui.HEADER, bold = true))
            val summary = "정차 ${route.stops.size}곳 · 완료 $done · ${route.distanceMeters / M_PER_KM}km"
            addView(Ui.text(context, summary, Ui.NOTE, Ui.MUTED))
            addView(Ui.button(context, "새로 고침") { onReload() })
            route.stops.forEach { addView(stopRow(it)) }
        }
        return ScrollView(context).apply { addView(list) }
    }

    private fun stopRow(stop: DriverStop): View {
        val status = statusOf(stop.result)
        val body = Ui.column(context, 0).apply {
            addView(Ui.text(context, "${Ui.clock(stop.arrival)} 예정${status.first}", Ui.SMALL, status.second))
            addView(Ui.text(context, stop.title, Ui.BODY))
        }
        return Ui.row(context).apply {
            setPadding(0, Ui.dp(context, Ui.ROW), 0, Ui.dp(context, Ui.ROW))
            val color = if (stop.result?.completedAt != null) Ui.MUTED else Ui.ACCENT
            addView(Ui.number(context, stop.seq.toString(), color))
            addView(body, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener { onOpen(stop) }
        }
    }

    private companion object {
        const val M_PER_KM = 1000
    }
}

/** What happened at a stop so far, as a short line and its color. Empty when nothing was reported. */
fun statusOf(result: StopResult?): Pair<String, Int> = when {
    result?.outcome == "DELIVERED" -> {
        val how = if (result.podMethod == "FACE") "대면 ${result.recipientName.orEmpty()}" else "비대면"
        " · 배송 완료 ${Ui.clock(result.completedAt)} · $how" to Ui.OK
    }
    result?.outcome == "EXCEPTION" -> " · 예외 ${result.code} ${Ui.clock(result.completedAt)}" to Ui.WARN
    result?.arrivedAt != null -> " · 도착 ${Ui.clock(result.arrivedAt)}" to Ui.ACCENT
    else -> "" to Ui.MUTED
}

/** What the stop screen asks the activity to do. */
interface StopActions {
    fun back()
    fun arrive(stop: DriverStop)
    fun delivered(stop: DriverStop, density: String, recipientName: String?)
    fun exception(stop: DriverStop, code: String)
    fun takePhoto(stop: DriverStop)
}

/**
 * One stop: where to go and the parcel, then arrival, delivery (commercial or residential; face to face with the
 * recipient's English name, or at the door with a photo) or a UPS exception code.
 */
class StopScreen(private val context: Context, private val actions: StopActions) {
    val note = Ui.text(context, "", Ui.NOTE, Ui.WARN)

    fun view(stop: DriverStop, codes: List<UpsCode>, photos: Int): View {
        val page = Ui.column(context).apply {
            addView(Ui.button(context, "← 노선") { actions.back() })
            addView(Ui.text(context, "${stop.seq}. ${stop.title}", Ui.HEAD, bold = true))
            if (stop.addressKo != null) addView(Ui.text(context, stop.original, Ui.SMALL, Ui.MUTED))
            val parcel = "운송장 ${stop.trackingNumber} · ${Ui.clock(stop.arrival)} 도착 예정"
            addView(Ui.text(context, parcel, Ui.NOTE, Ui.MUTED))
            val status = statusOf(stop.result)
            if (status.first.isNotEmpty()) {
                addView(Ui.text(context, "지금" + status.first, Ui.BODY, status.second, bold = true))
            }
            addView(Ui.gap(context))
            if (stop.result?.arrivedAt == null) addView(Ui.button(context, "도착") { actions.arrive(stop) })
            addView(delivery(stop, photos))
            addView(Ui.button(context, "예외 보고") { chooseException(stop, codes) })
            addView(Ui.detached(note))
        }
        return ScrollView(context).apply { addView(page) }
    }

    private fun delivery(stop: DriverStop, photos: Int): View {
        val density = choice("상업(C)" to "C", "주거(R)" to "R")
        val method = choice("대면" to "FACE", "비대면(사진)" to "DOOR")
        val name = EditText(context).apply { hint = "수령인 영문 이름"; inputType = InputType.TYPE_CLASS_TEXT }
        return Ui.column(context, 0).apply {
            addView(Ui.text(context, "배송 완료", Ui.SECTION, bold = true))
            addView(density.first)
            addView(method.first)
            addView(name)
            addView(Ui.button(context, "현장 사진 찍기 ($photos)") { actions.takePhoto(stop) })
            addView(Ui.button(context, "배송 완료(KB)") {
                val face = method.second() == "FACE"
                note.text = when {
                    density.second() == null -> "상업·주거를 고르세요."
                    method.second() == null -> "대면·비대면을 고르세요."
                    face && name.text.isBlank() -> "대면은 수령인 이름이 필요합니다."
                    !face && photos == 0 -> "비대면은 사진이 필요합니다."
                    else -> null.also {
                        actions.delivered(stop, density.second()!!, if (face) name.text.toString().trim() else null)
                    }
                } ?: ""
            })
        }
    }

    private fun chooseException(stop: DriverStop, codes: List<UpsCode>) {
        val exceptions = codes.filter { it.code != "KB" }
        AlertDialog.Builder(context)
            .setTitle("예외 코드")
            .setItems(exceptions.map { it.label }.toTypedArray()) { _, index ->
                actions.exception(stop, exceptions[index].code)
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    /** A row of choices and a way to read the chosen value. */
    private fun choice(vararg options: Pair<String, String>): Pair<RadioGroup, () -> String?> {
        val group = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
        val values = mutableMapOf<Int, String>()
        options.forEach { (label, value) ->
            val button = RadioButton(context).apply { text = label; id = View.generateViewId() }
            values[button.id] = value
            group.addView(button)
        }
        return group to { values[group.checkedRadioButtonId] }
    }
}
