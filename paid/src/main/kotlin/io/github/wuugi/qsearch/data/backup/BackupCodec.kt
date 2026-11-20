package io.github.wuugi.qsearch.data.backup

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import io.github.wuugi.qsearch.data.drawer.AppDrawerGrid
import io.github.wuugi.qsearch.data.favorites.FavoritesGrid
import io.github.wuugi.qsearch.data.favorites.FavoritesStore
import io.github.wuugi.qsearch.data.notes.NotesStore
import io.github.wuugi.qsearch.data.settings.HomeSettingsStore
import io.github.wuugi.qsearch.data.settings.HomeTileMaterial
import io.github.wuugi.qsearch.data.settings.SearchSettingsStore
import io.github.wuugi.qsearch.data.settings.UnreadDotColor
import io.github.wuugi.qsearch.data.valueOrNull
import io.github.wuugi.qsearch.search.KeyCombo
import io.github.wuugi.qsearch.search.command.MAX_IMPORT_ITEMS
import io.github.wuugi.qsearch.search.command.MAX_NOTE_CHARS
import io.github.wuugi.qsearch.search.command.MAX_TODO_CHARS
import io.github.wuugi.qsearch.search.command.NoteCodec
import io.github.wuugi.qsearch.search.command.NoteItem
import io.github.wuugi.qsearch.search.command.TodoItem
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** 파일 속 위젯 하나: 위젯을 준 앱의 `패키지/클래스` 이름과, 정해 둔 높이(dp, 없으면 기본 높이). */
internal data class BackupWidget(val provider: String, val heightDp: Int?)

/**
 * 검증이 끝난 가져오기 계획. 저장소마다 [MutablePreferences] 에 적용할 편집(키 하나를 쓰거나 지우기)의 목록이고,
 * 사용자가 복원을 누르기 전에는 아무것도 실행되지 않는다. 숫자들은 요약 화면이 보여 준다.
 */
internal class RestorePlan(
    val favorites: List<(MutablePreferences) -> Unit>,
    val home: List<(MutablePreferences) -> Unit>,
    val search: List<(MutablePreferences) -> Unit>,
    val favoritesRestored: Int,
    val appsSkipped: Int,
    val unusable: Int,
    /** 칸(스택)별 복원할 위젯. null 이면 현재 위젯을 그대로 둔다(파일에 필드가 없거나 하나도 못 살릴 때). 빈 목록은 위젯을 모두 비운다는 뜻이다. */
    val widgets: List<List<BackupWidget>>? = null,
    /** 위젯을 준 앱이 이 폰에 없어 건너뛴 위젯 수. */
    val widgetsSkipped: Int = 0,
    /** 메모·할 일 저장소에 적용할 편집. */
    val notes: List<(MutablePreferences) -> Unit> = emptyList(),
    /** 교체할 메모 수. null은 현재 목록 유지, 0은 현재 목록을 비운다는 뜻이다. */
    val notesRestored: Int? = null,
    /** 교체할 할 일 수. null은 현재 목록 유지, 0은 현재 목록을 비운다는 뜻이다. */
    val todosRestored: Int? = null,
)

/**
 * 백업 파일 형식. **이 형식은 공개 계약이다(D-08, 되돌릴 수 없음)** -- 빌드가 한 번 배포되면 사용자 손에 파일이 남으므로
 * 필드 이름·[APP_ID]·[FORMAT]·null 규칙을 바꿀 수 없다. 바꿔야 하면 [FORMAT] 을 올린다.
 *
 * - 그룹 이름은 DataStore 파일 이름, 필드 이름은 DataStore 키 이름 그대로다(저장소 상수의 `.name`).
 * - null 은 "설정한 적 없음"이다. 가져오기는 그 키를 지운다(D-11). 필드가 아예 없으면 "현재 값을 그대로 둔다"(D-04).
 * - 어떤 키가 들어가는지는 [BackupContract] 가 정하고, `BackupCodecTest` 가 이 인코더와 계약이 어긋나면 실패시킨다.
 * - 형식 번호 정책: 선택 필드나 그룹을 **더하는** 것은 [FORMAT] 을 올리지 않는다 -- 디코더는 아는 이름만 읽고 나머지는 무시하므로
 *   옛 앱은 새 파일에서 아는 것만 복원한다(거절하지 않는다). 있던 필드의 뜻이나 모양을 **바꿀** 때만 [FORMAT] 을 올린다.
 * - 위젯 번호는 설치마다 새로 받으므로 `widget_ids` 는 번호가 아니라 위젯을 준 앱의 `패키지/클래스` 이름을 칸(스택)별 배열로 담고,
 *   `widget_heights` 는 같은 모양으로 dp 정수(기본 높이면 null)를 담는다.
 * - 암호화·압축을 하지 않는다 -- 사용자가 열어서 읽을 수 있는 UTF-8 JSON 이다(오픈소스 신뢰).
 */
