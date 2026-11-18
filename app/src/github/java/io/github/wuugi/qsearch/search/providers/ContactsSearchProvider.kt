package io.github.wuugi.qsearch.search.providers

import io.github.wuugi.qsearch.search.ContactResult
import io.github.wuugi.qsearch.search.ContactsPermissionPrompt
import io.github.wuugi.qsearch.search.ResultGroup
import io.github.wuugi.qsearch.search.SearchProvider
import io.github.wuugi.qsearch.search.SearchResult
import io.github.wuugi.qsearch.search.ranking.ResultRanker
import io.github.wuugi.qsearch.sms.ContactHit
import io.github.wuugi.qsearch.sms.phoneDigitsOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * 연락처 검색을 `SearchEngine`에 꽂는 얇은 어댑터. 시스템 연락처 provider 조회는 전부 `:provider-sms`의
 * `ContactsSearcher`가 하고, 이 클래스는 [scan]·[hasContacts] 람다로만 그것을 안다 — `:app`에는 연락처 API 토큰이
 * 없다. 블로킹 호출을 하지 않는다(조회는 IO로 옮겨진다).
 *
 * - **D-07:** 이름은 1글자부터, 퍼지 없이(`fuzzy = false`) 접두·부분·초성으로만 점수를 매긴다.
 * - **D-08:** 쿼리가 번호 모양(숫자 3개 이상, 공백·하이픈·괄호·`+`만)이면 [phoneDigitsOrNull]이 비교용 숫자열을 넘겨 번호로도 찾는다.
 * - **D-09:** 추가 지연 없이 바로 조회한다(컨트롤러의 80ms 디바운스가 이미 쿼리 전체를 디바운스한다). 결과는 [MAX_RESULTS]건까지.
 * - **D-12:** 권한은 수집마다 [hasContacts]로 시스템에 묻고 저장하지 않는다. 조회 예외(권한 회수의 `SecurityException`
 *   포함)는 빈 리스트로 접는다 — 취소만 다시 던진다. 빈 쿼리는 조회 없이 즉시 빈 리스트다(미응답 null이면 웹 폴백이 기다린다).
 * - **결정 테이블(D-02, 쿼리는 trim 후):**
 *
 *   | 권한 | 쿼리 | 거부 플래그 | 방출 |
 *   |---|---|---|---|
 *   | - | 빈 문자열 | - | `[]` (조회 없음) |
 *   | 있음 | 1자 이상 | 무시 | 조회 결과 |
 *   | 없음 | 1 코드 포인트 | - | `[]` |
 *   | 없음 | 2 코드 포인트 이상 | true | `[]` |
 *   | 없음 | 2 코드 포인트 이상 | false | `[ContactsPermissionPrompt]` |
 *
 *   [declined]는 수집 안에서 계속 관찰한다 -- 플래그가 바뀌면 같은 수집이 다시 방출한다.
 *
 * - 프롬프트를 웹 행 아래로 옮기는 일은 `injectWebFallback`의 몫이다.
 *
 * [refresh]는 권한 상태가 바뀌었을 수 있으니 다시 평가하라는 신호다(홈의 ON_RESUME·권한 콜백이 부른다).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactsSearchProvider(
    private val hasContacts: () -> Boolean,
    private val declined: Flow<Boolean>,
    private val scan: suspend (nameScore: (String) -> Double?, phoneDigits: String?, limit: Int) -> List<ContactHit>,
) : SearchProvider {

    override val group: ResultGroup = ResultGroup.CONTACTS

    private val refreshTicks = MutableStateFlow(0)

    /** 메인 스레드에서 부른다는 전제다 -- 읽고 쓰는 `update`라 경쟁은 없다. */
    fun refresh() {
        refreshTicks.update { it + 1 }
    }

    override fun search(query: String): Flow<List<SearchResult>> {
        val q = query.trim()
        if (q.isEmpty()) return flowOf(emptyList())
        return refreshTicks.flatMapLatest {
            if (hasContacts()) {
                searchFlow(q)
            } else if (q.codePointCount(0, q.length) < PROMPT_MIN_CODE_POINTS) {
                flowOf(emptyList())
            } else {
                declined.distinctUntilChanged().map { if (it) emptyList() else listOf(ContactsPermissionPrompt) }
            }
        }
    }

    private fun searchFlow(q: String): Flow<List<SearchResult>> = flow {
        val hits = try {
            scan({ name -> ResultRanker.score(q, name, fuzzy = false) }, phoneDigitsOrNull(q), MAX_RESULTS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        emit(
            hits.take(MAX_RESULTS).map {
                ContactResult(
                    id = "contact:" + it.lookupKey,
                    title = it.name,
                    score = it.score,
                    number = it.number,
                    lookupUri = it.lookupUri,
                )
            },
        )
    }

    companion object {
        const val MAX_RESULTS = 5

        /** 권한 프롬프트가 나오는 최소 쿼리 길이(코드 포인트) -- 첫 글자마다 깜박이지 않게 문자 프롬프트와 같다. 권한이 있으면 이름 검색은 1자부터(D-07). */
        const val PROMPT_MIN_CODE_POINTS = 2
    }
}
