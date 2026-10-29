package kr.precheck

import ai.pleos.playground.vehicle.api.model.telltale.TelltaleData
import android.os.Handler
import android.os.Looper

// 서비스 → 화면으로 넘기는 상태. 프로세스 하나라 메모리 공유로 충분하다
// ponytail: 옵저버 직접 구현. 화면이 늘면 LiveData/Flow로
class Observable<T>(initial: T) {
    private val listeners = mutableSetOf<(T) -> Unit>()
    var value: T = initial
        set(v) { field = v; val snapshot = listeners.toList(); Handler(Looper.getMainLooper()).post { snapshot.forEach { it(v) } } }
    fun observe(l: (T) -> Unit) { listeners += l; l(value) }
    fun remove(l: (T) -> Unit) { listeners -= l }
}

object AppState {
    val active = Observable<List<TelltaleData>>(emptyList())   // 지금 켜진 경고등
    val lastCheck = Observable<CheckResult?>(null)             // 마지막 출발 전 점검
    val logVersion = Observable(0)                             // 기록이 바뀌면 +1
    val status = Observable("연결 중…")                         // 머리말에 보여 줄 연결 상태
    val driving = Observable(false)                             // 기어가 P가 아니면 true: 화면을 줄인다
    val repeats = Observable<Map<String, Int>>(emptyMap())      // 최근에 반복된 경고등 이름 → 횟수
    val odometerKm = Observable<Float?>(null)                   // 누적 주행거리(km). 소모품 주기 계산용
}