internal object BackupCodec {
    /** 어느 빌드(무료·Play·GitHub)에서 만든 파일이든 같아야 하므로 applicationId 가 아니라 고정 상수다(D-12). */
    const val APP_ID = "qsearch"
    const val FORMAT = 1
    const val MIME = "application/json"

    /**
     * 가져오기가 이 크기를 넘는 파일을 거절한다(사용자 결정 2026-11-19). 메모가 들어가 파일이 커진다: 2000자 한글 메모는 JSON 으로
     * 6KB 쯤이라 650개 남짓이면 넘는다 -- 그보다 많이 쌓은 내보내기는 되돌려 읽을 수 없다(알려진 한계).
     */
    const val MAX_BYTES = 4 * 1024 * 1024

    /** 이 폰에서 값이 쓸 수 있는지 판정하는 근거. 파일을 읽는 쪽(설정 화면)이 현재 폰에서 채운다. */
    class Env(
        val installedIds: Set<String>,
        val themeIds: Set<String>,
        val currencyCodes: Set<String>,
        /** 이 폰의 검색 결과 스위치 id(레지스트리 항목과 웹 폴백). 모르는 id 는 쓸 수 없는 값이다. */
        val searchIds: Set<String>,
        /** 이 폰에 설치된 아이콘 팩의 패키지 이름. */
        val iconPackIds: Set<String>,
        /** 이 폰에 설치된 위젯 제공자의 `패키지/클래스` 이름(ComponentName.flattenToString). */
        val widgetProviders: Set<String>,
    )

    /** 가져오기를 거절하는 네 가지 이유(D-07). */
    enum class Reject { CORRUPT, OTHER_APP, TOO_LARGE, NEWER_FORMAT }

    sealed interface Decoded {
        class Ok(val plan: RestorePlan) : Decoded
        class Rejected(val reason: Reject) : Decoded
    }

    private val ICON_PACK_SHAPE = Regex("[A-Za-z0-9_.]{1,255}")
    private val PROVIDER_SHAPE = Regex("[A-Za-z0-9_.]{1,255}/[A-Za-z0-9_.$]{1,255}")

    /** 파일 하나가 띄울 수 있는 시스템 바인딩 창의 상한. 조작된 파일이 창을 끝없이 띄우지 못하게 한다. */
    const val MAX_WIDGETS = 40

