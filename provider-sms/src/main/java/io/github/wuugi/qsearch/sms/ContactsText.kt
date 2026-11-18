package io.github.wuugi.qsearch.sms

/**
 * 번호 일치의 점수. 번호 일치는 부분 일치라 `:app` `ResultRanker.SCORE_SUBSTRING`과 같은 무게다 — 이 모듈은 그것을
 * import할 수 없어 값을 복제한다.
 */
const val NUMBER_MATCH_SCORE = 0.75

private val NUMBER_SHAPED = Regex("[0-9 \\-()+]+")

/**
 * 번호의 비교용 숫자열 — 숫자만 남기되 `+82`로 시작하면 국가번호를 0으로 바꾼다("+82 10-1818" -> "0101818").
 * shortcut: +82만 0으로 바꾼다, 다른 국가번호 사용자가 요청하면 일반화(A4).
 */
fun normalizePhoneDigits(s: String): String {
    val t = s.trim()
    val digits = { x: String -> x.filter { it in '0'..'9' } }
    return if (t.startsWith("+82")) "0" + digits(t.removePrefix("+82")) else digits(t)
}

/**
 * 쿼리가 번호 검색이면 비교용 숫자열, 아니면 null(D-08). trim한 쿼리가 숫자·공백·하이픈·괄호·`+`만으로 이루어지고
 * 정규화한 숫자가 3개 이상일 때만이다 — 점·별표·문자가 있으면 번호가 아니라서 `100.5`나 `12*3` 같은 계산식이
 * 연락처 검색으로 새지 않는다.
 * shortcut: '1-2-3'처럼 숫자 3개 + 하이픈도 번호 모양으로 본다, 오탐이 보고되면 하이픈 위치를 본다.
 */
fun phoneDigitsOrNull(query: String): String? {
    val t = query.trim()
    if (!NUMBER_SHAPED.matches(t)) return null
    return normalizePhoneDigits(t).takeIf { it.length >= 3 }
}
