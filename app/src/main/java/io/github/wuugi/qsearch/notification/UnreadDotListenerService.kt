package io.github.wuugi.qsearch.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.wuugi.qsearch.App
import io.github.wuugi.qsearch.boot.BootGuard

/**
 * 읽지 않은 알림 점(HOME-06)의 `NotificationListenerService` 구현.
 *
 * **수집 범위와 그 이유 (PROJECT.md §Constraints "권한 사용처가 코드와 문서에서
 * 명확해야 함"):** `BIND_NOTIFICATION_LISTENER_SERVICE`는 기기 전체의 알림 내용에
 * 접근하는 특수 권한이지만, 이 서비스가 실제로 읽는 것은 각 알림의 `packageName`과
 * "지울 수 있는 알림인가" 판정([StatusBarNotification]의 프로퍼티 하나) 두 가지뿐이고
 * 보관하는 것은 [NotificationDotState]의 패키지명 집합 하나뿐이다. 알림 제목·본문·
 * 발신자는 읽지도, 보관하지도, 내보내지도 않는다 — 이 파일 전체에 로깅 호출이 없다.
 * 이 서비스의 존재 자체가 프로세스를 상주시키므로 콜백에서 무거운 작업을 하지
 * 않는다(PROJECT.md 메모리·배터리 제약).
 *
 * **MUST-CLOSE와의 관계(04-05):** [onListenerConnected]가 가장 먼저 하는 일은
 * `App.uiStarted`가 거짓일 때 BootGuard `pending`을 내리는 공용 도우미 `App.onNonUiEntry()`를 부르는 것이다 — 시스템이
 * 이 서비스를 바인드하려고 `MainActivity` 없이 프로세스를 띄운 경우, 그 프로세스가
 * (정상적으로) 종료돼도 다음 프로세스 시작이 크래시로 오인하지 않게 한다.
 */
class UnreadDotListenerService : NotificationListenerService() {

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
        pushSnapshot()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        val app = application as App
        if (app.bootMode != BootGuard.BootMode.NORMAL) return
        app.notificationDotState.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
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
}
