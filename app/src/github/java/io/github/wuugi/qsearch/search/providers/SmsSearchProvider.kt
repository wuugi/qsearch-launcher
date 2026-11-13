package io.github.wuugi.qsearch.search.providers

import io.github.wuugi.qsearch.search.ResultGroup
import io.github.wuugi.qsearch.search.SearchProvider
import io.github.wuugi.qsearch.search.SearchResult
import io.github.wuugi.qsearch.search.SmsPermissionPrompt
import io.github.wuugi.qsearch.search.SmsResult
import io.github.wuugi.qsearch.sms.SmsHit
import io.github.wuugi.qsearch.sms.makeSnippet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * 문자 본문 검색을 `SearchEngine`에 꽂는 얇은 어댑터. 시스템 SMS provider 조회는 전부
 * `:provider-sms`의 `SmsSearcher`가 하고(SMS-04), 이 클래스는 [searchSms]·[hasSms] 람다로만
 * 그것을 안다 — `:app`에는 SMS API 토큰이 없다. 블로킹 호출을 하지 않는다(조회는 IO로 옮겨진다).
 *
 * - **D-10:** trim한 쿼리의 코드 포인트가 [MIN_QUERY_CODE_POINTS] 미만이면 조회 없이 빈 리스트를
 *   즉시 방출한다. null(미응답)이 아니라 0건 확정이라 03 D-11의 구글 폴백 판정이 유지된다.
 * - **D-05:** 권한은 수집마다 [hasSms]로 시스템에 묻고 저장하지 않는다. 조회 예외(권한 회수의
 *   `SecurityException` 포함)는 빈 리스트로 접는다 — 오류 문구도 크래시도 없다. 취소만 다시 던진다.
 * - **D-09:** 결과는 [MAX_RESULTS]건까지. 정렬은 `SmsResult.score`(= 날짜)에 맡긴다.
 *
 * **권한 결정 테이블(08-04, 쿼리가 2 코드 포인트 이상일 때만 — D-10):**
 *
 * | [hasSms] | 거부 플래그 | 방출 |
 * |---|---|---|
 * | 참 | 무관 | 검색 결과 (D-03 — 허용돼 있으면 플래그를 무시) |
 * | 거짓 | 거짓 | `[SmsPermissionPrompt]` (D-01 — 인라인 '문자 검색 켜기' 행) |
 * | 거짓 | 참 | 빈 리스트 (D-02/D-05 — 다시 묻지 않는다) |
 *
 * 토글 꺼짐은 `ToggleableSearchProvider`가 이미 처리하므로 테이블에 없다. 표는 [refresh] 틱마다 다시 평가된다 —
 * 허용 여부를 저장하지 않으므로(04 D-12) 요청 결과·설정 복귀(ON_RESUME)·자동 회수 뒤에는 홈이 신호를 줘야 한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmsSearchProvider(
    private val hasSms: () -> Boolean,
    private val declined: Flow<Boolean>,
    private val searchSms: suspend (String) -> List<SmsHit>,
) : SearchProvider {

    override val group: ResultGroup = ResultGroup.SMS

    private val refreshTicks = MutableStateFlow(0)

    /**
     * 권한 상태가 바뀌었을 수 있으니 결정 테이블을 다시 평가하라는 신호. 메인 스레드에서 부른다는 전제다
     * (`HomeScreen`의 결과 콜백·ON_RESUME) — 읽고 쓰는 `update`라 경쟁은 없다.
     */
    fun refresh() {
        refreshTicks.update { it + 1 }
    }

    override fun search(query: String): Flow<List<SearchResult>> {
        val q = query.trim()
        if (belowMinimum(q)) return flowOf(emptyList())
        return refreshTicks.flatMapLatest {
            if (hasSms()) {
                searchFlow(q)
            } else {
                declined.distinctUntilChanged().map { isDeclined ->
                    if (isDeclined) emptyList() else listOf(SmsPermissionPrompt)
                }
            }
        }
    }

    private fun searchFlow(q: String): Flow<List<SearchResult>> = flow {
        delay(SMS_DEBOUNCE_MS)
        val hits = try {
            searchSms(q)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        emit(hits.take(MAX_RESULTS).map { it.toResult(q) })
    }

    private fun SmsHit.toResult(q: String): SmsResult {
        val snippet = makeSnippet(body, q)
        return SmsResult(
            id = id,
            title = name ?: address ?: "",
            score = dateMillis.toDouble(),
            address = address,
            dateMillis = dateMillis,
            body = body,
            snippet = snippet.text,
            highlights = snippet.highlights,
        )
    }

    companion object {
        const val MIN_QUERY_CODE_POINTS = 2
        const val MAX_RESULTS = 5

        /**
         * D-10 최소 길이 판정 — trim한 쿼리의 코드 포인트가 [MIN_QUERY_CODE_POINTS] 미만이면 참.
         * [search]와 디버그 지연 프로브가 같은 규칙을 쓰도록 한 곳에 둔다(G-08-5).
         */
        fun belowMinimum(query: String): Boolean {
            val q = query.trim()
            return q.codePointCount(0, q.length) < MIN_QUERY_CODE_POINTS
        }

        // ponytail: 기존 SearchController.DEBOUNCE_MS(80ms) 위에 얹는 튜닝치. flatMapLatest가 이전
        // 쿼리를 취소하므로 비용이 없고, 실기기 체감으로 확정한다(RESEARCH Open Question 3).
        const val SMS_DEBOUNCE_MS = 120L
    }
}
