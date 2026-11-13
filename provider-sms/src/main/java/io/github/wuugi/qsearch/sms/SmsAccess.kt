package io.github.wuugi.qsearch.sms

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony

/**
 * 이 모듈이 SMS·연락처 시스템 API를 만지는 유일한 곳이다(SMS-04) — `:app`은 권한 문자열도
 * `Telephony`도 직접 쓰지 않고 이 객체를 거친다. `./gradlew checkSmsIsolation`이 강제한다.
 */
object SmsAccess {

    /**
     * 인라인 '문자 검색 켜기' 행이 한 번에 요청하는 권한(D-01, D-07) — READ_SMS 다음 READ_CONTACTS. 시스템이 그룹별로
     * 대화상자 2개를 잇달아 띄운다. 호출부는 선택 순간에만 이것을 launcher에 넘긴다(첫 실행·앱 시작 요청 금지).
     */
    val REQUEST: Array<String> = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS)

    /** 매 호출 시스템에 묻는다 — 저장·캐시 금지(04 D-12). 회수 직후에도 낡은 허용 판정이 남지 않는다. */
    fun hasSms(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /**
     * 권한 요청 결과 콜백 **직후에만** 부른다. 참 = 사용자가 READ_SMS 대화상자를 보고 거부했다(D-02의 '거부').
     * 거짓 = 시스템이 묻지 않고 막았다(사이드로드 '제한된 설정', 두 번 거부·user-fixed로 대화상자 억제,
     * Android 11+ 바깥 탭 닫기) -- D-17. 제한 상태를 직접 묻는 공개 API가 없어서 상태가 아니라 "사용자가 답했는가"를
     * 본다. 매번 시스템에 묻고 저장하지 않는다(04 D-12).
     */
    fun userDeclinedSms(activity: Activity): Boolean =
        activity.shouldShowRequestPermissionRationale(Manifest.permission.READ_SMS)

    /** 연락처 이름 표시용 선택 권한(D-07) — 매 호출 시스템에 묻는다. */
    fun hasContacts(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /**
     * 기본 문자 앱 패키지(없으면 null) — 결과 행 아이콘용(D-09). 모듈 매니페스트의 SMS_DELIVER `<queries>`가
     * API 30+ 가시성을 준다.
     */
    fun defaultSmsPackage(context: Context): String? =
        runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()

    /**
     * [address]의 기존 대화를 기본 문자 앱에서 연다. 다이얼할 수 있는 주소([isDialable])가 아니면 인텐트를 만들지 않고 false.
     * 본문 초안 extra를 붙이지 않는다(대화가 아니라 새 초안이 열린다) — 고르는 것은 열기뿐이고
     * 작성·전송은 하지 않는다(SMS-02). `resolveActivity` 사전 확인도 하지 않는다 — API 30+
     * 패키지 가시성이 가짜 음성을 만든다(03 Pitfall 7). [context]는 Application이라
     * `FLAG_ACTIVITY_NEW_TASK`가 필수다.
     */
    fun openConversation(context: Context, address: String?): Boolean {
        val dialable = address?.takeIf { isDialable(it) } ?: return false
        return runCatching {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(dialable)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.isSuccess
    }
}
