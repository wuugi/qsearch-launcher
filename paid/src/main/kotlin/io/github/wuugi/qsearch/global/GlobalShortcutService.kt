package io.github.wuugi.qsearch.global

import android.annotation.SuppressLint
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import io.github.wuugi.qsearch.search.KeyCombo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import io.github.wuugi.qsearch.App
import io.github.wuugi.qsearch.searchui.SearchActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 다른 앱 위에서 검색 단축키로 검색창([SearchActivity])을 여는 키 필터 전용 접근성 서비스(10 D-01).
 *
 * **모든 하드웨어 키가 [onKeyEvent]를 지나간다**(비밀번호 입력 포함). 그래서 이 서비스가 하는 일은 keyCode+metaState를
 * 저장된 단축키와 비교하는 것뿐이다. 키를 저장·기록·전송하지 않고 화면 내용을 읽지 않는다(선언 XML이 같은 약속을 한다).
 * 핫패스라 할당·I/O·로그가 없고 O(1)이다. 이 클래스와 `global/` 패키지에는 로그 호출을 두지 않는다(10-02가 빌드 게이트로 강제).
 *
 * 소비 판정은 순수 상태 기계 [GlobalKeyGate]가 한다: 단축키 DOWN·반복·UP과, 단축키 직후 약 1초 창(D-10)의 글자만 소비한다.
 * 핫패스 규칙: 키 문자 맵·시스템 키 판정은 그 창(armed) 안에서만, 키가드 조회는 단축키가 일치해 열려 할 때만 한다.
 *
 * **끄기(10-04, D-09):** 설정 스위치를 끄면 키 필터가 실제로 사라져야 한다(개인정보). 앱은 시스템 접근성 설정을 바꿀 수 없으므로
 * 서비스가 [onServiceConnected]에서 사용자 의도 플래그를 지켜보다가 꺼지면 [disableSelf]로 스스로 끈다.
 */
class GlobalShortcutService : AccessibilityService() {

    private lateinit var keyguard: KeyguardManager

    /** 의도 플래그 감시 코루틴의 소유 스코프. 연결 때 만들고 [onUnbind]·[onDestroy]에서 취소한다. */
    private var scope: CoroutineScope? = null

    override fun onCreate() {
        super.onCreate()
        // D-07: 첫 줄. SystemUI가 서비스를 바인드하며 프로세스를 깨워도 다음 시작이 크래시로 집계되지 않게 한다.
        (application as App).onNonUiEntry()
        keyguard = getSystemService(KeyguardManager::class.java)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = this
        // D-11: SOFT_FALLBACK 프로세스면 검색 의존성을 지금 만든다 -- 첫 키에서 만들면 저장된 단축키가 비동기로 읽히기 전이라
        // 그 첫 조합을 놓친다. 정상 부팅이면 이미 준비돼 있어 아무 일도 없다.
        val app = application as App
        if (app.ensureSearchReady()) watchWantedFlag(app)
    }

