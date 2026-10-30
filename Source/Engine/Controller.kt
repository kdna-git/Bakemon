package engine

import bakemon.MoveDef

/** What a monster does on its turn. */
sealed class Action {
    /**
     * Use [move]. [slot] is the battler's MoveSlot (null for the fallback move).
     * [target] is the chosen target when the move's target needsChoice, otherwise ignored (may be null).
     */
    data class UseMove(val move: MoveDef, val slot: MoveSlot?, val target: Battler?) : Action()

    /** Switch the acting monster out for [replacement] (a benched teammate). */
    data class Switch(val replacement: Battler) : Action()

    /** Do nothing (used when nothing is possible). */
    data object Pass : Action()
}

/**
 * Decides for one side: a human in the terminal (ui.HumanController) or the AI (engine.AiController).
 * The manager validates every answer and falls back to a legal action if it is not legal.
 */
interface Controller {
    val name: String

    /** Called once per active monster per round (speed_order) or once per turn (action_bar). */
    fun chooseAction(battle: Battle, actor: Battler): Action

    /** A monster in [slot] fainted: choose who replaces it among [options] (non-empty). */
    fun chooseReplacement(battle: Battle, side: Side, slot: Int, options: List<Battler>): Battler
}
