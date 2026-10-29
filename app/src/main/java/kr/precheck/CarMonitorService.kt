package kr.precheck

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.telltale.TelltaleData
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.util.Locale

// 차 상태를 지켜보다가 세 순간에만 나선다: 목적지 설정(출발 전 점검), 경고등 켜짐(해석), 꺼진 뒤(기록)
class CarMonitorService : Service() {
    companion object {
        const val ACTION_SEARCH = "kr.precheck.SEARCH"
        const val EXTRA_KEYWORD = "keyword"
        private const val TAG = "CarMonitor"
        private const val CH_FG = "fg"
        private const val CH_ALERT = "alert"
        private const val REPEAT_DAYS = 7L   // 같은 경고가 이 기간에
        private const val REPEAT_COUNT = 3   // 이만큼 반복되면 정비소를 권한다 (ponytail: 고정값)

        const val ACTION_CHECK_NOW = "kr.precheck.CHECK_NOW"
        const val ACTION_SERVICED = "kr.precheck.SERVICED"
        const val EXTRA_ITEM = "item"
        private const val AUTO_CHECK_GAP_MS = 5 * 60_000L   // 기어 D 자동 점검은 이 간격 안에서 한 번만
        fun start(c: Context) = c.startForegroundService(Intent(c, CarMonitorService::class.java))
        fun search(c: Context, keyword: String) =
            c.startService(Intent(c, CarMonitorService::class.java).setAction(ACTION_SEARCH).putExtra(EXTRA_KEYWORD, keyword))
        fun checkNow(c: Context) = c.startService(Intent(c, CarMonitorService::class.java).setAction(ACTION_CHECK_NOW))
        fun serviced(c: Context, item: String) =
            c.startService(Intent(c, CarMonitorService::class.java).setAction(ACTION_SERVICED).putExtra(EXTRA_ITEM, item))
    }

    private lateinit var vehicle: Vehicle
    private lateinit var navi: NaviHelper
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var navigating = false
    private var lastOdometer: Float? = null
    private var lastAutoCheck = 0L

