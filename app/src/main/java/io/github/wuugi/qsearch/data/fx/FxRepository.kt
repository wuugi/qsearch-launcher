package io.github.wuugi.qsearch.data.fx

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.wuugi.qsearch.data.valueOrNull
import io.github.wuugi.qsearch.fx.FxClient
import io.github.wuugi.qsearch.fx.FxRate
import io.github.wuugi.qsearch.fx.FxRates
import java.io.IOException
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** 환율 캐시 파일. **파일 이름과 아래 키 이름은 저장 계약이다(costly)** — PRIVACY.md가 이름으로 적는다. */
internal val Context.fxRatesDataStore by preferencesDataStore(name = "fx_rates")

/**
 * 받아 둔 환율 표와 받은 시각(벽시계 ms). [legacy]는 이전 버전의 세 키에서 읽은 표라는 표시 — 업데이트 직후 한 번만
 * 24시간 신선도를 건너뛰고 새로 받게 한다(메모리 전용, 저장하지 않는다).
 */
data class FxCache(val rates: FxRates, val fetchedAtMs: Long, val legacy: Boolean = false)

/**
 * 환율 스냅샷의 앱 스코프 소유자(11 D-03·D-05·D-06) — 켜짐 플래그(`search_settings`의 `fx_enabled`)와 디스크 캐시
 * (`fx_rates`)를 메모리 [cache]에 올리고, 검색 경로에는 [usable] 하나만 내준다.
 *
 * - **검색 경로 I/O 없음(SC4·FX-05):** [usable]은 메모리 읽기 하나다. 디스크 읽기는 [init] 수집기가, 쓰기와 받기는 앱 스코프
 *   코루틴이 한다. 생성자는 디스크·네트워크를 기다리지 않는다(BootGuard 5초, UsageStore 관례).
 * - **단일 스레드(Main.immediate) 한정이 메모리 상태 정합성 근거다** — UsageStore와 같은 규약. 공개 함수는 메인
 *   스레드에서 부른다.
 * - **끄면 삭제(D-03):** [setEnabled]`(false)`가 메모리를 먼저 내리고 진행 중인 받기를 취소한 뒤 모든 키를 지운다.
 * - **저장하는 값(PRIVACY.md와 같다):** 통화별 달러당 환율과 통화별 기준 날짜(문자열 하나, `CODE,perUsd,date;…`),
 *   받은 시각, 마지막 시도 시각. 검색어는 저장하지 않는다. 로그 없음. 이전 버전의 세 키(달러당 원·엔·날짜)는 읽기
 *   폴백으로만 남고 다음 저장 때 지운다.
 *
 * **언제 받는가(D-05)** — 모든 경로가 먼저 켜짐·첫 방출 완료·진행 중 아님을 본다:
 *
 * | 시점 | 호출 | 조건 |
 * |---|---|---|
 * | 켤 때 | 수집기(꺼짐→켜짐 방출)·[setEnabled] | [refreshIfStale] — 캐시가 없으니 곧바로 받는다 |
 * | 홈이 다시 보일 때 | `HomeScreen` ON_RESUME → [refreshIfStale] | 받은 지 24시간 이상, 마지막 시도 1시간 이상 |
 * | 다른 앱 위 검색창 화면 진입 | 그 화면의 `onCreate` → [refreshIfStale] | 같음 |
 * | 지금 갱신 | 설정 화면(11-05) → [refreshNow] | 신선도·1시간 대기 무시 |
 * | 업데이트 직후(구 키 캐시) | 위 모든 경로 | 24시간 신선도만 건너뜀, 1시간 대기는 그대로 — 한 번 받으면 새 키로 바뀐다 |
 *
 * WorkManager·주기 작업·부팅 수신기 없음 — 상주 홈 프로세스의 resume이 하루 한 번을 충분히 만든다(STACK 2d).
 *
 * 생성자 인자 넷(fetch·now·scope·dataStore)은 JVM 테스트가 바꿔 끼운다 — Context 없이 돈다.
 */
