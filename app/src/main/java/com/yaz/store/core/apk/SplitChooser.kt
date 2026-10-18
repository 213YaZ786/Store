package com.yaz.store.core.apk

// Ported from App Manager (GPL-3.0-or-later), SplitApkChooser: getInitialSelections, select, deselect and
// getUnsupportedOrRequiredSplitIds:
// https://github.com/MuntashirAkon/AppManager/blob/master/app/src/main/java/io/github/muntashirakon/AppManager/apk/splitapk/SplitApkChooser.java
// Changes: pure Kotlin on [SplitEntry] with no dialog; selections are entry ids; an ABI split the phone
// cannot run is never picked by default (App Manager disables its row but could still tick it).

/**
 * Which APKs of a bundle go in. [entries] are sorted by type then rank, as ApkFile does, so the first
 * entry of a type for a feature is the best fit for the phone.
 */
class SplitChooser(entries: List<SplitEntry>) {

    val entries: List<SplitEntry> = entries.sortedWith(SplitEntry.ORDER)

    private val selectedIds = LinkedHashSet<String>()

    /** The types already chosen for each feature (null is the base app): mSeenSplits. */
    private val seen = HashMap<String?, MutableSet<SplitType>>()

    val selected: Set<String> get() = selectedIds

    fun isSelected(id: String): Boolean = id in selectedIds

    private fun seenFor(feature: String?): MutableSet<SplitType> = seen.getOrPut(feature) { mutableSetOf() }

    /**
     * getInitialSelections. [installedSplitNames] are the splits of the app already on the phone: those
     * present in this bundle stay chosen. Then every required split, then one split of each type for each
     * feature chosen, the best ranked first.
     */
    fun initialSelections(installedSplitNames: Set<String> = emptySet()): Set<String> {
        selectedIds.clear()
        seen.clear()
        if (installedSplitNames.isNotEmpty()) {
            for (entry in entries) {
                if (entry.name !in installedSplitNames) continue
                selectedIds.add(entry.id)
                seenFor(entry.feature).add(entry.type)
            }
        }
        for (entry in entries) {
            if (entry.id in selectedIds) continue
            if (entry.required) {
                selectedIds.add(entry.id)
                seenFor(entry.feature).add(entry.type)
            }
        }
        // Dependencies of the features chosen: only the first of a type works as the entries are ranked.
        for (entry in entries) {
            if (entry.id in selectedIds) continue
            val types = seen[entry.feature] ?: continue
            if (autoType(entry) && entry.supported && types.add(entry.type)) selectedIds.add(entry.id)
        }
        return selectedIds
    }

    /** Density, ABI and locale splits are picked one per feature; the others never by default. */
    private fun autoType(entry: SplitEntry) =
        entry.type == SplitType.DENSITY || entry.type == SplitType.ABI || entry.type == SplitType.LOCALE

    /**
     * select: ticks [id] and, for its feature, one split of every type not yet seen. Returns the ids
     * ticked besides [id]; nothing when the row is locked.
     */
    fun select(id: String): Set<String> {
        val chosen = entries.firstOrNull { it.id == id } ?: return emptySet()
        if (!chosen.supported) return emptySet()
        val types = seenFor(chosen.feature)
        selectedIds.add(chosen.id)
        val added = LinkedHashSet<String>()
        for (entry in entries) {
            if (entry.feature != chosen.feature || entry.type == chosen.type) continue
            if (entry.id in selectedIds || !entry.supported) continue
            if (autoType(entry) && types.add(entry.type)) {
                selectedIds.add(entry.id)
                added.add(entry.id)
            }
        }
        return added
    }

    /**
     * deselect: unticks [id]. A required split stays. A feature split takes all its splits with it. Any
     * other split goes only if another of its type and feature stays ticked. Returns the ids unticked
     * (including [id]), or null when the split must stay.
     */
    fun deselect(id: String): Set<String>? {
        val entry = entries.firstOrNull { it.id == id } ?: return null
        if (entry.required) return null
        if (entry.type == SplitType.FEATURE) {
            seen.remove(entry.feature)
            val removed = LinkedHashSet<String>()
            for (other in entries) {
                if (other.feature == entry.feature && selectedIds.remove(other.id)) removed.add(other.id)
            }
            return removed
        }
        val another = entries.any {
            it.id != entry.id && it.type == entry.type && it.feature == entry.feature && it.id in selectedIds
        }
        if (!another) return null
        selectedIds.remove(entry.id)
        return setOf(entry.id)
    }

    /** A row is toggled: [select] or [deselect]. Returns false when it cannot change. */
    fun toggle(id: String): Boolean {
        val entry = entries.firstOrNull { it.id == id } ?: return false
        if (entry.locked) return false
        return if (id in selectedIds) deselect(id) != null else {
            select(id)
            true
        }
    }

    /** The entries to install, in order. */
    fun chosen(): List<SplitEntry> = entries.filter { it.id in selectedIds }
}
