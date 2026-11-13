package io.github.wuugi.qsearch.fx

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * 저장소 유일의 네트워크 코드(FX-07) — 고정 주소 [RATES_URL]에 GET 요청 하나를 보내 달러 기준 환율표를 받는다.
 * `./gradlew checkSmsIsolation` 규칙 E가 네트워크 API를 이 파일에만 허용하고, URL 리터럴을 이 주소 하나로 묶는다.
 * 주소를 바꾸면 규칙 E의 고정 리터럴과 PRIVACY.md를 같이 고친다.
 *
 * 이 함수를 언제 부를지는 앱의 FxRepository(11-04)가 정한다 — 설정에서 환율 변환을 켜지 않으면 불리지 않는다(D-03).
 */
object FxClient {
    const val RATES_URL = "https://api.frankfurter.dev/v2/rates?base=USD"

    /** 풀린 본문 상한(1MB). 실제 응답은 약 11KB다 — 넘으면 고장이거나 공격이라 읽기를 멈춘다(D-04). */
    private const val MAX_BODY_BYTES = 1_048_576

    /**
     * 환율표를 받아 검증된 [FxRates]로 돌려준다. **인자가 없다** — 입력한 글자·문자·앱 목록이 요청(URL·헤더·본문)에
     * 들어갈 자리가 구조적으로 없다(D-04, FX-07).
     *
     * - 연결 5초·읽기 10초 제한(D-04) — Dispatchers.IO에서만 돌아 검색·홈 스레드를 막지 않는다.
     * - HTTPS 연결이 아니면 보내지 않는다(안전 캐스트가 실패하면 null — 평문 차단).
     * - 리디렉트를 따르지 않고 HTTP 200만 받는다 — 다른 호스트·평문 주소로 끌려가지 않는다.
     * - User-Agent는 고정 문자열이다 — 기본값은 기기 모델·OS 버전을 담는다(PRIVACY.md).
     * - Accept-Encoding을 직접 넣지 않는다 — 플랫폼이 gzip을 풀어 주고 1MB 상한은 풀린 본문에 걸린다.
     * - 본문은 [FxRates.parse]만 보고 버린다 — 로그·보관 없음(규칙 C).
     *
     * 어떤 실패(네트워크·HTTP·크기·모양)도 예외가 아니라 null이다 — 취소만 전파한다. 11-04는 null이면 캐시를
     * 건드리지 않는다(FX-05).
     */
    suspend fun fetch(): FxRates? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(RATES_URL).openConnection() as? HttpsURLConnection ?: return@withContext null
            try {
                conn.connectTimeout = 5_000
                conn.readTimeout = 10_000
                conn.instanceFollowRedirects = false
                conn.useCaches = false
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("User-Agent", "QSearchLauncher")
                if (conn.responseCode != 200) return@withContext null
                val body = ByteArrayOutputStream()
                conn.inputStream.use { input ->
                    val buf = ByteArray(8 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (body.size() + n > MAX_BODY_BYTES) return@withContext null
                        body.write(buf, 0, n)
                    }
                }
                FxRates.parse(body.toString(Charsets.UTF_8.name()))
            } finally {
                conn.disconnect()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
