package io.github.wuugi.qsearch.global

import android.view.KeyEvent
import io.github.wuugi.qsearch.search.KeyCombo

/**
 * 접근성 키 필터의 소비 판정과 단축키 직후 타입어헤드 버퍼를 한곳에 둔 상태 기계(10 D-10, GLOB-05).
 *
 * 모든 호출은 메인 스레드에서 온다(서비스 `onKeyEvent`는 메인 루퍼로 전달되고, 검색창 화면 콜백도 메인 스레드다) -- 락이 없다.
 * 키마다 할당이 없다: 눌림 표는 생성 때 한 번, 버퍼는 용량을 미리 잡은 [StringBuilder]다. Android 메서드를 부르지 않는
 * 순수 클래스라(KeyEvent 상수만 쓴다 -- KeyCombo의 Stub! 관례) JVM 테스트로 고정한다.
 *
 * **키 내용을 저장하지 않는다.** 버퍼는 검색창에 넘기거나 창이 닫히면 비우는 메모리 전용이며 디스크·로그·네트워크로 가지 않는다.
 *
 * 규칙(순서 고정, [decide]): UP은 우리가 소비한 DOWN의 짝일 때만 소비 -> 반복은 소비한 키일 때만 소비 -> 단축키 직후 창
 * ([armed]) 안이면 수식어·시스템 키는 통과, 글자는 버퍼로 -> 창 밖이면 단축키 DOWN만 [ENTRY].
 */
class GlobalKeyGate {

    companion object {
        /** 이 키를 그대로 통과시킨다(아래 앱이 받는다). */
        const val PASS = 0

        /** 이 키를 소비한다(아래 앱에 가지 않는다). */
        const val CONSUME = 1

        /** 단축키 DOWN이다 -- 호출자가 검색창을 열 수 있으면 [onOpened]를 부르고 소비한다. */
        const val ENTRY = 2

        /** 단축키 직후 문자 소비 창(D-10 "약 1초 상한"). */
        const val TYPE_AHEAD_MS = 1000L

        /**
         * 시스템이 접근성 키 필터의 답을 기다리는 시간(AOSP KeyEventDispatcher 500ms). 이보다 늦게 처리한 키는 시스템이 이미
         * 포커스 창으로 보냈다 -- 버퍼에도 넣으면 같은 글자가 두 번 들어간다(콜드 프로세스 첫 호출, 검색창 첫 생성이 메인 스레드를
         * 막는 동안). 그런 키는 버퍼에 넣지 않고 통과로 답한다.
         */
        const val LATE_KEY_MS = 500L

        /** 버퍼 상한 -- 64자를 넘는 글자는 소비하되 붙이지 않는다(검색창에 붙여 넣을 만한 길이가 아니다). */
        const val TYPE_AHEAD_MAX = 64

        /** 눌림 표 크기 -- KeyCombo의 키코드 상한(1000)과 같은 이유로 고정 상한이다. 범위 밖 키코드는 손대지 않고 통과시킨다. */
        private const val KEY_TABLE_SIZE = 1024

        /**
         * 타입어헤드로 받을 글자. Ctrl·Meta가 눌려 있으면 null(시스템·앱 단축키 몫), 스페이스는 공백, 그 밖은 서랍 첫 글자 판정
         * ([KeyCombo.drawerTypedChar])을 재사용한다 -- 서랍과 달리 공백은 쿼리의 일부라 받는다.
         */
        fun typeAheadChar(keyCode: Int, unicodeChar: Int, metaState: Int): Char? {
            if (metaState and (KeyEvent.META_CTRL_ON or KeyEvent.META_META_ON) != 0) return null
            if (keyCode == KeyEvent.KEYCODE_SPACE) return ' '
            return KeyCombo.drawerTypedChar(unicodeChar, metaState)
        }
    }

    private val pressed = BooleanArray(KEY_TABLE_SIZE)
    private val buffer = StringBuilder(16)
    private var open = false
    private var deadline = 0L

    /** [eventTime]이 단축키로 연 직후 소비 창 안이다. */
    fun armed(eventTime: Long): Boolean = open && eventTime <= deadline

