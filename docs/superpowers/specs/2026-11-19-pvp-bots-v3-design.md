# PvP Bots v3: team fights and boss bots

Status: draft for approval. Builds on v1 (`2026-11-12-pvp-bots-design.md`) and v2
(`2026-11-18-pvp-bots-v2-design.md`); everything there still holds unless this document says otherwise.

## 1. Intent

The server owner wants two things on top of v2:

1. **Team fights.** v2 built `squad` and `team` arenas with spawn pads for up to 5 per side, but every player still
   gets a private 1v1 trainer. v3 turns a crowd in one arena into a real team fight. The user dropped a matchmaking
   queue: players in the arena are **split into two teams balanced by skill rating**, and **bots fill the gaps**.
2. **Boss bots.** Player-like bots that *are* the enemy, with a "souls" feel: **hard and powerful**, readable but
   punishing attacks, patterns to learn. Bosses live in **their own plugin-built structures**, have **their own gear and
   attacks**, and players fight them **in their own gear** (no kit swap).

## 2. Goals, non-goals, anti-goals

**Goals**
- In a `squad`/`team` arena with 2+ players, rounds are even fights of equal size per side, balanced by rating.
- A boss fight is hard for a solo player and for a group alike, and is won by learning the boss, not by out-gearing it.
- Admins place boss arenas and define boss types in the config without code.
- Rewards integrate with any other plugin through console commands and an event.

**Non-goals**
- A matchmaking queue, lobby or cross-arena matching (user dropped it).
- A first-kill bonus (user dropped it).
- Mob "adds" summoned by bosses (approach C, possible later).
- Changes to ally squads in boss *regions* drawn by hand (v1 behaviour stays).

**Anti-goals**
- A boss that can be cheesed: stuck on a pillar, knocked into lava, shot from an unreachable spot, or out-healed by
  standing and trading hits.
- An unreadable boss: damage with no tell, so dying feels random.
- Team fights where one side is visibly stronger at the start.
- Any v2 guarantee broken: players never lose their own items through the plugin; terrain is always restorable.

## 3. Approaches (boss bots)

The user picked **A**. Team fights have one sensible way (a team round beside the 1v1 round, reusing the trainer
brain, pads and ratings).

| | Approach | Trade-off |
|---|---|---|
| **A (picked)** | Boss is a fake player like the trainers, driven by a new boss brain (moveset, tells, phases); special moves done by the plugin with particles, sound, knockback and damage zones | Looks and moves like a player; reuses adapters, pathfinding, fair hits, load budget; most code to write |
| B | A vanilla mob in disguise (custom gear, health, attacks) | Simpler, no NMS; moves like a mob, no player skin — against "bots as bosses" |
| C | A plus summoned mob adds | More chaos, more tuning; adds can trivialise or swamp small groups — later, as a move |

## 4. Team fights

### 4.1 When a team round happens
- Applies to built arenas with preset `squad` (3 per side) or `team` (5 per side). `duel` arenas and hand-drawn
  regions keep v2 behaviour.
- **One player inside:** the v2 personal 1v1 trainer, unchanged.
- **Two or more players inside** when a round starts: a team round. (My call.) Personal trainers end; the arena runs
  one shared round.
- Players who walk in during a round **wait** for the next one (action bar: "Next round starts soon"). They get no
  trainer meanwhile. (My call.)
- More players than seats (over 6 in `squad`, over 10 in `team`): the extra players wait; on the next round, players
  who waited go first. (My call.)

### 4.2 Splitting the teams (user: equal, by rating)
- Sort players by rating (highest first); give each in turn to the team with the lower rating total that still has a
  free seat. Ties → team A. (My call; simple, close to optimal for ≤ 10 players.)
- Team sizes differ by at most one before bots fill.
- **Bots fill every empty seat on both sides** up to the preset size (user: A). Each filler bot fights at **its own
  team's average player rating** (blended profile as in v2 §6.1, fixed for the round — no in-fight adjustment in team
  rounds; my call).
- Bots and players spawn on their team's pads (A pads / B pads). Players are teleported onto their pad at the start of
  the countdown.
