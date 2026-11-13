# 권한과 개인정보

이 런처가 문자, 연락처, 알림, 키 입력, 인터넷을 어디에 어떻게 쓰는지 적은 문서입니다.
앱 전체 소스는 공개하지 않지만, 여기 적은 내용과 관련된 코드는 이 저장소에 올려 두었습니다. 문서와 코드가 다른 부분을 찾으면 이슈로 알려 주세요.

## 요약

- 개발자는 사용자 데이터를 전혀 수집하지 않습니다. 분석·광고·오류 보고 도구가 없습니다.
- 문자, 연락처, 알림, 키 입력은 기기 안에서만 씁니다. 저장하지도, 보내지도 않습니다.
- 인터넷은 환율표를 받을 때만 씁니다. 환율 변환은 기본으로 꺼져 있고, 켜도 입력한 내용은 보내지 않습니다.
- 모든 권한은 선택입니다. 거부해도 나머지 기능은 그대로 됩니다.

## 권한별 용도

GitHub 릴리스 APK가 선언하는 권한과 특수 구성요소 전부입니다.

| 권한 | 용도 | 거부하면 |
|---|---|---|
| `android.permission.READ_SMS` | 검색창에서 문자 본문 검색 | 문자 결과만 안 나옵니다. |
| `android.permission.READ_CONTACTS` | 문자 결과에 번호 대신 연락처 이름 표시 | 번호로 보입니다. 연락처를 검색하거나 목록으로 보여 주는 기능은 없습니다. |
| 알림 접근(`BIND_NOTIFICATION_LISTENER_SERVICE`) | 즐겨찾기의 알림 점 | 점이 안 나옵니다. 사용자가 시스템 설정에서 직접 허용해야만 켜집니다. |
| 접근성 서비스(`BIND_ACCESSIBILITY_SERVICE`) | 다른 앱 위에서 검색 단축키 받기 | 기본으로 꺼져 있습니다. 사용자가 시스템 접근성 설정에서 직접 켜야만 동작하고, 앱이 스스로 켤 수 없습니다. |
| 빠른 설정 타일(`BIND_QUICK_SETTINGS_TILE`) | 알림창 타일로 검색창 열기 | 타일을 알림창에 넣지 않으면 아무 일도 하지 않습니다. |
| `android.permission.INTERNET` | 환율표 받기 | 설치할 때 자동으로 주어지는 권한이라 따로 끌 수 없습니다. 환율 변환을 끄면 요청이 한 건도 없습니다. |
| 상태 표시줄 펼치기 | 두 손가락으로 아래로 밀어 알림창 내리기 | 설치할 때 자동으로 주어지는 일반 권한입니다. 제스처를 끄면 쓰지 않습니다. |
| `android.permission.REQUEST_DELETE_PACKAGES` | 길게 누르기 메뉴의 "삭제" | 시스템 삭제 확인창만 띄웁니다. |
| `io.github.wuugi.qsearch.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | AndroidX 라이브러리가 자동으로 더하는 이 앱 전용 내부 권한입니다. 같은 서명의 앱에만 주어지고 사용자 데이터에 접근하지 않습니다. | 해당 없음 |

매니페스트 `<queries>`는 권한이 아니라 앱 목록, 위젯 제공자, 아이콘 팩, Play 스토어 앱, 기본 브라우저가 보이게 하는 선언입니다.

선언 위치: [`app/src/main/AndroidManifest.xml`](../app/src/main/AndroidManifest.xml), [`paid/src/main/AndroidManifest.xml`](../paid/src/main/AndroidManifest.xml),
[`provider-sms/src/main/AndroidManifest.xml`](../provider-sms/src/main/AndroidManifest.xml), [`provider-fx/src/main/AndroidManifest.xml`](../provider-fx/src/main/AndroidManifest.xml).
문자·연락처 권한은 `provider-sms` 모듈에서만, 인터넷 권한은 `provider-fx` 모듈에서만 선언합니다.

## 문자와 연락처

### 코드 위치

문자와 연락처를 읽는 코드는 `provider-sms` 모듈에만 있습니다.

- 권한 확인과 대화 열기: [`SmsAccess.kt`](../provider-sms/src/main/java/io/github/wuugi/qsearch/sms/SmsAccess.kt)
- 문자 조회와 연락처 이름 조회: [`SmsSearcher.kt`](../provider-sms/src/main/java/io/github/wuugi/qsearch/sms/SmsSearcher.kt), [`SmsQuery.kt`](../provider-sms/src/main/java/io/github/wuugi/qsearch/sms/SmsQuery.kt), [`SmsText.kt`](../provider-sms/src/main/java/io/github/wuugi/qsearch/sms/SmsText.kt)
- 검색창이 이 모듈을 부르고 결과를 버리는 곳: [`SmsSearchProvider.kt`](../app/src/github/java/io/github/wuugi/qsearch/search/providers/SmsSearchProvider.kt)
- 이 모듈에는 네트워크 라이브러리 의존성이 없습니다: [`provider-sms/build.gradle.kts`](../provider-sms/build.gradle.kts)

### 읽는 것

- SMS 본문, 상대 번호, 날짜
- MMS의 텍스트 부분, 상대 번호, 날짜
- 화면에 보이는 결과(최대 5건)의 번호에 해당하는 연락처 이름. `READ_CONTACTS`가 있을 때만 읽습니다.

검색어가 2글자 이상일 때 시스템 문자 저장소를 직접 조회합니다. 따로 복사본이나 색인을 만들지 않습니다.
결과는 메모리에만 있고, 검색창을 닫으면 5초 안에 버립니다.

### 저장하지 않는 것

문자 본문, 번호, 연락처 이름, 검색어는 디스크나 로그, 백업 어디에도 남기지 않습니다. 앱의 `allowBackup`은
`false`이고, `provider-sms` 모듈에는 로그 호출이 없습니다.

### 클립보드

문자 결과를 골랐는데 기본 문자 앱에서 그 대화를 열 수 없을 때만, 그 문자 한 건의 본문을 클립보드에 복사합니다.
Android 13 이상에서는 민감한 내용으로 표시해서 미리보기에 뜨지 않게 합니다.

### 권한을 묻는 때

- 앱을 처음 열 때는 묻지 않습니다. 검색 결과에서 "문자 검색 켜기"를 고를 때만 시스템 창이 뜹니다.
- 거부하면 다시 묻지 않고 그 항목도 사라집니다. 나중에 설정 → 권한 → "문자 읽기"에서 다시 켤 수 있습니다.
- 설정의 "문자 메시지" 스위치는 검색에 포함할지만 정합니다. 권한과는 별개입니다.

### 찾지 못하는 것

RCS 채팅은 Android가 다른 앱에 열어 주지 않아 읽을 수 없습니다. 카카오톡 같은 메신저도 대상이 아닙니다.

## 알림

[`UnreadDotListenerService.kt`](../app/src/main/java/io/github/wuugi/qsearch/notification/UnreadDotListenerService.kt)가 알림에서 읽는 것은
각 알림의 앱 패키지 이름과 "지울 수 있는 알림인가" 두 가지뿐입니다. 알림 제목, 본문, 보낸 사람은 읽지도 저장하지도 않습니다.
알림이 있는 앱 목록은 메모리에만 있습니다.

## 접근성 서비스

코드: [`paid/.../global/`](../paid/src/main/kotlin/io/github/wuugi/qsearch/global), 서비스 선언 [`paid/src/main/res/xml/`](../paid/src/main/res/xml)

- 하드웨어 키보드의 키 입력을 받는 데만 씁니다. 서비스 선언에 키 필터만 요청하고, 화면 내용 읽기(`canRetrieveWindowContent`)는 `false`이며,
  화면 이벤트 종류도 선언하지 않습니다. 접근성 도구(`isAccessibilityTool`)로 선언하지도 않습니다.
- 켜져 있으면 비밀번호 입력을 포함한 모든 하드웨어 키가 이 서비스를 지나갑니다. 서비스는 키 번호와 보조키 상태를 저장된 단축키와 비교만 하고,
  키를 저장하거나 기록하거나 보내지 않습니다. 단축키를 누른 직후 약 1초 동안 친 글자만 검색창으로 넘깁니다. 이 코드에는 로그 호출이 없습니다.
- 설정에서 "단축키로 열기"를 끄면 서비스가 스스로 꺼집니다.

## 인터넷: 환율 조회

- 보내는 것: `GET https://api.frankfurter.dev/v2/rates?base=USD` 요청 하나입니다. 주소는 코드에 고정되어 있고, 입력한 글자나 문자, 앱 목록은 들어가지 않습니다. 서버는 여느 웹 요청과 마찬가지로 IP 주소와 요청 시각을 알게 됩니다. User-Agent는 기기 모델이나 OS 버전이 없는 고정 문자열입니다.
- 보내는 때: 환율 변환을 켤 때 한 번, 그 뒤로는 홈이나 검색창을 열 때 마지막으로 받은 지 24시간이 지났으면 다시 받습니다. 실패하면 1시간 동안 다시 시도하지 않습니다. 설정의 "지금 업데이트"를 누를 때도 받습니다. 백그라운드 예약 작업은 없습니다.
- 저장하는 것: 지원하는 통화의 달러당 환율과 기준 날짜, 받은 시각, 마지막 시도 시각. 환율 변환을 끄면 모두 지웁니다.
- 검색할 때는 네트워크를 쓰지 않고, 메모리에 올려 둔 환율로 계산합니다. 비행기 모드에서도 받아 둔 환율로 답합니다.