    /**
     * 파일을 읽고 **전부 검증한 뒤에야** [RestorePlan] 을 만든다(D-00d) -- 저장소는 여기서 건드리지 않는다.
     *
     * - 앞문: 4MB 상한(제공자가 알려 준 크기를 믿지 않고 읽은 바이트를 센다), JSON 객체 하나이고 그 뒤에 아무것도 없음,
     *   `app` 이 [APP_ID], `format` 이 1..[FORMAT] 인 정수. 이유는 [Reject] 의 넷이다.
     * - 그룹이 없으면 그 그룹은 건드리지 않는다(D-04). 필드가 없으면 현재 값을 그대로 둔다(D-04).
     * - JSON null 은 그 키를 지운다(D-11). 기본값으로 돌아가는 것이다.
     * - 모양은 맞지만 이 폰이 쓸 수 없는 값(모르는 테마, 범위 밖 열 수, 없는 통화, 쓸 수 없는 진입 조합, 설치되지 않은 아이콘 팩, 모르는 검색 id, 틀린 JSON 형)은
     *   건너뛰고 현재 값을 두며 [RestorePlan.unusable] 로 센다(D-05). 값을 형 변환하지 않는다.
     * - 표에 없는 필드(권한 질문 여부 같은 제외 키 이름 포함)는 읽지 않는다 -- 조작된 파일이 권한 상태를 심을 수 없다(D-06).
 *   환율 변환 켜짐과 전역 단축키 의도는 사용자 설정이라 읽는다(접근성 허용 자체는 옮겨지지 않는다).
 * - 위젯은 설치된 제공자만 살린다. 파일에 항목이 있는데 하나도 못 살리면 현재 위젯을 둔다. 필드가 배열이 아니거나, 칸이 비었거나,
 *   항목이 `패키지/클래스` 모양이 아니거나, 높이가 양의 정수가 아니거나(그 위젯은 기본 높이), [MAX_WIDGETS] 를 넘으면 쓸 수 없는 값으로 센다.
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
        val notesGroup = group(BackupContract.Store.NOTES)

        val b = PlanBuilder(env)
        favoritesGroup?.let { b.favorites(it) }
        homeGroup?.let { b.home(it) }
        searchGroup?.let { b.search(it) }
        notesGroup?.let { b.notes(it) }
        return Decoded.Ok(b.build())
    }

    private class PlanBuilder(val env: Env) {
        private val favoritesEdits = mutableListOf<(MutablePreferences) -> Unit>()
        private val homeEdits = mutableListOf<(MutablePreferences) -> Unit>()
        private val searchEdits = mutableListOf<(MutablePreferences) -> Unit>()
        private val notesEdits = mutableListOf<(MutablePreferences) -> Unit>()
        private var restored = 0
        private var skipped = 0
        private var unusable = 0

        private var widgets: List<List<BackupWidget>>? = null
        private var widgetsSkipped = 0
        private var notesRestored: Int? = null
        private var todosRestored: Int? = null

        fun build() = RestorePlan(
            favoritesEdits, homeEdits, searchEdits, restored, skipped, unusable, widgets, widgetsSkipped, notesEdits,
            notesRestored = notesRestored,
            todosRestored = todosRestored,
        )

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
            widgets(g)
        }

        /** 위젯은 저장소 편집이 아니라 [RestorePlan.widgets] 로 간다 -- 새 번호를 받아야 쓸 수 있다. */
        private fun widgets(g: JSONObject) {
            val idsName = HomeSettingsStore.KEY_WIDGET_IDS.name
            val heightsName = HomeSettingsStore.KEY_WIDGET_HEIGHTS.name
            if (!g.has(idsName)) return
            val slots = g.opt(idsName) as? JSONArray ?: run { unusable++; return }
            val heights = g.opt(heightsName) as? JSONArray
            if (g.has(heightsName) && heights == null) unusable++
            val result = mutableListOf<List<BackupWidget>>()
            var seen = 0
            for (s in 0 until slots.length()) {
                val slot = slots.opt(s) as? JSONArray
                if (slot == null || slot.length() == 0) {
                    unusable++
                    continue
                }
                val kept = mutableListOf<BackupWidget>()
                for (i in 0 until slot.length()) {
                    val provider = (slot.opt(i) as? String)?.takeIf { PROVIDER_SHAPE.matches(it) }
                    if (provider == null || seen++ >= MAX_WIDGETS) {
                        unusable++
                        continue
                    }
                    if (provider !in env.widgetProviders) {
                        widgetsSkipped++
                        continue
                    }
                    val h = (heights?.opt(s) as? JSONArray)?.opt(i)
                    val dp = (h as? Int)?.takeIf { it > 0 }
                    if (dp == null && h != null && h !== JSONObject.NULL) unusable++
                    kept += BackupWidget(provider, dp)
                }
                if (kept.isNotEmpty()) result += kept
            }
            // 파일에 위젯이 있는데 하나도 못 살리면 현재 위젯을 지우지 않고 둔다(favorites 와 같은 규칙).
            if (result.isEmpty() && slots.length() > 0) return
            widgets = result
        }

