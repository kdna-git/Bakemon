package bakemon

import engine.Formula
import engine.TargetSelector
import engine.Trigger
import util.JsonObj
import util.SetupException
import java.io.File

/** A held item from Items.json. Single-use items remove themselves with the `consumeItem` effect. */
class ItemDef(
    val id: String,
    val name: String,
    val description: String,
    /** stat id -> multiplier formula, applied while the owner holds this item. */
    val statModifiers: Map<String, Formula>,
    /** Effect target defaults to "self". */
    val triggers: List<Trigger>,
) {
    override fun toString(): String = name
}

/** All items of a setup (Items.json). Keeps file order. */
class ItemManager(val items: Map<String, ItemDef>) {
    operator fun get(id: String): ItemDef? = items[id]

    /** Throws util.SetupException("Unknown item '<id>'") if missing. */
    fun require(id: String): ItemDef = items[id] ?: throw SetupException("Unknown item '$id'")

    fun all(): List<ItemDef> = items.values.toList()

    companion object {
        private val KEYS = setOf("id", "name", "description", "statModifiers", "triggers")

        /**
         * Parses Items.json (bare array or {"items": [...]}). Bad entries are reported and skipped.
         * Triggers are parsed with engine.Trigger.parseList(obj, "triggers", TargetSelector.SELF).
         */
        fun load(file: File, report: LoadReport): ItemManager = ItemManager(
            LoadSupport.loadList(file, report, "items", "item", LoadSupport.Missing.WARN) { parse(it, report) },
        )

        private fun parse(obj: JsonObj, report: LoadReport): ItemDef {
            LoadSupport.warnUnknownKeys(obj, KEYS, report)
            LoadSupport.warnTriggerKeys(obj, report)
            val id = obj.string("id")
            return ItemDef(
                id = id,
                name = obj.string("name", id),
                description = obj.string("description", ""),
                statModifiers = LoadSupport.statModifiers(obj),
                triggers = Trigger.parseList(obj, "triggers", TargetSelector.SELF),
            )
        }
    }
}
