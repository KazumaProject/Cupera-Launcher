package io.github.kazumaproject.cuperalauncher

enum class TileKind { APP, SHORTCUT, FOLDER, WIDGET, CLOCK, DATE }

data class AppKey(val component: String, val profile: Long, val shortcut: String = "")

data class Slot(val page: Int, val cell: Int, val folder: String = "") {
    val capacity: Int get() = when {
        folder.isNotEmpty() -> 54
        page == DOCK -> 4
        else -> 24
    }

    companion object { const val DOCK = -1 }
}

data class Tile(
    val id: String,
    val kind: TileKind,
    val slot: Slot,
    val key: AppKey? = null,
    val name: String = "",
    val widgetId: Int = 0,
    val width: Int = 1,
    val height: Int = 1
) {
    fun occupied(): Set<Int> = buildSet {
        for (y in 0 until height) for (x in 0 until width) add(slot.cell + y * 4 + x)
    }
}

data class Layout(val revision: Long, val tiles: List<Tile>) {
    val pages get() = ((tiles.filter { it.slot.folder.isEmpty() }.maxOfOrNull { it.slot.page } ?: 0) + 1)
        .coerceAtLeast(1)

    fun at(slot: Slot): Tile? = tiles.firstOrNull {
        it.slot.page == slot.page && it.slot.folder == slot.folder && slot.cell in it.occupied()
    }

    fun firstFree(page: Int, width: Int = 1, height: Int = 1, start: Int = 0): Slot? {
        for (p in page until MAX_PAGES) {
            for (cell in (if (p == page) start else 0) until 24) {
                val candidate = Slot(p, cell)
                if (cell % 4 + width > 4 || cell / 4 + height > 6) continue
                if ((0 until height).all { y ->
                    (0 until width).all { x -> at(candidate.copy(cell = cell + y * 4 + x)) == null }
                }) return candidate
            }
        }
        return null
    }

    fun move(id: String, destination: Slot, createFolder: Boolean = false): List<Tile>? {
        val moving = tiles.firstOrNull { it.id == id } ?: return null
        if (moving.slot == destination) return null
        val target = at(destination)
        if (target?.id == id) return null
        val result = tiles.toMutableList()
        result.remove(moving)
        if (createFolder && moving.isApp && target != null) {
            if (target.kind == TileKind.FOLDER) {
                val free = (0 until 54).firstOrNull { at(Slot(0, it, target.id)) == null } ?: return null
                result.add(moving.copy(slot = Slot(0, free, target.id)))
                return removeEmptyFolders(result).takeIf(::valid)
            }
            if (target.isApp && target.slot.folder.isEmpty()) {
                val folderId = java.util.UUID.randomUUID().toString()
                result.remove(target)
                result.add(Tile(folderId, TileKind.FOLDER, target.slot, name = "Folder"))
                result.add(target.copy(slot = Slot(0, 0, folderId)))
                result.add(moving.copy(slot = Slot(0, 1, folderId)))
                return removeEmptyFolders(result).takeIf(::valid)
            }
        }
        if (target != null) {
            if (moving.width != 1 || moving.height != 1 || target.width != 1 || target.height != 1) return null
            if (moving.slot.page == destination.page && moving.slot.folder == destination.folder) {
                val step = if (moving.slot.cell < destination.cell) -1 else 1
                var cell = destination.cell
                val shifts = mutableListOf<Pair<Tile, Slot>>()
                while (cell != moving.slot.cell) {
                    val occupant = at(destination.copy(cell = cell)) ?: break
                    if (occupant.width != 1 || occupant.height != 1) return null
                    shifts += occupant to destination.copy(cell = cell + step)
                    cell += step
                }
                shifts.forEach { (tile, slot) -> result.remove(tile); result.add(tile.copy(slot = slot)) }
            } else {
                result.remove(target)
                result.add(target.copy(slot = moving.slot))
            }
        }
        result.add(moving.copy(slot = destination))
        return removeEmptyFolders(result).takeIf(::valid)
    }

    companion object {
        const val MAX_PAGES = 32
        fun removeEmptyFolders(tiles: List<Tile>): List<Tile> {
            val occupiedFolders = tiles.mapTo(hashSetOf()) { it.slot.folder }
            return tiles.filter { it.kind != TileKind.FOLDER || it.id in occupiedFolders }
        }

        fun valid(tiles: List<Tile>): Boolean {
            if (tiles.size > 4096 || tiles.map { it.id }.toSet().size != tiles.size) return false
            val folders = tiles.filter { it.kind == TileKind.FOLDER }.mapTo(hashSetOf()) { it.id }
            val slots = hashSetOf<Slot>()
            val widgetIds = hashSetOf<Int>()
            return tiles.all { tile ->
                val s = tile.slot
                if (tile.id.isBlank() || tile.name.length > 120) return@all false
                if (tile.isApp != (tile.key != null)) return@all false
                if (tile.kind == TileKind.SHORTCUT && tile.key?.shortcut.isNullOrBlank()) return@all false
                if (tile.kind == TileKind.APP && tile.key?.shortcut?.isNotEmpty() == true) return@all false
                if (s.page !in Slot.DOCK until MAX_PAGES || s.cell !in 0 until s.capacity) return@all false
                if (s.folder.isNotEmpty() && (s.page != 0 || s.folder !in folders || !tile.isApp)) return@all false
                if (tile.isWidget) {
                    if (s.page == Slot.DOCK || s.folder.isNotEmpty() || tile.width !in 1..4 ||
                        tile.height !in 1..6 || s.cell % 4 + tile.width > 4 || s.cell / 4 + tile.height > 6
                    ) return@all false
                    if (tile.widgetId < 0 || tile.kind != TileKind.WIDGET && tile.widgetId != 0) return@all false
                    if (tile.widgetId > 0 && !widgetIds.add(tile.widgetId)) return@all false
                } else if (tile.width != 1 || tile.height != 1 || tile.widgetId != 0) return@all false
                tile.occupied().all { slots.add(s.copy(cell = it)) }
            }
        }
    }
}

val Tile.isApp get() = kind == TileKind.APP || kind == TileKind.SHORTCUT
val Tile.isWidget get() = kind == TileKind.WIDGET || kind == TileKind.CLOCK || kind == TileKind.DATE