class FxRepository(
    internal val dataStore: DataStore<Preferences>,
    enabledFlag: Flow<Boolean>,
    private val writeEnabledFlag: suspend (Boolean) -> Boolean,
    /** 저장할 통화 코드(앱의 통화 표) — 받은 응답은 이 코드로 거른다. 요청에는 아무것도 싣지 않는다(FX-15). */
    private val supported: Set<String>,
    private val fetch: suspend () -> FxRates? = { FxClient.fetch() },
    private val now: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    /** 환율 행을 보여 줄 검색 항목("계산·변환")이 켜져 있는지. 꺼져 있으면 켜짐 플래그가 true여도 받지 않는다. */
    usedFlag: Flow<Boolean> = flowOf(true),
) {

    private val _cache = MutableStateFlow<FxCache?>(null)

    /** 디스크 캐시의 거울(설정 화면의 기준 날짜·받은 시각 표시용). 켜짐 여부와 무관하다 — 검색은 [usable]을 쓴다. */
    val cache: StateFlow<FxCache?> = _cache.asStateFlow()

    private val _refreshing = MutableStateFlow(false)

    /** 받는 중이면 true(설정 화면의 "갱신 중" 표시용). */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    @Volatile
    private var enabled = false
    private var loaded = false

    /** 마지막으로 본 플래그 값 — 플래그가 실제로 바뀔 때만 [enabled]를 따른다(아래 [init]). */
    private var lastFlag: Boolean? = null

    /** 마지막 받기 시도 시각 — 디스크 `fx_last_attempt`의 거울이자, 쓰기가 닿기 전에도 1시간 대기를 지키는 메모리 값. */
    private var lastAttemptMs: Long? = null
    private var refreshJob: Job? = null
    private var used = true

    init {
        scope.launch {
            usedFlag.collect { on ->
                used = on
                if (enabled) refreshIfStale()
            }
        }
        scope.launch {
            combine(
                enabledFlag,
                dataStore.data.catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error },
            ) { on, prefs -> on to prefs }
                .collect { (on, prefs) ->
                    // 캐시만 바뀐 재방출(예: 끄는 중의 삭제)이 이미 내린 메모리 플래그를 옛 값 true로 되돌리지 않게 한다.
                    if (on != lastFlag) {
                        lastFlag = on
                        enabled = on
                    }
                    _cache.value = decode(prefs)
                    prefs.valueOrNull(KEY_LAST_ATTEMPT)?.let { lastAttemptMs = it }
                    loaded = true
                    // 꺼짐→켜짐 전환이 곧 "켤 때 한 번"이다. 우리 쓰기로 인한 재방출은 신선도·대기 조건에 걸려 아무것도 하지 않는다.
                    if (enabled) refreshIfStale()
                }
        }
    }

    /**
     * **검색 경로가 부르는 유일한 함수** — suspend가 아니고 메모리만 읽는다(SC4·FX-05). 꺼져 있으면 받아 둔 값이 있어도
     * null이라 환율 행이 생기지 않는다(FX-01·D-09). 받은 값이 없어도 null(FX-04, 캐시 없으면 행 없음).
     */
    fun usable(): FxRates? = if (enabled) _cache.value?.rates else null

    /**
     * 받은 지 24시간이 지났고(또는 캐시 없음) 마지막 시도가 1시간 이전일 때만 받기를 시작한다 — 아니면 메모리 비교만 하고
     * 끝난다. suspend가 아니다: 받기는 앱 스코프에서 비동기로 돈다. 메인 스레드에서 부른다.
     */
    fun refreshIfStale() {
        if (!canStart()) return
        val t = now()
        val cache = _cache.value
        if (cache != null && !cache.legacy && !elapsed(cache.fetchedAtMs, t, FRESH_MS)) return
        if (lastAttemptMs?.let { elapsed(it, t, RETRY_MS) } == false) return
        startRefresh(t)
    }

    /** 신선도·1시간 대기를 무시하고 받는다(설정의 "지금 갱신", 11-05). 꺼져 있거나 받는 중이면 아무 일도 없다. */
    fun refreshNow() {
        if (canStart()) startRefresh(now())
    }

    /**
     * 켜고 끄는 유일한 길(설정 화면 11-05·계측). 끌 때는 메모리 플래그를 **먼저** 내려 곧바로 [usable]이 null이 되게 하고,
     * 진행 중인 받기를 취소한 뒤 플래그를 쓰고 캐시를 지운다(D-03). 지우기가 `fx_last_attempt`까지 지우므로 다시 켜면 곧바로
     * 받는다. 켤 때는 플래그를 쓰고, 성공하면 곧바로 확인한다(수집기도 같은 확인을 한다 — 중복은 진행 중 검사가 막는다).
     * 반환은 플래그 쓰기 결과.
     */
    suspend fun setEnabled(on: Boolean): Boolean {
        if (!on) {
            enabled = false
            refreshJob?.cancel()
            _refreshing.value = false
            lastAttemptMs = null
            val written = writeEnabledFlag(false)
            save(null)
            return written
        }
        val written = writeEnabledFlag(true)
        if (written) {
            enabled = true
            refreshIfStale()
        }
        return written
    }

    private fun canStart() = enabled && used && loaded && refreshJob?.isActive != true

    // ponytail: 실기기 시계는 역행할 수 있다, 역행 시 한 번 더 받는 비용이 영영 안 받는 위험보다 작다.
    private fun elapsed(since: Long, t: Long, span: Long) = t < since || t - since >= span

    /**
     * 받기 본체 하나. 시도 시각을 **먼저** 기록한다(메모리·디스크) — 받기가 실패하거나 프로세스가 죽어도 재시도가 폭주하지
     * 않는다(PITFALLS 10). 결과가 null이면 아무것도 덮지 않는다(D-04 좋은 캐시 보존). 끈 뒤에 끝난 받기는 쓰지 않는다 —
     * 취소 확인과 메모리 플래그 확인 둘 다 거친다(취소를 무시하는 받기라도).
     */
    private fun startRefresh(t: Long) {
        lastAttemptMs = t
        _refreshing.value = true
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                edit { it[KEY_LAST_ATTEMPT] = t }
                val rates = try {
                    fetch()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null // FxClient는 던지지 않지만, 상주 홈의 앱 스코프에서 새는 예외는 곧 크래시다.
                }
                coroutineContext.ensureActive()
                if (rates != null && enabled) merge(rates, _cache.value?.rates)?.let { save(FxCache(it, now())) }
            } finally {
                if (refreshJob === coroutineContext[Job]) _refreshing.value = false
            }
        }
        refreshJob = job
        job.start()
    }

    /**
     * 한 번의 `dataStore.edit`로 쓴다 — 반쯤 쓴 표가 남지 않는다. null이면 모든 키를 지운다. 성공하면 메모리 거울도
     * 곧바로 맞춘다(수집기 재방출을 기다리지 않는다).
     */
    internal suspend fun save(value: FxCache?) {
        val written = edit { prefs ->
            if (value == null) {
                ALL_KEYS.forEach { prefs.remove(it) }
            } else {
                prefs[KEY_FX_RATES] = encodeRates(value.rates)
                prefs.remove(LEGACY_KRW_PER_USD)
                prefs.remove(LEGACY_JPY_PER_USD)
                prefs.remove(LEGACY_RATE_DATE)
                prefs[KEY_FETCHED_AT] = value.fetchedAtMs
            }
        }
        if (written) _cache.value = value
    }

    /**
     * 받은 표를 지원 통화로 거르고 이전 표와 합친다 — USD 제외 지원 코드마다 새 값이 범위 안이면 새 값, 아니면 이전 값(날짜
     * 포함). 새 응답에서 쓸 수 있는 지원 행이 하나도 없으면 null이라 호출자는 아무것도 쓰지 않는다(받은 시각도 그대로).
     */
    internal fun merge(fresh: FxRates, old: FxRates?): FxRates? {
        val kept = LinkedHashMap<String, FxRate>()
        var anyFresh = false
        for (code in supported) {
            if (code == "USD") continue // USD는 늘 1, 저장하지 않는다
            val new = fresh.rates[code]?.takeIf { inRange(it.perUsd) }
            if (new != null) anyFresh = true
            kept[code] = new ?: old?.rates?.get(code) ?: continue
        }
        return if (anyFresh) FxRates(kept) else null
    }

    /** 디스크 실패는 흡수해 false(다음 받기가 다시 쓴다), 취소는 전파한다. */
    private suspend fun edit(transform: (MutablePreferences) -> Unit): Boolean = try {
        dataStore.edit { transform(it) }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /**
     * 디스크 값을 캐시로 — 받은 시각이 없거나 쓸 수 있는 항목이 하나도 없으면 캐시 없음(null). 새 키 `fx_rates`를 먼저
     * 읽고, 없으면 이전 버전의 세 키(원·엔·날짜)로 KRW·JPY 두 항목짜리 표를 만든다. 어떤 값에도 던지지 않는다 — 상주
     * 홈이 여기서 죽지 않는다(T-08-20).
     */
    private fun decode(prefs: Preferences): FxCache? = runCatching {
        val fetchedAt = prefs.valueOrNull(KEY_FETCHED_AT) ?: return null
        val current = prefs.valueOrNull(KEY_FX_RATES)?.let(::decodeRates)
        val table = current ?: decodeLegacy(prefs)
        table?.let { FxCache(it, fetchedAt, legacy = current == null) }
    }.getOrNull()

    private fun decodeLegacy(prefs: Preferences): FxRates? {
        val krw = prefs.valueOrNull(LEGACY_KRW_PER_USD) ?: return null
        val jpy = prefs.valueOrNull(LEGACY_JPY_PER_USD) ?: return null
        val date = LocalDate.parse(prefs.valueOrNull(LEGACY_RATE_DATE) ?: return null)
        val k = BigDecimal(krw)
        val j = BigDecimal(jpy)
        if (!inRange(k) || !inRange(j)) return null
        return FxRates(mapOf("KRW" to FxRate(k, date), "JPY" to FxRate(j, date)))
    }

    companion object {
        private const val FRESH_MS = 24 * 60 * 60 * 1000L
        private const val RETRY_MS = 60 * 60 * 1000L

        /**
         * 통화별 환율 표 — DataStore 파일 `fx_rates` 안의 키 `fx_rates`, 값은 `CODE,perUsd,date`를 `;`로 이은 문자열.
         * **저장 계약이다(costly)** — PRIVACY.md가 이름으로 적는다.
         */
        val KEY_FX_RATES = stringPreferencesKey("fx_rates")

        /** 읽기 폴백과 삭제에만 쓴다, 새로 쓰지 않는다. */
        val LEGACY_KRW_PER_USD = stringPreferencesKey("fx_krw_per_usd")

        /** 읽기 폴백과 삭제에만 쓴다, 새로 쓰지 않는다. */
        val LEGACY_JPY_PER_USD = stringPreferencesKey("fx_jpy_per_usd")

        /** 읽기 폴백과 삭제에만 쓴다, 새로 쓰지 않는다. */
        val LEGACY_RATE_DATE = stringPreferencesKey("fx_rate_date")
        val KEY_FETCHED_AT = longPreferencesKey("fx_fetched_at")
        val KEY_LAST_ATTEMPT = longPreferencesKey("fx_last_attempt")
        private val ALL_KEYS = listOf(
            KEY_FX_RATES, LEGACY_KRW_PER_USD, LEGACY_JPY_PER_USD, LEGACY_RATE_DATE, KEY_FETCHED_AT, KEY_LAST_ATTEMPT,
        )

        /**
         * 달러당 값의 허용 범위(가정 A4) — 측정한 15개 값은 0.74~25,653이다. 시세 변동이 아니라 역수·거대 지수 같은 모양
         * 변화를 잡는 상식 범위다. 네트워크와 디스크가 같은 검사를 지난다.
         */
        val MIN_PER_USD = BigDecimal("0.0001")
        val MAX_PER_USD = BigDecimal("10000000")

        private fun inRange(v: BigDecimal) = v >= MIN_PER_USD && v <= MAX_PER_USD

        private val CODE = Regex("^[A-Z]{3}$")

        /** 표를 저장 문자열로 — 항목은 맵 순서대로 `CODE,perUsd,date`, `;`로 잇는다. */
        internal fun encodeRates(rates: FxRates): String =
            rates.rates.entries.joinToString(";") { (code, r) -> "$code,${r.perUsd.toPlainString()},${r.date}" }

        /**
         * 저장 문자열을 표로 — 항목마다 따로 검사해 나쁜 항목(필드 수·코드·양수·날짜)만 건너뛴다. 쓸 항목이 없으면 null.
         * 손편집·다운그레이드·깨진 값에도 던지지 않는다.
         */
        internal fun decodeRates(text: String): FxRates? {
            val map = LinkedHashMap<String, FxRate>()
            for (entry in text.split(';')) {
                runCatching {
                    val f = entry.split(',')
                    if (f.size != 3 || !CODE.matches(f[0])) return@runCatching
                    val v = BigDecimal(f[1])
                    if (!inRange(v)) return@runCatching
                    map[f[0]] = FxRate(v, LocalDate.parse(f[2]))
                }
            }
            return if (map.isEmpty()) null else FxRates(map)
        }
    }
}
