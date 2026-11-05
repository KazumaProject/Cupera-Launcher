package io.github.kazumaproject.cuperalauncher

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WorkspaceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: Workspace
    private val databaseName = "workspace-instrumentation.db"

    @Before fun open() {
        context.deleteDatabase(databaseName)
        store = Workspace(context, databaseName)
    }

    @After fun close() {
        store.close()
        context.deleteDatabase(databaseName)
    }

    @Test fun commitSurvivesReopenAndRejectsStaleEdit() {
        val initial = store.read()
        val tiles = listOf(app("personal", 0, 0))
        assertTrue(store.save(initial.revision, tiles))
        store.close()
        store = Workspace(context, databaseName)
        assertFalse(store.save(initial.revision, emptyList()))
        assertEquals(tiles, store.read().tiles)
    }

    @Test fun backupExcludesOtherProfilesAndWidgetIds() {
        val tiles = listOf(app("personal", 0, 0), app("work", 1, 55),
            Tile("widget", TileKind.WIDGET, Slot(0, 4), widgetId = 42, width = 2, height = 2))
        assertTrue(store.save(store.read().revision, tiles))
        store.put("icon_size", "large")
        store.put("private_container", "false")
        val backup = store.export(0)
        assertFalse(backup.contains("work"))
        assertFalse(backup.contains("42"))
        assertFalse(backup.contains("private_container"))
        store.restore(backup, 7)
        assertEquals(listOf(7L, null), store.read().tiles.map { it.key?.profile })
        assertEquals(0, store.read().tiles.last().widgetId)
        assertEquals("large", store.setting("icon_size"))
    }

    @Test fun invalidRestoreLeavesLayoutAndPreferencesUntouched() {
        assertTrue(store.save(store.read().revision, listOf(app("personal", 0, 0))))
        store.put("icon_size", "large")
        val before = store.read()
        val invalid = JSONObject(store.export(0)).apply { getJSONObject("settings").put("icon_size", "unknown") }
        assertTrue(runCatching { store.restore(invalid.toString(), 0) }.isFailure)
        assertEquals(before, store.read())
        assertEquals("large", store.setting("icon_size"))
    }

    @Test fun pageVisibilityRoundTripsWithoutChangingPlacement() {
        val tiles = listOf(app("first", 0, 0), app("second", 0, 0).copy(slot = Slot(1, 0)))
        assertTrue(store.save(store.read().revision, tiles))
        store.setHiddenPages(setOf(1))
        val backup = store.export(0)
        store.setHiddenPages(emptySet())
        store.restore(backup, 0)
        assertEquals(setOf(1), store.hiddenPages())
        assertEquals(listOf(0), store.read().visiblePages(store.hiddenPages()))
        assertEquals(tiles, store.read().tiles)
        val invalid = JSONObject(backup).apply { getJSONObject("settings").put("hidden_pages", "32") }
        assertTrue(runCatching { store.restore(invalid.toString(), 0) }.isFailure)
        assertEquals(setOf(1), store.hiddenPages())
        assertEquals(tiles, store.read().tiles)
    }

    private fun app(id: String, cell: Int, serial: Long) =
        Tile(id, TileKind.APP, Slot(0, cell), AppKey("fixture.app/.$id", serial))
}
