package io.github.wuugi.qsearch.data.backup

import io.github.wuugi.qsearch.data.favorites.FavoritesStore
import io.github.wuugi.qsearch.data.favorites.UsageStore
import io.github.wuugi.qsearch.data.fx.FxRepository
import io.github.wuugi.qsearch.data.settings.HomeSettingsStore
import io.github.wuugi.qsearch.data.settings.SearchSettingsStore

/**
 * 저장된 값 중 무엇이 백업 파일에 들어가고 무엇이 들어가지 않는지 말하는 단 하나의 장소다.
 *
 * 새 키를 만들면 [included] 와 [excluded] 중 정확히 한쪽에 넣는다. 어느 쪽에도 없으면
 * `BackupContractTest` 가 실패하고, 실패 메시지가 저장소 이름과 키 이름을 알려 준다.
 * 새 저장소 파일(DataStore 이름, SharedPreferences 이름)도 [Store] 에 넣어야 한다.
 *
 * 구매·라이선스 상태는 어떤 이유로든 반드시 [excluded] 에 둔다(BKUP-03) — 백업 파일이 권한을 옮기면 안 된다.
 *
 * 이 파일은 일부러 공개 트리에 있다. 무엇을 옮기는지는 오픈소스 신뢰의 일부이고, 이름만 담는다.
 * 키 이름은 저장 계약이라 상수의 `.name` 으로 적는다 — 이름을 바꾸면 컴파일이 깨진다.
 */
object BackupContract {

    /** 저장소 파일 이름. DataStore 파일과 SharedPreferences 파일을 모두 센다. */
    enum class Store(val file: String) {
        FAVORITES("favorites"),
        HOME("home_settings"),
        SEARCH("search_settings"),
        USAGE("usage"),
        FX_RATES("fx_rates"),
        BOOT_GUARD("boot_guard"),
    }

    /** 키를 백업에서 뺀 이유. */
    enum class Why { LEGACY, DEVICE_SPECIFIC, PERMISSION_STATE, CONSENT, USAGE_HISTORY, CACHE, SAFETY_BREAKER }

    val included: Map<Store, Set<String>> = mapOf(
        Store.FAVORITES to setOf(
            FavoritesStore.KEY_FAVORITE_CELLS.name,
        ),
        Store.HOME to setOf(
            HomeSettingsStore.KEY_SHOW_UNREAD_DOT.name,
            HomeSettingsStore.KEY_UNREAD_DOT_COLOR.name,
            HomeSettingsStore.KEY_HOME_TILE_MATERIAL.name,
            HomeSettingsStore.KEY_DRAWER_COLUMNS.name,
            HomeSettingsStore.KEY_HIDDEN_APP_IDS.name,
            HomeSettingsStore.KEY_ICON_PACK.name,
            HomeSettingsStore.KEY_DOUBLE_TAP_LOCK.name,
            HomeSettingsStore.KEY_WALLPAPER_GESTURE.name,
            HomeSettingsStore.KEY_NOTIFICATION_GESTURE.name,
            HomeSettingsStore.KEY_THEME_ID.name,
        ),
        Store.SEARCH to setOf(
            SearchSettingsStore.KEY_ENTRY_KEY_CODE.name,
            SearchSettingsStore.KEY_ENTRY_META_STATE.name,
            SearchSettingsStore.KEY_DISABLED_SEARCH_IDS.name,
            SearchSettingsStore.KEY_SEARCH_BAR_AT_BOTTOM.name,
            SearchSettingsStore.KEY_EMPTY_BACKSPACE_HINT.name,
            SearchSettingsStore.KEY_FX_BASE_CURRENCY.name,
        ),
        Store.USAGE to emptySet(),
        Store.FX_RATES to emptySet(),
        Store.BOOT_GUARD to emptySet(),
    )

    val excluded: Map<Store, Map<String, Why>> = mapOf(
        Store.FAVORITES to mapOf(
            FavoritesStore.KEY_FAVORITE_IDS.name to Why.LEGACY,
        ),
        Store.HOME to mapOf(
            HomeSettingsStore.KEY_WIDGET_IDS.name to Why.DEVICE_SPECIFIC,
            HomeSettingsStore.KEY_WIDGET_HEIGHTS.name to Why.DEVICE_SPECIFIC,
            HomeSettingsStore.KEY_NOTIFICATION_PROMPT_TAPPED.name to Why.PERMISSION_STATE,
        ),
        Store.SEARCH to mapOf(
            SearchSettingsStore.KEY_FX_ENABLED.name to Why.CONSENT,
            SearchSettingsStore.KEY_SMS_PROMPT_DECLINED.name to Why.PERMISSION_STATE,
            SearchSettingsStore.KEY_GLOBAL_SHORTCUT_WANTED.name to Why.PERMISSION_STATE,
        ),
        Store.USAGE to mapOf(
            UsageStore.KEY_USAGE.name to Why.USAGE_HISTORY,
        ),
        Store.FX_RATES to mapOf(
            FxRepository.KEY_FX_RATES.name to Why.CACHE,
            FxRepository.LEGACY_KRW_PER_USD.name to Why.CACHE,
            FxRepository.LEGACY_JPY_PER_USD.name to Why.CACHE,
            FxRepository.LEGACY_RATE_DATE.name to Why.CACHE,
            FxRepository.KEY_FETCHED_AT.name to Why.CACHE,
            FxRepository.KEY_LAST_ATTEMPT.name to Why.CACHE,
        ),
        // BootGuard 가 키 이름을 private 으로 두어 문자열로 적는다 — 테스트가 리플렉션으로 이름을 대조한다.
        Store.BOOT_GUARD to mapOf(
            "crashCount" to Why.SAFETY_BREAKER,
            "lastCrashAtMs" to Why.SAFETY_BREAKER,
            "pending" to Why.SAFETY_BREAKER,
        ),
    )
}
