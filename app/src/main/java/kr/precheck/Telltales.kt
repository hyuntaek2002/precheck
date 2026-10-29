package kr.precheck

import ai.pleos.playground.vehicle.api.model.telltale.TelltaleData
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType

// 위험도: 운전자가 지금 뭘 해야 하는지로 나눈다
enum class Severity(val label: String, val emoji: String) {
    RED("즉시 정차", "🔴"),
    YELLOW("점검 필요", "🟡"),
    INFO("기능 표시", "⚪"),
}

// 경고등 하나의 설명. action은 버튼에 쓸 검색 키워드(없으면 버튼 없음)
data class TelltaleInfo(val title: String, val meaning: String, val todo: String, val severity: Severity, val action: String? = null)

object TelltaleBook {
    // SDK TelltaleType 상수 → 이름 (public 접근자가 없어 리플렉션으로 한 번만 매핑)
    private val names: Map<TelltaleType, String> by lazy {
        TelltaleType::class.java.fields.filter { it.type == TelltaleType::class.java }
            .associate { (it.get(null) as TelltaleType) to it.name }
    }

    fun nameOf(t: TelltaleType): String = names[t] ?: t.toString()
    fun titleOf(name: String): String = table[name]?.title ?: name.lowercase().replace('_', ' ')

    // 켜졌다 꺼졌다를 반복해 설명할 가치가 없는 표시등. 구독에서 아예 뺀다
    val IGNORE = setOf("TURN_SIGNAL_LEFT", "TURN_SIGNAL_RIGHT", "HEADLAMP_LOW", "POSITION_LAMP", "REV", "XEV_READY")

    private fun red(title: String, meaning: String, todo: String, action: String? = "정비소") = TelltaleInfo(title, meaning, todo, Severity.RED, action)
    private fun yellow(title: String, meaning: String, todo: String, action: String? = null) = TelltaleInfo(title, meaning, todo, Severity.YELLOW, action)
    private fun info(title: String, meaning: String, todo: String = "따로 할 일 없어요.") = TelltaleInfo(title, meaning, todo, Severity.INFO)