    /**
     * 연결되면 사용자 의도를 참으로 기록한다(시스템 설정에서 직접 켠 것도 사용자 의도, D-09). 그 뒤 의도에서 **참을 한 번 본 다음
     * 처음 오는 거짓**을 기다려 [disableSelf]한다. 기록이 디스크 오류로 실패해 거짓만 보이는 경우 서비스가 스스로 꺼지지 않게 하는 순서다.
     * 바인드된 서비스가 던지면 시스템이 크래시로 보고 꺼 둘 수 있어(PITFALLS 3) 취소 외의 실패는 모두 삼킨다.
     */
    private fun watchWantedFlag(app: App) {
        scope?.cancel()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        s.launch {
            try {
                val store = app.searchSettingsStore
                store.setGlobalShortcutWanted(true)
                store.globalShortcutWanted.first { it }
                store.globalShortcutWanted.first { !it }
                disableSelf()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 삼킨다 -- 끄기 감시가 실패해도 서비스는 키 필터 일을 계속한다.
            }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = null
        scope?.cancel()
        scope = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = null
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val app = application as App
        // D-11: SOFT_FALLBACK은 홈을 위한 판정이다 -- 단축키는 막히지 않는다. 연속 크래시(HARD)만 막는다.
        if (!app.ensureSearchReady()) return false

        val gate = GlobalKeyGateHolder.gate
        // armed(단축키 직후 약 1초)일 때만 키 문자 맵·시스템 키를 본다. 아니면 키마다 하는 일은 표 조회와 조합 비교뿐이다.
        val armed = gate.armed(event.eventTime)
        val keyCode = event.keyCode
        val result = gate.decide(
            action = event.action,
            keyCode = keyCode,
            repeatCount = event.repeatCount,
            metaState = event.metaState,
            unicodeChar = if (armed) event.unicodeChar else 0,
            eventTime = event.eventTime,
            passThroughKey = armed && (KeyCombo.isModifierKey(keyCode) || event.isSystem),
            isEntryCombo = app.searchController.matchesEntry(event),
            late = armed && SystemClock.uptimeMillis() - event.eventTime >= GlobalKeyGate.LATE_KEY_MS,
        )
        if (result != GlobalKeyGate.ENTRY) return result == GlobalKeyGate.CONSUME
        // D-02: 이 앱이 앞이면 홈·검색창이 직접 처리한다.
        if (app.resumedOwnActivities > 0) return false
        // D-04: 잠금 중엔 아무것도 안 한다. 키가드 조회는 IPC라 조합이 일치한 **뒤에** 본다(모든 키에 걸면 핫패스가 무거워진다).
        if (keyguard.isKeyguardLocked) return false
        gate.onOpened(keyCode, event.eventTime)
        // 서비스 컨텍스트의 일반 startActivity다 -- PendingIntent로 돌리지 않는다(시스템이 접근성 서비스를 BAL 허용 바인드로 묶는다).
        SearchActivity.launch(this)
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    companion object {
        // 바인드된 동안의 서비스 자신 -- onUnbind·onDestroy에서 지운다. 서비스라 Activity를 붙잡지 않는다.
        @SuppressLint("StaticFieldLeak")
        private var connected: GlobalShortcutService? = null
            set(value) {
                field = value
                bound.value = value != null
            }

        /**
         * 이 프로세스에서 서비스가 바인드돼 있는가. 재부팅 직후엔 홈이 첫 잠금 해제 때 먼저 뜨고 시스템이 서비스를 조금 뒤에 바인드해서,
         * 홈이 ON_RESUME에서 한 번 물은 값만으론 '꺼져 있음' 안내가 남는다 -- 연결되는 순간 홈이 이 값으로 안내를 거둔다.
         */
        val bound = MutableStateFlow(false)

        /**
         * 시스템 동작 하나를 부탁한다(화면 잠금, 알림창). 추가 권한 선언이 필요 없고 화면 내용을 읽지 않는다.
         * 서비스가 꺼져 있으면 `false`.
         */
        fun performAction(action: Int): Boolean = connected?.performGlobalAction(action) ?: false

        /**
         * 시스템이 이 서비스를 실제로 바인드해 돌리고 있는가. 켜짐 목록이 아니라 **바인드된 서비스 목록**이라 강제 종료·크래시로 꺼진 경우
         * 거짓이다(`Settings.Secure` 문자열 비교보다 정확하다). 10-01 XML의 feedbackGeneric이 이 목록에 들어오는 전제다.
         * 캐시하지 않는다 -- 호출자가 화면이 다시 보일 때마다 부른다. 실패는 모두 거짓이다.
         */
        fun isRunning(context: Context): Boolean = runCatching {
            val am = context.getSystemService(AccessibilityManager::class.java)
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
                val si = it.resolveInfo.serviceInfo
                si.packageName == context.packageName && si.name == GlobalShortcutService::class.java.name
            }
        }.getOrDefault(false)
    }
}