        fun search(g: JSONObject) {
            entryCombo(g)
            idList(g, SearchSettingsStore.KEY_DISABLED_SEARCH_IDS, searchEdits, env.searchIds)
            scalar(g, SearchSettingsStore.KEY_SEARCH_BAR_AT_BOTTOM, searchEdits) { it as? Boolean }
            scalar(g, SearchSettingsStore.KEY_EMPTY_BACKSPACE_HINT, searchEdits) { it as? Boolean }
            scalar(g, SearchSettingsStore.KEY_FX_BASE_CURRENCY, searchEdits) { v -> (v as? String)?.takeIf { it in env.currencyCodes } }
            scalar(g, SearchSettingsStore.KEY_FX_ENABLED, searchEdits) { it as? Boolean }
            scalar(g, SearchSettingsStore.KEY_GLOBAL_SHORTCUT_WANTED, searchEdits) { it as? Boolean }
            scalar(g, SearchSettingsStore.KEY_COMMAND_PREFIX_ENABLED, searchEdits) { it as? Boolean }
            scalar(g, SearchSettingsStore.KEY_REPLY_ENABLED, searchEdits) { it as? Boolean }
        }

        fun notes(g: JSONObject) {
            val beforeNotes = notesEdits.size
            val keptNotes = items(g, NotesStore.KEY_NOTE_ITEMS) { o, id, createdAt ->
                val text = (o.opt("text") as? String)?.takeIf { it.isNotBlank() && it.length <= MAX_NOTE_CHARS }
                text?.let { NoteItem(id, it, createdAt) }
            }
            keptNotes?.let {
                val raw = NoteCodec.encodeNotes(it)
                notesEdits += { prefs -> prefs[NotesStore.KEY_NOTE_ITEMS] = raw }
            }
            if (notesEdits.size > beforeNotes) notesRestored = keptNotes?.size ?: 0

            val beforeTodos = notesEdits.size
            val keptTodos = items(g, NotesStore.KEY_TODO_ITEMS) { o, id, createdAt ->
                val text = (o.opt("text") as? String)?.takeIf { it.isNotBlank() && it.length <= MAX_TODO_CHARS }
                val done = o.opt("done") as? Boolean
                if (text == null || done == null) null else TodoItem(id, text, createdAt, done)
            }
            keptTodos?.let {
                val raw = NoteCodec.encodeTodos(it)
                notesEdits += { prefs -> prefs[NotesStore.KEY_TODO_ITEMS] = raw }
            }
            if (notesEdits.size > beforeTodos) todosRestored = keptTodos?.size ?: 0
        }

