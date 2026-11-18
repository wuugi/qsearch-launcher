package io.github.wuugi.qsearch.sms

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone

/**
 * 연락처 한 명. [lookupKey]는 D-05의 안정 키(`"contact:" + lookupKey`가 결과 id), [lookupUri]는 연락처 앱을
 * 여는 `content://com.android.contacts/contacts/lookup/...` 주소 문자열이다. [number]는 표시용 번호(없으면 null).
 */
data class ContactHit(
    val lookupKey: String,
    val name: String,
    val number: String?,
    val lookupUri: String,
    val score: Double,
)

/**
 * 시스템 연락처 provider(`ContactsContract.Contacts`·`Phone`)를 이름 점수 람다와 번호 숫자열로 거른다.
 * [contactsUri]·[phoneUri]는 테스트에서 가짜 provider로 바꿔 끼우는 이음매다.
 *
 * - 검색어는 selection·sortOrder 문자열에 절대 들어가지 않는다 — 점수 람다([search]의 `nameScore`)와 Kotlin 비교로만
 *   거른다(T-18-02).
 * - 호출 사이에 아무것도 보관하지 않는다: 필드·캐시·ContentObserver 없음(T-18-01).
 * - 로그를 남기지 않는다(`checkSmsIsolation` 규칙 C). 예외(권한 회수의 `SecurityException` 포함)는 던진다 — 어댑터가 접는다.
 * - 주소록 전체 목록이 이 함수 밖으로 나가지 않는다(T-08-14 원칙) — 점수가 있는 후보만 정렬해 [limit]건이 나간다.
 */
class ContactsSearcher(
    context: Context,
    private val contactsUri: Uri = ContactsContract.Contacts.CONTENT_URI,
    private val phoneUri: Uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
) {
    private val resolver = context.applicationContext.contentResolver

    private class Candidate(val lookupKey: String, val name: String, var number: String?, var score: Double, val lookupUri: String)

    /**
     * 3단계(이름 스캔 -> 번호 스캔 -> 최종 후보 대표 번호). 이름이 [nameScore]에서 null이 아니거나 번호가 [phoneDigits]를 포함하는([phoneDigits]가 null이면 번호 경로는 조회하지
     * 않는다) 연락처를 점수 내림차순·이름 오름차순으로 최대 [limit]명. 이름·번호가 모두 맞는 연락처는 한 명으로 합치고 점수는
     * 큰 쪽이며, 번호로 찾은 연락처는 일치한 저장 원문 번호를, 이름으로만 찾은 연락처는 대표 번호를 보여 준다. 블로킹 쿼리는 [cancellable]로 IO에서 돌고 코루틴이 취소되면 provider
     * 쪽 실행까지 끊긴다.
     */
    suspend fun search(nameScore: (String) -> Double?, phoneDigits: String?, limit: Int): List<ContactHit> {
        val candidates = LinkedHashMap<Long, Candidate>()
        cancellable { signal ->
            resolver.query(
                contactsUri,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.LOOKUP_KEY,
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ),
                // 상수 가시성 조건 하나 — 검색어는 여기에 없다(T-18-02).
                VISIBLE_ONLY,
                null,
                null,
                signal,
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                val keyCol = c.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY)
                val nameCol = c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                while (c.moveToNext()) {
                    signal.throwIfCanceled()
                    val name = c.getString(nameCol) ?: continue
                    val key = c.getString(keyCol) ?: continue
                    val score = nameScore(name) ?: continue
                    val id = c.getLong(idCol)
                    val uri = ContactsContract.Contacts.getLookupUri(id, key)?.toString() ?: continue
                    candidates[id] = Candidate(key, name, null, score, uri)
                }
            }
        }
        if (phoneDigits != null) {
            cancellable { signal ->
                resolver.query(
                    phoneUri,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                        ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ),
                    VISIBLE_ONLY,
                    null,
                    null,
                    signal,
                )?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                    val keyCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY)
                    val nameCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
                    val numCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (c.moveToNext()) {
                        signal.throwIfCanceled()
                        val number = c.getString(numCol) ?: continue
                        if (!normalizePhoneDigits(number).contains(phoneDigits)) continue
                        val id = c.getLong(idCol)
                        val existing = candidates[id]
                        if (existing != null) {
                            existing.score = maxOf(existing.score, NUMBER_MATCH_SCORE)
                            if (existing.number == null) existing.number = number
                        } else {
                            val key = c.getString(keyCol) ?: continue
                            val uri = ContactsContract.Contacts.getLookupUri(id, key)?.toString() ?: continue
                            candidates[id] = Candidate(key, c.getString(nameCol) ?: number, number, NUMBER_MATCH_SCORE, uri)
                        }
                    }
                }
            }
        }
        val top = candidates.entries
            .sortedWith(compareByDescending<Map.Entry<Long, Candidate>> { it.value.score }.thenBy { it.value.name })
            .take(limit)
        fillRepresentativeNumbers(top.filter { it.value.number == null }.map { it.key }, candidates)
        return top.map { (_, c) -> ContactHit(c.lookupKey, c.name, c.number, c.lookupUri, c.score) }
    }

    /**
     * 3단계: 번호가 비어 있는(이름으로만 찾은) 최종 후보에게 대표 번호 1개를 채운다 — 대표(IS_SUPER_PRIMARY) -> 기본(IS_PRIMARY)
     * -> 처음 본 행. 한 번의 쿼리이며 selection에는 자리표시자 개수만 들어가고 id는 인자로 바인딩한다(T-08-13 원칙, T-18-07).
     * 번호 없는 연락처는 null로 남는다 — '번호 있음' 컬럼으로 거르지 않는다.
     */
    private suspend fun fillRepresentativeNumbers(ids: List<Long>, candidates: Map<Long, Candidate>) {
        if (ids.isEmpty()) return
        // 연락처 id -> (순위, 번호). 순위: 2 = super primary, 1 = primary, 0 = 처음 본 행(덮어쓰지 않음).
        val best = HashMap<Long, Pair<Int, String>>()
        cancellable { signal ->
            resolver.query(
                phoneUri,
                arrayOf(Phone.CONTACT_ID, Phone.NUMBER, Phone.IS_PRIMARY, Phone.IS_SUPER_PRIMARY),
                "${Phone.CONTACT_ID} IN (${ids.joinToString(",") { "?" }})",
                Array(ids.size) { ids[it].toString() },
                null,
                signal,
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(Phone.CONTACT_ID)
                val numCol = c.getColumnIndexOrThrow(Phone.NUMBER)
                val primaryCol = c.getColumnIndexOrThrow(Phone.IS_PRIMARY)
                val superCol = c.getColumnIndexOrThrow(Phone.IS_SUPER_PRIMARY)
                while (c.moveToNext()) {
                    signal.throwIfCanceled()
                    val number = c.getString(numCol) ?: continue
                    val rank = if (c.getInt(superCol) != 0) 2 else if (c.getInt(primaryCol) != 0) 1 else 0
                    val id = c.getLong(idCol)
                    if (rank > (best[id]?.first ?: -1)) best[id] = rank to number
                }
            }
        }
        for ((id, ranked) in best) candidates[id]?.number = ranked.second
    }

    private companion object {
        const val VISIBLE_ONLY = "${ContactsContract.Contacts.IN_VISIBLE_GROUP} = 1"
    }
}