    // 프로세스가 다시 떠도 이어지도록 파일에 둔다: 이미 알린 경고등, 켜진 시각, 설명을 이미 보여 준 기능 표시등
    private val prefs by lazy { getSharedPreferences("state", Context.MODE_PRIVATE) }
    private var alerted: Set<String>
        get() = prefs.getStringSet("alerted", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("alerted", v).apply()
    private var seenInfo: Set<String>
        get() = prefs.getStringSet("seenInfo", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("seenInfo", v).apply()
    private fun onSince(name: String) = prefs.getLong("since_$name", 0L)

    private val telltaleListener = object : TelltaleInfoListener {
        override fun onTelltaleInfoUpdated(t: Telltales?) {
            val on = t?.telltales?.filter { it.state != TelltaleState.OFF && TelltaleBook.nameOf(it.type) !in TelltaleBook.IGNORE } ?: return
            AppState.active.value = on
            val onNames = on.map { TelltaleBook.nameOf(it.type) }.toSet()
            val before = alerted
            val fresh = on.filter { TelltaleBook.nameOf(it.type) !in before }
            val gone = before - onNames
            alerted = onNames
            gone.forEach { onTelltaleOff(it) }
            fresh.forEach { onTelltaleOn(it) }
        }
        override fun onFailed(e: Exception) { Log.w(TAG, "telltale failed", e) }
    }

    // 기어가 P가 아니면 주행 중: 화면은 꼭 필요한 것만 보여 준다.
    // P에서 빠져나오는 순간 = 출발. 내비를 안 찍어도 차 상태만 점검하고, 문제 있을 때만 말한다
    private val gearListener = object : GearSelectionListener {
        override fun onSelected(g: VehicleGear?) {
            val driving = g != null && g != VehicleGear.GEAR_PARK && g != VehicleGear.GEAR_UNKNOWN
            val leavingPark = driving && !AppState.driving.value
            AppState.driving.value = driving
            val now = System.currentTimeMillis()
            if (leavingPark && !navigating && now - lastAutoCheck > AUTO_CHECK_GAP_MS) { lastAutoCheck = now; runPreTrip("출발", 0, auto = true) }
        }
        override fun onFailed(e: Exception) { Log.w(TAG, "gear failed", e) }
    }

    private val odometerListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(km: Float?) { lastOdometer = km; AppState.odometerKm.value = km }
        override fun onFailed(e: Exception) { Log.w(TAG, "odometer failed", e) }
    }

    // 꺼진 경고등: 얼마나 켜져 있었는지 남긴다. 정비소에서 "아까는 켜졌는데"를 증명하는 줄
    private fun onTelltaleOff(name: String) {
        val since = onSince(name)
        if (since == 0L) return   // 기능 표시등(켜진 시각을 안 남김)은 기록하지 않는다
        prefs.edit().remove("since_$name").apply()
        record("경고등 꺼짐", "${TelltaleBook.titleOf(name)} 꺼짐", "${duration(System.currentTimeMillis() - since)} 동안 켜져 있었어요.")
        AppState.repeats.value = AppState.repeats.value - name
    }

    private fun duration(ms: Long): String {
        val s = ms / 1000
        return when { s < 60 -> "${s}초"; s < 3600 -> "${s / 60}분 ${s % 60}초"; else -> "${s / 3600}시간 ${s % 3600 / 60}분" }
    }

    private val naviListener = object : NaviHelperEventListener {
        override fun onRouteStarted(info: RouteStartInfo) {
            navigating = true
            Log.i(TAG, "route started ${info.name} ${info.distance}m arrivalSoc=${info.remainDestinationChargingValue}")
            runPreTrip(info.name ?: "목적지", info.distance, info.remainDestinationChargingValue)
        }
        override fun onRouteCancelled() { navigating = false }
        override fun onError(code: NaviErrorCode) { Log.w(TAG, "navi error $code") }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_FG, "차 상태 감시", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "경고등·출발 전 점검", NotificationManager.IMPORTANCE_HIGH))
        onCreateForeground()