    // SDK TelltaleType 95종 전부. 차종에 없는 것(수소차·N 모델 등)도 켜지면 뜻은 읽힌다
    private val table: Map<String, TelltaleInfo> = mapOf(
        // ---- 즉시 정차 ----
        "ENGINE_OIL_PRESSURE" to red("엔진오일 압력 이상", "엔진에 오일이 제대로 돌지 않고 있어요. 계속 달리면 엔진이 망가질 수 있어요.", "안전한 곳에 바로 세우고 시동을 끄세요. 견인을 부르는 게 안전해요."),
        "ENGINE_TEMPERATURE" to red("엔진 과열", "냉각수 온도가 너무 높아요.", "안전한 곳에 세우고 시동을 끄세요. 보닛은 식을 때까지 열지 마세요."),
        "LOW_VOLT_BATTERY" to red("배터리 충전 이상", "12V 배터리가 충전되지 않고 있어요. 시동이 꺼질 수 있어요.", "전기 장치를 끄고 가까운 정비소로 가세요."),
        "MDPS" to red("조향 장치 이상", "전동 파워 스티어링에 문제가 있어요. 핸들이 무거워질 수 있어요.", "속도를 줄이고 정비소로 가세요."),
        "H2_LEAK" to red("수소 누출", "수소가 새고 있어요.", "안전한 곳에 바로 세우고 시동을 끈 뒤 차에서 떨어지세요. 서비스센터에 연락하세요.", "서비스센터"),

        // ---- 점검 필요: 고장·이상 ----
        "ENGINE" to yellow("엔진 점검", "엔진이나 배기 장치에 이상 신호가 잡혔어요. 당장 멈출 일은 드물어요.", "출력이 떨어지거나 소리가 이상하면 세우고, 아니면 가까운 시일 내 정비소에서 진단받으세요.", "정비소"),
        "MASTER_WARNING" to yellow("종합 경고", "다른 경고와 함께 켜지는 주의 표시예요.", "함께 켜진 경고등을 확인하세요."),
        "ABS" to yellow("ABS 이상", "급제동 시 바퀴 잠김을 막는 장치가 꺼졌어요. 일반 브레이크는 돼요.", "급제동을 피하고 정비소에서 점검받으세요.", "정비소"),
        "ESC_OFF" to yellow("자세 제어 꺼짐", "차체 자세 제어(ESC)가 꺼져 있어요. 미끄러운 길에서 위험해요.", "일부러 끈 게 아니면 ESC OFF 버튼을 눌러 다시 켜세요."),
        "AIRBAG" to yellow("에어백 이상", "에어백 장치에 문제가 있어 사고 때 작동하지 않을 수 있어요.", "가까운 시일 내 정비소에서 점검받으세요.", "정비소"),
        "AFS" to yellow("전조등 조향 장치 이상", "핸들 방향에 따라 전조등을 움직이는 장치(AFS)에 문제가 있어요.", "밤길 시야가 좁아질 수 있어요. 정비소에서 점검받으세요.", "정비소"),
        "LED_HEADLAMP" to yellow("LED 전조등 이상", "LED 전조등에 문제가 있어요.", "밤에 운전하기 전에 정비소에서 점검받으세요.", "정비소"),
        "RBS" to yellow("회생 제동 이상", "회생 제동 장치에 이상이 있어요. 브레이크 페달 느낌이 평소와 다를 수 있어요.", "차간 거리를 넉넉히 두고 정비소로 가세요.", "정비소"),
        "EMERGENCY_CALL" to yellow("긴급 통화 장치 이상", "사고 시 자동 신고 기능에 문제가 있어요.", "정비소에서 점검받으세요.", "정비소"),
        "IMMOBILIZER" to yellow("도난 방지 장치", "키를 인식하지 못하고 있어요.", "스마트키를 시동 버튼 가까이 대 보세요."),
        "WATER_SEPARATOR" to yellow("연료 필터 수분", "연료 필터에 물이 찼어요.", "정비소에서 물을 빼 주세요.", "정비소"),
        "PARTICLE_FILTER" to yellow("매연 필터(DPF) 포화", "배기 필터에 매연이 쌓였어요.", "고속도로 등에서 20분 이상 60km/h 넘게 달리면 자동으로 태워져요. 계속 켜져 있으면 정비소로.", "정비소"),
        "SCR" to yellow("요소수 부족", "요소수가 부족해요. 다 떨어지면 시동이 안 걸릴 수 있어요.", "주유소에서 요소수를 보충하세요.", "요소수"),
        "LPI" to yellow("LPG 연료 장치 점검", "LPG 연료 장치에 이상 신호가 있어요.", "정비소에서 점검받으세요.", "정비소"),
        "EV_SYSTEM" to yellow("전기차 시스템 점검", "고전압 시스템에 이상 신호가 있어요.", "출력이 떨어지면 세우고, 가까운 서비스센터에 문의하세요.", "서비스센터"),
        "HEV_SYSTEM" to yellow("하이브리드 시스템 점검", "하이브리드 구동 장치에 이상 신호가 있어요.", "출력이 떨어지면 세우고, 서비스센터에 문의하세요.", "서비스센터"),
        "FCEV_SYSTEM" to yellow("수소전기차 시스템 점검", "연료전지 장치에 이상 신호가 있어요.", "출력이 떨어지면 세우고, 서비스센터에 문의하세요.", "서비스센터"),
        "EREV_SYSTEM" to yellow("주행거리 연장 장치 점검", "배터리를 충전하는 발전용 엔진 쪽에 이상 신호가 있어요.", "서비스센터에서 점검받으세요.", "서비스센터"),
        "H2_SENSOR" to yellow("수소 센서 이상", "수소 누출을 감지하는 센서에 문제가 있어요.", "서비스센터에서 점검받으세요.", "서비스센터"),
        "POWER_DOWN" to yellow("출력 제한", "차가 스스로 출력을 줄이고 있어요. 배터리 온도나 잔량 때문일 수 있어요.", "속도를 줄이고 무리한 가속을 피하세요."),
        "DRIVER_ATTENTION" to yellow("휴식 권장", "운전 패턴이 피로한 상태로 보여요.", "다음 휴게소에서 쉬어 가세요.", "휴게소"),

        // ---- 점검 필요: 출발 전에 풀 것 ----
        "TPMS" to yellow("타이어 공기압 낮음", "타이어 하나 이상의 공기압이 기준보다 낮아요.", "천천히 달려 가까운 주유소나 정비소에서 공기를 넣으세요.", "타이어"),
        "PARKING_BRAKE" to yellow("주차 브레이크 걸림", "주차 브레이크가 걸려 있어요.", "출발 전에 주차 브레이크를 푸세요. 풀었는데도 켜져 있으면 브레이크액을 확인하세요."),
        "ELECTRONIC_PARKING_BRAKE" to yellow("전자 주차 브레이크", "전자 주차 브레이크가 걸려 있거나 이상이 있어요.", "출발 전에 풀어 주세요. 깜빡이면 정비소 점검이 필요해요."),
        "SEAT_BELT" to yellow("안전벨트", "안전벨트를 매지 않은 자리가 있어요.", "벨트를 매세요."),
        "DOOR_OPEN" to yellow("문 열림", "문이 완전히 닫히지 않았어요.", "문을 다시 닫아 주세요."),
        "TRUNK_OPEN" to yellow("트렁크 열림", "트렁크가 열려 있어요.", "트렁크를 닫아 주세요."),
        "EV_CHARGING_CONNECTOR" to yellow("충전 케이블 연결", "충전 케이블이 꽂혀 있어요.", "출발 전에 케이블을 분리하세요."),
        "LOW_FUEL" to yellow("연료 부족", "연료가 얼마 남지 않았어요.", "가까운 주유소에 들르세요.", "주유소"),
        "LOW_EV_BATTERY" to yellow("배터리 부족", "구동 배터리가 얼마 남지 않았어요.", "가까운 충전소에 들르세요.", "충전소"),
        "LOW_FUEL_FCEV" to yellow("수소 부족", "수소가 얼마 남지 않았어요.", "가까운 수소 충전소에 들르세요.", "수소충전소"),

        // ---- 기능 표시: 안전·주행 보조 ----
        "ESC" to info("차체 자세 제어 작동", "미끄러운 길에서 자세 제어 장치가 개입하고 있어요.", "속도를 줄이세요. 깜빡임이 멈추면 정상이에요."),
        "ESC_SPORT" to info("자세 제어 스포츠", "자세 제어 개입을 줄여 미끄러짐을 더 허용해요.", "일반 도로에서는 기본 설정으로 되돌리세요."),
        "AUTO_HOLD" to info("오토 홀드 켜짐", "정차하면 브레이크를 떼도 멈춤 상태를 유지해 줘요.", "가속 페달을 밟으면 풀려요."),
        "DBC" to info("내리막 저속 주행", "가파른 내리막에서 속도를 자동으로 낮춰 줘요.", "브레이크를 계속 밟지 않아도 돼요."),
        "FCA" to info("전방 충돌 방지 보조", "앞차와 가까워지면 경고하고 필요하면 제동해요.", "경고가 뜨면 바로 브레이크를 밟으세요."),
        "LKA" to info("차로 이탈 방지 보조", "차선을 벗어나면 핸들을 살짝 돌려 줘요.", "핸들에서 손을 떼라는 뜻은 아니에요."),
        "LFA" to info("차로 유지 보조", "차로 가운데를 유지하도록 돕고 있어요.", "핸들은 계속 잡고 계세요."),
        "ELK" to info("차로 이탈 방지 긴급 조향", "차선을 넘을 때 충돌 위험이 있으면 핸들을 돌려 줘요.", "핸들은 계속 잡고 계세요."),
        "LCA_LEFT" to info("왼쪽 후측방 차량", "왼쪽 뒤에 차가 있어요.", "지금은 왼쪽으로 차로를 바꾸지 마세요."),
        "LCA_RIGHT" to info("오른쪽 후측방 차량", "오른쪽 뒤에 차가 있어요.", "지금은 오른쪽으로 차로를 바꾸지 마세요."),
        "ICC" to info("ICC 표시", "ICC 기능 표시등이에요. 정확한 뜻은 차 설명서를 확인하세요."),
        "ICY_ROAD" to info("결빙 주의", "바깥 온도가 낮아 길이 얼었을 수 있어요.", "속도를 줄이고 차간 거리를 넉넉히 두세요."),
        "OVER_SPEED" to info("과속", "설정한 속도를 넘었어요.", "속도를 줄이세요."),
        "PASSENGER_AIRBAG_OFF" to info("조수석 에어백 꺼짐", "조수석 에어백이 꺼져 있어요. 뒤보기 어린이 시트를 놓았을 때는 정상이에요.", "어른이 앉았는데 꺼져 있으면 다시 앉아 보고, 그래도 꺼져 있으면 점검받으세요."),
        "PASSENGER_AIRBAG_ON" to info("조수석 에어백 켜짐", "조수석 에어백이 작동 대기 상태예요.", "뒤보기 어린이 시트를 놓았다면 꺼 주세요."),
        "CHILD_PRESENCE_DETECTION_OFF" to info("뒷좌석 승객 알림 꺼짐", "뒷자리에 아이나 반려동물이 남았는지 알려 주는 기능이 꺼져 있어요.", "차량 설정에서 다시 켤 수 있어요."),

        // ---- 기능 표시: 등화 ----
        "AUTO_HIGH_BEAM" to info("자동 상향등", "앞차와 맞은편 차에 맞춰 상향등을 자동으로 조절해요."),
        "HEADLAMP_HIGH" to info("상향등 켜짐", "상향등이 켜져 있어요. 맞은편 차가 눈부실 수 있어요.", "앞차나 맞은편 차가 있으면 하향등으로 바꾸세요."),
        "HEADLAMP_LOW" to info("하향등 켜짐", "전조등이 켜져 있어요."),
        "POSITION_LAMP" to info("미등 켜짐", "미등이 켜져 있어요."),
        "FRONT_FOG_LAMP" to info("앞 안개등 켜짐", "앞 안개등이 켜져 있어요.", "안개가 걷히면 꺼 주세요."),
        "REAR_FOG_LAMP" to info("뒤 안개등 켜짐", "뒤 안개등은 밝아서 뒤차가 눈부셔요.", "안개가 걷히면 꺼 주세요."),
        "TURN_SIGNAL_LEFT" to info("왼쪽 방향지시등", "왼쪽 방향지시등이 켜져 있어요."),
        "TURN_SIGNAL_RIGHT" to info("오른쪽 방향지시등", "오른쪽 방향지시등이 켜져 있어요."),

        // ---- 기능 표시: 구동·전동화 ----
        "XEV_READY" to info("주행 준비 완료", "시동이 걸려 출발할 수 있어요. 전기차는 소리가 없어서 이 표시로 확인해요.", "기어를 D에 놓고 출발하세요."),
        "REV" to info("후진", "후진 기어가 들어가 있어요."),
        "I_PEDAL" to info("원페달 모드", "가속 페달만으로 가감속과 정차까지 돼요.", "브레이크 페달도 평소처럼 쓸 수 있어요."),
        "CREEP_OFF" to info("크리프 꺼짐", "브레이크를 떼도 차가 저절로 움직이지 않아요.", "출발하려면 가속 페달을 밟으세요."),
        "ISG" to info("공회전 제한", "정차 중 연료를 아끼려고 엔진을 잠시 껐어요.", "브레이크를 떼면 다시 켜져요."),
        "PREHEAT" to info("예열 중", "디젤 엔진을 예열하고 있어요.", "표시가 꺼진 뒤 시동을 거세요."),
        "HEV_MODE" to info("EV 모드", "지금 엔진 없이 전기로만 달리고 있어요."),
        "GREEN_ZONE" to info("그린존", "주택가·학교 앞 같은 구역이라 엔진 대신 전기로 달리고 있어요."),
        "EV_BATTERY_CONDITIONING" to info("배터리 온도 조절 중", "충전 전에 배터리 온도를 맞추고 있어요. 전비가 잠시 떨어질 수 있어요."),
        "PHEV_MODE" to info("플러그인 하이브리드 모드", "주행 모드가 바뀌었어요."),
        "PHEV_MODE_HEV" to info("하이브리드 모드", "엔진과 모터를 함께 써요. 배터리를 아껴 둘 때 좋아요."),
        "PHEV_MODE_AUTO" to info("자동 모드", "차가 상황에 맞게 전기와 엔진을 알아서 골라요."),
        "PHEV_MODE_EV_PLUS" to info("EV+ 모드", "가능한 한 전기로만 달려요."),
        "EREV_MODE" to info("주행거리 연장 모드", "엔진은 바퀴를 돌리지 않고 발전기로만 돌아요."),
        "UTILITY_MODE" to info("유틸리티 모드", "주행하지 않고 에어컨·오디오 같은 전기 장치를 계속 쓰는 모드예요.", "주행하려면 모드를 꺼 주세요."),

        // ---- 기능 표시: 주행 모드·4륜 ----
        "DRIVE_MODE" to info("주행 모드 변경", "주행 모드가 바뀌었어요."),
        "DRIVE_MODE_ECO" to info("에코 모드", "연비·전비를 우선해요. 가속이 부드러워져요."),
        "DRIVE_MODE_COMFORT" to info("컴포트 모드", "일상 주행용 기본 모드예요."),
        "DRIVE_MODE_SPORT" to info("스포츠 모드", "가속이 빨라지고 연비·전비는 떨어져요."),
        "DRIVE_MODE_TERRAIN" to info("험로 모드", "눈길·진흙·모래 같은 길에 맞춰 구동을 조절해요.", "포장도로로 나오면 꺼 주세요."),
        "AWD_LOCK" to info("4륜 잠금", "네 바퀴에 힘을 고정 배분해요. 험로나 눈길용이에요.", "포장도로에서는 꺼 주세요."),
        "FORCED_ALL_WHEEL_DRIVE" to info("4륜 강제 구동", "항상 네 바퀴로 달려요."),
        "FOUR_WHEEL_DRIVE_MODE_2H" to info("2륜 구동(2H)", "뒷바퀴로만 달려요. 평소 포장도로용이에요."),
        "FOUR_WHEEL_DRIVE_MODE_4A" to info("자동 4륜(4A)", "필요할 때 알아서 네 바퀴로 바꿔요."),
        "FOUR_WHEEL_DRIVE_MODE_4H" to info("4륜 고속(4H)", "네 바퀴로 달려요. 눈길·비포장용이에요.", "마른 포장도로에서는 2H로 바꾸세요."),
        "FOUR_WHEEL_DRIVE_MODE_4L" to info("4륜 저속(4L)", "힘을 크게 내는 저속 4륜이에요. 험로 탈출용이에요.", "빠르게 달리면 안 돼요. 벗어나면 4H나 2H로."),
        "TOW_MODE" to info("견인 모드", "트레일러를 끌 때 변속과 안정 제어를 맞춰 줘요.", "트레일러를 떼면 꺼 주세요."),

        // ---- 기능 표시: 고성능(N) ----
        "DRIFT_MODE" to info("드리프트 모드", "서킷용 모드예요. 일반 도로에서는 위험해요.", "공도에서는 꺼 주세요."),
        "TRACK_MODE" to info("트랙 모드", "서킷용 최대 성능 모드예요.", "공도에서는 꺼 주세요."),
        "LAUNCH_MODE" to info("론치 컨트롤", "정지 상태에서 최대 가속을 준비해요. 서킷용이에요.", "공도에서는 쓰지 마세요."),
        "ACCEL_RESPONSE_DASH" to info("가속 응답: 즉각", "가속 페달 반응을 가장 빠르게 했어요."),
        "ACCEL_RESPONSE_ACCEL" to info("가속 응답: 일반", "가속 페달 반응이 기본 설정이에요."),
        "N_BATTERY_DRAG" to info("N 배터리: 드래그", "짧은 전력 질주에 맞춰 배터리를 준비했어요."),
        "N_BATTERY_SPRINT" to info("N 배터리: 스프린트", "짧은 서킷 주행에 맞춰 배터리를 준비했어요."),
        "N_BATTERY_ENDURANCE" to info("N 배터리: 내구", "긴 서킷 주행에 맞춰 배터리 출력을 아껴요."),
    )

    // 색 id → 위험도 (SDK TelltaleColor 순서: WHITE, GREEN, BLUE, YELLOW, RED, GRAY)
    private fun severityByColor(color: Int) = when (color) { 4 -> Severity.RED; 3 -> Severity.YELLOW; else -> Severity.INFO }

    fun severityOf(name: String): Severity? = table[name]?.severity

    fun infoOf(d: TelltaleData): TelltaleInfo {
        val name = nameOf(d.type)
        return table[name] ?: TelltaleInfo(
            name.lowercase().replace('_', ' '), "아직 설명을 준비하지 못한 표시등이에요.",
            "빨간색이면 안전한 곳에 세우고, 노란색이면 가까운 시일 내 점검받으세요.", severityByColor(d.color)
        )
    }
}