- If the server's `max-bots` cap can't seat all filler bots, the round waits for slots (action bar as v1's "busy"
  message). (My call: never start an uneven round.)

### 4.3 The round
- Countdown (v1 setting) → fight → the round ends when one side has nobody left standing, or at the v1 time limit
  (draw). Then the v1 rematch delay, then a new split with everyone present.
- **No friendly fire:** teammates (players and bots) cannot hurt each other. Enemies (players or bots) can.
- Filler bots target the nearest enemy (player or bot) they can reach; they re-pick targets as the trainer brain does.
- A player who dies is out for that round; they respawn per vanilla (outside the arena) and walking back in makes them
  wait for the next round. Kit arenas behave exactly as v2 §5.6 (stash, kit, restore), per player.
- A player who leaves the arena mid-round is out for that round; if a side has no players and no bots left, it loses.
- Titles at the end: "Your team won!" / "Your team lost" / "Time's up — draw".

### 4.4 Ratings
- Every player on the winning team counts as **PLAYER_WON**, every player on the losing team as **BOT_WON**, a draw as
  **DRAW**, including players who died early. (My call from "team result".)
- Update: `new = RatingUpdate.after(old, old, outcome)` — the result nudge only (+4 / −2 / 0), since there is no
  in-fight tracker in team rounds. (My call.)
- Rounds that end with no result (arena removed, reload, clear) change nothing, as in v2.

## 5. Boss bots

### 5.1 Boss arenas (user: own structures)
- `/pvpbots boss create <name> <boss-type> [confirm]`, `/pvpbots boss remove <name> [confirm]`,
  `/pvpbots boss list`; permission `pvpbots.admin`. Same preview/confirm, checks, snapshot, staged build, crash resume
  and exact restore as v2 arenas (v2 §5.3) — the same machinery with a new preset.
- Preset `boss`: a **round** arena, inside radius 20 (floor diameter 41), walls 6 high, open roof, cleared to wall
  height + 4, 3-wide entrance, **no cover**, light blocks in the floor. One boss spawn point at the centre, player
  entry at the entrance. (Sizes are my call; config entries.)
- Region `pvpbots_<name>`, priority 10, flags: new role value **`pvpbot-role boss`**, plus `build deny`,
  `pvp allow` with player-vs-player blocked by the plugin (§8), and a new flag
  **`pvpbot-boss <type>`** naming the boss type. Recorded in `arenas.yml` like other arenas.
- `arena list` shows boss arenas too, with their state (idle / fighting / cooldown m:ss).

### 5.2 Boss types (config)
Each entry under `bosses:` defines:
- `name` (boss bar and chat), `skin` (optional signed texture, as `skins:`), `base-health`,
  `health-per-extra-player` (default 0.6 = +60% per extra player), `gear` (armor, main hand, off hand, enchantments),
  `move-speed` multiplier, `knockback-resistance` (default 0.8),
- `moves:` the moveset (§5.4), `phases:` (§5.5),
- `loot:`, `xp`, `commands:`, `cooldown-seconds` (§5.7).

The plugin ships **two example types** with modest example loot (names are placeholders the owner can rename):
- `ashblade` — melee: 3-hit sword combo, leap slam, shockwave; phase 2 at 50%: faster combos, adds a 5-hit combo.
- `thornbow` — mixed: melee combo, arrow volley, backstep + charged shot; phase 2 at 50%: volleys fire twice,
  adds a leap slam to close distance.

### 5.3 Encounter flow
- States per boss arena: **IDLE → INTRO → FIGHT → (VICTORY → COOLDOWN) or (RESET)** → IDLE.
- IDLE: when the first player (survival/adventure) enters and the arena isn't in cooldown, INTRO starts: 3 s, boss
  spawns at the centre with a roar (sound + particles), boss bar shown to everyone inside.
- FIGHT: participants are players inside the region. Health scaling (user: A): max health =
  `base-health × (1 + health-per-extra-player × (players − 1))`, computed at INTRO; when more players enter mid-fight,
  max health is raised and current health keeps its fraction. (My call: players leaving never lowers it.)
- **RESET (wipe):** if no participant is alive inside the region for 10 s, the boss is removed, the arena returns to
  IDLE (full health next time). Players who died drop their own items as vanilla (user: own gear, normal deaths).
