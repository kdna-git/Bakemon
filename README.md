# Bakemon

A small Kotlin engine that runs turn-based monster battles defined entirely by JSON files.
One engine runs both:

- **Classic-style** battles (`"turnSystem": "speed_order"`): every active monster picks an action each round, and actions run by priority, then speed.
- **Action-bar / ATB-style** battles (`"turnSystem": "action_bar"`): each monster fills an action bar based on its speed, and whoever fills it first acts.

Each folder in `Setups/` is a separate game (a **setup**) with its own monsters, moves, types and rules.

## Building and running

```bat
Bakemon.bat              :: compile build\jar\Bakemon.jar and package build\Bakemon\Bakemon.exe
Bakemon.bat jar          :: compile only the jar
Bakemon.bat run          :: run the jar (builds it first if missing); extra args are forwarded
Bakemon.bat test         :: compile and run the self tests in Tests\
Bakemon.bat clean        :: delete build\
```

The first build downloads the Kotlin compiler into `.tools\`. You need a JDK 17+ (`java` and `jpackage`).

Command-line options for the app (`Bakemon.exe [options]` or `Bakemon.bat run [options]`):

| Option | Meaning |
|---|---|
| `--setups <dir>` | Folder that contains the setups (default: auto-detects the `Setups` folder) |
| `--setup <name>` | Open this setup directly |
| `--seed <n>` | Fix the random seed so battles can be reproduced |
| `--no-color` / `--color` | Turn ANSI colours off or on (auto-detected by default) |
| `--validate` | Load every setup, print errors and warnings, then exit (exit code 1 if any setup is invalid) |
| `--simulate <setup> [--count N] [--teams A,B] [--ai easy\|normal\|hard] [--fields a,b]` | Run N AI-vs-AI battles without the UI and print statistics. `--fields` adds starting [fields](#fieldsjson) (Fields.json ids; permanent; side fields go on both sides) to every battle, on top of `Rules.json` `startFields`. An unknown id is an error (exit code 2) |

## Demo setup

`Setups/Demo` is a small example game (`speed_order`): 6 monsters, 4 types, 3v3 singles. Start from it or make your own with the setup creator.

It is the only setup in this repository: any other folder in `Setups/` is git-ignored. Self tests that need a larger setup are skipped when it is not there.

## Setup creator

The setup creator makes new setups and edits any setup through menus, with no JSON or
formulas to write. Open it from the main menu (**Setup creator**), or from the setup selection screen
(**Create a new setup** / **Setup creator**). The selection screen offers both even when no setup can be loaded, so a
new or broken setup can always be reached and fixed.

**Home.** The setups with their status: `OK`, `N warnings`, `N errors`, `incomplete (N things missing)` (something
needed to battle is missing) or `N files cannot be read` (a JSON syntax error). Pick a setup to edit it, or create a
new setup, rename a setup folder, or delete a setup (asked twice; "no" is the default both times).

**A new setup** starts from rule templates: a name, how turns work (Classic-style rounds or an action-bar turn order), which
stats monsters have (Classic, Action Bar, Simple or your own list), then the rules as a list of questions (team size,
levels, stat growth, damage, accuracy, critical hits, PP or cooldowns...). Each choice has a one-line help and each
question a recommended answer in brackets: Enter accepts it. The rules and their formulas are generated from the answers. The setup starts
empty (plus a typeless Desperate Strike fallback move when moves use PP); it is saved at once and a checklist shows what is
still needed to battle (types, moves, bakemons...), with a link to each section.

**The setup editor** has Rules, Types, Statuses, Moves, Abilities, Items, Fields and Bakemons, plus:
- *Checklist*: what is still needed to battle, with links;
- *Validate*: the full check of the setup (the one `--validate` does), without saving;
- *Save*: lists the changes per file (added / changed / removed entries), writes only the changed files (the unchanged
  parts keep their exact text), then shows the validation. An incomplete setup can be saved. When the setup that is
  loaded for battles is saved, it is reloaded;
- *Discard changes*, *Raw JSON files* (view or replace a whole file; the way to repair a file with a syntax error),
  and *Back*, which asks about unsaved changes. Nothing is written before Save.

**Each section** lists its entries, 20 per page: type text at the prompt to search (by name, id, type, kind...).
Pick an entry to see it in plain words, then:
- *Edit*: a form with every field in plain words. Done keeps the changes, Cancel drops them;
- *Copy*, under a new name;
- *Rename id*: shows every place that uses the id (bakemons, moves, effects, teams, rules, the type chart, formulas)
  and updates them all;
- *Delete*: shows where it is used and offers to remove those references too (references inside formulas must be
  fixed by hand; the saved teams that used it are named so you can check them in the team creator);
- *View / Replace the raw JSON* of the entry.

*Add* is a short wizard, then the form: statuses, abilities, items and fields start from a ready-made behaviour if you
want one (damage over time, skip turns, paralysis-like, stat change, shield, revive on death; +X% to a stat, boost a
type, immunity to a type, heal each turn, status on contact, survive a lethal hit, used-up items; weather, screen,
speed boost, entry hazard, reverse turn order, block statuses / stat drops / moves). A move asks its type, category and
how strong it is (fixed power, doubles below X% HP, stronger at low HP, doubles against a status, power from a stat,
fixed damage, grows with each hit, per buff / debuff on the target, per ally standing, faster than the target, the HP
the user lost, doubles if the target already acted), then its **hits** (one, a fixed number, a random range such as 2
to 5, or a formula; with several hits: does the power grow with each hit, does each hit pick a random enemy, does each
hit roll the accuracy), how much of the target's **defense it ignores**, and whether it is a **charge move** (turns of
charging, out of reach meanwhile, a message, and "skip the charge when..." built from conditions, e.g. a field is
active). The move form has the same rows, plus the new automatic targets (the weakest of the team, the weakest enemy,
a random enemy for each hit). Something that happens on one hit only ("the 3rd hit stuns") is an effect "when it
hits" with the condition "It is hit number N". A bakemon asks its name, types, one base value per stat (named as in
the rules), abilities, learnable moves (with search and the shortcuts "all moves of its types" and "all moves
without a type"), description and image
(default `Assets/<id>.png`; a missing image is only a warning). Types have a colour and a chart editor: for each
matchup pick strong (x2), weak (x0.5), immune (x0), neutral, a multiplier already used in the chart, or another one.

**Effects are triggers**, shown as one line each ("When it hits, 30% chance: apply a status (status: Burn)"). Opening
one shows WHEN (the event, in plain words: also "when it is targeted by a move", "when it has been attacked", "when
an ally / enemy takes damage", "when an ally / enemy uses a move", "when it applies a status to someone" and "when its
HP drops below X%", which is "when it takes damage" with the condition "its HP just dropped below X%", once per battle
unless you change it), IF (conditions that must all hold: HP below X%, has a status or a type, number of buffs /
debuffs, the move's type / category / flag, critical hit, super effective, hit number N, the last hit, the attack
landed, the other monster is an enemy, already acted, faster than the other, allies standing, charging, a field is
active...), the chance, how often (every time, once per battle, at most N times), a message, and what it DOES. Long
lists of choices come in two steps, a group then a choice: *Damage* (% of its HP, recoil, % of a stat, an amount, an
extra strike with the damage formula, multiply the damage), *Healing and HP* (heal, drain, shields, set HP, share HP
within a group, swap HP, revive), *Statuses and stats* (apply, remove, steal, copy or pass on statuses, raise or lower
stats, copy stat changes), *Moves and turns* (strike back with its first move, join an ally's attack, use this move
again, use any move, an extra turn, a delayed effect whose own effects are edited like a DO list, cancel the move,
action bar, cooldowns), *Switching* (drag the target out, switch out like U-turn) and *Fields, items and messages*.
The "% of its HP" effects work on groups: "heal its allies 20% of its max HP" gives each ally 20% of **its own** max
HP (`recipient`). Targets include the weakest of its team, a random ally, the weakest / healthiest enemy and, when the
other monster used a move, the target of that move. Only the choices that work in that place are offered (for example
"multiply the damage" only when damage is about to be dealt or taken, "strike back" only when it was attacked).

**Rules.** *Change the rules* asks the template questions again (the answers are read back from Rules.json) and
regenerates only the parts you changed. Parts written by hand are marked "(edited by hand)" and are only replaced if
you agree. Adding a stat gives every bakemon a base value for it; removing one takes it out of their base stats.
*Rename a stat id* updates every reference (base stats, stat points of teams, stat changes, formulas such as
`user.atk`). *Edit one value (advanced)* and the raw JSON views edit Rules.json directly.

**No formulas needed.** Values are numbers and choices. Where a formula would also work, the prompt says
`a = advanced formula`: type `a`, then a formula (see [Formulas](#formulas)). It is checked at once, in the place it
goes, and problems are explained (for example "unknown variable 'user.bogus'"); it is asked again until it is valid
or you cancel. Formulas only appear in these advanced choices and in the raw JSON views.

**What it cannot do.** Everything the forms cannot express is kept exactly as it is: such a condition or effect is
shown as an "advanced" part (a formula or raw JSON), and a trigger with keys the forms do not know is shown as a
"Custom trigger" that can only be replaced as raw JSON (or left untouched). The rule template only knows its own
formulas: other damage or stat formulas are "edited by hand" and are changed with *Edit one value* or raw JSON.
The template answers are not stored; they are recognised from Rules.json each time. Images are not created, teams
are made in the team creator, and Fields.json "special rules" (reverse order, blocks...) come from the ready-made
behaviours or are edited as JSON. "The power grows with each hit" is offered for a fixed power only (an advanced power
formula can use `hitIndex`), and a charge with keys or values the form does not know is kept as advanced JSON.

## Project layout

```
Bakemon
├ Main.kt                     entry point (menus, command-line options)
├ Bakemon.bat                 build script
├ Source
│ ├ Util/Json.kt              JSON parser/writer + typed accessors with readable errors
│ ├ Engine
│ │ ├ Expression.kt           formula language used everywhere in the JSON
│ │ ├ ActionEvents.kt         event catalog, triggers, event dispatch, battle log
│ │ ├ Effects.kt              effect catalog (damage, heal, applyStatus...) and their execution
│ │ ├ DamageCalculator.kt     hit chance, crits, hit types, type effectiveness, damage formula
│ │ ├ BattleState.kt          runtime state: Battle, Side, Battler, formula context
│ │ ├ Controller.kt           Action + Controller interface (who decides what to do)
│ │ ├ AI.kt                   AI controller
│ │ └ Manager.kt              battle loop: turn systems, move execution, fainting, victory
│ ├ Bakemon
│ │ ├ Rules.kt                Rules.json
│ │ ├ Types.kt                Types.json (type chart)
│ │ ├ Bakedex.kt              Bakemons.json (species)
│ │ ├ Bakemon.kt              a configured monster (team member) + Team
│ │ ├ Moves.kt                Moves.json
│ │ ├ Ability.kt              Abilities.json
│ │ ├ Item.kt                 Items.json
│ │ ├ Status.kt               Statuses.json
│ │ ├ Fields.kt               Fields.json (weather, terrain, screens, hazards...) + start fields
│ │ └ Setup.kt                loads and cross-validates a whole setup folder, team storage
│ ├ Creator                   setup creator logic, independent of the UI (drafts, references, rule templates,
│ │                           recipes, triggers, forms, presets)
│ └ UI
│   ├ Terminal.kt             terminal helpers (menus, input, colours, bars)
│   ├ Combat.kt               battle screen + human controller
│   ├ TeamCreator.kt          team editor + Bakedex browser
│   ├ SetupCreator.kt         setup creator: home, new setup, setup editor, sections, rules
│   ├ CreatorForms.kt         setup creator: entry forms, add wizards, trigger editor, type chart editor
│   └ CreatorInput.kt         setup creator: choices, paged lists, numbers / advanced formulas, recipe values
├ Setups
│ └ <SetupName>
│   ├ Rules.json  Types.json  Bakemons.json  Moves.json
│   ├ Abilities.json  Items.json  Statuses.json  Fields.json (optional)
│   ├ Assets/                 monster images (not used by the terminal UI)
│   ├ Teams/                  teams saved by the team creator
│   └ NOTES.md                optional notes about the setup (not read by the engine)
└ Tests                       self tests (Bakemon.bat test)
```

---

## License

MIT, see [LICENSE](LICENSE). It covers `Setups/Demo` but no other setup.

---

# Writing a setup

Every list file can be either a bare JSON array or an object with one list key
(`{"bakemons": [...]}`, `{"moves": [...]}`, `{"abilities": [...]}`, `{"items": [...]}`, `{"statuses": [...]}`, `{"fields": [...]}`).
Ids are free text. By convention they are `lower_snake_case`, and every id must be unique within its file.
Unknown keys produce a **warning**, so a typo like `"acuracy"` gets reported instead of silently ignored.

## Formulas

**Fields marked "number or formula" accept a formula string instead of a number**, e.g. `"power": "user.spe > target.spe ? 80 : 40"`.
These are: move `power`, `hits`, `critBonus`, `ignoreDefense` and `charge.skipIf`; the Rules formulas (`statFormulas`,
`statStages.multiplier`, `damage`, `hitChance`, `critChance`, `critMultiplier`, `hitTypes` `chance` and `multiplier`,
`actionBar.fill`, `actionBar.start`, `startFields` `duration`); status and field `duration`; every `statModifiers`
value; the field `rules` formulas; trigger `condition` and `chance`; and the numbers of effects (`amount`, `chance`,
`duration`, `stacks`, `stages`, `value`, `count`, `turns`, `power`).
Every other number (accuracy, priority, pp, cooldown, maxStacks, trigger limit, type-chart multipliers, base stats,
team/level/statPoints/statStages limits, minDamage, actionBar.size, turnLimit...) must be a plain JSON number.

Syntax:

| | |
|---|---|
| numbers | `12`, `0.5` |
| strings | `'fire'` or `"fire"` (single quotes are easier inside JSON) |
| booleans | `true`, `false` (true = 1, false = 0; any non-zero number counts as true) |
| arithmetic | `+ - * / %`, `^` (power, right-associative), unary `-` |
| comparison | `< <= > >= == !=` (`==`/`!=` also compare strings) |
| logic | `&&  \|\|  !` |
| conditional | `cond ? a : b` |
| grouping | `( ... )` |
| variables | `user.atk`, `target.hpPercent`, `move.type`, `damage`, ... (see below) |

Division by zero and the square root of a negative number give `0`.

Built-in functions: `min(a, b, ...)`, `max(a, b, ...)`, `floor(x)`, `ceil(x)`, `round(x)`, `abs(x)`, `sqrt(x)`,
`pow(a, b)`, `clamp(x, lo, hi)`, `if(cond, a, b)`, `random()` (0 to 1), `random(a, b)` (a real number between a and b),
`randint(a, b)` (an integer from a to b inclusive), `chance(p)` (1 with probability p percent, otherwise 0).

Battle functions: `hasType(m, 'fire')`, `hasStatus(m, 'burn')`, `statusStacks(m, 'burn')`, `hasAbility(m, 'blaze')`,
`hasItem(m)`, `hasItem(m, 'leftovers')`, `hasFlag('contact')` (the current move has that flag),
`statusCount(m)` (how many statuses `m` has; each status counts once, whatever its stacks) and
`statusCount(m, 'debuff')` (only those of one kind: `'major'`, `'volatile'`, `'buff'` or `'debuff'`).
Here `m` is a monster reference: `self`, `other`, `user`, `target` or `recipient`.
`hasAbility` and `hasItem` read `0` while a field's `disableAbilities` / `disableItems` rule suppresses them.

Field functions (see [Fields.json](#fieldsjson)):

| function | value |
|---|---|
| `fieldActive('rain')` | 1 if that field is active globally or on any side |
| `sideField(m, 'reflect')` | 1 if `m`'s side has that side field |
| `fieldStacks('rain')` | stacks of that global field (0 if inactive) |
| `sideFieldStacks(m, 'spikes')` | stacks of that side field on `m`'s side (0 if absent) |
| `fieldTurns('rain')` | turns left of that global field (0 if permanent or inactive) |
| `typeMultiplier('rock', m)` | type chart multiplier of an attack of that type against `m`'s current types (1 if `m` is absent), e.g. for a Stealth Rock-like hazard |
| `sameSide(a, b)` | 1 if both monsters are present and on the same side |

A field id or type id written as a string literal (`fieldActive('rain')`) must exist; `fieldStacks`/`fieldTurns` on a
side field and `sideField`/`sideFieldStacks` on a global field get a warning (they always read 0).
`random`, `randint` and `chance` cannot be used in `Rules.statFormulas` (a monster's stats must not change between calculations)
nor in `statModifiers` (an effective stat must be the same every time it is read: the turn order preview, the battle
screen and the AI read stats too, and must not change the battle by rolling its dice).

### Monster references

| reference | in ability / item / status triggers | in moves and move triggers | in Rules damage/hit/crit/hit type formulas |
|---|---|---|---|
| `self` | the owner of the trigger | the move user | the attacker |
| `other` | the other monster of the event (may be absent) | the current target | the defender |
| `user` | the move user if a move is involved, otherwise `self` | the move user | the attacker |
| `target` | the move target if a move is involved, otherwise `other` | the current target | the defender |
| `recipient` | the monster the effect is being applied to | the monster the effect is being applied to | - |

`recipient` only exists in the values of effects that have a target (`amount`, `chance`, `duration`, `stacks`, `stages`,
`count`, `power`...): the value is computed again for each monster the effect resolves to, with that monster as
`recipient`. So "heal every ally 20% of **its own** max HP" is
`{"type": "heal", "target": "allies", "amount": "recipient.maxHp * 0.2"}` (with `self.maxHp` they would all get 20%
of the owner's). In a `useMove` effect it is the actor. Elsewhere (trigger conditions and chances, durations, the
values of `multiplyDamage`, `setField` and `delay`) the setup check reports it as an error.

If a reference is absent (for example `other` during `turnEnd`), its numbers read as `0` and its strings as `''`.
In a **status's** triggers, `other` is the monster that applied the status (if it is still active) whenever the
event itself has no other monster. For example, a leech-seed status can drain HP on `turnEnd` and heal `other`.

Monster fields (`m` is any reference):

| field | meaning |
|---|---|
| `m.hp`, `m.maxHp`, `m.hpPercent` | current HP, max HP, HP percent (0-100) |
| `m.level` | level |
| `m.<stat>` | **effective** stat: raw stat x stage multiplier x modifiers from ability/item/statuses (e.g. `m.atk`) |
| `m.raw.<stat>` | stat before stages and modifiers |
| `m.base.<stat>` | species base stat |
| `m.stage.<stat>` | current stat stage |
| `m.gauge` | action-bar fill, 0-100 percent |
| `m.shield` | remaining shield points |
| `m.fainted` | 1 if fainted |
| `m.revives` | how many times it was revived in this battle |
| `m.reviveBlocked` | 1 if `preventRevive` made it unrevivable |
| `m.acted` | speed_order: 1 once it acted this round (also for a monster that came in during the round, which does not act); action_bar: 1 while it is the monster whose turn it is |
| `m.charging` | 1 while it is charging a move (see `charge` in [Moves.json](#movesjson)) |
| `m.id`, `m.name` | species id, display name (strings) |

Inside `statModifiers` formulas, `self.<stat>` returns the **raw** stat, which avoids infinite recursion.

### Move fields

`move.id`, `move.name`, `move.type` (`''` when typeless), `move.category`, `move.power` (evaluated; `0` inside the power formula itself),
`move.accuracy` (`0` when the move never misses), `move.priority`, `move.pp`, `move.cooldown`.
When no move is involved, these read as `0` / `''`.
A power formula is evaluated once per hit, with `self`/`user` = the move user and `other`/`target` = the target, whoever
reads `move.power`: the damage formula and the triggers of that hit (`modifyDamageDealt`, `modifyDamageTaken`, `hit`) all see that value.
It can read `hitIndex` and `hitCount`, so a Triple Kick is `"power": "20 * hitIndex"` (20, 40, 60).

### Other variables

| variable | where | meaning |
|---|---|---|
| `turn` | everywhere in battle | current round (speed_order) or turn (action_bar) number, starting at 1 |
| `allies`, `enemies` | everywhere in battle | number of active, non-fainted allies of `self` (excluding `self`) / enemies |
| `damage` | `hit`, `modifyDamageDealt`, `modifyDamageTaken`, `dealtDamage`, `damaged`, `allyDamaged`, `enemyDamaged`, `attacked` | damage of this event (see below) |
| `hpBefore` | `damaged` | the owner's HP just before this damage (`self.hpPercent < 50 && hpBefore * 100 / self.maxHp >= 50` = "its HP just dropped below 50%") |
| `landed` | `attacked` | 1 if at least one hit of the move landed on the owner (0 when it missed, could not reach it or the type was immune) |
| `hitIndex`, `hitCount` | `hit`, `modifyDamageDealt`, `modifyDamageTaken`; move `power` and `ignoreDefense`; damage formula, `Rules.hitTypes` formulas | the number of the current hit on this target (from 1) and the hits planned on it (the move's `hits`; for `randomEnemies`, the hit number among all of them and the total). `1`/`1` for a move that deals no damage and for the `attack` effect. A condition `hitIndex == 3` makes "the 3rd hit stuns" |
| `amount` | `healed` | HP restored |
| `effectiveness` | `hit`, `modifyDamageDealt`, `modifyDamageTaken`, damage formula, `Rules.hitTypes` formulas, `Rules.hitChance` / `critChance` / `critMultiplier`, move `critBonus` | type multiplier of the move against the target (`1` in the `hit` of a move that deals no damage) |
| `isCrit` | `hit`, `modifyDamageDealt`, `modifyDamageTaken`, damage formula, `Rules.hitTypes` formulas | 1 if critical hit (`0` once a `cancelsCrit` hit type cancelled it) |
| `hitType` | `hit`, `modifyDamageDealt`, `modifyDamageTaken`, damage formula | id of this hit's hit type (string, `''` for a normal hit, a move that deals no damage, or a setup without `hitTypes`) |
| `cancelled` | `beforeMove` | 1 once an earlier trigger of this event cancelled the move (e.g. so a second "can't move" status stays quiet) |
| `status` | `statusApplied`, `statusRemoved`, `statusInflicted`, a field's `blockStatus` rule | id of the status (string) |
| `statusKind` | a field's `blockStatus` rule | kind of the status being applied (`'major'`, `'volatile'`, `'buff'`, `'debuff'`) |
| `stat`, `stages` | a field's `blockStatStage` rule | the stat id and the stage change being made (e.g. `-1`) |
| `field` | `fieldSet`, `fieldRemoved` | id of the field (string) |
| `stacks`, `turnsLeft` | a status's own `statModifiers` and `triggers`; a field's `statModifiers`, `triggers`, `rules` and `duration` | its stacks; remaining turns (`0` if permanent) |
| `attack`, `defense` | damage formula | effective attacking/defending stat for the move's category |
| `sameType` | damage formula, `Rules.hitTypes` formulas, `Rules.hitChance` / `critChance` / `critMultiplier`, move `critBonus` | 1 if the user has the move's type |
| `crit` | damage formula, `Rules.hitTypes` formulas | the crit multiplier if this hit is critical, otherwise 1 |
| `targets` | damage formula, `Rules.hitTypes` formulas | how many targets the move hits |
| `base`, `level`, `points` | `Rules.statFormulas` | species base stat, level, stat points invested in that stat |
| `stage` | `Rules.statStages.multiplier` | the stage being converted to a multiplier |
| `speed` | `Rules.actionBar.fill`, `Rules.actionBar.start` | effective speed of `self` |

`damage` is:
- `modifyDamageDealt` / `modifyDamageTaken`: the damage still pending. It starts as the damage formula's result, times
  the hit type's `multiplier` (`modifyDamageTaken`: after all the attacker's `modifyDamageDealt` modifiers) and, after each `multiplyDamage`, already
  includes it. So an item of the defender sees what its ability left: a "survive at 1 HP" item does not trigger on a
  hit the ability already absorbed, and `"value": "(self.hp - 1) / damage"` leaves exactly 1 HP.
- `hit`, `damaged` / `dealtDamage`, `allyDamaged` / `enemyDamaged`: the damage the hit (or effect) really did,
  counting the part a shield absorbed but not damage beyond the remaining HP. So "recover half of the damage dealt" or
  "1/3 of it as recoil" never count the overkill of a finishing blow. `hit` has `0` for moves that deal no damage and
  for hits a modifier cancelled.
- `attacked`: the total of all the move's hits on the owner (HP + shield; not the damage of the move's trigger effects).

Unknown variable or function names, and battle functions called with the wrong number of arguments, are reported when the setup loads.

---

## Rules.json

```jsonc
{
  "name": "Demo",
  "description": "Classic-style singles",
  "turnSystem": "speed_order",            // "speed_order" | "action_bar"
  "team": { "min": 1, "max": 6, "active": 1, "speciesClause": true, "itemClause": false },
  "switching": true,                      // can a player switch as their action?
  "level": { "min": 1, "max": 100, "default": 50 },
  "maxMoves": 4,
  "heldItems": true,
  "stats": [ { "id": "hp", "name": "HP" }, { "id": "atk", "name": "Attack" }, { "id": "spe", "name": "Speed" } ],
  "hpStat": "hp",
  "speedStat": "spe",
  "statFormulas": {                       // per stat id, "default" for the rest. vars: base, level, points
    "hp": "floor((2 * base + floor(points / 4)) * level / 100) + level + 10",
    "default": "floor((2 * base + floor(points / 4)) * level / 100) + 5"
  },
  "statPoints": { "total": 510, "maxPerStat": 252 },   // optional, omit to disable stat points
  "statStages": { "min": -6, "max": 6, "multiplier": "stage >= 0 ? (2 + stage) / 2 : 2 / (2 - stage)" },
  "categories": {                         // move categories; {} = does not deal damage
    "physical": { "attack": "atk", "defense": "def" },
    "special":  { "attack": "spa", "defense": "spd" },
    "status":   {}
  },
  "damage": "floor(floor(floor(2 * user.level / 5 + 2) * move.power * attack / defense) / 50 + 2) * (sameType ? 1.5 : 1) * effectiveness * crit * random(0.85, 1)",
  "hitChance": "move.accuracy / 100",     // 0..1, only used when the move has an accuracy
  "critChance": "1 / 24",                 // 0..1
  "critMultiplier": "1.5",
  "hitTypes": [                           // optional, see "Hit types" below
    { "id": "crushing", "chance": "effectiveness > 1 && !isCrit ? 100 : 0", "multiplier": 1.3, "message": "Crushing hit!" },
    { "id": "glancing", "chance": "effectiveness < 1 ? 50 : 0", "multiplier": 0.7, "cancelsCrit": true, "message": "Glancing hit!" }
  ],
  "minDamage": 1,                         // damaging hits deal at least this much (unless immune)
  "pp": true,                             // use the moves' pp
  "cooldowns": false,                     // use the moves' cooldown
  "fallbackMove": "desperate_strike",     // used when no move is usable (optional)
  "actionBar": { "size": 100, "fill": "speed", "start": "0" },  // action_bar only
  "turnLimit": 300,                       // 0 = no limit; the battle is a draw after this many turns
  "startFields": [ { "field": "rain", "duration": 5 } ]   // optional, see "Fields.json"
}
```

The example shows typical values, not the defaults. **Required:** `turnSystem`, `stats`, `categories`, `damage`.
**Defaults** of the optional fields:

| field | default |
|---|---|
| `name`, `description` | `""` (the folder name is shown) |
| `team` | `min` 1, `max` 6, `active` 1, `speciesClause` false, `itemClause` false |
| `switching` | true |
| `level` | `min` 1, `max` 100, `default` 50 |
| `maxMoves` | 4 |
| `heldItems` | true |
| `hpStat`, `speedStat` | `"hp"`, `"spe"` |
| `statFormulas` | `"default": "base"` (a stat without its own formula uses `default`) |
| `statPoints` | none: stat points are disabled (`maxPerStat` defaults to `total`) |
| `statStages` | `min` -6, `max` 6, `multiplier` `"stage >= 0 ? (2 + stage) / 2 : 2 / (2 - stage)"` |
| `hitChance` | `"move.accuracy / 100"` |
| `critChance` | `0` (no critical hits) |
| `critMultiplier` | `1.5` |
| `hitTypes` | none (every hit is a normal hit); in an entry: `chance` 0, `multiplier` 1, no `message`, `cancelsCrit` false |
| `minDamage` | 1 |
| `pp`, `cooldowns` | true, false |
| `fallbackMove` | none (a monster without usable moves waits) |
| `actionBar` | `size` 100, `fill` `"speed"`, `start` 0 |
| `turnLimit` | 0 (no limit) |
| `startFields` | none |

`hitChance`, `critChance` and `critMultiplier` (and a move's `critBonus`) see the type matchup against the current
target: `effectiveness` and `sameType`. For example `"critChance": "user.crate / 100 + (effectiveness > 1 ? 0.15 : 0)"`.
The `damage` formula and the `hitTypes` formulas also see `hitIndex` and `hitCount` (the hit in progress).

**Hit types**

`hitTypes` is an ordered list of special kinds of hits, such as the crushing and glancing hits of action bar games.
On every hit that deals damage (each hit of a multi-hit move, never a hit a type immunity stops), right after the crit
roll, the entries are tried in order: the first whose `chance` roll succeeds is this hit's type; if none does, it is a
normal hit. Each entry:

| key | meaning |
|---|---|
| `id` | required, unique simple name (letters, digits and `_`, not starting with a digit); `hitType` reads it |
| `chance` | percent (formula). `0` or less never happens and `100` or more always does, without rolling dice |
| `multiplier` | multiplies the damage formula's result (formula), before rounding, `minDamage` and `modifyDamage*` |
| `message` | logged with the crit message (only when the hit deals damage); placeholders `{user}` `{target}` `{move}` |
| `cancelsCrit` | `true`: a critical hit of this type is not critical any more (no crit message, `crit` 1, `isCrit` 0) |

`chance` and `multiplier` see the variables of the damage formula except `attack`, `defense` and `hitType`: battle
variables, `user`/`target`/`self`/`other`, `move.*`, `effectiveness`, `sameType`, `targets`, and the crit roll as `isCrit`
and `crit`. `multiplier` is evaluated once the hit type is chosen, so after a `cancelsCrit` it sees `isCrit` 0 and `crit` 1.
Then the damage formula runs with `hitType` (the id, `''` for a normal hit), and its result is multiplied by the
multiplier. `hit`, `modifyDamageDealt` and `modifyDamageTaken` read `hitType` too (`''` for moves that deal no damage).
The AI's damage estimates roll no dice: they use the expected multiplier (for each crit outcome, `p1 x m1 +
(1 - p1) x p2 x m2 + ... + the rest x 1`, with the crit multiplier dropped where a hit type cancels the crit).

**Turn systems**

- `speed_order`: each round, each active monster's controller picks an action (move or switch).
  Switches go first, then moves by `priority`, then by effective speed (ties are random).
  A move that became unusable before its turn came (e.g. a cooldown effect) fails; a switch that became impossible
  (e.g. a teammate already sent that monster in) is replaced by the first usable move, at that move's place in the order.
  `turnStart`/`turnEnd` fire for every active monster at the start/end of the round, fastest first (effective speed,
  random ties; each monster's `turnEnd` is followed by its own countdown). The battle end is checked after all of them,
  so if the last monsters of both sides faint there (burn, poison...), the battle is a draw.
  Fainted monsters are replaced at the end of the round.
  A monster that got an extra turn (the `extraTurn` effect) chooses and runs one more action after everybody's
  action, before the `turnEnd` phase (in the order the extra turns were given; at most one per monster per round).
- `action_bar`: every active monster's bar fills at `actionBar.fill` points per time unit.
  The first to reach `size` takes a turn, and its bar resets to 0 when the turn starts.
  `turnStart`/`turnEnd` fire only for the monster whose turn it is.
  Fainted monsters are replaced immediately if a bench exists. `actionBar.start` is the initial fill, in percent.
  A monster that got an extra turn has a full bar and takes the next turn, before anyone else, without time passing
  (a complete turn: `turnStart`, its action, `turnEnd`, countdowns; the turn line says "- extra turn"). It can get at
  most one extra turn between two of its normal turns, so extra turns cannot loop.

Monsters that enter together are all placed first, then their `switchIn` triggers run fastest first (effective speed,
random ties): the leads at battle start (then `battleStart`, in the same order) and the replacements of monsters that
fainted at the same time. Each side chooses its replacements without seeing the other side's choice, so a switch-in
effect such as "lower the enemies' Attack" also reaches a replacement that enters at the same time.

**Durations and cooldowns are counted in the owner's turns.** A status with `duration: 2` affects 2 of the owner's
turns (2 of its actions), wherever it comes from. In speed_order every round counts as every active monster's own turn.
- A status that arrives before the owner acts in its turn (a faster enemy's move, a `turnStart` effect) already affects
  that turn: it counts down at the end of it. So sleep for 1 turn applied by a faster enemy costs exactly that turn's move.
- A status that arrives once the owner's action has started (its own move, a slower enemy's move, a `turnEnd`
  effect before the owner's countdown) has not affected any of its actions yet: it is *fresh* and does not count down
  at the end of that turn.
  A monster switched in during a speed_order round has no action that round, so the same applies to it.
- A status that arrives outside the owner's turn (at battle start, with a replacement sent in at the end of a round,
  after the owner's countdown) counts down at the end of the owner's next turn.

`duration: 0` means "until the end of the current turn" (useful for flinch- or protect-like effects).
A move with `cooldown: 2` cannot be used for the owner's next 2 turns after it is used.
The `cooldown` effect counts the same way: `amount: 1` on a ready move blocks one of the owner's actions. Arriving
before the owner acts in its turn, it blocks that turn's action; arriving once the owner's action has started (or
outside its turn), it blocks its next one. A move already on cooldown simply waits `amount` more turns.

In detail, at the end of each of the owner's turns, each status with a duration does this:
if it is *fresh* and `turnsLeft > 0`, it stops being fresh.
Otherwise `turnsLeft` goes down by 1, and the status is removed when it reaches 0 or less.

Fields count down differently (they have no owner): see [Fields.json](#fieldsjson).

## Types.json

```jsonc
{
  "types": [ { "id": "fire", "name": "Fire", "color": "red" } ],   // color: black red green yellow blue magenta cyan white gray/grey (or bright*, e.g. brightRed)
  "chart": {                                // attacker -> defender -> multiplier; missing pairs = 1
    "fire":  { "grass": 2, "water": 0.5, "fire": 0.5 }
  },
  "messages": { "strong": "It's super effective!", "weak": "It's not very effective...", "immune": "It doesn't affect {target}..." }
}
```
A monster with several types multiplies the multipliers together. The messages can only use `{target}`.

## Bakemons.json

```jsonc
{ "bakemons": [ {
  "id": "embercub", "name": "Embercub",
  "types": ["fire"],
  "baseStats": { "hp": 45, "atk": 60, "def": 40, "spa": 70, "spd": 50, "spe": 65 },   // every stat in Rules.stats
  "abilities": ["blaze", "flash_fire"],        // the abilities it may have (first = default)
  "moves": ["ember", "tackle", "growl"],       // the moves it can learn
  "image": "Assets/embercub.png",              // optional
  "description": "A cub with a smouldering tail."
} ] }
```

## Moves.json

```jsonc
{ "moves": [ {
  "id": "ember", "name": "Ember", "description": "May burn the target.",
  "type": "fire",                // optional (typeless if omitted)
  "category": "special",         // a key of Rules.categories
  "power": 40,                   // number or formula; omit/0 = deals no direct damage (a formula that gives 0 for a hit: that hit deals none)
  "accuracy": 100,               // percent; omit = never misses
  "priority": 0,
  "pp": 25,                      // 0 = unlimited (only if Rules.pp)
  "cooldown": 0,                 // only if Rules.cooldowns
  "target": "enemy",             // see below
  "hits": 1,                     // formula, e.g. "randint(2, 5)"
  "hitAccuracy": "once",         // "once" | "each" (every hit rolls the accuracy)
  "critBonus": 0,                // added to the crit chance (0..1); same variables as Rules.critChance
  "ignoreDefense": 0,            // percent (formula) of the target's defense the damage formula ignores
  "charge": { "turns": 1, "message": "{user} is charging up!", "invulnerable": false, "skipIf": "fieldActive('sun')" },  // optional
  "flags": ["contact"],          // free tags, readable with hasFlag('contact')
  "triggers": [ { "on": "hit", "chance": 10, "effects": [ { "type": "applyStatus", "status": "burn" } ] } ]
} ] }
```

| key | default | meaning |
|---|---|---|
| `hits` | 1 | hits on each target (formula evaluated once per target, before the first hit; it cannot use `hitIndex` / `hitCount`) |
| `hitAccuracy` | `"once"` | `"once"`: one accuracy roll per target, before the first hit. `"each"`: every hit rolls; the first miss ("X avoided the attack!", `missed`) ends the move against that target |
| `ignoreDefense` | none | percent (formula, 0-100, may use `hitIndex`): the damage formula's `defense` is multiplied by `1 - value / 100` for this move, but never goes below 1 (so `100` makes it 1 and a formula that divides by the defense still works) |
| `charge` | none | a charge move, see below |

**Charge moves.** With `"charge"`, the first use only charges: `beforeMove` runs, then the charge `message` (default
`"{user} is charging up!"`; placeholders `{user}` `{move}` `{target}`) instead of "X used Y!", and the PP / cooldown
are paid. The monster is then locked: its next `turns` turns (default 1) are not chosen by its controller (the battle
menu does not ask, the AI does not choose): each one runs `beforeMove` again, then either charges on (the message
again) or, on the last one, releases the move with the normal procedure ("X used Y!", accuracy, hits...) on the same
target (retargeted like a normal move if it is gone), without paying again.
- `invulnerable: true`: while it charges, every other monster's move aimed at it misses before the accuracy roll
  ("X can't be reached!", `missed`), even moves that never miss. Effects (damage, `attack`...) still reach it.
- `skipIf`: a formula (user/target/move, like `power`) checked at the first use; when true the move is used at once,
  like a normal move (Solar Beam in sun: `"skipIf": "fieldActive('sun')"`).
- The charge ends without a release when the monster faints, leaves the field (switch, `forceSwitch`, `switchOut`),
  or its release is cancelled (a status cancels `beforeMove`, a field's `blockMove` stops it). `m.charging` reads 1
  from the charging turn until the release starts. A charge move used through the `useMove` effect is released at
  once (it does not charge). The battle screen shows "Charging <move>" (and "(out of reach)").

Move `target` values: `enemy` (choose one enemy), `allEnemies`, `randomEnemy`, `self`, `ally` (choose one ally),
`allyOrSelf` (choose an ally or yourself), `allAllies` (allies, not yourself), `team` (yourself and allies), `all` (every other active monster),
`faintedAlly` (choose a fainted teammate that can still be revived, on the field or not: see [Revives](#revives)),
`lowestHpAlly` (yourself or the ally with the lowest HP percent, chosen automatically when the move runs; ties: field
order), `lowestHpEnemy` (the enemy with the lowest HP percent, chosen automatically), `randomEnemies` (every hit picks
a new random active enemy: `hits` is the total number of hits, each hit is aimed separately, with its own `targeted`
for a first-time target, reach check and accuracy roll; "Hit N times!" counts all of them).
A move that has nobody to affect when it is chosen (`ally` or `allAllies` with no ally left) cannot be chosen, like a
move without PP (the same goes for `faintedAlly` when no teammate has fainted); a monster left with no other move uses `Rules.fallbackMove` (or waits).

How a move runs: `beforeMove` (can be cancelled) → "X used Y!" → pay PP/cooldown → for each target:
`targeted` (another monster's move) → out of reach (charging, invulnerable) → accuracy check → type immunity → for
each hit: (accuracy again with `hitAccuracy: "each"`) → crit roll → hit type → damage formula →
`modifyDamageDealt`/`modifyDamageTaken` → damage → `hit` → after all targets: `afterMove` → `attacked` for every
targeted monster → `allyUsedMove` / `enemyUsedMove` for the user's allies and enemies. Moves that deal no damage fire `hit` once per target that wasn't missed;
put their effects there (type immunity only stops damaging moves). A hit whose power formula gives 0 deals no damage
and fires `hit` with `damage` 0.
A move cancelled in `beforeMove` by a status or an ability (sleep, flinch...) is not used and costs nothing.
A move cancelled only by **its own** `beforeMove` trigger counts as a failed use: "X used Y!", then the cancel message
(default "But it failed!"), and its PP/cooldown are spent.
Move triggers can use the events `beforeMove`, `hit`, `missed`, `modifyDamageDealt`, `afterMove`, `knockedOut`.
The effect target defaults to `target`.

## Abilities.json and Items.json

```jsonc
{ "abilities": [ {
  "id": "blaze", "name": "Blaze", "description": "Fire moves are 1.5x stronger when HP is low.",
  "statModifiers": { "atk": 1.0 },      // optional: stat multipliers while it is active (formulas)
  "triggers": [ {
    "on": "modifyDamageDealt",
    "condition": "move.type == 'fire' && self.hpPercent <= 33",
    "effects": [ { "type": "multiplyDamage", "value": 1.5 } ]
  } ]
} ] }
```
Items use the same format (`{"items": [...]}`). Use the `consumeItem` effect for single-use items.
The effect target defaults to `self`.
`statModifiers` multiply the effective stat (abilities first, then the item, then statuses); each formula reads the
**raw** stat as `self.<stat>`, so a flat bonus written as `1 + 15 / max(self.crate, 1)` must add the flat bonuses
applied before it to that denominator to add up with them. Being a multiplier, such a bonus cannot raise a raw stat
of 0 (0 x anything is 0): give every species a base value of at least 1 in the stats that get flat bonuses.
`statModifiers` cannot change max HP or HP.

## Statuses.json

```jsonc
{ "statuses": [ {
  "id": "burn", "name": "Burn", "short": "BRN", "description": "Loses 1/16 HP each turn, Attack halved.",
  "kind": "major",               // major (only one major status at a time) | volatile | buff | debuff
  "duration": null,              // formula in owner turns; omit/null = until removed
  "stacking": "ignore",          // ignore | refresh (reset duration, stays at 1 stack) | stack (add stacks up to maxStacks, refresh duration)
  "maxStacks": 1,                // only with "stack"
  "immuneTypes": ["fire"],
  "statModifiers": { "atk": 0.5 },          // formulas, may use `stacks`
  "triggers": [ { "on": "turnEnd", "effects": [ { "type": "damage", "target": "self", "amount": "self.maxHp / 16" } ] } ],
  "applyMessage": "{target} was burned!",       // placeholders: {target} {self} (the holder), {user} {other} (who applied it), {status}, {stacks}
  "removeMessage": "{target}'s burn wore off."
} ] }
```
When a monster switches out, it loses every status that is not `major`, and its stat stages reset.
When it faints, its statuses are cleared after its `fainted` triggers ran (statuses can react to their holder fainting).

## Fields.json

Optional. A **field** is something that affects the battle itself rather than one monster: weather, a terrain, a
screen, an entry hazard, a "speed inversion"... A field is either **global** (it covers every monster of both sides) or
on **one side** (it covers that side's monsters). Fields never belong to a monster: they keep working when the monster
that set them faints or switches out. Moves, abilities, items, statuses and other fields start and end them with the
`setField` and `removeField` effects; a battle can also start with some (`Rules.json` `startFields`, or a choice in the
battle menu).

```jsonc
{ "fields": [ {
  "id": "rain", "name": "Rain", "short": "RAIN", "description": "Water moves 1.5x, fire moves 0.5x.",
  "scope": "global",             // global (default) | side
  "group": "weather",            // optional exclusive group, see below
  "duration": 5,                 // formula in turns, see "Countdown"; omit/null = until removed
  "stacking": "ignore",          // ignore | refresh | stack, when it is set again while active (like statuses)
  "maxStacks": 1,                // only with "stack"
  "selectable": true,            // may be picked as a starting field in the battle menu (default false)
  "statModifiers": { "spd": "hasType(self, 'rock') ? 1.5 : 1" },   // for every covered monster
  "triggers": [ { "on": "modifyDamageDealt", "condition": "move.type == 'water'",
                  "effects": [ { "type": "multiplyDamage", "value": 1.5 } ] } ],
  "rules": { },                  // overrides and blocks, see below
  "startMessage": "It started to rain!",   // placeholders {field} {side} {user} {stacks}; "" = silent
  "endMessage": "The rain stopped."
} ] }
```

| key | meaning |
|---|---|
| `id` | required, unique (`"all"` is reserved) |
| `name`, `short`, `description` | display texts (`short` defaults to the first 4 letters of the name, upper-case) |
| `scope` | `"global"` (default) or `"side"` |
| `group` | setting a field removes every other active field of the same group **in the same place** (globally, or on that side), silently but with their `fieldRemoved` events (rain replaces sun) |
| `duration` | turns (formula, evaluated when the field is set: `self`/`user` = the monster that sets it, absent for start fields, plus `stacks` and `turnsLeft` like a status duration); omitted = permanent until removed |
| `stacking`, `maxStacks` | `ignore` (default): setting it again fails. `refresh`: the duration starts again. `stack`: adds layers up to `maxStacks` and restarts the duration (a full permanent stack fails). A refresh or a new layer logs the start message again and fires `fieldSet` again |
| `selectable` | the battle menu offers it as a starting field |
| `statModifiers` | stat multipliers for every covered monster, applied in `m.<stat>` after the statuses' (variables: `self`, `stacks`, `turnsLeft`, battle variables; `self.<stat>` reads raw stats; no dice) |
| `triggers` | see below |
| `rules` | see below |
| `startMessage`, `endMessage` | defaults: `"<Name> started."` / `"<Name> ended."` (side fields: `"<Name> started on <side>'s side."` / `"... ended on <side>'s side."`). `{side}` = the side's name, `{user}` = the monster that set it (start message) or removed it (end message; the one that set it when it expires), if any |

**Triggers.** A field's triggers are normal [triggers](#triggers) plus an optional `"for"` key that says whose events
they listen to. The trigger behaves as if the monster the event is about owned it (`self`):

| `for` | listens to events about... |
|---|---|
| `"covered"` (default) | a monster the field covers |
| `"opponents"` | side fields only: a monster of the **other** side. `"on": "damaged", "for": "opponents"` = "when an enemy takes damage" |
| `"all"` | any monster on the field |

`other`, `user` and `target` are as in a status's triggers: when the event has no other monster, `other` is the
monster that set the field (if it is still on the field and is not `self`). `stacks` and `turnsLeft` are the field's,
`{field}` is its name in messages, and the effect target defaults to `self`. For one event, the field triggers run
after the move's, the ability's, the item's and the statuses' triggers, field by field in the order they were set. A
field that ends also hears its own `fieldRemoved` (it can react to its own end, like a status).

**Countdown.** A field with a duration counts down by itself, not in a monster's turns:
- `speed_order`: at the end of every round, after every monster's `turnEnd` and status countdown, **including the round
  it was set in**. A 5-turn Rainfall used in round 1 ends at the end of round 5 (as in Classic-style rounds).
- `action_bar`: at the end of every turn of a monster of the side that **set** it (the turn it was set in counts).
  Fields with no setter (start fields) count down at the end of every turn.
When `turnsLeft` reaches 0 the field ends: its end message, then `fieldRemoved`.

**Rules.** The `"rules"` object changes how the battle works while the field is active. Every key is optional:

| key | does |
|---|---|
| `damage`, `hitChance`, `critChance`, `critMultiplier` | replace the Rules.json formula of the same name (same variables, plus `stacks`/`turnsLeft`) when the **attacker** is covered. If several active fields replace the same formula, the most recently set one wins |
| `actionBarFill` | replaces `Rules.actionBar.fill` for covered monsters (action_bar) |
| `reverseSpeed` | `true`: in speed_order, actions of the same priority run **slowest first** (switches still go first, ties stay random). Decided when the round's actions are sorted, so a field set during a round changes the next one. Global fields only; does nothing in action_bar |
| `disableItems`, `disableAbilities` | condition (formula or `true`; `self` = a covered monster, `self.<stat>` reads raw stats, no dice): while true, that monster's held item / ability does nothing (no `statModifiers`, no triggers) and `hasItem` / `hasAbility` read 0. The item or ability is kept |
| `blockMove`, `blockMoveMessage` | condition (`user`/`self` = the covered monster about to move, `move`, `target`/`other` = the primary target; no dice). When choosing, the target is absent: a true result makes the move unusable, like a move on cooldown (the battle menu and the AI skip it). When the move runs, it is checked again with the target: a true result makes it fail with the message (default `"{user} can't use {move} because of {field}!"`; placeholders `{user}` `{move}` `{target}` `{field}`), without spending PP (the AI also checks its chosen target and avoids such a move unless nothing else is legal). The fallback move is only checked when it runs |
| `blockStatus`, `blockStatusMessage` | condition (`self`/`target` = the covered monster about to get a status, `other`/`user` = the source, which is `self` for a self-inflicted status; variables `status`, `statusKind`): true = the status is not applied. The message (default `"{self} is protected by {field}!"`; placeholders `{self}` `{target}` `{other}` `{status}` `{field}`) is logged when a move applied the status (once per target and move) or when the status would have announced itself (not `""`) |
| `blockStatStage`, `blockStatStageMessage` | condition (`self` = the covered target, `other` = the source; variables `stat`, `stages` = the requested change): true = the stage change does not happen. Message default `"{self} is protected by {field}!"` (placeholders `{self}` `{target}` `{other}` `{stat}` (its name) `{field}`; `""` = silent) |

For example Safeguard is `"blockStatus": "statusKind == 'major' && !sameSide(self, other)"` on a side field, Mist is
`"blockStatStage": "stages < 0 && !sameSide(self, other)"`, Speed Inversion is a global field with `"reverseSpeed": true`,
Gravity is `"blockMove": "hasFlag('flying')"` and Stealth Rock is a side field with a `switchIn` trigger that deals
`"floor(self.maxHp * typeMultiplier('rock', self) / 8)"`.

**Start fields.** `Rules.json` `"startFields"` lists fields set when the battle starts, before the leads enter (so
their `switchIn` already sees them), silently except their start message:

```jsonc
"startFields": [
  { "field": "rain", "duration": 5 },                     // global field, 5 turns
  { "field": "spikes", "side": "side2" },                  // side fields: "both" (default), "side1" or "side2"
  { "field": "grassy_terrain", "permanent": false }        // the field's own duration
]
```
`duration` is a formula (battle variables only); `permanent` defaults to `true` when there is no `duration` (the
field never expires) and to `false` otherwise. Start fields have no setter, so in action_bar they count down at the end
of every turn.

**In the app.** When the setup has fields with `"selectable": true`, the battle menu asks for starting fields after
the teams are chosen:
- It lists the `startFields` under "Set at the start of every battle" (with their duration when they have one).
- It offers one choice per exclusive group (for example *Weather: Rain / Harsh Sunlight / None*), then a yes/no for
  every selectable field without a group. A side field also asks for the side: either player, or both.
- "No extra fields" skips the questions. A setup without selectable fields shows no field menu.

Fields picked there have no duration (they do not expire on their own) and are set after the `startFields`, so a
pick replaces a start field of the same group. They are still ordinary fields: a move or ability can replace them
(another field of the same group) or remove them (`removeField`). A rematch keeps them.

During the battle:
- The field display shows the global fields on one line: `Field: Rain (3) | Grassy Terrain (2)`. The number in
  brackets is the turns left, and `x2` means two layers.
- Each side's fields are shown next to the side's name: `Player  Reflect (4), Spikes x2`.
- Moves blocked by a `blockMove` rule are marked `[blocked by <field>]` and cannot be picked.
- Info lists the active fields with their descriptions.

The Bakedex browser also has a Fields page.

## Triggers

```jsonc
{
  "on": "turnEnd",                      // event, see below
  "condition": "self.hp < self.maxHp",  // optional formula (fires only if true)
  "chance": 30,                         // optional percent (formula)
  "message": "{self} restored a little HP with its {item}!",   // optional, logged when it fires
  "limit": 1,                           // optional: at most this many times per battle (0 = unlimited)
  "effects": [ ... ]
}
```

Triggers are evaluated in this order: the move in use, then the owner's ability, then its held item,
then its statuses (oldest first), then the active fields that listen to the owner (in the order they were set).
Effects can trigger further events, up to 10 levels deep.
A fainted monster's triggers only fire for its own `fainted` event.

### Events (`on`)

The **owner** is the monster whose ability, item or status (or the move it is using) holds the trigger; for a field's
trigger it is the monster the event is about.

| event | fires when... | `other` is |
|---|---|---|
| `battleStart` | once, for every monster on the field when the battle begins | - |
| `switchIn` | the owner enters the field (including at battle start) | - |
| `switchOut` | the owner is withdrawn by a switch | - |
| `turnStart` | the owner's turn begins (speed_order: every round; action_bar: its own turn) | - |
| `turnEnd` | the owner's turn ends | - |
| `beforeMove` | the owner is about to use a move (`cancelAction` allowed) | the primary target |
| `afterMove` | the owner finished using a move | the primary target |
| `hit` | the owner's move connected (once per hit) | the target |
| `missed` | the owner's move missed a target | the target |
| `modifyDamageDealt` | the owner is about to deal move damage (`multiplyDamage` allowed) | the target |
| `modifyDamageTaken` | the owner is about to take move damage (`multiplyDamage` allowed) | the attacker |
| `dealtDamage` | the owner damaged another monster (move or effect), even if a shield absorbed it all | the damaged monster |
| `damaged` | the owner took damage from any source (also when its shield absorbed it all) | the source, if any |
| `healed` | the owner recovered HP | the source, if any |
| `statusApplied` | a status was applied to the owner | the source, if any |
| `statusRemoved` | one of the owner's statuses ended | - |
| `fainted` | the owner fainted (`move` is the move that knocked it out, if any; `user` is its user) | the attacker, if any |
| `knockedOut` | the owner made another monster faint | the fainted monster |
| `allyFainted` | an ally of the owner fainted | the fainted ally |
| `enemyFainted` | an enemy of the owner fainted | the fainted enemy |
| `revived` | the owner was revived; `amount` = the HP it came back with | the monster that revived it, if any |
| `allyRevived` | an ally of the owner was revived | the revived ally |
| `enemyRevived` | an enemy of the owner was revived | the revived enemy |
| `fieldSet` | a field started (or was refreshed / stacked): fires once for every monster on the field, fastest first; `field` = its id | the monster that set it, if any |
| `fieldRemoved` | a field ended (expired, removed, or replaced by its group): fires once for every monster on the field, fastest first; `field` = its id | the monster that removed it, else the one that set it (if still on the field) |
| `targeted` | the owner is a target of another monster's move (an enemy's or an ally's), before the accuracy check; once per move use | the attacker (`move` = its move) |
| `attacked` | another monster's move that targeted the owner finished, whether it hit, missed or did nothing (after its `afterMove`; also when the attacker fainted during it); `damage` = what its hits took from the owner, `landed` = 1 if one hit landed. "When I've been attacked", unlike `damaged` ("when I lost HP") | the attacker (`move` = its move) |
| `allyDamaged` | an ally of the owner took damage (any source, also when a shield absorbed it all); `damage` | the damaged ally (`user` = the attacker when a move did it) |
| `enemyDamaged` | an enemy of the owner took damage (any source); `damage` | the damaged enemy |
| `allyUsedMove` | an ally of the owner finished using a move (after its `afterMove`; not for a charging turn or a move that failed) | the ally (`user` = the ally, `target` = its move's primary target) |
| `enemyUsedMove` | an enemy of the owner finished using a move | the enemy (`user` / `target` as for `allyUsedMove`) |
| `statusInflicted` | the owner applied a status to **another** monster (after that monster's `statusApplied`); `status` = its id | the monster that got it |

Events that happen to the allies or enemies of a monster (`allyDamaged`, `allyUsedMove`...) fire in field order
(the owner's side first), like `allyFainted`. For moves used inside these events (counterattacks...), see `useMove`.

## Effects

Every effect has a `type`. Most also take a `target`.

| type | fields | does |
|---|---|---|
| `damage` | `target`, `amount` | direct damage (ignores the damage formula and types) |
| `heal` | `target`, `amount` | restores HP (up to max) |
| `applyStatus` | `target`, `status`, `chance`?, `duration`?, `stacks`?, `message`? | applies a status (chance in percent; duration overrides the status default; `message` replaces the status's `applyMessage`, `""` = silent) |
| `removeStatus` | `target`, `status`? (id or `"all"`), `kind`? | removes statuses by id or by kind |
| `statStage` | `target`, `stat`, `stages` | raises/lowers a stat stage (clamped to Rules.statStages) |
| `multiplyDamage` | `value` | multiplies the pending damage (only in `modifyDamageDealt` / `modifyDamageTaken`) |
| `cancelAction` | `message`? | cancels the move (only in `beforeMove`) |
| `gauge` | `target`, `amount` | adds `amount` percent of the action bar (negative = push back; action_bar only) |
| `shield` | `target`, `amount` | adds a shield that absorbs damage first |
| `cooldown` | `target`, `amount` | adds `amount` to the remaining cooldown of all the target's moves (negative = reduce) |
| `consumeItem` | `target`, `message`? | removes the target's held item for the rest of the battle (`message` replaces the default "X's item was used up." line, `""` = silent) |
| `message` | `text` | prints text |
| `setField` | `field`, `side`?, `duration`?, `stacks`?, `chance`?, `message`? | sets a [field](#fieldsjson). `side` (side fields only, relative to the trigger owner): `"self"` (default), `"enemy"` or `"both"`. `duration` overrides the field's, `stacks` default 1, `chance` in percent, `message` replaces the start message (`""` = silent; also `{field}` and `{side}`). Follows the group and stacking rules; when it changes nothing, a status move logs "But it failed!" |
| `revive` | `target`, `amount`, `message`? | brings a fainted target back with `amount` HP (at least 1, at most max HP); `message` replaces the default line (`""` = silent). See [Revives](#revives) |
| `preventRevive` | `target`, `message`? | the target (fainted or not) can no longer be revived in this battle |
| `removeField` | `field` (an id or `"all"`) or `group`, `side`? | ends the matching active fields: global ones always, side ones on `side` (`"self"`, `"enemy"`, `"both"` = default). `message` replaces each end message (`""` = silent; also `{field}` and `{side}`) |
| `useMove` | `actor`?, `move`?, `moveTarget`?, `power`?, `free`?, `message`? | every monster `actor` resolves to (default `"self"`) uses a move right away, with the full move procedure (beforeMove, accuracy, hits, triggers...), outside its turn and without changing its turn or action bar. See below |
| `extraTurn` | `target` | the target gets another turn ("X gets another turn!"; see [Turn systems](#rulesjson)). Fails (silently, or "But it failed!" for a status move) when it already has one waiting or already had its extra turn (speed_order: this round; action_bar: since its last normal turn) |
| `transferStatus` | `target`, `to`?, `status`? and/or `kind`?, `count`?, `mode`? | moves statuses from `target` (the source) to every `to` monster (default `"self"`): those matching `status` (an id or `"all"`) and/or `kind`, the oldest `count` of them (formula, default 1; `0` = all). `mode`: `"steal"` (default: removed from the source once a receiver got it) or `"copy"` (the source keeps them). Turns left and stacks are kept; the receiver's immunities, the single-major rule and fields' blocks still apply (a status nobody could take stays on the source). "Move my debuffs onto the target" = `"target": "self", "to": "target", "kind": "debuff"` |
| `copyStages` | `target`, `to`? | the stat stages of every `to` monster (default `"self"`) become a copy of `target`'s ("X copied Y's stat changes!") |
| `setHp` | `target`, `amount` | sets the target's HP to `amount` (clamped to 0..max HP). Lowering it is damage from the owner that no shield absorbs (`damaged`, `dealtDamage`..., it can faint); raising it is healing (`healed`) |
| `balanceHp` | `target` | every monster `target` resolves to (a group: `"team"`, `"allies"`, `"enemies"`, `"all"`) gets the group's average HP percent (at least 1 HP; computed before anybody changes; damage / healing events as for `setHp`) |
| `swapHp` | `target` | the owner and the target swap their HP percentages (at least 1 HP each) |
| `forceSwitch` | `target` | the target is replaced right away by a random benched teammate ("X was dragged out!", then its `switchOut` and the replacement's `switchIn`). Fails if it has nobody on the bench |
| `switchOut` | `target`? (default `"self"`, also in move triggers) | the target switches out right away and its controller picks who comes in from the bench (a U-turn: in a move's `afterMove` trigger). Fails if the bench is empty. Works even with `Rules.switching` false |
| `delay` | `turns`, `attachTo`?, `effects`, `message`? | runs `effects` later, see below |
| `attack` | `target`, `power`, `moveType`?, `category`? | a damaging strike of the owner computed with the damage formula: `power` (formula), `moveType` (a type id; default the current move's type, typeless without a move) and `category` (default the current move's if it deals damage, else the first damaging category of Rules.json). Type immunity, crits, hit types and `modifyDamageDealt` / `modifyDamageTaken` apply, it never misses and fires `dealtDamage` / `damaged` but **not** `hit` (so a "on hit: extra strike" trigger cannot loop). `move` in those events is the current move (its name and flags), or a stand-in named after the trigger's ability / item / status... (The key is `moveType` because `type` is the effect type.) |

**useMove.**
- `move`: `"first"` (default: the actor's first move), `"same"` (the move of the current event, if the actor knows it;
  else its first move) or a move id (one the actor knows, or any move of the setup, which is then used without PP).
- `moveTarget`: a target selector resolved from the **actor's** point of view (`"self"` = the actor, `"enemies"` = its
  enemies, `"lowestHpEnemy"`...; `"other"`, `"user"`, `"target"` and `"otherTarget"` are the event's roles). Default:
  the event's `other` monster (a counterattack hits the attacker); when the event has none (or it is the actor
  itself), the move's normal target (a random enemy for an `enemy` move...). A single-target move (`enemy`, `ally`,
  `allyOrSelf`, `faintedAlly`) that cannot be aimed at the chosen monster (it fainted, left the field or is on the wrong
  side) fails, and so does an explicit `moveTarget` that selects nobody; the other move targets ignore the choice. `"otherTarget"` = the target of the other monster's move: in `allyUsedMove`
  ("join the attack") it is the target of the ally's move.
- `power`: a percent (formula, default 100; `recipient` = the actor) that multiplies the move's damage for this use
  (before the damage modifiers, at least `minDamage`). `free`: `true` (default) = no PP or cooldown spent; `false` = it
  pays them, and fails if the move has no PP left or is on cooldown. `message` is logged just before the move, only
  when it is really used (a trigger's own `message` is logged even when the move then fails).
- It fails silently when the actor is not on the field, has no move to use or nobody to aim it at. Counters of
  counters are limited: at most 3 moves used by `useMove` can be nested inside one another.
- Examples: counterattack = `"on": "attacked", "condition": "landed && !sameSide(self, other)"` (an ally's
  heal targets it too) with `{"type": "useMove", "move": "first", "message": "{self} strikes back!"}` (`moveTarget`
  defaults to `other`, the attacker); an ally joining an attack =
  `"on": "allyUsedMove"` with `{"type": "useMove", "moveTarget": "otherTarget", "power": 50}`; "use this move again" =
  a move's `afterMove` trigger `{"type": "useMove", "move": "same", "moveTarget": "target"}` (limit it with a trigger
  `chance` or `limit`).

**delay.** `effects` run later, at the end of the `attachTo` monster's `turns`th turn from now (`turns` is a formula,
at least 1; `attachTo` defaults to `"target"` in move triggers and `"self"` elsewhere). In speed_order that is the end
of the `turns`th round, counting the current one (after every `turnEnd`, before the fields count down); in
action_bar the end of that monster's `turns`th turn, counting the current one when it is in its turn. The delayed
effects use the roles of the trigger that scheduled them (`self`, `other`, `user`, `target`, `move`, the event
variables) and `recipient` per target; `message` is logged when they run. Nothing happens if the attached monster
faints or leaves the field before. Future Sight = `"on": "hit"` with
`{"type": "delay", "turns": 2, "effects": [{"type": "attack", "power": 120}]}` (`attachTo` and the strike's target
default to the move's target); Wish = `{"type": "delay", "turns": 1, "attachTo": "self", "effects": [{"type": "heal",
"target": "self", "amount": "self.maxHp / 2"}]}`. A delay scheduled while delayed effects run waits at least until the
next count, so delays cannot repeat endlessly in one moment.

Effect `target` values: `self`, `other`, `user`, `target`, `allies` (the owner's allies), `team` (the owner + allies),
`enemies`, `randomEnemy`, `all` (every active monster), `faintedAllies` (every fainted teammate that can still be revived),
`randomFaintedAlly` (one of them), `lowestHpAlly` (the owner or the ally with the lowest HP percent; ties: field order),
`lowestHpEnemy` / `highestHpEnemy` (the enemy with the lowest / highest HP percent), `randomAlly` (one random ally,
not the owner; nobody when it is alone), `otherTarget` (the target of the other monster's move, when the event's
`other` is the move user: `allyUsedMove`, `enemyUsedMove`, `targeted`, `attacked`...). `setField` and `removeField`
take no target.
Only `revive` and `preventRevive` affect fainted monsters: they reach them through `self`, `other`, `user`, `target`,
`faintedAllies` and `randomFaintedAlly`. Every other effect skips fainted monsters.

Message placeholders: `{self}`, `{other}`, `{user}`, `{target}`, `{move}`, `{damage}`, `{amount}`, `{status}`, `{ability}`, `{item}`, `{field}`.
In an effect's `message`, they refer to the trigger's event like in the trigger `message`, except that `{status}` is the
applied status and `{item}` the consumed item. Some depend on the event: `{damage}` is filled only in the events that
have `damage`, `{amount}` only in `healed` and `revived`, `{move}` only in move triggers and in the events that can involve a move,
`{status}` in `statusApplied`/`statusRemoved` and in a status's own triggers, `{field}` in `fieldSet`/`fieldRemoved` and in
a field's own triggers; `{other}`/`{target}` are empty in ability
and item triggers of `battleStart`, `switchIn`, `switchOut`, `turnStart`, `turnEnd` and `statusRemoved`.
A status's `applyMessage` / `removeMessage` has its own set (`{target}`, `{self}`, `{user}`, `{other}`, `{status}`, `{stacks}`)
and the Types.json messages only `{target}`; the setup check warns about placeholders a message can never fill.

## Revives

The `fainted` event is the "on death" hook; `revived`, `allyRevived` and `enemyRevived` are the "on revive" ones.
A fainted monster comes back with the `revive` effect:

- **Where it comes back:** a monster still in its field slot (the usual case in action_bar battles, or before the end
  of the round in speed_order) comes back right there. One that was already replaced comes back to the bench, ready to
  be switched in or to replace the next monster that faints.
- **Its state:** the HP given by `amount` (1 to max HP), no statuses, no stat stages, no shield and an empty action bar.
  It keeps its moves (with their PP and cooldowns), its item, and how many times its triggers fired (`limit`).
  In speed_order it does not run the action it chose before fainting (it acts again from the next round); in action_bar a
  monster that faints at the start of its own turn (e.g. from continuous damage) and is revived loses that turn.
- **Revive on death:** a `revive` with `"target": "self"` in a `fainted` trigger (of an ability, item or status) brings
  the monster back once its fainting has been fully handled. The `fainted`, `knockedOut`, `allyFainted` and
  `enemyFainted` events all happen first, then its statuses are cleared, then it is revived, so `revived` triggers can
  give it new statuses. Use `"limit": 1` (or `m.revives` in a condition) to make it work only once.
- **Revive moves:** with `"target": "faintedAlly"` the player picks a fallen teammate. The move's `hit` triggers run with
  that fainted monster as `target`, so a `revive` effect there brings it back. `faintedAllies` and `randomFaintedAlly`
  work in any trigger.
- **Anti-revive:** `preventRevive` makes a monster unrevivable for the rest of the battle, for example from a
  `knockedOut` trigger. A blocked or living monster ignores `revive`.

```jsonc
// Statuses.json: revive with 30% HP when the holder dies
{ "id": "soul_protection", "name": "Soul Protection", "kind": "buff", "duration": 2,
  "triggers": [ { "on": "fainted", "effects": [ { "type": "revive", "target": "self", "amount": "self.maxHp * 0.3" } ] } ] }

// Moves.json: revive a chosen fallen ally with half its HP
{ "id": "resurrect", "name": "Resurrect", "category": "support", "target": "faintedAlly", "cooldown": 5,
  "triggers": [ { "on": "hit", "effects": [ { "type": "revive", "amount": "target.maxHp / 2" } ] } ] }
```

## Teams (`Teams/<name>.json`)

```jsonc
{
  "name": "My team",
  "members": [
    { "species": "embercub", "nickname": "Sparky", "level": 50, "ability": "blaze", "item": "leftovers",
      "moves": ["ember", "tackle"], "points": { "atk": 252, "spe": 252 } }
  ]
}
```
The team creator saves a new team as `Teams/<name>.json`, with the characters Windows does not allow in file names
replaced by `_` (trailing dots and spaces removed, at most 100 characters); it asks before a save would overwrite the
file of another team, and an edited team is saved back to the file it was loaded from (written again under that file
name if the file was renamed or deleted meanwhile, never into another team's file).
The first `team.active` members start the battle on the field.
When both teams have a monster with the same name (e.g. the same species), the battle adds the side's name to both,
like `Embercub (Player 1)` and `Embercub (Player 2)`, so the battle log stays unambiguous.
