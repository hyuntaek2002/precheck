# Precheck

Pleos Connect 차량 관리 앱입니다. 현대자동차그룹 차량용 앱 개발 공모전 출품작입니다.

경고등이 켜지면 읽어 주고, 꺼진 뒤에도 기록으로 남기고, 다음 출발 전에 미리 점검합니다.

## 하는 일

**출발할 때** 목적지를 찍거나 기어를 D에 넣으면 점검합니다. 주행 가능 거리, 도착 시 배터리, 오일·브레이크액·워셔액, 켜진 경고등, 소모품 주기를 봅니다. 문제가 있을 때만 알리고, 버튼 하나로 충전소나 정비소를 찾습니다.

**경고등이 켜지면** 뜻과 할 일을 음성으로 읽어 줍니다. 95종 모두 한국어 설명이 있고, 위험도는 색과 글자로 보여 줍니다. 7일에 3번 켜지면 정비소 예약을 권합니다.

**다녀온 뒤** 언제, 몇 km에서, 얼마나 켜져 있었는지 남습니다. 정비 기록 버튼 한 번이면 교체 시점을 저장하고, 때가 되면 알려 줍니다.

앱을 열지 않아도 됩니다. 시동을 걸면 스스로 시작하고, 주행 중에는 꼭 필요한 것만 보여 줍니다.

시연 영상: [docs/demo.mp4](docs/demo.mp4) · 기획서: [docs/기획서.md](docs/기획서.md)

## 사용 SDK

- Vehicle — 경고등, 기어, 주행거리, 주행 가능 거리, 오일·브레이크액·워셔액
- NaviHelper — 경로 시작, 도착 시 배터리 잔량, 검색 화면 열기
- TextToSpeech — 음성 안내

## 빌드와 설치

```bash
./gradlew assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

Pleos Connect Emulator v2.1(Android 14)에서 확인했습니다. 빌드 없이 보려면 `apk/Precheck-debug.apk`를 설치하면 됩니다.

## 에뮬레이터에서 시연하기

Virtual Console(`adb forward tcp:8088 tcp:8088` 뒤 http://127.0.0.1:8088)의 Emulation Controls에서 차 상태를 바꿉니다.

1. Telltales에서 TPMS를 On으로 두면 음성과 알림이 뜹니다.
2. Gear를 D로 두면 출발 전 점검이 돌고 화면이 주행 모드로 바뀝니다. P로 돌리면 원래대로 돌아옵니다.
3. 앱의 "타이어 찾기"를 누르면 내비가 타이어 가게를 검색합니다. 하나를 목적지로 잡으면 경로 거리로 다시 점검합니다.
4. 앱의 "정비 기록"에서 엔진오일을 고르고, Odometer를 9,500km 올린 뒤 "지금 점검"을 누르면 교체 시기가 올라옵니다.

기록을 지우려면 다음을 실행합니다.

```bash
adb shell am start -n kr.precheck/.MainActivity -a kr.precheck.DEBUG_CLEAR
```

## 코드

`app/src/main/java/kr/precheck`

- `CarMonitorService` — 포그라운드 서비스. SDK 연결, 점검 실행, 음성·알림·기록
- `PreTripCheck` — 출발 전 점검 규칙
- `Telltales.kt` — 경고등 95종 설명표
- `Maintenance` — 소모품 교체 주기
- `EventLog` — 기록 저장
- `MainActivity` — 화면 하나
- `BootReceiver` — 부팅 때 서비스 시작

## 알아 둘 점

- 경고등 구독에는 PRIVATE_CAR_SAFETY 권한이 필요합니다. 에뮬레이터에서는 일반 권한이고, 실차 허용 여부는 확인 중입니다.
- 외부 앱의 헤드업 알림은 msg·call·navigation 카테고리만 허용됩니다. 경로 버튼이 있는 알림만 navigation을 씁니다.
- 주행 중 화면은 distractionOptimized 선언으로 엽니다. 심사 기준에 따라 화면은 막고 음성·알림만 쓰도록 바꿀 수 있습니다.
- 도착 시 배터리 잔량(remainDestinationChargingValue)은 Wh 단위라 배터리 용량으로 나눠 퍼센트를 냅니다.
- TTS 로케일은 ko-KR이어야 합니다. ko만 주면 엔진이 거부합니다.
- 에뮬레이터는 전기차뿐이라 내연기관차의 주유 안내는 확인하지 못했습니다.
