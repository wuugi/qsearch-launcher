# 원래 런처로 되돌리기

Q Search Launcher를 기본 홈으로 쓰다가 문제가 생겼을 때 원래 런처로 돌아가는 방법입니다.

## 앱이 스스로 하는 일

앱이 켜지자마자 꺼지는 일이 반복되면 앱이 스스로 단순한 대체 화면으로 전환합니다. 그 화면의 버튼을 누르면 시스템의
기본 홈 앱 설정이 열리고, 거기서 원래 런처를 고르면 됩니다.

## 휴대폰만으로 되돌리기

1. 설정 → 앱 → 기본 앱 → 홈 앱에서 원래 런처를 고릅니다.
2. 홈 화면이 떠 있지 않아 설정을 열기 어렵다면 안전 모드로 재부팅합니다. 안전 모드에서는 직접 설치한 앱이 꺼지고 기기 기본 런처가 뜹니다.
   - 대부분의 기기는 전원 메뉴를 열고 "전원 끄기"를 길게 누르면 "안전 모드로 다시 시작"이 나옵니다. 방법은 제조사마다 다를 수 있습니다.
   - 안전 모드 안에서 위 1번처럼 홈 앱을 원래 런처로 바꾼 뒤 다시 재부팅합니다. 바꾸지 않고 재부팅하면 Q Search Launcher가 다시 홈이 될 수 있습니다.

## PC로 되돌리기 (adb)

USB 디버깅이 켜져 있다면 PC에서 바로 바꿀 수 있습니다. 원래 런처의 패키지 이름은 기기마다 다르니,
Q Search Launcher를 기본 홈으로 바꾸기 전에 확인해 두면 좋습니다.

```bash
# 지금 기본 홈인 앱 확인
adb shell cmd role get-role-holders android.app.role.HOME

# 원래 런처로 되돌리기 (예: Pixel 런처)
adb shell cmd package set-home-activity com.google.android.apps.nexuslauncher

# Q Search Launcher를 기본 홈으로 지정
adb shell cmd package set-home-activity io.github.wuugi.qsearch
```

패키지 이름만 넣으면 됩니다. 성공하면 `Success`가 나오고, 홈 버튼을 누르면 바뀐 런처가 뜹니다.

자주 쓰이는 기본 런처 패키지 이름입니다.

| 기기 | 패키지 |
|---|---|
| Pixel, 에뮬레이터 | `com.google.android.apps.nexuslauncher` |
| Motorola | `com.motorola.launcher3` |
| Samsung | `com.sec.android.app.launcher` |

## 앱 지우기

그래도 안 되면 앱을 지우면 됩니다. 설정 → 앱 → Q Search Launcher → 삭제, 또는 PC에서
`adb uninstall io.github.wuugi.qsearch`. 지우면 기기가 원래 런처로 돌아갑니다.
