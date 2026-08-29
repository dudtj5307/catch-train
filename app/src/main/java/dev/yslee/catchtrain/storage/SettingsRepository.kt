package dev.yslee.catchtrain.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.yslee.catchtrain.watcher.ReloadScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "srt_watcher_settings")

/**
 * DataStore Preferences 기반 설정 저장소. (DESIGN.md §23)
 *
 * 저장하는 것은 앱 동작 설정뿐이다. 감시 대상(어떤 열차의 어느 좌석)은
 * 매번 조회 결과 화면에서 고르는 값이라 저장하지 않는다.
 *
 * 구버전이 저장해 둔 구간/날짜/좌석등급 키는 그냥 읽지 않고 남겨 둔다.
 * 지우려고 따로 마이그레이션을 돌리는 것보다 무시하는 편이 안전하다.
 */
class SettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs -> prefs.toAppSettings() }

    suspend fun updateReloadIntervalRange(minMs: Long, maxMs: Long) {
        val (min, max) = ReloadScheduler.clampRange(minMs, maxMs)
        context.dataStore.edit { prefs ->
            prefs[KEY_MIN_INTERVAL] = min
            prefs[KEY_MAX_INTERVAL] = max
        }
    }

    suspend fun updateNotificationEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_NOTIFICATION] = enabled }
    }

    suspend fun updateAutoReserveEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_RESERVE] = enabled }
    }

    suspend fun updateStopOnMatch(enabled: Boolean) {
        context.dataStore.edit { it[KEY_STOP_ON_MATCH] = enabled }
    }

    private fun Preferences.toAppSettings(): AppSettings {
        val (minInterval, maxInterval) = readIntervalRange()

        return AppSettings(
            minIntervalMs = minInterval,
            maxIntervalMs = maxInterval,
            notificationEnabled = this[KEY_NOTIFICATION] ?: true,
            autoReserveEnabled = this[KEY_AUTO_RESERVE] ?: true,
            stopOnMatch = this[KEY_STOP_ON_MATCH] ?: true,
        )
    }

    /**
     * 새로고침 간격 범위를 읽는다.
     *
     * 이전 버전은 고정 간격 하나(`reloadInterval`)만 저장했다. 그 값만 있는 경우
     * [저장값, 저장값 x 2] 범위로 옮겨서, 업데이트 직후에도 간격이 무작위가 되게 한다.
     */
    private fun Preferences.readIntervalRange(): Pair<Long, Long> {
        val min = this[KEY_MIN_INTERVAL]
        val max = this[KEY_MAX_INTERVAL]
        if (min != null && max != null) return ReloadScheduler.clampRange(min, max)

        val legacy = this[KEY_INTERVAL]
        if (legacy != null) return ReloadScheduler.clampRange(legacy, legacy * 2)

        return ReloadScheduler.DEFAULT_MIN_INTERVAL_MS to ReloadScheduler.DEFAULT_MAX_INTERVAL_MS
    }

    private companion object {
        /** 구버전(고정 간격) 키. 마이그레이션용으로만 읽는다. */
        val KEY_INTERVAL = longPreferencesKey("reloadInterval")
        val KEY_MIN_INTERVAL = longPreferencesKey("reloadIntervalMin")
        val KEY_MAX_INTERVAL = longPreferencesKey("reloadIntervalMax")
        val KEY_NOTIFICATION = booleanPreferencesKey("notificationEnabled")
        val KEY_AUTO_RESERVE = booleanPreferencesKey("autoReserveEnabled")
        val KEY_STOP_ON_MATCH = booleanPreferencesKey("stopOnMatch")
    }
}
