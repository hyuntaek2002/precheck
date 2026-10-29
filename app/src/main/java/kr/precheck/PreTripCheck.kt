package kr.precheck

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.OilLevelState
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

// 점검 결과 한 항목. action은 내비 검색 키워드
data class CheckItem(val severity: Severity, val title: String, val detail: String, val action: String? = null)

data class CheckResult(val routeKm: Float, val rangeKm: Float?, val items: List<CheckItem>, val time: Long = System.currentTimeMillis()) {
    val ok get() = items.isEmpty()
    fun summary(): String = if (ok) "이상 없음 · 주행 가능 ${rangeKm?.toInt() ?: "?"}km" else items.joinToString(" · ") { it.title }
}

// 목적지가 잡힌 순간 경로 거리 × 차 상태를 맞춰 본다. SDK 콜백이 전부 비동기라 카운터로 모은다
object PreTripCheck {
    private const val TAG = "PreTripCheck"
    private const val MARGIN = 1.2f // 남은 주행 가능 거리가 경로의 1.2배는 돼야 안심
    private const val RESERVE_KM = 20f // 경로와 무관하게 이보다 짧으면 먼저 채우라고 한다 (고정값, 차종별 조정 여지)
    private const val ARRIVAL_MIN_SOC = 15 // 내비가 계산한 도착 시 배터리 잔량(%)이 이보다 낮으면 가는 길에 충전

    // arrivalEnergy: 내비가 경로 시작 때 준 도착 시 배터리 잔량. 에뮬레이터 확인: Wh (77,358 = 77.4kWh의 99%). 모르면 -1 → 거리 어림(1.2배)으로 판단
    fun run(vehicle: Vehicle, routeMeters: Int, activeWarnings: List<CheckItem>, arrivalEnergy: Int = -1, done: (CheckResult) -> Unit) {
        val routeKm = routeMeters / 1000f
        val items = java.util.Collections.synchronizedList(mutableListOf<CheckItem>())
        var rangeKm: Float? = null
        var isEv = false
        var capacityWh: Float? = null
        val pending = AtomicInteger(7)
        // 모든 조회가 끝난 뒤에 거리 판정 (전기차 여부와 주행 가능 거리가 각각 비동기로 오므로)
        fun finish() {
            if (pending.decrementAndGet() != 0) return
            val km = rangeKm
            val what = if (isEv) "충전" else "주유"
            // 100 이하면 이미 퍼센트로 보고 그대로 쓴다 (실차 단위가 다를 가능성 대비)
            val cap = capacityWh
            val arrivalSoc = when {
                arrivalEnergy < 0 -> -1
                arrivalEnergy <= 100 -> arrivalEnergy
                cap != null && cap > 0 -> (arrivalEnergy * 100f / cap).toInt()
                else -> -1
            }
            Log.i(TAG, "arrivalEnergy=$arrivalEnergy capacity=$cap → ${arrivalSoc}%")
            if (km != null && km < RESERVE_KM) {
                items.add(CheckItem(Severity.YELLOW, "$what 필요", "주행 가능 거리가 ${"%.0f".format(km)}km뿐이에요. 먼저 ${what}하고 출발하세요.", if (isEv) "충전소" else "주유소"))
            } else if (arrivalSoc in 0..100) {
                // 내비가 도착 시 잔량을 계산해 줬으면 그게 거리 어림보다 정확하다
                if (arrivalSoc < ARRIVAL_MIN_SOC)
                    items.add(CheckItem(Severity.YELLOW, "충전 필요", "도착하면 배터리가 ${arrivalSoc}%쯤 남아요. 가는 길에 충전하세요.", "충전소"))
            } else if (km != null && routeKm > 0 && km < routeKm * MARGIN) {
                items.add(CheckItem(Severity.YELLOW, "$what 필요",
                    "목적지까지 ${"%.1f".format(routeKm)}km인데 ${"%.1f".format(km)}km만 갈 수 있어요.", if (isEv) "충전소" else "주유소"))
            }
            done(CheckResult(routeKm, rangeKm, items.sortedBy { it.severity }))
        }

        // 1. 주행 가능 거리 vs 경로 거리 (전기차 여부는 배터리 조회 성공으로 판단)
        vehicle.getEvBattery().getEvBatteryStateOfCharge({ isEv = it != null && it > 0; finish() }, { finish() })
        vehicle.getEvBattery().getEvBatteryCapacity({ capacityWh = it; finish() }, { finish() })
        vehicle.getOdometer().getRangeRemainingDistance({ r ->
            // 에뮬레이터에서 확인: 미터 단위 (Virtual Console 1,000m → 계기판 1km → SDK 1000.0)
            rangeKm = r?.let { it / 1000f }
            Log.i(TAG, "range=$r → ${rangeKm}km, route=${routeKm}km")
            finish()
        }, { Log.w(TAG, "range failed $it"); finish() })

        // 2. 액체 레벨
        vehicle.getSafety().getEngineOilLevelState({ s ->
            if (s == OilLevelState.LOW || s == OilLevelState.CRITICALLY_LOW)
                items.add(CheckItem(if (s == OilLevelState.CRITICALLY_LOW) Severity.RED else Severity.YELLOW, "엔진오일 부족", "출발 전에 보충하거나 정비소에 들르세요.", "정비소"))
            finish()
        }, { finish() })
        vehicle.getSafety().getBrakeFluidLevelState({ s ->
            if (s == OilLevelState.LOW || s == OilLevelState.CRITICALLY_LOW)
                items.add(CheckItem(Severity.RED, "브레이크액 부족", "제동이 약해질 수 있어요. 정비소에서 점검받고 출발하세요.", "정비소"))
            finish()
        }, { finish() })
        vehicle.getSafety().hasLowWasherFluidWarning({ low ->
            if (low == true) items.add(CheckItem(Severity.INFO, "워셔액 부족", "비나 눈이 오면 시야가 안 좋아져요. 주유소에서 채우세요.", "주유소"))
            finish()
        }, { finish() })

        // 3. 지금 켜진 경고등(타이어, 주차 브레이크, 문 열림 등) — 구독 중인 상태를 그대로 쓴다
        items.addAll(activeWarnings)
        finish()
    }
}
