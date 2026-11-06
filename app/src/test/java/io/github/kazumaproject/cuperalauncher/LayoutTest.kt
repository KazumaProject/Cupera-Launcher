package io.github.kazumaproject.cuperalauncher

import org.junit.Assert.*
import org.junit.Test

class LayoutTest {
    private fun app(id: String, cell: Int, page: Int = 0, profile: Long = 0) =
        Tile(id, TileKind.APP, Slot(page, cell), AppKey("test.pkg/.$id", profile))

    @Test fun occupiedCellsShiftAsOneMove() {
        val tiles = listOf(app("a", 0), app("b", 1), app("c", 2))
        val original = Layout(9, tiles)
        val moved = requireNotNull(original.move("a", Slot(0, 2))).associateBy { it.id }
        assertEquals(2, moved.getValue("a").slot.cell)
        assertEquals(0, moved.getValue("b").slot.cell)
        assertEquals(1, moved.getValue("c").slot.cell)
        assertEquals(0, original.tiles.first().slot.cell)
    }

    @Test fun moveToNewPagePreservesProfileIdentity() {
        val original = Layout(0, listOf(app("a", 0), app("a-work", 1, profile = 10)))
        val changed = Layout(1, requireNotNull(original.move("a-work", Slot(1, 4))))
        assertEquals(2, changed.pages)
        assertEquals(10L, changed.at(Slot(1, 4))?.key?.profile)
        assertNotNull(changed.at(Slot(0, 0)))
    }

    @Test fun insertingAtEitherDockEdgePreservesTheOrderOfExistingApps() {
        val original = Layout(0, listOf(app("a", 0, Slot.DOCK), app("b", 3, Slot.DOCK), app("c", 1)))
        val before = requireNotNull(original.moveToDockEdge("c", DockEdge.START))
        val after = requireNotNull(original.moveToDockEdge("c", DockEdge.END))
        assertEquals(listOf("c", "a", "b"), before.sortedBy { it.slot.cell }.map { it.id })
        assertEquals(listOf("a", "b", "c"), after.sortedBy { it.slot.cell }.map { it.id })
        assertTrue(before.all { it.slot.page == Slot.DOCK })
        assertTrue(after.all { it.slot.page == Slot.DOCK })
        assertEquals(Slot(0, 1), original.tiles.last().slot)
    }

    @Test fun droppingAnExtraAppAtTheEdgeOfAFullDockIsRejected() {
        val dock = (0 until 4).map { app("dock$it", it, Slot.DOCK) }
        val original = Layout(0, dock + app("home", 1))
        assertNull(original.moveToDockEdge("home", DockEdge.START))
        assertNull(original.moveToDockEdge("home", DockEdge.END))
    }

    @Test fun folderCreatesMovesAndDisappearsWhenEmpty() {
        val original = Layout(0, listOf(app("a", 0), app("b", 1)))
        val folderLayout = Layout(1, requireNotNull(original.move("a", Slot(0, 1), true)))
        val folder = requireNotNull(folderLayout.at(Slot(0, 0)))
        assertEquals(TileKind.FOLDER, folder.kind)
        assertEquals(2, folderLayout.tiles.count { it.slot.folder == folder.id })
        val one = Layout(2, requireNotNull(folderLayout.move("a", Slot(0, 4))))
        assertNotNull(one.at(Slot(0, 0)))
        val both = Layout(3, requireNotNull(one.move("b", Slot(0, 5))))
        assertNull(both.at(Slot(0, 0)))
    }

    @Test fun groupingCompactsTheOccupiedRunButPreservesAnExistingGap() {
        val original = Layout(0, listOf(app("source", 0), app("target", 1), app("next", 2), app("after-gap", 4)))
        val grouped = Layout(1, requireNotNull(original.move("source", Slot(0, 1), createFolder = true)))

        assertEquals(TileKind.FOLDER, grouped.at(Slot(0, 0))?.kind)
        assertEquals("next", grouped.at(Slot(0, 1))?.id)
        assertNull(grouped.at(Slot(0, 2)))
        assertEquals("after-gap", grouped.at(Slot(0, 4))?.id)
    }

    @Test fun widgetsCannotOverlapOrLeaveGrid() {
        val widget = Tile("w", TileKind.WIDGET, Slot(0, 0), widgetId = 5, width = 2, height = 2)
        val layout = Layout(0, listOf(widget, app("a", 10)))
        assertFalse(Layout.valid(listOf(widget, app("x", 4))))
        assertNull(layout.move("a", Slot(0, 4)))
        assertNull(layout.move("w", Slot(0, 3)))
        assertNull(layout.move("w", Slot(Slot.DOCK, 0)))
        assertEquals(Slot(0, 7), layout.firstFree(0, 1, 2, 4))
    }

    @Test fun invalidLayoutsAreRejected() {
        val one = app("a", 0)
        assertFalse(Layout.valid(listOf(one, one.copy(slot = Slot(0, 1)))))
        assertFalse(Layout.valid(listOf(one, app("b", 0))))
        assertFalse(Layout.valid(listOf(one.copy(slot = Slot(0, 0, "absent")))))
        assertFalse(Layout.valid(listOf(one.copy(slot = Slot(Layout.MAX_PAGES, 0)))))
    }
}