- **VICTORY:** boss health reaches 0 → title "BOSS DEFEATED" + sound to everyone inside, boss bar fades, rewards
  (§5.7), then COOLDOWN.
- COOLDOWN: no boss spawns for `cooldown-seconds` (default 600). Nobody is ejected: winners may stay and leave on
  their own, and players may walk in; anyone inside sees an action bar "The arena is quiet… returns in m:ss".
  (My call.)
- The boss uses one `max-bots` slot; if none is free, INTRO waits.

### 5.4 Moves and the souls feel
Each move in `moves:` has: `type`, `weight` (how often it's picked), `range` (when usable), `telegraph-ticks` (wind-up
with visible tell), `damage`, `cooldown-ticks`, `recovery-ticks` (the punish window: boss stands vulnerable and slow
afterwards), and type-specific values. Move types (v3 set):
- `combo` — `hits`, `interval-ticks`; melee swings through the normal fair-hit path, each hit at full combat reach.
- `leap-slam` — jumps to the target, lands with an area hit (`radius`), knockback outward.
- `shockwave` — expanding ring along the ground (`radius`, `speed`); players jump it to avoid damage.
- `volley` — `arrows`, `spread`; real arrows the boss shoots.
- `charged-shot` — a single long-telegraphed high-damage arrow.
- `backstep` — quick dash away (no damage), used to set up ranged moves.
- `roar` — phase-change move: `invulnerable-ticks`, pushes players back.

Rules that make it feel "souls" (my calls within the user's "hard and powerful"):
- **Every damaging special move has a tell** of at least 10 ticks: the boss stops, raises its weapon, particles gather
  on it, a distinct sound plays. Damage lands only at the end of the tell.
- **Damage is high:** the example bosses are tuned so a player in full diamond dies to about 4–5 big hits.
- **Punish windows:** after `recovery-ticks` the boss resumes; during recovery it takes 1.5× damage. (My call.)
- **No cheese:** the boss is immune to fall damage, fire/lava, drowning and suffocation; knockback resistance from
  config; if it leaves its region it is teleported back to the centre; if no target is reachable on foot for 3 s or a
  target is more than 4 blocks above it, it uses a leap or ranged move; it never stands still for more than 3 s while
  a target is alive.
- **Fair targeting:** it focuses the player who has dealt the most damage in the last 10 s, with some randomness, so
  groups have to manage it. (My call.)

### 5.5 Phases
`phases:` is a list ordered by health threshold. Each phase: `at` (health fraction, e.g. 0.5), `roar` (bool), and
changes: `move-speed` multiplier, `telegraph-scale` (e.g. 0.8 = 20% shorter tells), `add-moves`, `remove-moves`.
On entering a phase the boss does its roar (if set): a short invulnerable roar with a sound and a boss-bar colour change.

### 5.6 Damage rules
- Boss ↔ players inside its arena: yes. Players ↔ players in a boss arena: no (blocked by the plugin, §8).
- Ally squads and trainers never fight bosses; bosses never target bots.
- The boss cannot be hurt by players outside its region (stops sniping in from outside).

### 5.7 Rewards (user: personal loot, XP, commands, cooldown, victory moment; no first-kill bonus)
- **Winners** are players inside the region at the moment the boss dies who dealt at least 1 damage to it during the
  fight. (My call: no rewards for AFK players at the door.)
- **Personal loot** per winner, rolled from the boss type's `loot:`:
  ```yaml
  loot:
    guaranteed: [ { item: GOLDEN_APPLE, amount: 2 } ]
    rolls: 2
    table:
      - { item: DIAMOND, amount: 3, chance: 0.5 }
      - { item: ENCHANTED_GOLDEN_APPLE, amount: 1, chance: 0.05 }
  ```
  Items go into the winner's inventory; overflow drops at their feet.
- `xp`: XP points given to each winner.
- `commands`: run from the console once per winner, with `%player%` and `%boss%` placeholders.
- A Bukkit event `BossDefeatedEvent(arena, bossType, winners)` is fired before rewards are given (not cancellable).
- `cooldown-seconds` per type (default 600).

## 6. Testing
- **Unit:** team split (balance, seat caps, tie rule, waiting players first), team round state machine (start,
  elimination, draw, leave, no-result), rating results per team; boss encounter state machine (intro, scaling,
  mid-fight join, wipe reset, victory, cooldown); move selection (range, weights, cooldowns, phases); loot rolling
  (guaranteed, rolls, chance, overflow); boss config loading with warnings.
- **Self-test** (every supported version): T8 team round with dummies (3 dummies in a `team` arena → 5v5 with 7 bots,
  teams balanced, no friendly-fire damage, ratings updated with team results); B1 boss arena build/remove exact
  restore; B2 boss spawns on entry with scaled health; B3 a telegraphed move deals no damage before its tell ends;
  B4 phase change at 50%; B5 wipe resets; B6 victory gives loot/XP/commands to winners only and starts the cooldown;
  B7 cheese guards (boss pushed into lava takes no damage; boss outside region returns).
- **Load:** load:100 still ≤ 10 ms/tick; plus a boss load check (4 boss fights at once).
- **Manual:** the feel test for a boss: a group of 1, 2 and 4 real players; tells readable, deaths feel earned.

## 7. Docs
ADMIN.md: team rounds (how splitting works, bots filling, waiting players, ratings), boss commands, boss arena preset,
the `bosses:` config with a fully commented example, move types and phases, loot/XP/commands/cooldown, the event for
developers. MANUAL-CHECK.md: the boss feel test and a team-round walkthrough.

## 8. Builder-check answers (my calls)

A reviewer read this design as a builder; these settle what they asked.

- **Boss damage.** Move `damage` is raw damage dealt as a normal attack by the boss entity
  (`player.damage(amount, boss)`), so armor, enchantments and absorption apply as in vanilla. Melee combos use the
  existing fair-hit path. Because a fake-player boss counts as a player to WorldGuard, boss regions use
  **`pvp allow`**, and the plugin's own damage rules block real player ↔ real player inside boss arenas (replaces
  "pvp deny" in §5.1/§5.6).
