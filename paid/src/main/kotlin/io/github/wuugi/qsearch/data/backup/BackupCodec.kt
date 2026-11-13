package io.github.wuugi.qsearch.data.backup

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import io.github.wuugi.qsearch.data.drawer.AppDrawerGrid
import io.github.wuugi.qsearch.data.favorites.FavoritesGrid
import io.github.wuugi.qsearch.data.favorites.FavoritesStore
import io.github.wuugi.qsearch.data.settings.HomeSettingsStore
import io.github.wuugi.qsearch.data.settings.HomeTileMaterial
import io.github.wuugi.qsearch.data.settings.SearchSettingsStore
import io.github.wuugi.qsearch.data.settings.UnreadDotColor
import io.github.wuugi.qsearch.data.valueOrNull
import io.github.wuugi.qsearch.search.KeyCombo
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 검증이 끝난 가져오기 계획. 저장소마다 [MutablePreferences] 에 적용할 편집(키 하나를 쓰거나 지우기)의 목록이고,
 * 사용자가 복원을 누르기 전에는 아무것도 실행되지 않는다. 세 숫자는 요약 화면이 보여 준다.
 */
internal class RestorePlan(
    val favorites: List<(MutablePreferences) -> Unit>,
    val home: List<(MutablePreferences) -> Unit>,
    val search: List<(MutablePreferences) -> Unit>,
    val favoritesRestored: Int,
    val appsSkipped: Int,
    val unusable: Int,
)

/**
 * 백업 파일 형식. **이 형식은 공개 계약이다(D-08, 되돌릴 수 없음)** -- 빌드가 한 번 배포되면 사용자 손에 파일이 남으므로
 * 필드 이름·[APP_ID]·[FORMAT]·null 규칙을 바꿀 수 없다. 바꿔야 하면 [FORMAT] 을 올린다.
 *
 * - 그룹 이름은 DataStore 파일 이름, 필드 이름은 DataStore 키 이름 그대로다(저장소 상수의 `.name`).
 * - null 은 "설정한 적 없음"이다. 가져오기는 그 키를 지운다(D-11). 필드가 아예 없으면 "현재 값을 그대로 둔다"(D-04).
 * - 어떤 키가 들어가는지는 [BackupContract] 가 정하고, `BackupCodecTest` 가 이 인코더와 계약이 어긋나면 실패시킨다.
 * - 암호화·압축을 하지 않는다 -- 사용자가 열어서 읽을 수 있는 UTF-8 JSON 이다(오픈소스 신뢰).
 */
internal object BackupCodec {
    /** 어느 빌드(무료·Play·GitHub)에서 만든 파일이든 같아야 하므로 applicationId 가 아니라 고정 상수다(D-12). */
    const val APP_ID = "qsearch"
    const val FORMAT = 1
    const val MIME = "application/json"

    /** 실제 파일은 몇 KB 다. 가져오기(15-03)가 이 크기를 넘는 파일을 거절한다. */
    const val MAX_BYTES = 256 * 1024

    /** 이 폰에서 값이 쓸 수 있는지 판정하는 근거. 파일을 읽는 쪽(설정 화면)이 현재 폰에서 채운다. */
    class Env(
        val installedIds: Set<String>,
        val themeIds: Set<String>,
        val currencyCodes: Set<String>,
        /** 이 폰의 검색 결과 스위치 id(레지스트리 항목과 웹 폴백). 모르는 id 는 쓸 수 없는 값이다. */
        val searchIds: Set<String>,
        /** 이 폰에 설치된 아이콘 팩의 패키지 이름. */
        val iconPackIds: Set<String>,
    )

    /** 가져오기를 거절하는 네 가지 이유(D-07). */
    enum class Reject { CORRUPT, OTHER_APP, TOO_LARGE, NEWER_FORMAT }

    sealed interface Decoded {
        class Ok(val plan: RestorePlan) : Decoded
        class Rejected(val reason: Reject) : Decoded
    }

    private val ICON_PACK_SHAPE = Regex("[A-Za-z0-9_.]{1,255}")