코드: 네트워크 코드 [`FxClient.kt`](../provider-fx/src/main/java/io/github/wuugi/qsearch/fx/FxClient.kt) (앱에서 네트워크를 쓰는 유일한 파일),
응답 검사 [`FxRates.kt`](../provider-fx/src/main/java/io/github/wuugi/qsearch/fx/FxRates.kt),
언제 받고 무엇을 저장하는지 [`FxRepository.kt`](../app/src/main/java/io/github/wuugi/qsearch/data/fx/FxRepository.kt)

Android 권한은 앱 단위라서, `INTERNET`이 있으면 기술적으로는 앱의 어느 코드든 네트워크를 쓸 수 있습니다.
그래서 비공개 빌드에서 구조를 검사합니다. 네트워크 API와 네트워크 라이브러리가 `FxClient.kt` 한 파일 밖에 있거나, 문자 모듈이 네트워크를 쓰거나,
문자 모듈과 환율 모듈이 서로 의존하면 빌드가 실패합니다. 이 검사는 앱 전체 소스에 대해 돌기 때문에 이 저장소에서는 직접 돌려 볼 수 없습니다.

Google 검색과 Play 스토어 검색은 사용자가 그 결과를 직접 골랐을 때만 검색어를 넘깁니다. Google 검색은 기본 브라우저로 Google 검색 주소를 열므로 검색어가 그 브라우저와 Google로 가고, Play 스토어 검색은 Play 스토어 앱으로 갑니다. 문자 검색 결과는 여기에 쓰이지 않습니다.