        /**
         * 메모/할 일 배열 하나. 없으면 null(현재 값 유지), JSON null 이면 키를 지우는 편집을 더하고 null, 쓸 수 있는 원소가 하나도 없으면 null,
         * 아니면 살린 원소. id·createdAt 검증과 중복 id·개수 상한은 여기서, 글자 검증은 [build] 람다가 맡는다. 글자를 자르지 않는다.
         */
        private fun <T> items(
            g: JSONObject,
            key: Preferences.Key<String>,
            build: (JSONObject, String, Long) -> T?,
        ): List<T>? {
            if (!g.has(key.name)) return null
            val v = g.opt(key.name)
            if (v === JSONObject.NULL) {
                notesEdits += { it.remove(key) }
                return null
            }
            val array = v as? JSONArray ?: run { unusable++; return null }
            val kept = ArrayList<T>()
            val seenIds = HashSet<String>()
            for (i in 0 until array.length()) {
                val o = array.opt(i) as? JSONObject
                val id = (o?.opt("id") as? String)?.takeIf { it.isNotEmpty() }
                val c = o?.opt("createdAt")
                val createdAt = if (c is Int || c is Long) (c as Number).toLong().takeIf { it > 0 } else null
                val item = if (i >= MAX_IMPORT_ITEMS || o == null || id == null || createdAt == null || id in seenIds) null else build(o, id, createdAt)
                if (item == null || id == null) unusable++ else { seenIds += id; kept += item }
            }
            // 빈 배열은 비우는 뜻이고, 원소가 있는데 하나도 못 살리면 현재 목록을 둔다(favorites 와 같은 규칙).
            if (kept.isEmpty() && array.length() > 0) return null
            if (kept.isEmpty()) notesEdits += { it[key] = "[]" }
            return kept.takeIf { it.isNotEmpty() }
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

    /** [widgetProvider] 는 위젯 번호의 제공자 `패키지/클래스` 이름(번호는 파일에 넣지 않는다). 찾지 못하는 번호는 뺀다. */
    fun encode(
        favorites: Preferences,
        home: Preferences,
        search: Preferences,
        notes: Preferences,
        widgetProvider: (Int) -> String?,
    ): String = JSONObject().apply {
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
            widgets(home, widgetProvider)
        })
        put(BackupContract.Store.SEARCH.file, JSONObject().apply {
            scalar(search, SearchSettingsStore.KEY_ENTRY_KEY_CODE)
            scalar(search, SearchSettingsStore.KEY_ENTRY_META_STATE)
            idList(search, SearchSettingsStore.KEY_DISABLED_SEARCH_IDS)
            scalar(search, SearchSettingsStore.KEY_SEARCH_BAR_AT_BOTTOM)
            scalar(search, SearchSettingsStore.KEY_EMPTY_BACKSPACE_HINT)
            scalar(search, SearchSettingsStore.KEY_FX_BASE_CURRENCY)
            scalar(search, SearchSettingsStore.KEY_FX_ENABLED)
            scalar(search, SearchSettingsStore.KEY_GLOBAL_SHORTCUT_WANTED)
            scalar(search, SearchSettingsStore.KEY_COMMAND_PREFIX_ENABLED)
            scalar(search, SearchSettingsStore.KEY_REPLY_ENABLED)
        })
        put(BackupContract.Store.NOTES.file, JSONObject().apply {
            // 안 쓴 목록은 null 이 아니라 빈 배열이다(favorite_cells 와 같다).
            put(NotesStore.KEY_NOTE_ITEMS.name, JSONArray().also { a ->
                NoteCodec.decodeNotes(notes.valueOrNull(NotesStore.KEY_NOTE_ITEMS)).forEach {
                    a.put(JSONObject().put("id", it.id).put("text", it.text).put("createdAt", it.createdAt))
                }
            })
            put(NotesStore.KEY_TODO_ITEMS.name, JSONArray().also { a ->
                NoteCodec.decodeTodos(notes.valueOrNull(NotesStore.KEY_TODO_ITEMS)).forEach {
                    a.put(JSONObject().put("id", it.id).put("text", it.text).put("createdAt", it.createdAt).put("done", it.done))
                }
            })
        })
    }.toString(2)

    /** 없는 값은 [JSONObject.NULL] 로 적는다 -- `put(name, null)` 은 필드를 조용히 지워 D-11 이 깨진다. */
    private inline fun <reified T : Any> JSONObject.scalar(prefs: Preferences, key: Preferences.Key<T>) {
        put(key.name, prefs.valueOrNull(key) ?: JSONObject.NULL)
    }

    /** 위젯 번호를 제공자 이름으로 바꿔 칸별 배열 둘(이름, 높이)로 적는다. 없으면 빈 배열. */
    private fun JSONObject.widgets(prefs: Preferences, provider: (Int) -> String?) {
        val heights = HomeSettingsStore.decodeWidgetHeights(prefs.valueOrNull(HomeSettingsStore.KEY_WIDGET_HEIGHTS))
        val names = JSONArray()
        val dps = JSONArray()
        for (slot in HomeSettingsStore.decodeWidgetSlots(prefs.valueOrNull(HomeSettingsStore.KEY_WIDGET_IDS))) {
            val resolved = slot.mapNotNull { id -> provider(id)?.let { it to heights[id] } }
            if (resolved.isEmpty()) continue
            names.put(JSONArray(resolved.map { it.first }))
            dps.put(JSONArray().also { a -> resolved.forEach { a.put(it.second ?: JSONObject.NULL) } })
        }
        put(HomeSettingsStore.KEY_WIDGET_IDS.name, names)
        put(HomeSettingsStore.KEY_WIDGET_HEIGHTS.name, dps)
    }

    /** 쉼표로 이어 저장한 id 목록. 없으면 빈 배열(null 이 아니다). */
    private fun JSONObject.idList(prefs: Preferences, key: Preferences.Key<String>) {
        put(key.name, JSONArray(prefs.valueOrNull(key)?.split(",")?.filter { it.isNotEmpty() } ?: emptyList<String>()))
    }
}
