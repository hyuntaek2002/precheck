package kr.precheck

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 정비소에 보여 줄 기록: 언제, 몇 km에서, 무슨 경고가 켜졌는지. 한 줄에 JSON 하나
data class Event(val time: Long, val kind: String, val title: String, val detail: String, val odometerKm: Float?, val navigating: Boolean) {
    fun timeText(): String = SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(Date(time))
    fun toJson(): String = JSONObject().put("t", time).put("k", kind).put("ti", title).put("d", detail)
        .put("o", odometerKm ?: -1f).put("n", navigating).toString()

    companion object {
        fun fromJson(s: String): Event? = runCatching {
            val j = JSONObject(s)
            Event(j.getLong("t"), j.getString("k"), j.getString("ti"), j.getString("d"),
                j.getDouble("o").toFloat().takeIf { it >= 0 }, j.getBoolean("n"))
        }.getOrNull()
    }
}

object EventLog {
    private fun file(c: Context) = File(c.filesDir, "events.jsonl")

    @Synchronized fun append(c: Context, e: Event) = file(c).appendText(e.toJson() + "\n")
    @Synchronized fun clear(c: Context) { file(c).delete() }

    // 최신순
    fun readAll(c: Context): List<Event> =
        if (!file(c).exists()) emptyList()
        else file(c).readLines().mapNotNull(Event::fromJson).asReversed()
}