## 설정 백업과 복원

코드: [`BackupContract.kt`](../app/src/main/java/io/github/wuugi/qsearch/data/backup/BackupContract.kt), [`paid/.../data/backup/`](../paid/src/main/kotlin/io/github/wuugi/qsearch/data/backup)

- 설정 → 백업에서 내보내기를 누르면 시스템 파일 화면이 뜨고, 사용자가 고른 곳에만 JSON 파일 하나를 씁니다. 앱 안에 사본을 두지 않고,
  추가 권한이 없고, 네트워크로 보내지 않습니다.
- 파일에 들어가는 것: 즐겨찾기 배치, 숨긴 앱 목록, 홈·검색 설정(단축키, 검색창 위치, 테마, 아이콘 팩, 기준 통화 등),
  환율 변환 켜짐(복원하면 켜져 있던 경우 하루 한 번 환율 조회가 다시 시작됩니다), 다른 앱 위에서 단축키로 열기를 켜 둔 의도
  (접근성 서비스 허용 자체는 옮길 수 없어 새 폰에서 다시 켜야 합니다), 홈 위젯(위젯을 준 앱의 패키지·클래스 이름, 칸과 스택 배치, 높이.
  위젯 번호는 설치마다 새로 받으므로 넣지 않습니다).
- 들어가지 않는 것: 문자·연락처 관련 값, 앱 사용 횟수(어떤 앱을 얼마나 열었는지는 설정이 아니라 사용 기록이라 뺍니다),
  권한을 물었는지 여부, 환율 캐시, 위젯 안의 설정(시계의 도시 같은 것은 그 위젯 앱이 가집니다), 이용권·구매 상태.
- 복원할 때는 위젯마다 시스템 확인창과 그 위젯의 설정 화면을 거쳐 다시 붙입니다. 위젯을 준 앱이 없거나 사용자가 취소한 위젯은 건너뛰고,
  지금 홈에 있던 위젯은 백업의 위젯으로 바뀝니다.
- 어떤 값이 들어가고 빠지는지는 `BackupContract.kt` 한 곳에 적혀 있습니다.
- 숨긴 앱 목록과 위젯을 준 앱의 이름에는 앱 패키지 이름이 들어가므로, 백업 파일을 남에게 줄 때는 이 점에 유의해 주세요.

## 위젯

위젯 내용은 그 위젯을 제공한 앱이 그리고, 이 런처는 받은 화면을 자리에 놓기만 합니다. 위젯이 보여 주는 내용을
읽거나 저장하지 않습니다. 저장하는 것은 위젯 번호와 순서, 높이뿐입니다(백업 파일만은 번호 대신 위젯을 준 앱의 패키지·클래스 이름을 적습니다). 위젯을 붙일 때는 시스템 확인창에 맡깁니다(`BIND_APPWIDGET` 권한을 선언하지 않습니다).

## APK에서 확인하기

이 저장소만으로는 앱을 빌드할 수 없어서, 공개한 코드가 받은 APK와 같은지를 직접 빌드해 대조할 수는 없습니다.
대신 받은 APK의 권한과 구성요소는 Android SDK의 `aapt`로 누구나 볼 수 있습니다.

```bash
aapt dump permissions QSearchLauncher-<버전>.apk
aapt dump xmltree QSearchLauncher-<버전>.apk AndroidManifest.xml
```

## Play 스토어 빌드

Google Play는 `READ_SMS`를 기본 문자 앱, 전화 앱, 어시스턴트에만 허용합니다. 이 앱은 그 어디에도 해당하지 않아서
Play 빌드를 내게 되면 문자 검색과 문자·연락처 권한을 빼고 만듭니다. 아직 출시하지 않았습니다.
