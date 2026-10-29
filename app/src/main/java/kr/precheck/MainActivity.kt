package kr.precheck

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 설명 문장 없이, 큰 글씨로, 보자마자 읽히게. 생김새는 Pleos 순정: 연회색 바탕, 흰 카드, 검은 버튼
class MainActivity : Activity() {
    private lateinit var secCheck: LinearLayout
    private lateinit var secNow: LinearLayout
    private lateinit var secLog: LinearLayout
    private lateinit var status: TextView
    private lateinit var right: ScrollView

    private val onActive: (List<*>) -> Unit = { renderNow() }
    private val onRepeats: (Map<String, Int>) -> Unit = { renderNow() }
    private val onCheck: (CheckResult?) -> Unit = { renderCheck() }
    private val onLog: (Int) -> Unit = { renderLog() }
    private val onStatus: (String) -> Unit = { status.text = it }
    private val onDriving: (Boolean) -> Unit = { right.visibility = if (it) View.GONE else View.VISIBLE }   // 주행 중엔 기록을 숨긴다
    private val onOdometer: (Float?) -> Unit = { renderLog() }

    // 디버그 인텐트는 서비스로 넘긴다 (서비스는 비공개라 adb가 직접 못 깨움)
    private fun forwardDebug(i: android.content.Intent?) {
        if (i?.action?.startsWith("kr.precheck.DEBUG_") == true) startService(android.content.Intent(this, CarMonitorService::class.java).setAction(i.action).putExtras(i))
    }
    override fun onNewIntent(i: android.content.Intent?) { super.onNewIntent(i); forwardDebug(i) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        CarMonitorService.start(this)
        forwardDebug(intent)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG); setPadding(48, 28, 48, 24) }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(text("Precheck", H1, bold = true), LinearLayout.LayoutParams(-2, -2))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            status = text("연결 중", META, color = MUTED).apply { setPadding(28, 12, 28, 12); background = rounded(CARD, 999f) }
            addView(status)
        })

        val cols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        secCheck = section(left, "출발 전 점검")
        secNow = section(left, "경고등")
        val rightCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        secLog = section(rightCol, "기록")
        cols.addView(ScrollView(this).apply { addView(left); isVerticalScrollBarEnabled = false }, LinearLayout.LayoutParams(0, -1, 1.15f).apply { rightMargin = 40 })
        right = ScrollView(this).apply { addView(rightCol); isVerticalScrollBarEnabled = false }
        cols.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(cols, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        AppState.active.observe(onActive); AppState.repeats.observe(onRepeats); AppState.lastCheck.observe(onCheck)
        AppState.logVersion.observe(onLog); AppState.status.observe(onStatus); AppState.driving.observe(onDriving); AppState.odometerKm.observe(onOdometer)
    }

    override fun onDestroy() {
        AppState.active.remove(onActive); AppState.repeats.remove(onRepeats); AppState.lastCheck.remove(onCheck)
        AppState.logVersion.remove(onLog); AppState.status.remove(onStatus); AppState.driving.remove(onDriving); AppState.odometerKm.remove(onOdometer)
        super.onDestroy()
    }

    // 정비소 다녀온 뒤 버튼 한 번: 무엇을 갈았는지만 고르면 주행거리는 차에서 읽는다
    private fun askServiced() {
        val d = Dialog(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(44, 40, 44, 40); background = rounded(CARD, 24f) }
        box.addView(text("무엇을 교체했나요?", H2, bold = true))
        box.addView(text("지금 ${AppState.odometerKm.value?.let { "%,d".format(it.toInt()) } ?: "?"}km", BODY, color = MUTED))
        Maintenance.items.forEach { item ->
            box.addView(button(item.name, primary = false) { CarMonitorService.serviced(this, item.name); d.dismiss() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 16 })
        }
        box.addView(button("취소", primary = true) { d.dismiss() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 28 })
        d.setContentView(box)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.show()
        d.window?.setLayout(880, -2)
    }

    // ---- 크기·색 ----
    private val H1 = 44f; private val H2 = 34f; private val BODY = 26f; private val META = 21f; private val BTN = 26f
    private val BG = Color.parseColor("#F2F3F5"); private val CARD = Color.WHITE
    private val INK = Color.parseColor("#111111"); private val MUTED = Color.parseColor("#6B7280"); private val LINE = Color.parseColor("#DDE1E6")
    private val TEXT2 = Color.parseColor("#374151")

    private fun sevColor(s: Severity) = when (s) { Severity.RED -> Color.parseColor("#E5484D"); Severity.YELLOW -> Color.parseColor("#F5A524"); Severity.INFO -> Color.parseColor("#9CA3AF") }
    private fun chipBg(s: Severity) = when (s) { Severity.RED -> Color.parseColor("#FDECEC"); Severity.YELLOW -> Color.parseColor("#FFF4D6"); Severity.INFO -> Color.parseColor("#EEF0F3") }
    private fun chipInk(s: Severity) = when (s) { Severity.RED -> Color.parseColor("#B3261E"); Severity.YELLOW -> Color.parseColor("#7A4F00"); Severity.INFO -> Color.parseColor("#4B5563") }

    private fun text(s: String, size: Float, bold: Boolean = false, color: Int = INK) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD); setPadding(0, 6, 0, 6); setLineSpacing(0f, 1.12f)
    }

    private fun rounded(fill: Int, radius: Float = 16f, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = radius; setColor(fill); if (stroke != null) setStroke(2, stroke)
    }

    // 버튼: 순정처럼 검은 바탕 흰 글씨(주), 흰 바탕 검은 글씨(보조). 손가락 크기로 넉넉하게
    private fun button(label: String, primary: Boolean = true, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = BTN; gravity = Gravity.CENTER; setPadding(44, 28, 44, 28)
        setTextColor(if (primary) Color.WHITE else INK); setTypeface(null, Typeface.BOLD)
        background = if (primary) rounded(INK, 14f) else rounded(CARD, 14f, LINE)
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }
    private fun button(label: String, primary: Boolean = true, lp: LinearLayout.LayoutParams, onClick: () -> Unit) = button(label, primary, onClick).also { it.layoutParams = lp }

    private fun chip(s: Severity) = TextView(this).apply {
        text = s.label; textSize = META; setTextColor(chipInk(s)); setTypeface(null, Typeface.BOLD)
        setPadding(22, 8, 22, 8); background = rounded(chipBg(s), 999f)
    }

    private fun dot(s: Severity) = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(sevColor(s)) } }

    // 섹션 = 제목 + 내용 컨테이너
    private fun section(parent: LinearLayout, title: String): LinearLayout {
        parent.addView(text(title, H2, bold = true).apply { setPadding(0, 28, 0, 4) })
        return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; parent.addView(this) }
    }

    // 카드: 위험도 점 + 큰 제목 + 라벨 칩, 한 문장, 버튼 하나
    private fun card(sev: Severity?, title: String, detail: String, action: String? = null, onClick: (() -> Unit)? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(40, 32, 40, 32); background = rounded(CARD, 22f)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 16, 0, 4) }
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            if (sev != null) addView(dot(sev), LinearLayout.LayoutParams(26, 26).apply { rightMargin = 20 })
            addView(text(title, H2, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            if (sev != null) addView(chip(sev))
        })
        if (detail.isNotEmpty()) addView(text(detail, BODY, color = TEXT2).apply { setPadding(0, 10, 0, 6) })
        if (action != null) addView(button(action, primary = true, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 20 }) { onClick?.invoke() })
    }

    private fun empty(msg: String) = card(null, "", "").also { it.removeAllViews(); it.addView(text(msg, BODY, color = MUTED)) }

    private fun time(t: Long) = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(t))

    // ---- 렌더 ----
    private fun renderCheck() {
        secCheck.removeAllViews()
        val r = AppState.lastCheck.value
        if (r == null) {
            secCheck.addView(empty("출발하면 자동으로 점검해요."))
        } else {
            val where = if (r.routeKm > 0) "목적지까지 ${"%.1f".format(r.routeKm)}km" else "차 상태만"
            secCheck.addView(text("${time(r.time)} · $where", META, bold = true, color = MUTED).apply { setPadding(0, 12, 0, 0) })
            if (r.ok) secCheck.addView(card(null, "이상 없음", "주행 가능 ${r.rangeKm?.toInt() ?: "?"}km"))
            r.items.forEach { secCheck.addView(card(it.severity, it.title, it.detail, it.action?.let { a -> "$a 찾기" }) { it.action?.let { a -> CarMonitorService.search(this, a) } }) }
        }
        secCheck.addView(button("지금 점검", primary = false, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 16 }) { CarMonitorService.checkNow(this) })
    }

    private fun renderNow() {
        secNow.removeAllViews()
        val active = AppState.active.value
        if (active.isEmpty()) { secNow.addView(empty("켜진 경고등 없음")); return }
        active.map { it to TelltaleBook.infoOf(it) }.sortedBy { it.second.severity }.forEach { (d, i) ->
            // 반복된 경고는 할 일 뒤에 횟수를 붙이고 정비소 버튼을 보장한다
            val n = AppState.repeats.value[TelltaleBook.nameOf(d.type)]
            val todo = if (n != null) "${i.todo} 7일 사이 ${n}번째. 정비소 예약을 권해요." else i.todo
            val action = if (n != null) i.action ?: "정비소" else i.action
            secNow.addView(card(i.severity, i.title, todo, action?.let { "$it 찾기" }) { action?.let { CarMonitorService.search(this, it) } })
        }
    }

    private fun renderLog() {
        secLog.removeAllViews()
        secLog.addView(button("정비 기록", primary = false, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 12 }) { askServiced() })
        // 소모품: 교체 기록이 있는 것만
        val odo = AppState.odometerKm.value
        val svc = Maintenance.status(this, odo)
        if (svc.isNotEmpty()) secLog.addView(card(null, "소모품", svc.joinToString("\n")))
        val events = EventLog.readAll(this)
        if (events.isEmpty()) { secLog.addView(empty("기록 없음")); return }
        events.take(50).forEach { e ->
            val km = e.odometerKm?.let { " · ${"%,d".format(it.toInt())}km" } ?: ""
            val nav = if (e.navigating) " · 안내 중" else ""
            secLog.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(36, 26, 36, 26); background = rounded(CARD, 22f)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 16, 0, 0) }
                addView(text("${e.timeText()}$km$nav", META, color = MUTED))
                addView(text(e.title, BODY, bold = true))
                addView(text(e.detail, BODY, color = TEXT2))
            })
        }
    }
}