    /**
     * 파일을 읽고 **전부 검증한 뒤에야** [RestorePlan] 을 만든다(D-00d) -- 저장소는 여기서 건드리지 않는다.
     *
     * - 앞문: 256KB 상한(제공자가 알려 준 크기를 믿지 않고 읽은 바이트를 센다), JSON 객체 하나이고 그 뒤에 아무것도 없음,
     *   `app` 이 [APP_ID], `format` 이 1..[FORMAT] 인 정수. 이유는 [Reject] 의 넷이다.
     * - 그룹이 없으면 그 그룹은 건드리지 않는다(D-04). 필드가 없으면 현재 값을 그대로 둔다(D-04).
     * - JSON null 은 그 키를 지운다(D-11). 기본값으로 돌아가는 것이다.
     * - 모양은 맞지만 이 폰이 쓸 수 없는 값(모르는 테마, 범위 밖 열 수, 없는 통화, 쓸 수 없는 진입 조합, 설치되지 않은 아이콘 팩, 모르는 검색 id, 틀린 JSON 형)은
     *   건너뛰고 현재 값을 두며 [RestorePlan.unusable] 로 센다(D-05). 값을 형 변환하지 않는다.
     * - 표에 없는 필드(제외 키 이름 포함)는 읽지 않는다 -- 조작된 파일이 동의·권한 상태를 심을 수 없다(D-06).
     * - 즐겨찾기는 파일의 배치로 바꾼다. 앱이 없는 항목은 빈 칸으로 두고 센다(D-02). 파일의 배열이 정말 비었을 때만 빈
     *   칸 키를 쓴다(지우면 옛 `favorite_ids` 이관이 예전 즐겨찾기를 되살린다). 파일에 항목이 있는데 하나도 못 살리면 현재 배치를 둔다.
     * - 목록(숨긴 앱·꺼 둔 검색)은 합치지 않고 바꾼다(D-03). 쉼표가 든 원소는 저장 형식을 깨므로 버린다.
     * - 읽기 오류([java.io.IOException])는 호출한 쪽으로 보낸다.
     */
    fun decode(input: InputStream, env: Env): Decoded {
        val bytes = readBounded(input) ?: return Decoded.Rejected(Reject.TOO_LARGE)
        return try {
            parse(bytes, env)
        } catch (_: JSONException) {
            Decoded.Rejected(Reject.CORRUPT)
        } catch (_: RuntimeException) {
            Decoded.Rejected(Reject.CORRUPT)
        } catch (_: StackOverflowError) {
            // 깊게 중첩된 파일. Error 라 잡지 않으면 CrashHandler 까지 가서 상주 프로세스가 죽고 BootGuard 에 센다.
            Decoded.Rejected(Reject.CORRUPT)
        }
    }

