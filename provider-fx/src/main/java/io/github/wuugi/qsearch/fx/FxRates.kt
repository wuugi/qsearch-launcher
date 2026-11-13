package io.github.wuugi.qsearch.fx

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate

/** 한 통화의 달러당 환율 — 1 USD = [perUsd] 단위 — 과 그 행의 기준 날짜. */
data class FxRate(val perUsd: BigDecimal, val date: LocalDate)

/**
 * 달러 기준 환율 표 — 통화 코드마다 [FxRate] 하나. 어느 통화를 남길지는 이 클래스가 모른다(받은 행을 그대로 담는다) —
 * 거르는 일은 앱의 저장소가 한다.
 *
 * 요청 기준이 USD라 USD는 늘 1이다. [rate]`("USD")`는 표 안의 USD 행을 보지 않고 1과 표에서 가장 새 날짜를 돌려준다
 * (표가 비면 null). 두 통화의 교차 환산이 둘 중 더 오래된 날짜를 쓰더라도, USD가 낀 쪽은 늘 상대 통화의 날짜를 쓴다.
 */
data class FxRates(val rates: Map<String, FxRate>) {

    fun rate(code: String): FxRate? =
        if (code == "USD") rates.values.maxOfOrNull { it.date }?.let { FxRate(BigDecimal.ONE, it) } else rates[code]

    companion object {
        private val CURRENCY_CODE = Regex("^[A-Z]{3}$")

        /** 모든 항목이 같은 [date]인 표 — 테스트와 디버그 심기용. */
        fun of(date: LocalDate, vararg perUsd: Pair<String, BigDecimal>): FxRates =
            FxRates(perUsd.associate { (code, v) -> code to FxRate(v, date) })

        /**
         * Frankfurter v2 `GET /v2/rates?base=USD` 응답 본문(평평한 배열 `[{"date","base","quote","rate"}, …]`)을
         * 표로 바꾼다. 요청은 고정 주소 하나라 `quotes=` 같은 파라미터를 붙이지 않는다(FX-15).
         *
         * **행마다 검사해 나쁜 행만 버린다(FX-14)** — 한 통화 행이 이상하다고 나머지를 버리지 않는다. 행 조건: 객체,
         * base가 정확히 `USD`, quote가 대문자 세 글자, rate가 양의 유한 숫자(문자열 rate는 거부), date가 ISO 날짜.
         * 같은 quote가 두 번 나오면 둘 다 버린다(어느 쪽이 맞는지 알 수 없다). 최상위가 배열이 아니거나(v1 객체·HTML·빈
         * 문자열), 비었거나, 쓸 수 있는 행이 하나도 없으면 null이라 호출자가 좋은 캐시를 그대로 둔다. 어떤 입력에도
         * 예외를 던지지 않는다.
         */
        fun parse(body: String): FxRates? = try {
            val rows = JSONArray(body)
            val map = LinkedHashMap<String, FxRate>()
            val seen = HashSet<String>()
            val dup = HashSet<String>()
            for (i in 0 until rows.length()) {
                // 날짜 형식 오류는 rowOf 안에서 던진다 — runCatching이 그 행 하나로 가둔다.
                val (quote, rate) = runCatching { rows.optJSONObject(i)?.let(::rowOf) }.getOrNull() ?: continue
                if (!seen.add(quote)) dup += quote
                map[quote] = rate
            }
            dup.forEach(map::remove)
            if (map.isEmpty()) null else FxRates(map)
        } catch (e: Exception) {
            // JSONException 흡수 — 배열이 아닌 본문은 null이다.
            null
        }

        /** 한 행 검증. 어긋나면 null, 날짜 형식 오류는 예외(호출자 runCatching이 흡수). */
        private fun rowOf(o: JSONObject): Pair<String, FxRate>? {
            if (o.opt("base") != "USD") return null
            val quote = (o.opt("quote") as? String)?.takeIf { CURRENCY_CODE.matches(it) } ?: return null
            // 플랫폼 org.json은 Double, 표준 org.json은 BigDecimal로 담을 수 있다 — toString → BigDecimal 한 경로.
            val number = o.opt("rate") as? Number ?: return null
            if (number is Double && !number.isFinite()) return null
            if (number is Float && !number.isFinite()) return null
            val rate = BigDecimal(number.toString()).takeIf { it.signum() > 0 } ?: return null
            val date = LocalDate.parse(o.opt("date") as? String ?: return null)
            return quote to FxRate(rate, date)
        }
    }
}
