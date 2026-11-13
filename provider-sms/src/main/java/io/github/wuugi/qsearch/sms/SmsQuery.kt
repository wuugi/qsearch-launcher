package io.github.wuugi.qsearch.sms

/**
 * 시스템 SMS provider에서 읽어 온 메시지 한 건. 메모리에만 존재한다 — 어디에도 저장·기록하지
 * 않는다(P-01). [id]는 출처 접두 `sms:<_id>`다(LazyColumn 키 충돌 방지, 08-03이 `mms:`를 더한다).
 * [name]은 연락처 이름으로, 08-03 전까지 늘 null이다.
 */
data class SmsHit(
    val id: String,
    val address: String?,
    val name: String?,
    val dateMillis: Long,
    val body: String,
)

/**
 * SQL `LIKE` 패턴에 넣을 사용자 입력의 와일드카드를 이스케이프한다(`ESCAPE '\'`와 짝).
 * 백슬래시를 **먼저** 두 개로 만든 뒤 `%`와 `_` 앞에 백슬래시를 붙인다 — 순서가 바뀌면 방금 넣은
 * 이스케이프 백슬래시가 다시 이스케이프된다. Android 타입이 없는 순수 함수라 JVM 테스트 대상이다.
 */
internal fun escapeLike(query: String): String =
    query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

/** MMS 주소 type: PduHeaders.FROM / TO. 숨은 클래스라 리터럴이며 값은 기억 기반이다 — RESEARCH A3, 실기기 확인 대상. */
internal const val MMS_ADDR_FROM = 137
internal const val MMS_ADDR_TO = 151

/** part 테이블에는 날짜가 없어 후보를 넉넉히 받은 뒤 pdu 조인으로 거르고 자른다. */
internal const val MMS_PART_CANDIDATES = 20

/**
 * MMS `date`는 초 단위다(provider insert가 `timeInMillis / 1000L`로 저장 — Pitfall 1). SMS와 병합하기
 * 전에 반드시 이 한 곳에서 ms로 바꾼다. 안 바꾸면 MMS가 늘 맨 아래로 가거나 1970년으로 보인다.
 */
internal fun mmsSecondsToMillis(seconds: Long): Long = seconds * 1000L

/**
 * SMS·MMS 히트를 최신순으로 합쳐 [limit]건만 남긴다. 동점은 id 내림차순(문자열 비교)이라
 * `sms:`가 `mms:`보다 앞에 온다 — 결정적이면 충분하다. 이후 `SearchResults.ordered`의 안정 정렬이 이 순서를 화면까지 유지한다.
 */
internal fun mergeNewest(hits: List<SmsHit>, limit: Int): List<SmsHit> =
    hits.sortedWith(compareByDescending<SmsHit> { it.dateMillis }.thenByDescending { it.id }).take(limit)

/**
 * MMS 상대 주소: 받은(1)이면 FROM, 보낸(2)이면 TO 중 첫 번째로 공백이 아니고 `insert-address-token`이 아닌 것.
 * 못 찾으면 null — 행은 '알 수 없음'이 되고 선택 시 D-14 폴백으로 간다.
 */
internal fun pickMmsAddress(msgBox: Int, addrs: List<Pair<String?, Int>>): String? {
    val want = when (msgBox) {
        1 -> MMS_ADDR_FROM
        2 -> MMS_ADDR_TO
        else -> return null
    }
    return addrs.firstOrNull { (a, t) -> t == want && !a.isNullOrBlank() && a != "insert-address-token" }?.first
}
