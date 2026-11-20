package io.github.wuugi.qsearch.notification

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.wuugi.qsearch.App
import io.github.wuugi.qsearch.boot.BootGuard
import io.github.wuugi.qsearch.search.command.ReplySource
import io.github.wuugi.qsearch.search.command.ReplyTarget

/**
 * 읽지 않은 알림 점(HOME-06)의 `NotificationListenerService` 구현.
 *
 * **수집 범위와 그 이유 (PROJECT.md §Constraints "권한 사용처가 코드와 문서에서
 * 명확해야 함"):** `BIND_NOTIFICATION_LISTENER_SERVICE`는 기기 전체의 알림 내용에
 * 접근하는 특수 권한이다. 점 경로([onNotificationPosted]·[onNotificationRemoved]·[pushSnapshot])가
 * 읽는 것은 각 알림의 `packageName`과 "지울 수 있는 알림인가" 판정([StatusBarNotification]의
 * 프로퍼티 하나) 두 가지뿐이고 보관하는 것은 [NotificationDotState]의 패키지명 집합 하나뿐이다.
 * 점 경로는 알림 제목·본문·발신자를 읽지 않는다.
 *
 * **답장 경로(REPLY-02/03, D-17):** 시스템 알림 접근과 답장 스위치(기본 꺼짐)가 모두 켜진 활성
 * `@` 목록에서 [targets]가 답장 가능한 알림의 발신자와 본문을 읽는다. 목록 갱신 시 다시 읽을 수
 * 있다. 원래 알림 내용은 표시용 메모리에만 두고, 상태는 검색창을 닫거나 백그라운드로 보내면 지운다.
 * [send]는 key로 알림을 다시 찾아 사용자가 쓴 답장만 해당 앱에 전달한다. 스위치 값은 백업으로 복원할 수 있다.
 * `ReplyPrivacyGateTest`는 점 콜백과 보조 함수의 extras 접근, 답장 조회·전송·렌더링 경로의
 * 로그·저장·복사 및 알림 내용을 담은 토스트에 해당하는 소스 호출을 검사한다.
 *
 * 이 서비스의 존재 자체가 프로세스를 상주시키므로 콜백에서 무거운 작업을 하지
 * 않는다(PROJECT.md 메모리·배터리 제약).
 *
 * **MUST-CLOSE와의 관계(04-05):** [onListenerConnected]가 가장 먼저 하는 일은
 * `App.uiStarted`가 거짓일 때 BootGuard `pending`을 내리는 공용 도우미 `App.onNonUiEntry()`를 부르는 것이다 — 시스템이
 * 이 서비스를 바인드하려고 `MainActivity` 없이 프로세스를 띄운 경우, 그 프로세스가
 * (정상적으로) 종료돼도 다음 프로세스 시작이 크래시로 오인하지 않게 한다.
 */
class UnreadDotListenerService : NotificationListenerService(), ReplySource {

    override fun onListenerConnected() {
        super.onListenerConnected()
        val app = application as App
        // MUST-CLOSE(04-05): UI가 열리지 않은 프로세스에서 리스너가 연결됐다 — 이번
        // 시작이 정상 종료돼도 다음 시작이 크래시로 오인하지 않도록 pending을 내린다.
        // 판정과 해제는 공용 도우미 한 곳이다(App.onNonUiEntry, 09 D-04).
        app.onNonUiEntry()
        // 폴백 경로(01 D-02)에서는 NORMAL 분기의 lateinit 객체(notificationDotState 등)가
        // 초기화되지 않았다 — 여기서 즉시 반환해 접근하지 않는다.
        if (app.bootMode != BootGuard.BootMode.NORMAL) return
        app.replySource = this
        pushSnapshot()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        val app = application as App
        if (app.bootMode != BootGuard.BootMode.NORMAL) return
        if (app.replySource === this) app.replySource = null
        app.notificationDotState.clear()
    }