        vehicle = Vehicle(this).also { it.initialize() }
        vehicle.getSafety().registerTelltales(telltaleListener)
        vehicle.getGear().registerGearSelection(gearListener)
        vehicle.getOdometer().registerDistanceDriven(odometerListener)
        vehicle.getOdometer().getCurrentDistanceDriven({ lastOdometer = it; AppState.odometerKm.value = it }, {})
        navi = NaviHelper(this)
        AppState.status.value = "차 상태 감시 중"
        navi.initialize({ navi.addListener(naviListener); Log.i(TAG, "navi ready"); AppState.status.value = "차 상태 감시 중 · 내비 연결됨" },
            { Log.w(TAG, "navi init failed", it); AppState.status.value = "차 상태 감시 중 · 내비 연결 안 됨" })
        initTts()
    }

    // 경고를 읽어 준다: 운전 중엔 화면을 볼 수 없으니 소리가 먼저다. 차의 음성 엔진(온디바이스)을 쓴다
    private fun initTts() {
        // SDK 내부가 initialize 전엔 비어 있어서(addEventListener가 lateinit 예외) 리스너는 성공 콜백에서 건다
        val t = runCatching { TextToSpeech(this, Mode.ON_DEVICE) }.onFailure { Log.w(TAG, "tts create failed", it) }.getOrNull() ?: return
        runCatching {
            t.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    tts = t; ttsReady = true; Log.i(TAG, "tts ready")
                    runCatching { t.setLocale(Locale.KOREA) }   // 엔진은 "ko-KR"만 받는다. Locale.KOREAN("ko-")은 unsupported locale
                    runCatching {
                        t.addEventListener(object : EventListener {
                            override fun onStart() { Log.i(TAG, "tts start") }
                            override fun onReady() {}
                            override fun onDone() { Log.i(TAG, "tts done") }
                            override fun onError(e: ErrorType) { Log.w(TAG, "tts error $e") }
                            override fun onStop() {}
                            override fun onUpdatedRms(rms: Double) {}
                        })
                    }
                }
                override fun onFailure(e: ErrorType) { Log.w(TAG, "tts init failed $e") }
            })
        }.onFailure { Log.w(TAG, "tts init threw", it) }
    }

    private fun say(text: String) {
        if (!ttsReady) return
        runCatching { tts?.speak(text, true) }.onFailure { Log.w(TAG, "tts speak failed", it) }
    }

    private fun onCreateForeground() {
        val fg = Notification.Builder(this, CH_FG).setContentTitle("차 상태를 지켜보고 있어요")
            .setSmallIcon(android.R.drawable.ic_menu_info_details).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, fg, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, fg)
    }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        if (i?.action == ACTION_SEARCH) i.getStringExtra(EXTRA_KEYWORD)?.let { openSearch(it) }
        if (i?.action == ACTION_CHECK_NOW) runPreTrip("지금 바로", 0)   // 경로 없이 차 상태만 점검
        if (i?.action == ACTION_SERVICED) i.getStringExtra(EXTRA_ITEM)?.let { onServiced(it) }
        // 시연 준비용: 알림과 "이미 알림" 기록을 비운다 (adb shell am start -n kr.precheck/.MainActivity -a kr.precheck.DEBUG_CLEAR)
        if (i?.action == "kr.precheck.DEBUG_CLEAR") {
            getSystemService(NotificationManager::class.java).cancelAll(); prefs.edit().clear().apply(); onCreateForeground()
            EventLog.clear(this); AppState.lastCheck.value = null; AppState.repeats.value = emptyMap(); AppState.logVersion.value++
        }
        if (i?.action == "kr.precheck.DEBUG_SAY") say(i.getStringExtra("text") ?: "음성 안내 시험입니다.")
        return START_STICKY
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching {
            vehicle.getSafety().unregisterTelltales(telltaleListener); vehicle.getGear().unregisterGearSelection(gearListener)
            vehicle.getOdometer().unregisterDistanceDriven(odometerListener); vehicle.release()
        }
        runCatching { navi.removeListener(naviListener); navi.release() }
        runCatching { tts?.release() }
        super.onDestroy()
    }

    // 내비 검색 화면을 키워드로 연다. 안내 중이면 운전자가 '경유지로'를 고를 수 있다
    private fun openSearch(keyword: String) {
        runCatching { navi.requestNavigationScreen(NavigationScreenRequest.SearchPlace(keyword)) }
            .onFailure { Log.w(TAG, "search failed", it) }
    }

    private fun onTelltaleOn(d: TelltaleData) {
        val info = TelltaleBook.infoOf(d)
        val name = TelltaleBook.nameOf(d.type)
        if (info.severity == Severity.INFO) { if (name in seenInfo) return; seenInfo = seenInfo + name } // 기능 표시등은 처음 한 번만, 기록도 안 남긴다
        else prefs.edit().putLong("since_$name", System.currentTimeMillis()).apply()

        // 같은 경고가 최근에 반복되면 "껐다 켜지는" 고장: 정비소를 권한다
        val since = System.currentTimeMillis() - REPEAT_DAYS * 86_400_000
        val logTitle = "${info.title} 켜짐"
        val times = EventLog.readAll(this).count { it.kind == "경고등" && it.title == logTitle && it.time > since } + 1
        val repeated = info.severity != Severity.INFO && times >= REPEAT_COUNT
        if (repeated) AppState.repeats.value = AppState.repeats.value + (name to times)
        val todo = if (repeated) "${info.todo} 최근 ${REPEAT_DAYS}일 사이 ${times}번째예요. 정비소 예약을 권해요." else info.todo
        val action = if (repeated) info.action ?: "정비소" else info.action

        record("경고등", logTitle, info.meaning)
        if (info.severity != Severity.INFO) say("${info.title}. ${info.severity.label}. $todo")
        // 에뮬레이터 검증: 외부 앱 헤드업은 msg·call·navigation 카테고리만 허용. 경로 안내 버튼이 있는 경고만 navigation으로 올린다
        // ponytail: 심사에서 카테고리 오용으로 볼 수 있음. 설명회에서 차량 경고용 헤드업 허용 여부 확인 후 조정
        val category = if (action != null && info.severity != Severity.INFO) Notification.CATEGORY_NAVIGATION else null
        notify(2 + name.hashCode().and(0xffff), "${info.severity.emoji} ${info.title} · ${info.severity.label}", todo, action, info.severity, category)
    }

    // 정비소 다녀온 뒤 버튼 한 번: 지금 주행거리를 교체 시점으로 남긴다
    private fun onServiced(item: String) {
        val km = lastOdometer ?: 0f
        Maintenance.serviced(this, item, km)
        val next = km + (Maintenance.items.firstOrNull { it.name == item }?.intervalKm ?: 0)
        record("정비", "$item 교체", "${"%,d".format(km.toInt())}km에서 교체했어요. 다음은 ${"%,d".format(next.toInt())}km 즈음이에요.")
    }

    private fun runPreTrip(name: String, distanceM: Int, arrivalSoc: Int = -1, auto: Boolean = false) {
        // 지금 켜진 경고(기능 표시등 제외)와 때가 된 소모품은 전부 출발 전에 풀어야 할 항목
        val warnings = AppState.active.value.map { TelltaleBook.infoOf(it) }.filter { it.severity != Severity.INFO }
            .map { CheckItem(it.severity, it.title, it.todo, it.action) } + Maintenance.due(this, lastOdometer)
        PreTripCheck.run(vehicle, distanceM, warnings, arrivalSoc) { r ->
            AppState.lastCheck.value = r
            if (auto && r.ok) return@run   // 출발할 때마다 "이상 없음"을 기록에 쌓지 않는다
            record("출발 전 점검", if (distanceM > 0) "출발 전 점검 · $name ${"%.1f".format(r.routeKm)}km" else "출발 전 점검", r.summary())
            if (r.ok) return@run
            val worst = r.items.first()
            val titles = r.items.joinToString(", ") { it.title }
            say("출발 전 점검. $titles. ${worst.detail}")
            notify(1000, "${worst.severity.emoji} 출발 전 점검: $titles", worst.detail, worst.action, worst.severity, Notification.CATEGORY_NAVIGATION)
        }
    }

    private fun record(kind: String, title: String, detail: String) {
        vehicle.getOdometer().getCurrentDistanceDriven({ lastOdometer = it }, {})
        EventLog.append(this, Event(System.currentTimeMillis(), kind, title, detail, lastOdometer, navigating))
        AppState.logVersion.value++
    }

    // 알림 한 장: 제목(뜻·단계) + 할 일 + 버튼 하나(검색)
    private fun notify(id: Int, title: String, text: String, action: String?, severity: Severity, category: String? = null) {
        val open = PendingIntent.getActivity(this, id, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // car_* 카테고리는 시스템 앱 전용(checkRestrictedCategories)이라 쓰면 알림이 버려진다
        val b = Notification.Builder(this, CH_ALERT).setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setPriority(if (severity == Severity.INFO) Notification.PRIORITY_DEFAULT else Notification.PRIORITY_HIGH)
            .setContentIntent(open).setAutoCancel(true)
        if (category != null) b.setCategory(category)
        if (action != null) {
            val pi = PendingIntent.getService(this, id + 1, Intent(this, CarMonitorService::class.java).setAction(ACTION_SEARCH).putExtra(EXTRA_KEYWORD, action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(Notification.Action.Builder(null, "$action 찾기", pi).build())
        }
        getSystemService(NotificationManager::class.java).notify(id, b.build())
    }
}
