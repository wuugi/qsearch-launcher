package io.github.wuugi.qsearch.sms

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.ContactsContract
import android.provider.Telephony
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 시스템 SMS provider(`content://sms`)와 MMS 텍스트 파트(`content://mms/part`)의 본문을 부분 문자열로
 * 조회한다. [smsUri]·[mmsUri]는 테스트에서 가짜 provider로 바꿔 끼우는 이음매다. RCS 채팅은 대상이 아니다(D-15).
 *
 * 실패(권한 회수의 `SecurityException` 포함)는 던진다 — 어댑터가 빈 리스트로 접는다. 이 클래스는
 * 로그를 남기지 않는다: 쿼리·본문·주소가 logcat으로 새는 길을 막는다(P-01, `checkSmsIsolation` 규칙 C).
 */
class SmsSearcher(
    context: Context,
    private val smsUri: Uri = Telephony.Sms.CONTENT_URI,
    private val mmsUri: Uri = Telephony.Mms.CONTENT_URI,
    private val contactsLookupUri: Uri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
) {
    private val resolver = context.applicationContext.contentResolver

    /**
     * 최신순 최대 [limit]건. 블로킹 쿼리는 각자 [cancellable]을 거쳐 IO로 옮긴다 — 호출 코루틴은
     * Main.immediate일 수 있고, 쿼리가 바뀌어 취소되면 provider 쪽 SQLite 실행까지 끊긴다(SMS-05).
     * [withNames]가 참이면 최종 목록의 번호만 연락처에서 이름을 찾는다(D-07). 필수 인자다 — 호출부가
     * 권한 판정(`SmsAccess.hasContacts`)을 빠뜨릴 수 없게 기본값이 없고, 이 클래스는 권한을 직접 묻지 않는다.
     */
    suspend fun search(query: String, limit: Int, withNames: Boolean): List<SmsHit> {
        // ponytail: SQLite LIKE의 대소문자 무시는 ASCII뿐이라 비ASCII 대소문자·NFC/NFD 차이는 일치하지
        // 않는다. 한글엔 대소문자가 없어 D-11을 충족하고 악센트 라틴은 한계다. 필요해지면 후보를
        // 넉넉히 받아 앱에서 접어 거른다.
        val pattern = "%" + escapeLike(query) + "%"
        val sms = querySms(pattern, limit)
        val mms = queryMms(pattern)
        val merged = mergeNewest(sms + mms.values.map { it.hit }, limit)
        val filled = fillMmsAddresses(merged, mms)
        return if (withNames) attachNames(filled) else filled
    }

    private suspend fun querySms(pattern: String, limit: Int): List<SmsHit> = cancellable { signal ->
        val hits = ArrayList<SmsHit>(limit)
        resolver.query(
            smsUri,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.DATE, Telephony.Sms.BODY),
            // 받은(1)/보낸(2) 문자만 — 초안·보낼 편지함·실패는 "주고받은 문자"가 아니다.
            "${Telephony.Sms.BODY} LIKE ? ESCAPE '\\' AND ${Telephony.Sms.TYPE} IN (1,2)",
            arrayOf(pattern),
            // SmsProvider는 sortOrder를 검증 없이 ORDER BY로 넘긴다 — Int에서만 만든 상수 문자열이어야
            // 하며 사용자 입력이 절대 들어가지 않는다(T-08-01). 서브쿼리는 비기본 앱에서 거부된다.
            "${Telephony.Sms.DATE} DESC LIMIT $limit",
            signal,
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addrCol = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val dateCol = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val bodyCol = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            while (c.moveToNext()) {
                hits += SmsHit(
                    id = "sms:" + c.getLong(idCol),
                    address = c.getString(addrCol),
                    name = null,
                    dateMillis = c.getLong(dateCol),
                    body = c.getString(bodyCol).orEmpty(),
                )
            }
        }
        hits
    }

    private class MmsCandidate(val hit: SmsHit, val msgBox: Int)

    /**
     * MMS 텍스트 파트 일치 -> pdu 배치 조인(날짜·상자). part에는 날짜가 없어 후보를 [MMS_PART_CANDIDATES]건
     * 넉넉히 받고, 서브쿼리는 비기본 앱의 `SqlQueryChecker`가 막아 쿼리 두 번으로 나눈다.
     * 주소는 최종 목록에 남은 것만 [fillMmsAddresses]에서 읽는다.
     */
    private suspend fun queryMms(pattern: String): Map<Long, MmsCandidate> {
        // mid -> 첫 일치 파트 텍스트 (최신 파트 우선).
        val texts = LinkedHashMap<Long, String>()
        cancellable { signal -> resolver.query(
            mmsUri.buildUpon().appendPath("part").build(),
            arrayOf(Telephony.Mms.Part.MSG_ID, Telephony.Mms.Part.TEXT),
            "${Telephony.Mms.Part.CONTENT_TYPE} = 'text/plain' AND ${Telephony.Mms.Part.TEXT} LIKE ? ESCAPE '\\'",
            arrayOf(pattern),
            // part의 _id는 provider 상수가 없다(숨은 BaseColumns) — Int 상수만으로 만든 문자열.
            "_id DESC LIMIT $MMS_PART_CANDIDATES",
            signal,
        )?.use { c ->
            val midCol = c.getColumnIndexOrThrow(Telephony.Mms.Part.MSG_ID)
            val textCol = c.getColumnIndexOrThrow(Telephony.Mms.Part.TEXT)
            while (c.moveToNext()) {
                texts.getOrPut(c.getLong(midCol)) { c.getString(textCol).orEmpty() }
            }
        } }
        if (texts.isEmpty()) return emptyMap()

        val out = LinkedHashMap<Long, MmsCandidate>()
        val ids = texts.keys.toList()
        cancellable { signal -> resolver.query(
            mmsUri,
            arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX),
            // 자리표시자 개수만 문자열에 들어가고 값은 앞 단계 cursor의 숫자 id 바인딩이다(T-08-13).
            // 받은(1)/보낸(2)만 — 초안과 비공개 뷰가 거른 것(WAP push 등)은 결과에 없어 버려진다.
            "${Telephony.Mms._ID} IN (${ids.joinToString(",") { "?" }}) AND ${Telephony.Mms.MESSAGE_BOX} IN (1,2)",
            ids.map { it.toString() }.toTypedArray(),
            null,
            signal,
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(Telephony.Mms._ID)
            val dateCol = c.getColumnIndexOrThrow(Telephony.Mms.DATE)
            val boxCol = c.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX)
            while (c.moveToNext()) {
                val mid = c.getLong(idCol)
                val body = texts[mid] ?: continue
                out[mid] = MmsCandidate(
                    SmsHit("mms:$mid", null, null, mmsSecondsToMillis(c.getLong(dateCol)), body),
                    c.getInt(boxCol),
                )
            }
        } }
        // ponytail: 긴 MMS 텍스트가 `_data` 파일로 빠진 경우는 text 컬럼에 없어 일치하지 않는다(RESEARCH A4,
        // 알려진 한계). 필요해지면 part 파일을 열어 읽는다.
        return out
    }

    /** 최종 목록의 MMS 항목만 `mms/<id>/addr`에서 상대 주소를 읽어 채운다. 못 찾으면 address는 null. */
    private suspend fun fillMmsAddresses(hits: List<SmsHit>, mms: Map<Long, MmsCandidate>): List<SmsHit> =
        hits.map { h ->
            if (!h.id.startsWith("mms:")) return@map h
            val mid = h.id.removePrefix("mms:").toLong()
            val box = mms[mid]?.msgBox ?: return@map h
            val addrs = ArrayList<Pair<String?, Int>>()
            cancellable { signal -> resolver.query(
                mmsUri.buildUpon().appendPath(mid.toString()).appendPath("addr").build(),
                arrayOf(Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE),
                null, null, null, signal,
            )?.use { c ->
                val aCol = c.getColumnIndexOrThrow(Telephony.Mms.Addr.ADDRESS)
                val tCol = c.getColumnIndexOrThrow(Telephony.Mms.Addr.TYPE)
                while (c.moveToNext()) addrs += c.getString(aCol) to c.getInt(tCol)
            } }
            h.copy(address = pickMmsAddress(box, addrs))
        }

    /**
     * 최종 표시 목록의 서로 다른 다이얼 가능 번호만 [contactsLookupUri]로 조회해 name을 채운다. 번호 정규화는
     * provider(PhoneLookup)에 맡긴다. 결과 맵은 이 호출 지역에만 있다 — 호출 사이 캐시·저장·로그 없음,
     * 연락처 열거 없음(T-08-14). 조회 중 권한이 회수돼도(SecurityException) 그 번호의 이름만 null이다(T-08-17).
     */
    private suspend fun attachNames(hits: List<SmsHit>): List<SmsHit> {
        val names = HashMap<String, String?>()
        for (a in hits.mapNotNull { it.address }.filter { isDialable(it) }.distinct()) {
            names[a] = try {
                cancellable { signal ->
                    resolver.query(
                        Uri.withAppendedPath(contactsLookupUri, Uri.encode(a)),
                        arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                        null, null, null, signal,
                    )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                }
            } catch (e: CancellationException) {
                throw e // 취소는 삼키지 않는다
            } catch (e: Exception) {
                null // 권한 회수 등: 이 번호의 이름만 null (T-08-17)
            }
        }
        return hits.map { h -> h.copy(name = h.address?.let { names[it] }) }
    }
}

/**
 * 블로킹 provider 조회를 IO에서 돌리고, 코루틴이 취소되면 [CancellationSignal]로 provider 쪽 실행을 끊는다.
 * 끊긴 쿼리가 던지는 [OperationCanceledException]은 [CancellationException]으로 바꿔 조용한 취소로 접는다.
 * 감시 자식은 UNDISPATCHED로 바로 대기에 들어가 취소 전에 시작하지 못하는 틈이 없다.
 *
 * ponytail: 실제 SmsProvider가 5인자 query만 재정의했다면 신호가 무시된다 — 그때도 낡은 조회는 IO에서
 * 끝까지 돌 뿐이고 `flatMapLatest`가 결과를 버리며 SMS 디바운스가 겹침을 줄인다(실기기 확인 항목).
 */
internal suspend fun <T> cancellable(block: (CancellationSignal) -> T): T = withContext(Dispatchers.IO) {
    val signal = CancellationSignal()
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            signal.cancel()
        }
    }
    try {
        block(signal)
    } catch (e: OperationCanceledException) {
        throw CancellationException("provider query canceled").apply { initCause(e) }
    } finally {
        watcher.cancel()
    }
}