- **Boss health** is a plugin-side pool (`base-health` scaled, can exceed 1024). The fake player's own health is kept
  full; every hit on it is cancelled and its final damage is subtracted from the pool (×1.5 during recovery, ignored
  from outside the region or during a roar). The boss bar shows the pool. Damage numbers are tuned for this.
- **No new NMS.** Boss effects (arrows with spread, particles, sounds, damage zones, leaps, invulnerability) use the
  Bukkit/Paper API from `core`; adapters stay as they are. A separate `BossBrain` with its own perception (all
  participants, aggro, reachability) sits beside the trainer brain; the trainer's v2 behaviour must not change.
- **Participants / winners.** A participant is a player in survival or adventure mode, alive and inside the region.
  The wipe timer starts when there are no participants. Winners are players who dealt ≥ 1 damage to the boss during
  this fight and are either inside the region when it dies or died inside it during this fight (so a player who
  fell just before the kill still gets loot).
- **Team rounds and the bot cap.** Team rounds are a new session type beside the 1v1 trainer session, with filler
  bots owned by the arena's team round. If not enough `max-bots` slots are free within 10 s, the round shrinks to the
  largest even size that fits (e.g. 3v3 in a `team` arena); if not even one filler bot is needed or available, the
  round runs players-only when the split is even, otherwise it keeps waiting. No arena is stuck forever by a low cap.
- **Second player during a 1v1.** When a second player enters an arena where a 1v1 trainer round is running, the 1v1
  ends with **no result** (no rating change) and the team round's countdown starts.
- **Sizes.** The boss preset uses the same staged build as v2 arenas; no extra size limit beyond the existing
  checks. The 4-boss load check must also stay ≤ 10 ms/tick.
- **Flags.** `pvpbot-boss` is registered with the other flags at load; `boss` is a new value of the existing role
  flag.

## 9. Left to the builder
- Exact particle, sound and boss-bar styling; boss arena floor pattern; light spacing.
- The example bosses' numbers, within §5.2 and §5.4's rules.
- How the boss brain shares code with the trainer brain, as long as the trainer's v2 behaviour doesn't change.
- Class names and config keys beyond those named here.
