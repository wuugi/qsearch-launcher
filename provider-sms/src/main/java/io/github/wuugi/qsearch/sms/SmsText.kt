package io.github.wuugi.qsearch.sms

import java.time.Instant
import java.time.ZoneId

/**
 * 일치 앞 문맥 최대 글자 수. UI-SPEC 비대칭 창 — 한 줄에 한글 28~32자만 들어가므로 RESEARCH의 대칭
 * 24자는 앞이 길 때 일치가 줄 밖으로 밀린다. 앞을 짧게 두면 30자 이하 쿼리는 늘 한 줄 안에서 보인다.
 */
const val SNIPPET_LEAD = 10

/** 일치 끝 뒤 문맥 최대 글자 수(끝 말줄임은 Text가 처리한다). */
const val SNIPPET_TRAIL = 40

/** [text] 안의 [highlights]는 `first..last` 포함 구간(UTF-16 좌표)이다. */
data class Snippet(val text: String, val highlights: List<IntRange>)

private val WHITESPACE_RUN = Regex("\\s+")

private fun fold(s: String): String = s.replace(WHITESPACE_RUN, " ").trim()

/**
 * [body]에서 [query] 첫 일치 주변의 한 줄 조각과 일치 구간을 만든다(D-08). 공백(개행·탭 포함)은 하나로
 * 접는다. 앞이 잘리면 '…'로 시작한다. 창 경계가 서로게이트 쌍을 가르지 않는다(Pitfall 8). 못 찾으면
 * (LIKE는 맞았지만 Kotlin 비교가 못 찾는 경우 — Pitfall 7c) 앞부분을 강조 없이 돌려준다.
 */
fun makeSnippet(body: String, query: String): Snippet {
    val folded = fold(body)
    val q = fold(query)
    val pos = if (q.isEmpty()) -1 else folded.indexOf(q, ignoreCase = true)
    if (pos < 0) {
        var end = minOf(folded.length, SNIPPET_LEAD + SNIPPET_TRAIL)
        if (end in 1 until folded.length && Character.isLowSurrogate(folded[end])) end++
        return Snippet(folded.substring(0, end), emptyList())
    }

    var start = maxOf(0, pos - SNIPPET_LEAD)
    var end = minOf(folded.length, pos + q.length + SNIPPET_TRAIL)
    if (start > 0 && Character.isLowSurrogate(folded[start])) start--
    if (end < folded.length && Character.isLowSurrogate(folded[end])) end++

    val window = folded.substring(start, end)
    val prefix = if (start > 0) "…" else ""
    val highlights = mutableListOf<IntRange>()
    var from = 0
    while (true) {
        val at = window.indexOf(q, from, ignoreCase = true)
        if (at < 0) break
        highlights += (prefix.length + at)..(prefix.length + at + q.length - 1)
        from = at + q.length
    }
    return Snippet(prefix + window, highlights)
}

/**
 * `smsto:`로 열어도 되는 주소인가(D-13, Pitfall 10) -- 숫자가 하나 이상 있고 숫자와 `+-() *#` 외 문자가 없는
 * 것만. 알파넘 발신자("KB국민카드")·`insert-address-token`·빈 값은 거짓이라 D-14 복사 폴백으로 간다.
 *
 * ponytail: 그룹 MMS는 주소 기준이라 1:1 대화로 열린다. `smsto:`가 기존 스레드를 여는지 새 작성 화면을
 * 여는지는 문자 앱 구현에 달렸다(RESEARCH A6) -- 08-06 실기기 확인 항목.
 */
internal fun isDialable(address: String?): Boolean {
    if (address.isNullOrBlank()) return false
    return address.any { it in '0'..'9' } && address.all { it in '0'..'9' || it in "+-() *#" }
}

/**
 * 날짜 표시 골격(UI-SPEC Date format) — 같은 날이면 시각 `"jm"`, 같은 해면 `"MMMd"`, 아니면 `"yMd"`.
 * 비교는 [zone] 기준 달력 날짜다(UTC로는 어제여도 기기 시간대로는 오늘일 수 있다). 골격을 실제 패턴으로
 * 바꾸는 `getBestDateTimePattern`은 Android API라 호출부(UI)의 몫이다.
 */
fun dateSkeleton(messageMillis: Long, nowMillis: Long, zone: ZoneId): String {
    val message = Instant.ofEpochMilli(messageMillis).atZone(zone).toLocalDate()
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    return when {
        message == now -> "jm"
        message.year == now.year -> "MMMd"
        else -> "yMd"
    }
}
