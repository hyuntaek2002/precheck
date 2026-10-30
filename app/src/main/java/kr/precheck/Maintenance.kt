package kr.precheck

import android.content.Context

// 소모품 교체 주기. 정비소 다녀온 뒤 버튼 한 번으로 "언제(몇 km에서) 갈았는지"를 남기고, 출발 전 점검이 때가 되면 알려 준다
// 주기는 고정값. 차종·설명서 기준으로 조정할 여지
object Maintenance {
    data class Item(val name: String, val intervalKm: Int, val ev: Boolean = true)   // ev=false면 전기차에는 없는 항목

    val all = listOf(
        Item("엔진오일", 10_000, ev = false),
        Item("타이어 위치 교환", 10_000),
        Item("에어컨 필터", 15_000),
        Item("브레이크 패드", 40_000),
    )
    fun items(ev: Boolean) = all.filter { !ev || it.ev }
    private const val DUE_RATIO = 0.9f   // 주기의 90%를 넘기면 "곧 교체"

    private fun prefs(c: Context) = c.getSharedPreferences("state", Context.MODE_PRIVATE)
    private fun key(name: String) = "svc_$name"

    fun serviced(c: Context, name: String, odoKm: Float) = prefs(c).edit().putFloat(key(name), odoKm).apply()
    fun servicedAt(c: Context, name: String): Float? = prefs(c).getFloat(key(name), -1f).takeIf { it >= 0 }

    // 출발 전 점검에 끼워 넣을 항목
    fun due(c: Context, odoKm: Float?): List<CheckItem> {
        if (odoKm == null) return emptyList()
        return all.mapNotNull { item ->
            val at = servicedAt(c, item.name) ?: return@mapNotNull null
            val ran = odoKm - at
            if (ran < item.intervalKm * DUE_RATIO) null
            else CheckItem(Severity.YELLOW, if (item.name.endsWith("교환")) "${item.name} 시기" else "${item.name} 교체 시기", "교체 후 ${"%,d".format(ran.toInt())}km 달렸어요. 정비소에 들르세요.", "정비소")
        }
    }

    // 기록 화면에 보여 줄 한 줄: "엔진오일 · 교체 후 1,234km (주기 10,000km)"
    fun status(c: Context, odoKm: Float?): List<String> = all.mapNotNull { item ->
        val at = servicedAt(c, item.name) ?: return@mapNotNull null
        val ran = odoKm?.let { "교체 후 ${"%,d".format((it - at).toInt())}km" } ?: "${"%,d".format(at.toInt())}km에서 교체"
        "${item.name} · $ran (주기 ${"%,d".format(item.intervalKm)}km)"
    }
}
