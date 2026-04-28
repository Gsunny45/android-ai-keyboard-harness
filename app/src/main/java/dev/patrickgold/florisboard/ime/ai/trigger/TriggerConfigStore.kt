package dev.patrickgold.florisboard.ime.ai.trigger

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Manages the CTE (Context-aware Type Engine) configuration on disk.
 *
 * On first run, [ensureDefaults] copies the bundled defaults from
 * `assets/cte_defaults/` into `getFilesDir()/cte/`, mirroring the
 * same directory tree. Subsequent runs read and write from the disk
 * copy, allowing the user to customise configs without modifying the
 * APK.
 *
 * Architecture reference:
 *   - Boot steps 2-9: [ensureDefaults] covers step 2 (asset extraction)
 *   - Hot-reload: Settings → AI → "Reload config" calls [reloadConfig]
 *   - Atomic writes: future writes will use *.tmp then rename()
 */
class TriggerConfigStore private constructor(
    private val appContext: Context,
) {
    companion object {
        private const val TAG = "TriggerConfigStore"
        private const val CTE_ROOT = "cte"
        private const val ASSET_PREFIX = "cte_defaults"

        @Volatile
        private var instance: TriggerConfigStore? = null

        /**
         * Singleton accessor. Uses [applicationContext] internally so the
         * passed [context] is only used to resolve the Application, not held.
         */
        fun getInstance(context: Context): TriggerConfigStore {
            return instance ?: synchronized(this) {
                instance ?: TriggerConfigStore(context.applicationContext).also { instance = it }
            }
        }
    }

    /** Root directory on disk: `getFilesDir()/cte/` */
    private val cteRoot: File = File(appContext.filesDir, CTE_ROOT)

    // ── First-run default extraction ──────────────────────────────────

    /**
     * Ensures the CTE config tree exists on disk. If `cte/` does not
     * exist, copies every file from `assets/cte_defaults/` into it,
     * preserving the subdirectory structure.
     *
     * Safe to call on every boot; the directory-existence check is O(1).
     */
    fun ensureDefaults() {
        if (cteRoot.exists()) {
            Log.d(TAG, "CTE config already exists at ${cteRoot.path}; skipping seed")
            return
        }
        Log.i(TAG, "Seeding CTE defaults from assets/$ASSET_PREFIX to ${cteRoot.path}")
        try {
            copyAssetTree(appContext.assets, ASSET_PREFIX, cteRoot)
            Log.i(TAG, "CTE defaults seeded successfully")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to seed CTE defaults", e)
        }
    }

    /**
     * Recursively copies a subtree from [assetManager] at [assetPath] into
     * the local filesystem at [targetDir].
     */
    private fun copyAssetTree(
        assetManager: AssetManager,
        assetPath: String,
        targetDir: File,
    ) {
        val entries: Array<String> = assetManager.list(assetPath)
            ?: throw IOException("Cannot list assets at $assetPath")

        targetDir.mkdirs()

        for (entry in entries) {
            val childAssetPath = "$assetPath/$entry"
            val childTarget = File(targetDir, entry)

            if (isDirectory(assetManager, childAssetPath)) {
                copyAssetTree(assetManager, childAssetPath, childTarget)
            } else {
                assetManager.open(childAssetPath).use { input ->
                    childTarget.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "  Extracted: $childAssetPath -> ${childTarget.path}")
            }
        }
    }

    /**
     * Checks whether [assetPath] is a directory by attempting to list it.
     * Android assets do not have a native isDirectory() API.
     */
    private fun isDirectory(assetManager: AssetManager, assetPath: String): Boolean {
        return try {
            val list = assetManager.list(assetPath)
            list != null && list.isNotEmpty()
        } catch (_: IOException) {
            false
        }
    }

    // ── Config reload (hot-reload stub) ───────────────────────────────

    /**
     * Reload all CTE configuration from disk.
     *
     * Currently a placeholder that verifies the config tree exists and
     * returns true. Will be wired into the full CTE reload pipeline
     * (ARCHITECTURE.md boot steps 2-9) once the remaining engine
     * components (PersonaMerger, SkillEngine, PluginLoader, EventLogger)
     * are implemented.
     *
     * @return true if the CTE tree exists on disk and the reload
     *         pipeline could be triggered, false otherwise.
     */
    fun reloadConfig(): Boolean {
        if (!cteRoot.exists()) {
            Log.w(TAG, "reloadConfig: CTE tree missing at ${cteRoot.path}; seeding defaults first")
            ensureDefaults()
        }
        // TODO: Wire into full CTE reload pipeline:
        //   2. Validate triggers.json vs schema
        //   3. Migrate schema version if needed
        //   4. Load personas, skills, routing JSONs
        //   5. Merge app profiles
        //   6. Eval plugins
        //   7. Preload KeyVault
        //   8. Warm health checks
        //   9. Open event log
        Log.i(TAG, "reloadConfig: CTE tree present at ${cteRoot.path} (reload stub)")
        return true
    }

    // ── Path accessors ────────────────────────────────────────────────

    /** Returns the absolute path to the root CTE config directory. */
    fun getCteRoot(): File = cteRoot

    /** Returns the `configs/` subdirectory. */
    fun getConfigsDir(): File = File(cteRoot, "configs").also { it.mkdirs() }

    /** Returns the `profiles/` subdirectory. */
    fun getProfilesDir(): File = File(cteRoot, "profiles").also { it.mkdirs() }

    /** Returns the `plugins/` subdirectory. */
    fun getPluginsDir(): File = File(cteRoot, "plugins").also { it.mkdirs() }

    /** Returns the `evals/` subdirectory. */
    fun getEvalsDir(): File = File(cteRoot, "evals").also { it.mkdirs() }

    /** Returns the `docs/` subdirectory. */
    fun getDocsDir(): File = File(cteRoot, "docs").also { it.mkdirs() }
}
