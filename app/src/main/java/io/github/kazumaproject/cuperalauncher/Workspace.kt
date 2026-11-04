package io.github.kazumaproject.cuperalauncher

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

class Workspace(context: Context, databaseName: String = "workspace.db") :
    SQLiteOpenHelper(context.applicationContext, databaseName, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE layout (id INTEGER PRIMARY KEY CHECK(id=1), revision INTEGER NOT NULL, data TEXT NOT NULL)")
        db.execSQL("INSERT INTO layout VALUES (1, 0, '[]')")
        db.execSQL("CREATE TABLE settings (name TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Missing workspace migration from $oldVersion to $newVersion")
    }

    fun read(): Layout = read(readableDatabase)

    private fun read(db: SQLiteDatabase): Layout = db.rawQuery("SELECT revision, data FROM layout", null).use {
        check(it.moveToFirst())
        Layout(it.getLong(0), decode(JSONArray(it.getString(1))))
    }

    fun save(revision: Long, tiles: List<Tile>): Boolean {
        if (!Layout.valid(tiles)) return false
        val statement = writableDatabase.compileStatement("UPDATE layout SET data=?, revision=revision+1 WHERE id=1 AND revision=?")
        return statement.use {
            it.bindString(1, encode(tiles).toString())
            it.bindLong(2, revision)
            it.executeUpdateDelete() == 1
        }
    }

    fun setting(name: String, default: String = ""): String =
        readableDatabase.rawQuery("SELECT value FROM settings WHERE name=?", arrayOf(name)).use {
            if (it.moveToFirst()) it.getString(0) else default
        }

    fun settingEnabled(name: String, default: Boolean = true) = setting(name, default.toString()) == "true"

    internal fun homeDisplaySettings(): Map<String, String> = readableDatabase.rawQuery(
        """SELECT name, value FROM settings WHERE name IN
            ('wallpaper', 'dock', 'search_button', 'page_dots', 'icon_size', 'labels', 'badges', 'widget_seconds', 'motion')""",
        null
    ).use { cursor ->
        buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
    }

    fun hiddenPages(): Set<Int> = setting("hidden_pages").split(',')
        .mapNotNull { it.toIntOrNull()?.takeIf { page -> page in 0 until Layout.MAX_PAGES } }.toSet()

    fun setHiddenPages(pages: Set<Int>) = put("hidden_pages", pages.sorted().joinToString(","))

    fun put(name: String, value: String) = put(writableDatabase, name, value)

    private fun put(db: SQLiteDatabase, name: String, value: String) {
        db.execSQL("INSERT OR REPLACE INTO settings VALUES (?, ?)", arrayOf(name, value))
    }

    fun export(personalSerial: Long): String {
        val values = JSONObject()
        backupSettings.forEach { (key, default) -> values.put(key, setting(key, default)) }
        val personal = read().tiles.filter { it.key == null || it.key.profile == personalSerial }.map {
            it.copy(key = it.key?.copy(profile = 0), widgetId = 0)
        }
        return JSONObject().put("version", 1).put("settings", values)
            .put("tiles", encode(Layout.removeEmptyFolders(personal))).toString(2)
    }

    fun restore(text: String, personalSerial: Long) {
        val root = JSONObject(text)
        require(root.getInt("version") == 1)
        val imported = decode(root.getJSONArray("tiles"))
        require(imported.all { (it.key == null || it.key.profile == 0L) && it.widgetId == 0 })
        val tiles = imported.map { it.copy(key = it.key?.copy(profile = personalSerial)) }
        require(Layout.valid(tiles))
        val settings = root.getJSONObject("settings")
        val values = backupSettings.mapValues { (name, default) ->
            settings.optString(name, default).also { require(allowed(name, it)) }
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE layout SET data=?, revision=revision+1 WHERE id=1", arrayOf(encode(tiles).toString()))
            values.forEach { (name, value) -> put(db, name, value) }
            put(db, "seeded", "true")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun allowed(name: String, value: String) = when (name) {
        "appearance" -> value in setOf("system", "light", "dark")
        "wallpaper" -> value in setOf("tide", "dusk", "system")
        "icon_size" -> value in setOf("small", "regular", "large")
        "motion" -> value in setOf("system", "reduced")
        "hidden_pages" -> value.isEmpty() || value.length <= Layout.MAX_PAGES * 3 &&
            value.split(',').all { it.toIntOrNull()?.let { page -> page in 0 until Layout.MAX_PAGES } == true }
        else -> value == "true" || value == "false"
    }

    companion object {
        private val backupSettings = mapOf(
            "appearance" to "system", "wallpaper" to "tide", "icon_size" to "regular",
            "labels" to "true", "dock" to "true", "search_gesture" to "true", "motion" to "system",
            "badges" to "true", "add_new" to "false", "folder_drop" to "true", "search_button" to "true",
            "library_groups" to "true", "page_dots" to "true", "widget_seconds" to "false", "hidden_pages" to ""
        )

        private fun encode(tiles: List<Tile>) = JSONArray().apply {
            tiles.forEach { t ->
                put(JSONObject().put("id", t.id).put("kind", t.kind.name)
                    .put("page", t.slot.page).put("cell", t.slot.cell).put("folder", t.slot.folder)
                    .put("name", t.name).put("widget", t.widgetId).put("width", t.width).put("height", t.height)
                    .apply {
                        t.key?.let {
                            put("app", JSONObject().put("component", it.component)
                                .put("profile", it.profile).put("shortcut", it.shortcut))
                        }
                    })
            }
        }

        private fun decode(array: JSONArray): List<Tile> {
            require(array.length() <= 4096)
            return List(array.length()) { i ->
                val t = array.getJSONObject(i)
                val key = t.optJSONObject("app")?.let {
                    AppKey(it.getString("component"), it.getLong("profile"), it.getString("shortcut"))
                }
                Tile(t.getString("id"), TileKind.valueOf(t.getString("kind")),
                    Slot(t.getInt("page"), t.getInt("cell"), t.getString("folder")), key,
                    t.getString("name"), t.getInt("widget"), t.getInt("width"), t.getInt("height"))
            }
        }
    }
}