    /**
     * 키 하나의 운명을 정한다. [unicodeChar]·[passThroughKey]는 호출자가 [armed]일 때만 채운다(키 문자 맵·시스템 키 판정을
     * 모든 키에 하지 않으려는 핫패스 배려) -- 아니면 0·false. [isEntryCombo]는 저장된 단축키와 일치(DOWN)한다는 뜻이다.
     */
    fun decide(
        action: Int,
        keyCode: Int,
        repeatCount: Int,
        metaState: Int,
        unicodeChar: Int,
        eventTime: Long,
        passThroughKey: Boolean,
        isEntryCombo: Boolean,
        late: Boolean = false,
    ): Int {
        if (keyCode < 0 || keyCode >= KEY_TABLE_SIZE) return PASS
        // 1) UP: 소비한 DOWN의 짝만 소비하고, 통과시킨 키의 UP은 통과시킨다.
        if (action == KeyEvent.ACTION_UP) {
            if (!pressed[keyCode]) return PASS
            pressed[keyCode] = false
            return CONSUME
        }
        if (action != KeyEvent.ACTION_DOWN) return PASS
        // 2) 반복: 소비한 키의 반복만 소비.
        if (repeatCount > 0) return if (pressed[keyCode]) CONSUME else PASS
        // 새 DOWN인데 눌림 표에 남아 있다 = 그 키의 UP을 놓쳤다. 해제하고 새로 평가한다(아래 앱이 DOWN 없는 UP을 받지 않게).
        pressed[keyCode] = false
        // 3) 단축키 직후 창 안. 수식어·시스템 키는 언제나 통과(D-10).
        if (armed(eventTime)) {
            if (passThroughKey) return PASS
            // 이미 시스템이 창으로 보낸 키다([LATE_KEY_MS]) -- 버퍼에 넣지 않고 눌림 표에도 남기지 않는다(UP도 통과).
            if (late) return PASS
            // 열리는 중의 단축키 재입력은 무시한다(D-02) -- 검색창을 다시 열거나 닫지 않는다.
            if (isEntryCombo) return consume(keyCode)
            val c = typeAheadChar(keyCode, unicodeChar, metaState)
            if (c != null) {
                if (buffer.length < TYPE_AHEAD_MAX) buffer.append(c)
                return consume(keyCode)
            }
            when (keyCode) {
                // 아래 앱의 초안을 지우지 않는다: 버퍼의 마지막 글자만 고친다(비었으면 그냥 소비).
                KeyEvent.KEYCODE_DEL -> {
                    if (buffer.isNotEmpty()) buffer.setLength(buffer.length - 1)
                    return consume(keyCode)
                }
                // 소비만 한다(버림): 아래 앱에서 초안 전송·창 닫기·포커스 이동이 일어나면 안 된다. Enter를 재생하지는 않는다 --
                // 포커스 전 몇백 ms 구간이라 검색창이 뜬 뒤 다시 누르면 된다. 방향키·기능키 등 나머지는 통과.
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_TAB ->
                    return consume(keyCode)
            }
            return PASS
        }
        // 4) 창 밖: 단축키 DOWN만 입구.
        if (isEntryCombo) return ENTRY
        return PASS
    }

    private fun consume(keyCode: Int): Int {
        pressed[keyCode] = true
        return CONSUME
    }

    /** 호출자가 검색창을 열기로 했다: 그 단축키의 반복·UP까지 소비하고 소비 창을 연다. */
    fun onOpened(keyCode: Int, eventTime: Long) {
        if (keyCode in 0 until KEY_TABLE_SIZE) pressed[keyCode] = true
        buffer.setLength(0)
        open = true
        deadline = eventTime + TYPE_AHEAD_MS
    }

    /**
     * 검색창이 창 포커스를 얻었다: 창을 닫고 버퍼를 꺼낸다. 마감 뒤 1초까지 받는 이유 -- 창 포커스는 마지막 글자보다 늦게 올
     * 수 있다. 그보다 오래된 버퍼는 실패한 실행의 잔재일 수 있어 타일이 연 다음 검색창에 섞이지 않게 버린다.
     */
    fun takeTypeAhead(now: Long): String? {
        if (!open) return null
        open = false
        val text = if (buffer.isNotEmpty() && now <= deadline + TYPE_AHEAD_MS) buffer.toString() else null
        buffer.setLength(0)
        return text
    }

    /** 창이 포커스 없이 닫혔다(홈 키 등): 소비 창을 닫고 버퍼를 비운다. */
    fun closeTypeAhead() {
        open = false
        buffer.setLength(0)
    }
}

/**
 * 접근성 서비스(소비·버퍼)와 검색창 화면(포커스 때 꺼냄)이 공유하는 프로세스 하나의 키 게이트(10 D-10). 의존성이 0이라 폴백 부팅에서도
 * 안전하다. 서비스가 다시 바인드돼도 눌림 상태가 이어져, 소비한 DOWN의 UP을 놓치지 않는다.
 */
object GlobalKeyGateHolder {
    val gate = GlobalKeyGate()
}