    /** [MAX_BYTES] 까지만 읽는다. 넘으면 null. 전체 읽기 도우미는 상한이 없고 하나는 API 33 이라 쓰지 않는다. */
    private fun readBounded(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun parse(bytes: ByteArray, env: Env): Decoded {
        var text = String(bytes, Charsets.UTF_8)
        if (text.startsWith('\uFEFF')) text = text.substring(1) // 윈도 편집기가 붙인다
        val tokener = JSONTokener(text)
        val root = tokener.nextValue() as? JSONObject ?: return Decoded.Rejected(Reject.CORRUPT)
        // 안드로이드의 JSONObject(String) 은 값 하나 뒤의 내용을 무시한다. 뒤에 뭐가 있는지 보는 건 우리 몫이다.
        if (tokener.nextClean() != '\u0000') return Decoded.Rejected(Reject.CORRUPT)
        if (root.opt("app") != APP_ID) return Decoded.Rejected(Reject.OTHER_APP)
        val format = root.opt("format") as? Int
        if (format == null || format < 1) return Decoded.Rejected(Reject.CORRUPT)
        if (format > FORMAT) return Decoded.Rejected(Reject.NEWER_FORMAT)

        fun group(store: BackupContract.Store): JSONObject? {
            if (!root.has(store.file)) return null
            return root.opt(store.file) as? JSONObject ?: throw JSONException("group ${store.file}")
        }
        val favoritesGroup = group(BackupContract.Store.FAVORITES)
        val homeGroup = group(BackupContract.Store.HOME)
        val searchGroup = group(BackupContract.Store.SEARCH)

        val b = PlanBuilder(env)
        favoritesGroup?.let { b.favorites(it) }
        homeGroup?.let { b.home(it) }
        searchGroup?.let { b.search(it) }
        return Decoded.Ok(b.build())
    }

    private class PlanBuilder(val env: Env) {
        private val favoritesEdits = mutableListOf<(MutablePreferences) -> Unit>()
        private val homeEdits = mutableListOf<(MutablePreferences) -> Unit>()
        private val searchEdits = mutableListOf<(MutablePreferences) -> Unit>()
        private var restored = 0
        private var skipped = 0
        private var unusable = 0

        fun build() = RestorePlan(favoritesEdits, homeEdits, searchEdits, restored, skipped, unusable)

        fun favorites(g: JSONObject) {
            val name = FavoritesStore.KEY_FAVORITE_CELLS.name
            if (!g.has(name)) return
            val array = g.opt(name) as? JSONArray ?: run { unusable++; return }
            val cells = LinkedHashMap<String, Int>()
            val seenIds = HashSet<String>()
            val seenSlots = HashSet<Int>()
            for (i in 0 until array.length()) {
                val entry = array.opt(i) as? JSONObject
                val id = (entry?.opt("id") as? String)?.takeIf { it.isNotEmpty() }
                val slot = (entry?.opt("slot") as? Int)?.takeIf { it in 0 until FavoritesGrid.MAX_CELLS }
                if (id == null || slot == null || id in seenIds || slot in seenSlots) {
                    unusable++
                    continue
                }
                seenIds += id
                seenSlots += slot
                if (id in env.installedIds) cells[id] = slot else skipped++
            }
            // 파일에 즐겨찾기가 있는데 이 폰에서 하나도 못 살리면(다른 폰의 백업, 앱 목록 로드 실패) 현재 배치를 지우지 않고 둔다.
            if (cells.isEmpty() && array.length() > 0) return
            restored = cells.size
            val encoded = FavoritesGrid.encode(cells)
            favoritesEdits += { it[FavoritesStore.KEY_FAVORITE_CELLS] = encoded }
        }

        fun home(g: JSONObject) {
            scalar(g, HomeSettingsStore.KEY_SHOW_UNREAD_DOT, homeEdits) { it as? Boolean }
            scalar(g, HomeSettingsStore.KEY_UNREAD_DOT_COLOR, homeEdits) { v -> (v as? String)?.takeIf { s -> UnreadDotColor.entries.any { it.name == s } } }
            scalar(g, HomeSettingsStore.KEY_HOME_TILE_MATERIAL, homeEdits) { v -> (v as? String)?.takeIf { s -> HomeTileMaterial.entries.any { it.name == s } } }
            scalar(g, HomeSettingsStore.KEY_DRAWER_COLUMNS, homeEdits) { v ->
                (v as? Int)?.takeIf { it in AppDrawerGrid.MIN_COLUMNS..AppDrawerGrid.MAX_COLUMNS }
            }
            idList(g, HomeSettingsStore.KEY_HIDDEN_APP_IDS, homeEdits)
            scalar(g, HomeSettingsStore.KEY_ICON_PACK, homeEdits) { v -> (v as? String)?.takeIf { ICON_PACK_SHAPE.matches(it) && it in env.iconPackIds } }
            scalar(g, HomeSettingsStore.KEY_DOUBLE_TAP_LOCK, homeEdits) { it as? Boolean }
            scalar(g, HomeSettingsStore.KEY_WALLPAPER_GESTURE, homeEdits) { it as? Boolean }
            scalar(g, HomeSettingsStore.KEY_NOTIFICATION_GESTURE, homeEdits) { it as? Boolean }
            scalar(g, HomeSettingsStore.KEY_THEME_ID, homeEdits) { v -> (v as? String)?.takeIf { it in env.themeIds } }
        }

        fun search(g: JSONObject) {
            entryCombo(g)
            idList(g, SearchSettingsStore.KEY_DISABLED_SEARCH_IDS, searchEdits, env.searchIds)
            scalar(g, SearchSettingsStore.KEY_SEARCH_BAR_AT_BOTTOM, searchEdits) { it as? Boolean }
            scalar(g, SearchSettingsStore.KEY_EMPTY_BACKSPACE_HINT, searchEdits) { it as? Boolean }
            scalar(g, SearchSettingsStore.KEY_FX_BASE_CURRENCY, searchEdits) { v -> (v as? String)?.takeIf { it in env.currencyCodes } }
        }

        /** 없으면 그대로, null 이면 지우고, [accept] 가 받아들인 값이면 쓰고, 아니면 건너뛰며 센다. 형 변환은 없다. */
        private fun <T : Any> scalar(
            g: JSONObject,
            key: Preferences.Key<T>,
            edits: MutableList<(MutablePreferences) -> Unit>,
            accept: (Any) -> T?,
        ) {
            if (!g.has(key.name)) return
            val v = g.opt(key.name)
            if (v === JSONObject.NULL) {
                edits += { it.remove(key) }
                return
            }
            val value = v?.let(accept)
            if (value == null) unusable++ else edits += { it[key] = value }
        }

        /** [allowed] 가 있으면 그 밖의 원소는 쓸 수 없는 값으로 세고 버린다(없으면 모양만 본다). */
        private fun idList(
            g: JSONObject,
            key: Preferences.Key<String>,
            edits: MutableList<(MutablePreferences) -> Unit>,
            allowed: Set<String>? = null,
        ) {
            if (!g.has(key.name)) return
            val array = g.opt(key.name) as? JSONArray ?: run { unusable++; return }
            val kept = LinkedHashSet<String>()
            for (i in 0 until array.length()) {
                val e = array.opt(i)
                // 쉼표는 저장 형식의 구분자라 든 원소는 저장할 수 없다.
                if (e is String && e.isNotEmpty() && ',' !in e && (allowed == null || e in allowed)) kept += e else unusable++
            }
            val joined = kept.joinToString(",")
            edits += { it[key] = joined }
        }

        /** 키 코드와 메타 상태는 한 쌍이다: 둘 다 null 이면 지우고, 둘 다 쓸 수 있는 정수면 쓰고, 그 밖의 조합은 하나로 센다. */
        private fun entryCombo(g: JSONObject) {
            val codeKey = SearchSettingsStore.KEY_ENTRY_KEY_CODE
            val metaKey = SearchSettingsStore.KEY_ENTRY_META_STATE
            if (!g.has(codeKey.name) && !g.has(metaKey.name)) return
            val code = g.opt(codeKey.name)
            val meta = g.opt(metaKey.name)
            when {
                code === JSONObject.NULL && meta === JSONObject.NULL -> searchEdits += { it.remove(codeKey); it.remove(metaKey) }
                code is Int && meta is Int && KeyCombo.isUsableEntryKey(code, meta) ->
                    searchEdits += { it[codeKey] = code; it[metaKey] = meta }
                else -> unusable++
            }
        }
    }

    /** 파일 이름 계약이라 문자열 리소스가 아니다. */
    fun fileName(date: LocalDate): String = "qsearch-backup-$date.json"

    fun encode(favorites: Preferences, home: Preferences, search: Preferences): String = JSONObject().apply {
        put("app", APP_ID)
        put("format", FORMAT)
        put(BackupContract.Store.FAVORITES.file, JSONObject().apply {
            val cells = FavoritesGrid.decode(favorites.valueOrNull(FavoritesStore.KEY_FAVORITE_CELLS))
            put(
                FavoritesStore.KEY_FAVORITE_CELLS.name,
                JSONArray().apply {
                    // 범위 밖 슬롯은 디스크에만 보존되는 점유자(D-08)다. 내보내면 가져올 때 "쓸 수 없는 값"으로 세어진다.
                    cells.entries.filter { it.value in 0 until FavoritesGrid.MAX_CELLS }.sortedBy { it.value }.forEach { (id, slot) ->
                        put(JSONObject().put("id", id).put("slot", slot))
                    }
                },
            )
        })
        put(BackupContract.Store.HOME.file, JSONObject().apply {
            scalar(home, HomeSettingsStore.KEY_SHOW_UNREAD_DOT)
            scalar(home, HomeSettingsStore.KEY_UNREAD_DOT_COLOR)
            scalar(home, HomeSettingsStore.KEY_HOME_TILE_MATERIAL)
            scalar(home, HomeSettingsStore.KEY_DRAWER_COLUMNS)
            idList(home, HomeSettingsStore.KEY_HIDDEN_APP_IDS)
            scalar(home, HomeSettingsStore.KEY_ICON_PACK)
            scalar(home, HomeSettingsStore.KEY_DOUBLE_TAP_LOCK)
            scalar(home, HomeSettingsStore.KEY_WALLPAPER_GESTURE)
            scalar(home, HomeSettingsStore.KEY_NOTIFICATION_GESTURE)
            scalar(home, HomeSettingsStore.KEY_THEME_ID)
        })
        put(BackupContract.Store.SEARCH.file, JSONObject().apply {
            scalar(search, SearchSettingsStore.KEY_ENTRY_KEY_CODE)
            scalar(search, SearchSettingsStore.KEY_ENTRY_META_STATE)
            idList(search, SearchSettingsStore.KEY_DISABLED_SEARCH_IDS)
            scalar(search, SearchSettingsStore.KEY_SEARCH_BAR_AT_BOTTOM)
            scalar(search, SearchSettingsStore.KEY_EMPTY_BACKSPACE_HINT)
            scalar(search, SearchSettingsStore.KEY_FX_BASE_CURRENCY)
        })
    }.toString(2)

    /** 없는 값은 [JSONObject.NULL] 로 적는다 -- `put(name, null)` 은 필드를 조용히 지워 D-11 이 깨진다. */
    private inline fun <reified T : Any> JSONObject.scalar(prefs: Preferences, key: Preferences.Key<T>) {
        put(key.name, prefs.valueOrNull(key) ?: JSONObject.NULL)
    }

    /** 쉼표로 이어 저장한 id 목록. 없으면 빈 배열(null 이 아니다). */
    private fun JSONObject.idList(prefs: Preferences, key: Preferences.Key<String>) {
        put(key.name, JSONArray(prefs.valueOrNull(key)?.split(",")?.filter { it.isNotEmpty() } ?: emptyList<String>()))
    }
}
