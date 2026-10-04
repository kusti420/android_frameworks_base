/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.panels.data.repository

import android.content.Context
import android.content.IntentFilter
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.UserHandle
import android.provider.Settings
import androidx.core.content.edit
import com.android.systemui.backup.BackupHelper
import com.android.systemui.backup.BackupHelper.Companion.ACTION_RESTORE_FINISHED
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.qs.panels.shared.model.PanelsLog
import com.android.systemui.qs.pipeline.shared.InternetTileMigration.logMigration
import com.android.systemui.qs.pipeline.shared.InternetTileMigration.migrateInternetTile
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.pipeline.shared.TilesUpgradePath
import com.android.systemui.settings.UserFileManager
import com.android.systemui.shade.ui.ShoalShade
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.util.kotlin.SharedPreferencesExt.observe
import com.android.systemui.util.kotlin.emitOnStart
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/** Repository for QS user preferences. */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class QSPreferencesRepository
@Inject
constructor(
    private val userFileManager: UserFileManager,
    private val userRepository: UserRepository,
    private val defaultLargeTilesRepository: DefaultLargeTilesRepository,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    @PanelsLog private val logBuffer: LogBuffer,
    broadcastDispatcher: BroadcastDispatcher,
    @Application private val context: Context,
) {
    private val logger by lazy { Logger(logBuffer, TAG) }

    private val backupRestorationEvents: Flow<Unit> =
        broadcastDispatcher
            .broadcastFlow(
                filter = IntentFilter(ACTION_RESTORE_FINISHED),
                flags = Context.RECEIVER_NOT_EXPORTED,
                permission = BackupHelper.PERMISSION_SELF,
            )
            .onEach { logger.i("Restored state for QS preferences.") }
            .emitOnStart()

    /** Set of [TileSpec] to display as large tiles for the current user. */
    val largeTilesSpecs: Flow<Set<TileSpec>> =
        combine(backupRestorationEvents, userRepository.selectedUserInfo, ::Pair)
            .flatMapLatest { (_, userInfo) ->
                val prefs = getSharedPrefs(userInfo.id)
                prefs.observe().emitOnStart().map {
                    val loaded = prefs.getLargeTilesSpecs()
                    loaded
                        .migrateInternetTile()
                        .also {
                            if (loaded != it) {
                                logger.logMigration()
                                writeLargeTileSpecs(it, changeDefault = false)
                            }
                        }
                        .let { prefs.migrateIchthysLargeDefaults(it) }
                        .let { prefs.migrateShoalWideInternet(it) }
                }
            }
            .flowOn(backgroundDispatcher)

    private val junctionSettingChanges: Flow<Unit> = secureSettingChanges(JUNCTION_TILES_SETTING)

    private fun secureSettingChanges(setting: String): Flow<Unit> =
        callbackFlow {
                val observer =
                    object : ContentObserver(null) {
                        override fun onChange(selfChange: Boolean) {
                            trySend(Unit)
                        }
                    }
                context.contentResolver.registerContentObserver(
                    Settings.Secure.getUriFor(setting),
                    false,
                    observer,
                    UserHandle.USER_ALL,
                )
                awaitClose { context.contentResolver.unregisterContentObserver(observer) }
            }
            .conflate()

    /**
     * IchthysOS "Shoal": the tiles in the junction slots (where four big tiles meet) for the
     * current user, top to bottom. A null entry is a free slot; trailing free slots are not
     * stored.
     *
     * Unlike the large tiles this lives in Settings.Secure ([JUNCTION_TILES_SETTING]) and not in
     * the shared preferences, so that the ichthys.nix option quickSettings.junctions (bound to
     * the same key by IchthysConfigService) and the tile editor read and write one value.
     */
    val junctionTilesSpecs: Flow<List<TileSpec?>> =
        userRepository.selectedUserInfo
            .flatMapLatest { userInfo ->
                junctionSettingChanges.emitOnStart().map { readJunctionTileSpecs(userInfo.id) }
            }
            .distinctUntilChanged()
            .flowOn(backgroundDispatcher)

    /**
     * IchthysOS: the tiles picked for the collapsed panel (quick quick settings) for the current
     * user, in order. Empty while nothing is picked: the collapsed panel then shows the first tiles
     * of the tile list, as it always did.
     *
     * The list only names tiles; it never adds one to quick settings. A spec that is not a current
     * tile is skipped by the readers. Lives in Settings.Secure ([COLLAPSED_TILES_SETTING]) for the
     * same reason as [junctionTilesSpecs]: the ichthys.nix option quickSettings.collapsed.tiles and
     * the tile editor share the value.
     */
    val collapsedTilesSpecs: Flow<List<TileSpec>> =
        userRepository.selectedUserInfo
            .flatMapLatest { userInfo ->
                secureSettingChanges(COLLAPSED_TILES_SETTING).emitOnStart().map {
                    readCollapsedTileSpecs(userInfo.id)
                }
            }
            .distinctUntilChanged()
            .flowOn(backgroundDispatcher)

    /**
     * IchthysOS: tiles per row of the collapsed panel for the current user, within
     * [COLLAPSED_COLUMNS_MIN]..[COLLAPSED_COLUMNS_MAX]; [COLLAPSED_COLUMNS_DEFAULT] while
     * [COLLAPSED_COLUMNS_SETTING] (quickSettings.collapsed.columns) is unset.
     */
    val collapsedColumns: Flow<Int> =
        userRepository.selectedUserInfo
            .flatMapLatest { userInfo ->
                secureSettingChanges(COLLAPSED_COLUMNS_SETTING).emitOnStart().map {
                    readCollapsedColumns(userInfo.id)
                }
            }
            .distinctUntilChanged()
            .flowOn(backgroundDispatcher)

    /** Whether or not the edit icon tooltip was shown for the current user. */
    val editTooltipShown: Flow<Boolean> =
        combine(backupRestorationEvents, userRepository.selectedUserInfo, ::Pair)
            .flatMapLatest { (_, userInfo) ->
                val prefs = getSharedPrefs(userInfo.id)
                prefs.observe().emitOnStart().map {
                    prefs.getBoolean(EDIT_TOOLTIP_SHOWN_KEY, false)
                }
            }
            .flowOn(backgroundDispatcher)

    /** Sets for the current user the set of [TileSpec] to display as large tiles. */
    fun writeLargeTileSpecs(specs: Set<TileSpec>, changeDefault: Boolean = true) {
        with(getSharedPrefs(userRepository.getSelectedUserInfo().id)) {
            writeLargeTileSpecs(specs)
            if (changeDefault) {
                setLargeTilesDefault(false)
            }
        }
    }

    /** Remove the set of [TileSpec] from the current large tiles. */
    fun removeLargeTileSpecs(specs: Set<TileSpec>) {
        with(getSharedPrefs(userRepository.getSelectedUserInfo().id)) {
            val largeSpecs = getLargeTilesSpecs()
            writeLargeTileSpecs(largeSpecs - specs)
            setLargeTilesDefault(false)
        }
    }

    /**
     * IchthysOS "Shoal": sets the junction slots for the current user. An empty list (or one with
     * only free slots) deletes the key, which is the option's default.
     */
    fun writeJunctionTileSpecs(slots: List<TileSpec?>) {
        writeJunctionTileSpecs(userRepository.getSelectedUserInfo().id, slots)
    }

    /**
     * IchthysOS "Shoal": frees the junction slots holding any of [specs]. The other slots keep
     * their position.
     */
    fun removeJunctionTileSpecs(specs: Set<TileSpec>) {
        val userId = userRepository.getSelectedUserInfo().id
        // Read the stored value rather than the flow's last one, so that a write that has just
        // happened (an undo, the editor) is not overwritten with something older.
        val stored = readJunctionTileSpecs(userId)
        val freed = stored.map { if (it in specs) null else it }
        if (freed != stored) {
            writeJunctionTileSpecs(userId, freed)
        }
    }

    private fun readJunctionTileSpecs(userId: Int): List<TileSpec?> {
        return decodeJunctionTiles(
            Settings.Secure.getStringForUser(
                context.contentResolver,
                JUNCTION_TILES_SETTING,
                userId,
            )
        )
    }

    private fun writeJunctionTileSpecs(userId: Int, slots: List<TileSpec?>) {
        val encoded = encodeJunctionTiles(slots)
        logger.i("Junction tiles for user $userId: $encoded")
        Settings.Secure.putStringForUser(
            context.contentResolver,
            JUNCTION_TILES_SETTING,
            encoded,
            userId,
        )
    }

    /**
     * IchthysOS: sets the collapsed panel's tiles for the current user. An empty list deletes the
     * key: the collapsed panel goes back to the first tiles of the tile list.
     */
    fun writeCollapsedTileSpecs(specs: List<TileSpec>) {
        writeCollapsedTileSpecs(userRepository.getSelectedUserInfo().id, specs)
    }

    /** IchthysOS: takes [specs] out of the collapsed panel's tiles. */
    fun removeCollapsedTileSpecs(specs: Set<TileSpec>) {
        val userId = userRepository.getSelectedUserInfo().id
        // The stored value, not the flow's last one; see removeJunctionTileSpecs.
        val stored = readCollapsedTileSpecs(userId)
        val kept = stored.filter { it !in specs }
        if (kept != stored) {
            writeCollapsedTileSpecs(userId, kept)
        }
    }

    /**
     * IchthysOS: sets the collapsed panel's tiles per row for the current user; null deletes the
     * key, which is the option's default.
     */
    fun writeCollapsedColumns(columns: Int?) {
        val userId = userRepository.getSelectedUserInfo().id
        val stored = columns?.coerceIn(COLLAPSED_COLUMNS_MIN, COLLAPSED_COLUMNS_MAX)?.toString()
        logger.i("Collapsed panel columns for user $userId: $stored")
        Settings.Secure.putStringForUser(
            context.contentResolver,
            COLLAPSED_COLUMNS_SETTING,
            stored,
            userId,
        )
    }

    private fun readCollapsedTileSpecs(userId: Int): List<TileSpec> {
        return decodeCollapsedTiles(
            Settings.Secure.getStringForUser(
                context.contentResolver,
                COLLAPSED_TILES_SETTING,
                userId,
            )
        )
    }

    private fun writeCollapsedTileSpecs(userId: Int, specs: List<TileSpec>) {
        val encoded = encodeCollapsedTiles(specs)
        logger.i("Collapsed panel tiles for user $userId: $encoded")
        Settings.Secure.putStringForUser(
            context.contentResolver,
            COLLAPSED_TILES_SETTING,
            encoded,
            userId,
        )
    }

    private fun readCollapsedColumns(userId: Int): Int {
        val stored =
            Settings.Secure.getIntForUser(
                context.contentResolver,
                COLLAPSED_COLUMNS_SETTING,
                COLLAPSED_COLUMNS_UNSET,
                userId,
            )
        // The clamp is for the stored value only; the default is ours and already in range.
        return if (stored == COLLAPSED_COLUMNS_UNSET) {
            COLLAPSED_COLUMNS_DEFAULT
        } else {
            stored.coerceIn(COLLAPSED_COLUMNS_MIN, COLLAPSED_COLUMNS_MAX)
        }
    }

    /** Sets the value for whether or not the edit icon tooltip was shown for the current user. */
    fun writeEditTooltipShown(value: Boolean) {
        getSharedPrefs(userRepository.getSelectedUserInfo().id).edit {
            putBoolean(EDIT_TOOLTIP_SHOWN_KEY, value)
        }
    }

    fun getLargeTilesForUser(userId: Int): Set<TileSpec> {
        return getSharedPrefs(userId).getLargeTilesSpecs()
    }

    fun setLargeTilesForUser(userId: Int, largeTiles: Set<TileSpec>) {
        getSharedPrefs(userId).writeLargeTileSpecs(largeTiles)
    }

    suspend fun deleteLargeTileDataJob() {
        userRepository.selectedUserInfo.collect { userInfo ->
            getSharedPrefs(userInfo.id)
                .edit()
                .remove(LARGE_TILES_SPECS_KEY)
                .remove(LARGE_TILES_DEFAULT_KEY)
                .apply()
        }
    }

    private fun SharedPreferences.writeLargeTileSpecs(specs: Set<TileSpec>) {
        edit().putStringSet(LARGE_TILES_SPECS_KEY, specs.map { it.spec }.toSet()).apply()
    }

    private fun SharedPreferences.getLargeTilesSpecs(): Set<TileSpec> {
        return getStringSet(
                LARGE_TILES_SPECS_KEY,
                defaultLargeTilesRepository.defaultLargeTiles.map { it.spec }.toSet(),
            )
            ?.map { TileSpec.create(it) }
            ?.toSet() ?: defaultLargeTilesRepository.defaultLargeTiles
    }

    /**
     * Sets the initial set of large tiles. One of the following cases will happen:
     * * If we are setting the default set (no value stored in settings for the list of tiles), set
     *   the large tiles based on [defaultLargeTilesRepository]. We do this to signal future reboots
     *   that we have performed the upgrade path once. In this case, we will mark that we set them
     *   as the default in case a restore needs to modify them later.
     * * If we got a list of tiles restored from a device and nothing has modified the list of
     *   tiles, set all the restored tiles to large. Note that if we also restored a set of large
     *   tiles before this was called, [LARGE_TILES_DEFAULT_KEY] will be false and we won't
     *   overwrite it.
     * * If we got a list of tiles from settings, we consider that we upgraded in place and then we
     *   will set all those tiles to large IF there's no current set of large tiles.
     *
     * Even if largeTilesSpec is read Eagerly before we know if we are in an initial state, because
     * we are not writing the default values to the SharedPreferences, the file will not contain the
     * key and this call will succeed, as long as there hasn't been any calls to setLargeTilesSpecs
     * for that user before.
     */
    fun setInitialOrUpgradeLargeTiles(upgradePath: TilesUpgradePath, userId: Int) {
        with(getSharedPrefs(userId)) {
            when (upgradePath) {
                is TilesUpgradePath.DefaultSet -> {
                    writeLargeTileSpecs(defaultLargeTilesRepository.defaultLargeTiles)
                    logger.i("Large tiles set to default on init")
                    setLargeTilesDefault(true)
                }
                is TilesUpgradePath.RestoreFromBackup -> {
                    if (
                        getBoolean(LARGE_TILES_DEFAULT_KEY, false) ||
                            !contains(LARGE_TILES_SPECS_KEY)
                    ) {
                        writeLargeTileSpecs(upgradePath.value)
                        logger.i("Tiles restored from backup set to large: ${upgradePath.value}")
                        setLargeTilesDefault(false)
                    }
                }
                is TilesUpgradePath.ReadFromSettings -> {
                    if (!contains(LARGE_TILES_SPECS_KEY)) {
                        writeLargeTileSpecs(upgradePath.value)
                        logger.i("Tiles read from settings set to large: ${upgradePath.value}")
                        setLargeTilesDefault(false)
                    }
                }
            }
        }
    }

    /**
     * IchthysOS: one-time move to this ROM's default large tiles (Wi-Fi / internet, Bluetooth,
     * mobile data). A stored set that is still an AOSP / device default, or that was written as
     * the default on init, was never customised by the user, so it is replaced by the current
     * default; anything else is kept. Runs once per user (marker key).
     */
    private fun SharedPreferences.migrateIchthysLargeDefaults(
        loaded: Set<TileSpec>
    ): Set<TileSpec> {
        if (getBoolean(ICHTHYS_LARGE_DEFAULTS_KEY, false)) return loaded
        val specs = loaded.map { it.spec }.toSet()
        val untouched =
            !contains(LARGE_TILES_SPECS_KEY) ||
                getBoolean(LARGE_TILES_DEFAULT_KEY, false) ||
                specs in STOCK_LARGE_DEFAULTS
        val result = if (untouched) defaultLargeTilesRepository.defaultLargeTiles else loaded
        val editor = edit().putBoolean(ICHTHYS_LARGE_DEFAULTS_KEY, true)
        if (result != loaded) {
            editor.putStringSet(LARGE_TILES_SPECS_KEY, result.map { it.spec }.toSet())
            logger.i("Large tiles moved to the IchthysOS default: $result")
        }
        editor.apply()
        return result
    }

    /**
     * IchthysOS "Shoal": one-time (per user) — Wi-Fi / internet is a wide tile in the Shoal
     * layout. Adds it to the stored large set if it is missing; the user's other wide tiles are
     * kept. Only runs (and only sets the marker) while Shoal is on, so a user who turns Shoal on
     * later still gets it once. Removing it afterwards in edit mode sticks.
     */
    private fun SharedPreferences.migrateShoalWideInternet(
        loaded: Set<TileSpec>
    ): Set<TileSpec> {
        if (getBoolean(SHOAL_WIDE_INTERNET_KEY, false)) return loaded
        if (!ShoalShade.isActive(context)) return loaded
        // "internet", or "wifi" when the internet tile is split.
        val internet = setOf(TileSpec.create("internet")).migrateInternetTile()
        val result = loaded + internet
        val editor = edit().putBoolean(SHOAL_WIDE_INTERNET_KEY, true)
        if (result != loaded) {
            editor.putStringSet(LARGE_TILES_SPECS_KEY, result.map { it.spec }.toSet())
            logger.i("Shoal: internet tile made large: $result")
        }
        editor.apply()
        return result
    }

    private fun SharedPreferences.setLargeTilesDefault(value: Boolean) {
        edit().putBoolean(LARGE_TILES_DEFAULT_KEY, value).apply()
    }

    private fun getSharedPrefs(userId: Int): SharedPreferences {
        return userFileManager.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE, userId)
    }

    companion object {
        private const val TAG = "QSPreferencesRepository"
        private const val LARGE_TILES_SPECS_KEY = "large_tiles_specs"
        private const val LARGE_TILES_DEFAULT_KEY = "large_tiles_default"
        private const val EDIT_TOOLTIP_SHOWN_KEY = "edit_tooltip_shown"
        private const val ICHTHYS_LARGE_DEFAULTS_KEY = "ichthys_large_tiles_defaults_v1"
        private const val SHOAL_WIDE_INTERNET_KEY = "ichthys_shoal_wide_internet_v1"
        /** Stock default large tile sets (AOSP, AOSP split, a52sxq overlay split). */
        private val STOCK_LARGE_DEFAULTS =
            setOf(
                setOf("internet", "bt", "dnd", "cast"),
                setOf("wifi", "bt", "cell", "cast", "dnd", "wallet"),
                setOf("wifi", "bt", "cell", "dnd"),
            )
        const val FILE_NAME = "quick_settings_prefs"

        /**
         * IchthysOS "Shoal": Settings.Secure key of the junction slots, the ichthys.nix option
         * quickSettings.junctions. Tile specs joined with "," from the top junction down, with
         * [JUNCTION_FREE] for a free slot ("rotation,-,location").
         */
        const val JUNCTION_TILES_SETTING = "ichthys_qs_junctions"
        /**
         * Marks a free slot. Not an empty entry, because the option's list type drops those and
         * the slots after it would move up.
         */
        const val JUNCTION_FREE = "-"

        /** Parses a stored [JUNCTION_TILES_SETTING] value; anything that is not a tile is free. */
        fun decodeJunctionTiles(stored: String?): List<TileSpec?> {
            if (stored.isNullOrEmpty()) return emptyList()
            return stored
                .split(",")
                .map { entry ->
                    val spec = entry.trim()
                    if (spec.isEmpty() || spec == JUNCTION_FREE) {
                        null
                    } else {
                        TileSpec.create(spec).takeIf { it != TileSpec.Invalid }
                    }
                }
                .dropLastWhile { it == null }
        }

        /** The stored form of [slots], or null (delete the key) when no slot holds a tile. */
        fun encodeJunctionTiles(slots: List<TileSpec?>): String? {
            val trimmed = slots.dropLastWhile { it == null || it == TileSpec.Invalid }
            if (trimmed.isEmpty()) return null
            return trimmed.joinToString(",") { spec ->
                if (spec == null || spec == TileSpec.Invalid) JUNCTION_FREE else spec.spec
            }
        }

        /**
         * IchthysOS: Settings.Secure key of the collapsed panel's tiles, the ichthys.nix option
         * quickSettings.collapsed.tiles. Tile specs joined with "," in the order shown
         * ("flashlight,rotation,bt").
         */
        const val COLLAPSED_TILES_SETTING = "ichthys_qqs_tiles"
        /**
         * IchthysOS: Settings.Secure key of the collapsed panel's tiles per row, the ichthys.nix
         * option quickSettings.collapsed.columns.
         */
        const val COLLAPSED_COLUMNS_SETTING = "ichthys_qqs_columns"
        // A collapsed tile is a 38 dp bubble in a cell that is a share of the row. On the 411 dp
        // wide phone the row is 379 dp: 8 cells of 44 dp keep 6 dp between the bubbles, 9 would
        // leave them touching. Under 3 the row stops reading as a row.
        const val COLLAPSED_COLUMNS_MIN = 3
        const val COLLAPSED_COLUMNS_MAX = 8
        /** What the collapsed panel drew before the key existed. */
        const val COLLAPSED_COLUMNS_DEFAULT = 7
        private const val COLLAPSED_COLUMNS_UNSET = -1

        /**
         * Parses a stored [COLLAPSED_TILES_SETTING] value. Entries that are not a tile are dropped,
         * and so is a tile named a second time: the panel shows a tile once.
         */
        fun decodeCollapsedTiles(stored: String?): List<TileSpec> {
            if (stored.isNullOrEmpty()) return emptyList()
            return stored
                .split(",")
                .map { TileSpec.create(it.trim()) }
                .filter { it != TileSpec.Invalid }
                .distinct()
        }

        /** The stored form of [specs], or null (delete the key) when there are none. */
        fun encodeCollapsedTiles(specs: List<TileSpec>): String? {
            val valid = specs.filter { it != TileSpec.Invalid }.distinct()
            if (valid.isEmpty()) return null
            return valid.joinToString(",") { it.spec }
        }
    }
}