    /** 방금 답장한 알림 key와 보낸 시각 -- [send]는 IO 스레드, 콜백은 메인 스레드라 volatile이다. 시각을 먼저 쓴다. */
    @Volatile private var repliedAt = 0L
    @Volatile private var repliedKey: String? = null

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        // 앱이 답장을 대화에 붙이며 같은 key로 알림을 다시 올리면 또 지운다(2026-11-21 UAT: 답장 후 알림이 남음).
        // shortcut: 이 2초 안에 상대가 새로 보낸 메시지도 함께 지워진다, 실기기에서 문제가 되면 본문 비교로 바꾼다.
        if (sbn != null && sbn.key == repliedKey && SystemClock.uptimeMillis() - repliedAt < REPOST_WINDOW_MS) {
            runCatching { cancelNotification(sbn.key) }
        }
        pushSnapshotIfNormal()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        pushSnapshotIfNormal()
    }

    private fun pushSnapshotIfNormal() {
        val app = application as App
        if (app.bootMode != BootGuard.BootMode.NORMAL) return
        pushSnapshot()
    }

    /**
     * 현재 활성 알림에서 지울 수 있는 알림이 있는 패키지 집합을 파생해
     * [NotificationDotState.refresh]로 넘긴다. 아래 필터 한 줄이 D-14/Pitfall 2의
     * 유일한 방어선이다 — 이것 없이는 음악 재생·다운로드 같은 지속(ongoing) 알림이
     * 점을 영원히 붙인다. 조회 실패는 흡수한다 — 시스템 콜백 스레드에서 던지면
     * 프로세스가 죽고, 그것이 다시 BootGuard 카운트로 이어진다.
     */
    private fun pushSnapshot() {
        val app = application as App
        val clearablePackages = runCatching { activeNotifications }
            .getOrDefault(emptyArray())
            .filter { it.isClearable }
            .map { it.packageName }
            .toSet()
        app.notificationDotState.refresh(clearablePackages)
    }

    /** 자유 입력 답장 액션이 있는 알림만, 요청 시점에 읽는다. 조회 실패는 빈 목록이다(리스너 스레드를 죽이지 않는다). */
    override fun targets(): List<ReplyTarget> = runCatching {
        activeNotifications.mapNotNull { sbn ->
            if (replyAction(sbn) == null) return@mapNotNull null
            val n = sbn.notification
            val extras = n.extras
            ReplyTarget(
                key = sbn.key,
                packageName = sbn.packageName,
                sender = (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                    ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString().orEmpty(),
                preview = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
                postTime = sbn.postTime,
                conversation = sbn.packageName + "|" + (n.shortcutId ?: sbn.key),
            )
        }
    }.getOrDefault(emptyList())

    /** key로 알림을 다시 찾아 보낸다 -- 결과에 PendingIntent를 실어 두지 않는다. 없어졌거나 취소됐으면 false. */
    override fun send(key: String, text: String): Boolean = runCatching {
        val sbn = activeNotifications.firstOrNull { it.key == key } ?: return false
        val action = replyAction(sbn) ?: return false
        val inputs = action.remoteInputs
        val results = Bundle()
        inputs.filter { it.allowFreeFormInput }.forEach { results.putCharSequence(it.resultKey, text) }
        val intent = Intent()
        RemoteInput.addResultsToIntent(inputs, intent, results)
        if (Build.VERSION.SDK_INT >= 28) {
            RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
        }
        // 보낸 알림은 알림창에서 지운다(2026-11-21 UAT). 앱의 재게시가 send보다 먼저 올 수 있어 표시를 먼저 한다.
        repliedAt = SystemClock.uptimeMillis()
        repliedKey = key
        action.actionIntent.send(this, 0, intent)
        runCatching { cancelNotification(key) }
        true
    }.getOrElse { false } // PendingIntent.CanceledException 포함

    private fun replyAction(sbn: StatusBarNotification): Notification.Action? =
        sbn.notification.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }

    private companion object {
        const val REPOST_WINDOW_MS = 2_000L
    }
}
