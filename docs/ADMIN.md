# PvpBots: admin guide

PvpBots adds hidden fake-player bots to WorldGuard regions: personal 1v1 **trainers** in arenas and **ally squads**
that help players fight mobs in boss areas. Players never control bots; admins configure regions and `config.yml`.
Since v2 the plugin can also **build arenas itself** (`/pvpbots arena create`) and trainers **adapt to each player's
skill** automatically. Since v3, players who meet in a `squad` or `team` arena fight as **two rating-balanced teams
filled up with bots** (see "Team rounds"), and admins can build **boss arenas** with configurable souls-like bosses
(see "Boss arenas").

> ## Upgrading from v1: an unset `pvpbot-difficulty` now means "adaptive"
>
> In v1 a `trainer` region without `pvpbot-difficulty` used **medium**. **From v2 on it is adaptive**: the trainer's
> strength follows a hidden per-player skill rating (see "Adaptive skill" below). Your existing hand-drawn trainer
> arenas that never had the flag therefore change behaviour after the update. To keep the old fixed behaviour, set
> the flag: `/rg flag <region> pvpbot-difficulty medium`. Any value pins the arena: bots use that fixed profile,
> nothing is measured and the player's rating is not changed. `ally` regions are unchanged: unset still means medium.

## Install

- Server: Paper **1.21.11**, **26.1.x**, **26.2.x** or **26.3.x** (other versions: the plugin logs the supported list and disables itself).
- Java: 21 or newer for 1.21.11 (the dev self-test runs it on Java 21 and 25); Paper 26.x itself needs Java 25.
- Requires **WorldGuard** (and WorldEdit). Use builds that match your server and Java version.
- Drop `pvp-bots-<version>.jar` into `plugins/` and **restart** the server. On success the log shows
  `Enabled for Minecraft <version> using adapter <Adapter>; max-bots <n>`.
- **`/reload` and PlugMan (or any plugin hot-loading) are not supported.** The WorldGuard flags are registered while
  plugins load, which only happens at server start. Use `/pvpbots reload` for config changes.

## Region flags (WorldGuard)

| Flag | Values | Default | Used by |
|---|---|---|---|
| `pvpbot-role` | `trainer` \| `ally` \| `boss` | unset = no bots | all |
| `pvpbot-difficulty` | `easy` \| `medium` \| `hard` \| `pro` | unset: **adaptive** for `trainer` (new in v2), `medium` for `ally` | both |
| `pvpbot-count` | integer >= 1 | `5` | ally |
| `pvpbot-boss` | boss type id (a key of `bosses:`) | unset | boss |

`boss` and `pvpbot-boss` are set by `/pvpbots boss create` on the region it builds; you do not set them by hand.

```
/rg flag arena_1 pvpbot-role trainer
/rg flag arena_1 pvpbot-difficulty hard
/rg flag dragon_lair pvpbot-role ally
/rg flag dragon_lair pvpbot-count 10
```

- Each player in a `trainer` region gets their own bot (3 s countdown, then a fight; rematch 5 s after a round ends,
  once the player is alive again). In an `ally` region the players share one squad of `pvpbot-count` allies.
- **Flag and region-bounds edits apply when players (re-)enter the region.** Removing `pvpbot-role` ends the
  sessions in that region within a second; a changed difficulty, count or shape is used for the next session.
- Players dying to trainers follow the server's normal rules. Set keep-inventory for arenas yourself if you want it.

## Arenas built by the plugin

`/pvpbots arena ...` (permission `pvpbots.admin`) builds ready-made trainer arenas, so no hand-built region is needed.

```
/pvpbots arena create duel1 duel                 shows what would happen; nothing changes yet
/pvpbots arena create duel1 duel confirm         builds it where you stand
/pvpbots arena create fort squad iron confirm    same, with the fixed kit "iron" (see Kit arenas)
/pvpbots arena kit duel1 diamond                 set the arena's kit later ("none" clears it)
/pvpbots arena list                              name, world, position, preset, kit, status, players inside
/pvpbots arena remove duel1                      shows what would happen
/pvpbots arena remove duel1 confirm              tears it down and puts back the original blocks
```

`create` and `remove` only act when the line ends with `confirm`; without it you get a preview (size, number of
blocks changed, warnings, refusals). `create` needs a player: the arena is built **centred on the block you stand
on** (the floor is flush with the block under your feet), **facing the way you look**, with the 3-wide entrance
behind you. Names are 1-32 characters of `a-z`, `0-9`, `_`, `-` (upper case is lowered).

| Preset | Spawn pads per side | Inside floor | Cover |
|---|---|---|---|
| `duel` | 1 | 17 x 17 | none |
| `squad` | 3 | 25 x 25 | 4 pillars |
| `team` | 5 | 33 x 33 | 8 pillars |
| `boss` | 1 (entry) | round, radius 20 (41 across) | none |

`boss` is built by `/pvpbots boss create`, not by `arena create` (see "Boss arenas"). The other presets have 5-high walls, an open roof (everything inside is cleared up to 3 blocks above the walls) and light
blocks in the floor so mobs cannot spawn inside. The trainer spawns on the nearest free team B pad, across the arena
(if all are taken, the normal spawn search is used). Materials (floor, wall, light, pads, cover) come from
`arenas.materials` in `config.yml`. A `duel` arena (and the first player in any arena) gives each player a personal 1v1 trainer; `squad` and `team`
arenas run team rounds when two or more players are inside (see "Team rounds").

**What `create` checks.** The preview shows `Cannot build: ...` lines and nothing is changed when:
- the name is invalid or an arena with that name exists;
- **the area contains containers or other blocks with stored data** (chests, furnaces, signs, spawners, ...). The
  positions are listed; move them and try again. This guarantees no container contents can be lost;
- **a WorldGuard region named `pvpbots_<name>` already exists**;
- the area does not fit the world's build height or world border, or **overlaps another PvpBots arena**;
- the kit name is not in `kits:`, WorldGuard regions are disabled in that world, or the snapshot / `arenas.yml`
  cannot be written.

Overlapping other WorldGuard regions is allowed with a `Warning:`; their flags still apply. Entities (dropped items,
mobs) inside are left alone; players standing in the footprint are teleported onto the finished floor.

**Snapshot, restore and crash resume.** Before the first block changes, every block of the affected volume is saved
to `plugins/PvpBots/arenas/<name>.snapshot` (compressed, flushed to disk). At most `arenas.blocks-per-tick` blocks
change per tick (a `team` arena is about 10k blocks). The arena is recorded in `plugins/PvpBots/arenas.yml`. Its
region `pvpbots_<name>` (priority 10, `pvpbot-role trainer`, **no** `pvpbot-difficulty` so it adapts, `build deny`,
`pvp allow`) is created when the build finishes and the arena is `ready`; fights only happen in `ready` arenas. You
may change the region's flags with `/rg` afterwards; the plugin does not reset them.

If the server stops mid-build or mid-removal, the record stays `building` / `removing` and **the job finishes by
itself at the next start** (a removal restores from the snapshot, a build is redone). If a snapshot cannot be read,
no blocks are changed, an error is logged and `arena list` shows `job failed, see the server log`: fix or restore
the file and restart.

**What the snapshot covers:** exactly the blocks of the arena volume (floor, walls, the cleared space above). Physics
the build sets off in **neighbouring** blocks outside that volume is not recorded and not undone: sand or gravel
that falls into the cleared space, leaves that decay because their log was cleared, blocks attached to a cleared
block (torches, signs, vines, doors) that pop off as items. Pick a spot where none of that matters, or check the edges
after building.

**`arenas.yml` entries that cannot be read** (hand-edited, say) are skipped at start with a `SEVERE` line naming them;
`arenas.yml` is copied once to `arenas.yml.broken-<ms>` next to it, and the unreadable entries are **kept**: every
later save writes them back exactly as they were, so their region and snapshot are not orphaned. Fix the entry by
hand (compare with a good one) and restart; an arena with that name cannot be created meanwhile.

**`remove`** ends every fight in the arena, hands kit-arena players their items back, deletes the region, moves every
player standing in the footprint to just outside the entrance (on the highest block there), then **restores the
original terrain** from the snapshot. When all blocks are back the world is saved to disk, and only then are the
record and the snapshot deleted (a crash before that redoes the restore at the next start). If the region was
deleted by hand, the arena stays listed as `(region missing)` and `remove` still works. If `arenas.yml` cannot be
written, `remove` says so: kit-arena inventories in the arena were already given back, but the arena was not removed.

**`<name>.snapshot.orphan-<ms>` files** in `plugins/PvpBots/arenas/` are snapshots that had no arena record when
an arena with the same name was created (for example after `arenas.yml` was lost or hand-edited); the plugin moves
them aside instead of overwriting, because they may hold original terrain. **Do not delete them.** Putting one back
needs a developer (there is no command for it): keep the file and ask.

**Kit arenas exist only for plugin-built arenas.** Hand-drawn regions never touch inventories.

## Kit arenas (fixed kit for player and bot)

When an arena has a kit (`arena create ... <kit>` or `arena kit <name> <kit>`, one of the `kits:` in `config.yml`),
both the player and the trainer fight in it, so the fight is about skill, not gear.

- **Entering** (survival or adventure mode only; creative and spectator are left alone): the player's whole
  inventory (main, armor, off-hand) is saved to `plugins/PvpBots/stash/<uuid>.dat`, written and flushed **before**
  anything changes; then the inventory is cleared, the kit given and health and hunger set to full. Each new round
  gives the kit again fresh (used apples, arrows and broken shields are refilled). Every kit item a player gets is
  tagged (`pvpbots:kit` on the item); bots' items are not, and bots in a kit arena get no bonus totem.
- **Items that come in from outside** while a player wears the kit (an `/enderchest`, a trade, auction or mail GUI,
  another plugin giving items) are the player's own: they are untagged, so the refill at the next round and the
  restore on leaving hand them back (what does not fit is dropped at the player's feet) instead of clearing them. The
  same goes for an item held on the cursor. Picking up items and arrows is blocked inside.
- **Getting items back:** the saved items are restored and the file deleted when the player walks out of the arena,
  respawns after dying, or logs out (restored before the server saves them), and when the plugin is disabled or the
  arena is removed or its kit changed. Dropping items is blocked inside, and dying drops nothing (XP is kept); the
  player's own items that came in from outside (see above) are kept and come back with the stash after the respawn.
- **Crash safety:** after a server crash the stash file is still there; it is restored when the player next joins.
  If the inventory the server loads then holds kit items, any other items in it are handed back on top of the stash.
  If it holds no kit item (usually it was saved before the player entered, so it is the stash again), the stash is
  put back and only what the loaded inventory holds beyond one copy of the stash is handed back (matched item by
  item and by amount): nothing is duplicated in the usual case and nothing gained since is lost; at worst a stashed
  item the player had already used up after an earlier restore comes back.
  The file is deleted only after a successful restore **and** after the player's data was saved to disk (Paper would
  otherwise save it only at the next autosave or logout). If that save fails, the file is kept, a `SEVERE` line says
  so, a marker `plugins/PvpBots/stash/<uuid>.unsaved` is written (the stash is then not handed out again while the
  player stays online, even across a plugin reload), and the save is retried when the player logs out and when the
  plugin is disabled or enabled; a successful retry deletes the `.dat` and the marker. If they are still there after a
  clean logout, the player's next join restores the stash with the rule above (nothing lost; at worst a duplicate).
- **If the stash cannot be saved** when entering, nothing is changed: the player keeps their gear and is told.
- **A stash that cannot be restored:** the plugin keeps the file, leaves the player's inventory as it is, tells the
  player ("Your items could not be restored automatically; an admin has been notified.") and logs a `SEVERE` line
  naming the file: `Inventory stash of <name> (<uuid>) could not be restored; the file is kept: .../stash/<uuid>.dat`
  (the player is still wearing the arena kit), with the cause. A marker `plugins/PvpBots/stash/<uuid>.failed`
  remembers the failure across restarts; a `.failed` marker left without its `.dat` is cleared on the player's next
  join.
  **Never delete the `.dat` while the player does not have their items back.** To recover: read the cause in the
  log (a corrupt or half-copied file, or an item the server version cannot read); fix or replace the `.dat` (for
  example from a backup, or by starting the server version that wrote it) and have the player rejoin. The next
  successful restore also hands back everything the player holds that is not a tagged kit item (what they gathered
  meanwhile; what does not fit is dropped at their feet), then removes the file and the marker. If it cannot be repaired, keep the file and ask a developer: it is the
  only copy of those items.
- After a successful restore whose file cannot be deleted, the file is renamed `<uuid>.dat.restored-<ms>`; if even
  that fails an error says to delete it by hand, or the player would get that old inventory again after a restart.

## Adaptive skill

A trainer region **without** `pvpbot-difficulty` (all plugin-built arenas) adapts to the player. Each player has a
hidden rating from 0 to 100 (easy = 0, medium = 33, hard = 67, pro = 100; in between the profiles are blended),
stored on the player's own data (`pvpbots:skill`; one rating for all arenas, survives restarts). A player without
one starts at `skill.start-rating`. During a fight the bot is re-tuned every `skill.adjust-every-seconds` from the
health lead (and the damage trade), aiming for a close fight slightly in the bot's favour; after a result the stored
rating moves toward where the fight ended. Tricks (crits, w-tap, strafing, shield, totem) and the kit are fixed at
the start of a fight; only reaction, aim, turn speed, cooldown and heal threshold move, in small steps. Players see
no numbers. A player's death from any cause during the fight counts as a loss. Fights that end without a result
(player leaves, `clear`, arena removed) change nothing. Pinned arenas (flag set) never change a rating.


## Team rounds

In **plugin-built `squad` (3 per side) and `team` (5 per side) arenas** a crowd fights as two teams. `duel` arenas and
hand-drawn regions keep the personal 1v1 trainer.

- **When a team round starts.** One player alone gets the usual 1v1 trainer. When a **second** player walks in, the
  running 1v1 ends with **no result** (no rating change) and a shared team round starts: slots, then the countdown
  (`trainer.countdown-seconds`), then the fight. Everyone present is split into two teams.
- **Split rule.** Players are sorted by skill rating (highest first); each goes to the team with the lower rating
  total, unless the other team has fewer members (team sizes differ by at most one; ties go to team A). More players
  than seats (over 6 in `squad`, over 10 in `team`): the extra players **wait**, and at the next round the players who
  waited are seated first.
- **Bot fill.** Every empty seat on both sides is filled by a filler bot, so the sides are always equal. A filler bot
  fights at its own team's average player rating (the same blend as adaptive trainers), fixed for the round: there is
  no in-fight adjustment in team rounds. Players are teleported onto their team's pads at the start of the countdown.
- **Bot cap and shrinking.** Each filler bot needs a `max-bots` slot. If the slots are not all there after **10 s**,
  the round shrinks to the largest equal size the free slots allow (for example 3v3 in a `team` arena); the round
  never starts uneven.
- **Waiting.** A player who walks in mid-round, or dies, gets "Next round starts soon" and waits for the next round
  (they get no trainer meanwhile). A player who leaves mid-round is out; they get no result and no title.
- **The round.** No friendly fire: teammates (players and bots) cannot hurt each other; enemies can. The round ends
  when one side has nobody left standing, or at `trainer.time-limit-seconds` (a draw). After
  `trainer.rematch-delay-seconds` a new split is made with everyone present. When fewer than two players are left
  for the next round, the team round ends and the arena goes back to personal trainers.
- **Ratings.** Every seated player who did not leave (dead ones too) gets the team result: win = player won, loss =
  bot won, draw = draw. The rating moves by the result nudge only (`skill.win-bonus` / `skill.loss-penalty`).
  Rounds that never fought, or end with no result (arena removed, `/pvpbots reload`, `clear`), change nothing.
- **Kit arenas** (`arena create ... <kit>`) work per player exactly as in "Kit arenas": stash, kit, restore.
- **Damage.** Inside a round only members of different sides can hurt each other.

## Boss arenas

A boss arena is a round, plugin-built arena (inside radius 20, walls 6 high, open roof, 3-wide entrance, no cover,
lights in the floor) holding one boss: a bot with its own gear, a plugin-side health pool and a telegraphed moveset.
Players fight it in their own gear (no kit swap).

```
/pvpbots boss create maw ashblade            shows what would happen; nothing changes yet
/pvpbots boss create maw ashblade confirm    builds it where you stand, entrance behind you
/pvpbots boss remove maw [confirm]           ends its fight and restores the terrain
/pvpbots boss list                           boss arenas: type, state (idle / intro / fighting / cooldown m:ss)
```

`/pvpbots arena list` shows boss arenas too. Permission `pvpbots.admin`. Boss arenas use the same machinery as other
built arenas: preview and `confirm`, the same checks (containers, existing region, overlaps, world limits), snapshot
before the first block, staged build, crash resume and exact restore (see "Arenas built by the plugin"). The boss type
must be a key of `bosses:` (the refusal lists the valid ones). The region `pvpbots_<name>` has priority 10,
`pvpbot-role boss`, `pvpbot-boss <type>`, `build deny` and `pvp allow` (a boss bot counts as a player to WorldGuard;
the plugin itself blocks player-versus-player damage inside).

**Encounter flow.** IDLE, INTRO, FIGHT, then VICTORY and COOLDOWN, or a RESET.
- **Intro.** The first player (survival or adventure) inside, while the arena is not in cooldown, starts a 3 s intro;
  the boss appears at the centre with a roar and a boss bar is shown to everyone inside.
- **Health.** `base-health x (1 + health-per-extra-player x (players - 1))`, fixed at the intro; when more players
  enter mid-fight the maximum rises and the current health keeps its fraction. Players leaving never lower it.
- **Bot slot.** The boss takes one `max-bots` slot, but **only when one is free with nothing queued**. On a busy
  server a boss may wait: the boss bar then says it is waiting for a free bot slot.
- **Wipe (reset).** If no participant (alive, survival or adventure, inside) is left for 10 s, the boss is removed and
  the arena returns to IDLE with full health next time. Dead players keep the normal vanilla death rules (own gear
  drops as usual).
- **Victory.** At 0 health: "BOSS DEFEATED" title and sound, the bar fades, rewards are given, then the cooldown.
- **Cooldown.** For `cooldown-seconds` no boss spawns. Nobody is ejected; players inside see "The arena is quiet...
  returns in m:ss". **The cooldown is in memory only: it is not kept across a server restart or `/pvpbots reload`**
  (the boss is available again right after either).

**Damage rules in boss arenas.**
- Damage dealt to the boss is its **final damage** (after the boss's own armour and enchantments) subtracted from the
  plugin health pool; the fake player's own health stays full. It is **x1.5 during recovery windows**, and **ignored
  during a roar and when it comes from outside the arena**. Fire, lava, fall, drowning, suffocation and other
  non-entity damage do nothing to it.
- "Inside the arena" for the boss is **inside the region AND within the round wall** (horizontal radius 20 from the
  centre): the region's square corners outside the wall do not count. A boss that leaves is brought back to the centre.
- **Players cannot hurt each other** in a boss arena. Bosses never target bots and bots never fight bosses (ally
  squads and trainers ignore them).
- Boss damage numbers are RAW attack damage dealt by the boss, so the target's armour, enchantments and absorption
  apply as in vanilla. Combo hits deal the configured `damage` per swing; weapon enchantments on the boss's main hand
  (fire aspect, sharpness) do **not** apply to combo swings. `knockback-resistance` is the base attribute value;
  the boss's armour adds on top.
- **The boss has no plain melee.** All its damage comes from telegraphed moves, each with a tell of at least 10 ticks
  (it stops, raises its weapon, particles gather, a sound plays) before damage lands. It focuses the player who dealt
  the most damage in the last 10 s, with some randomness.
- **Stuck guard (anti-cheese).** If no target is reachable on foot (more than 3.5 blocks away horizontally and no
  progress for 3 s) or a target is more than 4 blocks above it, it uses a leap or ranged move even if that move is on
  cooldown.

**Winners and rewards.** A winner is a player who dealt at least 1 damage to the boss in this fight and was inside
the region when it died, or died inside it during the fight. At the kill the `BossDefeatedEvent` fires once (before
any reward); then each winner gets personal loot (a winner who is dead gets it once alive again), `xp`, and the
`commands` run from the console once per winner (`%player%`, `%boss%`).

### The `bosses:` config

Each key under `bosses:` is a boss type id. The shipped `config.yml` has two examples, `ashblade` (melee) and
`thornbow` (mixed melee and ranged); copy and change them. The same keys, every one commented:

```yaml
bosses:
  my-boss:                      # the id used in /pvpbots boss create <name> my-boss
    name: "My Boss"             # boss bar and chat name
    skin:                       # optional signed texture, same format as the skins: list
      value: "..."
      signature: "..."
    base-health: 350            # health pool for ONE player; a plugin-side pool, may exceed 1024
    health-per-extra-player: 0.6   # +60% per extra player (default 0.6)
    gear:                       # material names; any of head chest legs feet main-hand off-hand
      head: NETHERITE_HELMET
      chest: NETHERITE_CHESTPLATE
      legs: NETHERITE_LEGGINGS
      feet: NETHERITE_BOOTS
      main-hand: NETHERITE_SWORD
    enchants:                   # per gear slot: ENCHANTMENT: level
      main-hand: { SHARPNESS: 5, FIRE_ASPECT: 2 }
    move-speed: 1.0             # movement multiplier, 1.0 = normal
    knockback-resistance: 0.8   # 0..1, default 0.8 (base value; armour adds on top)
    moves:                      # the moveset; the key is the move id
      combo3:
        type: combo             # see the move types below
        weight: 5               # how often it is picked (0 = never by chance)
        range: [0, 3.5]         # target distance in blocks where it may be used
        telegraph-ticks: 14     # wind-up with a visible tell; damaging moves: at least 10
        damage: 8               # RAW damage (per hit / arrow / area)
        cooldown-ticks: 40      # before it can be used again
        recovery-ticks: 20      # afterwards the boss is slow and takes 1.5x damage
        hits: 3                 # combo keys
        interval-ticks: 8
      combo5:                   # only available from phase 2 (see add-moves)
        type: combo
        weight: 4
        range: [0, 3.5]
        telegraph-ticks: 14
        damage: 8
        cooldown-ticks: 60
        recovery-ticks: 24
        hits: 5
        interval-ticks: 6
      roar:
        type: roar
        weight: 0               # used by phase changes only
        invulnerable-ticks: 40
    phases:                     # ordered by health fraction
      - at: 0.5                 # starts when health reaches 50%
        roar: true              # plays the roar move first
        move-speed: 1.15        # multipliers from here on
        telegraph-scale: 0.8    # 0.8 = tells 20% shorter (never below 10 ticks for damaging moves)
        add-moves: [combo5]     # moves NOT available before this phase
        remove-moves: []        # moves dropped from this phase on
    loot:
      guaranteed:               # always dropped
        - { item: GOLDEN_APPLE, amount: 2 }
      rolls: 2                  # then this many draws from table: each picks one entry at random ...
      table:
        - { item: DIAMOND, amount: 3, chance: 0.5 }   # ... and keeps it with its chance (0..1)
    xp: 500                     # XP points to each winner
    commands: ["say %player% defeated %boss%!"]   # console, once per winner
    cooldown-seconds: 600       # arena stays quiet this long after a kill (default 600)
```

Items go into the winner's inventory; the overflow drops at their feet.

**Move types and their keys.** Every move also has `type`, `weight`, `range`, `telegraph-ticks`, `damage`,
`cooldown-ticks` and `recovery-ticks` (above); these are the extra keys:

| Type | Keys | What it does |
|---|---|---|
| `combo` | `hits`, `interval-ticks` | Melee swings, each at full reach; `damage` is dealt per swing. |
| `leap-slam` | `radius` | Jumps to the target and lands with an area hit, knockback outward. |
| `shockwave` | `radius`, `speed` | Expanding ring along the ground; players jump it to take nothing. |
| `volley` | `arrows`, `spread`, `bursts` (default 1) | Real arrows in a fan; `bursts` is how many volleys in a row. |
| `charged-shot` | none | One long-telegraphed, high-damage arrow. |
| `backstep` | `distance` | Quick dash away, no damage; sets up ranged moves. |
| `roar` | `invulnerable-ticks` | Phase-change move (give it `weight: 0`): the boss is invulnerable and pushes players back. |

Tuning guide against full diamond armour (20 armour, 8 toughness): raw 8 hits for about 2.2 hp, 14 about 4.8 hp, 16
about 5.8 hp of a 20 hp bar; the examples let the big moves kill in about 4-5 hits. Invalid values are reported as
`config: ...` warnings in the log; `/pvpbots reload` re-reads the file.

### For developers: `BossDefeatedEvent`

`pvpbots.bukkit.BossDefeatedEvent(arena: String, bossType: String, winners: List<Player>)` is a normal Bukkit event,
fired once when a boss dies, **before any reward is given**. It cannot be cancelled. `arena` is the boss arena's
name, `bossType` the id under `bosses:`, `winners` the online winners (see "Winners and rewards").

```kotlin
@EventHandler
fun onBoss(e: BossDefeatedEvent) = e.winners.forEach { it.sendMessage("${e.bossType} fell in ${e.arena}") }
```

## config.yml

| Key | Default | Meaning |
|---|---|---|
| `max-bots` | 100 | Server-wide cap on live bots (trainers, allies, team-round fillers and bosses). Further requests wait in a queue ("All trainers busy, please wait…"). |
| `trainer.countdown-seconds` | 3 | Countdown before a trainer fights. |
| `trainer.time-limit-seconds` | 300 | A round ends as a draw after this. |
| `trainer.rematch-delay-seconds` | 5 | Pause before the next round. |
| `ally.respawn-seconds` | 15 | A dead ally comes back after this. |
| `ally.leave-grace-seconds` | 10 | A squad stays this long after the last player left. |
| `bot.view-distance` | 2 | Chunks kept loaded (and ticked) around a bot. |
| `bot.stuck-retry-seconds` | 5 | Without progress for this long a bot jumps and re-paths. |
| `bot.stuck-teleport-seconds` | 10 | Without progress for this long a bot is teleported near its target. |
| `bot.spawns-per-tick` | 2 | At most this many bots are spawned per server tick (a spawn costs about 5 ms); the rest wait their turn (min 1). |
| `skill.start-rating` | 50 | Rating (0-100) of a player with none stored. |
| `skill.adjust-every-seconds` | 5 | How often the bot's skill is re-tuned during a fight (min 1). |
| `skill.step` | 3.0 | Rating change per adjustment. |
| `skill.dead-zone` | 0.15 | Health-fraction lead (beyond `target-lead`) that counts as clearly ahead or behind. |
| `skill.target-lead` | 0.05 | The bot aims to leave the player this far ahead in health. |
| `skill.max-shift-per-fight` | 15.0 | In one fight the rating never moves more than this from where it began. |
| `skill.learn-rate` | 0.3 | How far the stored rating moves toward where the fight ended. |
| `skill.win-bonus` / `skill.loss-penalty` | 4.0 / 2.0 | Extra nudge: + `win-bonus` after a player win, − `loss-penalty` after a bot win (a draw: none). |
| `arenas.blocks-per-tick` | 4000 | Blocks changed per tick while an arena is built or removed (min 1). |
| `arenas.materials.<floor\|wall\|light\|pad-a\|pad-b\|cover>` | `SMOOTH_STONE`, `STONE_BRICKS`, `SEA_LANTERN`, `BLUE_CONCRETE`, `RED_CONCRETE`, `STONE_BRICKS` | Bukkit block names used by new arenas. |
| `bosses.<id>.*` | two examples | Boss types, see "The `bosses:` config". |
| `skins` | `[]` | Signed skin textures, see below. Empty = default skins. |
| `difficulties.<easy\|medium\|hard\|pro>.*` | see file | Reaction, aim error, turn speed, attack timing, crits, w-tap, strafing, shield, healing, totem, kit. |
| `kits.<name>.*` | see file | Armor, sword, axe, food, shield, bow, arrows. |

Invalid values are reported as `config: ...` warnings in the log and the default is used. `/pvpbots reload` re-reads
the file.

**Skins:** each `skins` entry is a signed Mojang texture property, a `value` (base64) and its `signature`. Get one
from a player profile fetched with signatures
(`https://sessionserver.mojang.com/session/minecraft/profile/<uuid-without-dashes>?unsigned=false`, fields
`properties[0].value` and `properties[0].signature`) or from a skin site that shows the signed value and signature.
Each bot gets a random entry.

## Commands and permissions

| Command | Permission | What it does |
|---|---|---|
| `/pvpbots status` | `pvpbots.admin` | Bots used / cap, live bots, queue, pending spawns, sessions, average bot tick time. |
| `/pvpbots reload` | `pvpbots.admin` | Ends every session, re-reads `config.yml`, starts again. |
| `/pvpbots clear` | `pvpbots.admin` | Removes every bot now. Players still inside a bot region get their bots back within a few seconds. |
| `/pvpbots arena <create\|remove\|list\|kit> ...` | `pvpbots.admin` | Plugin-built arenas; see "Arenas built by the plugin". |
| `/pvpbots boss <create\|remove\|list> ...` | `pvpbots.admin` | Boss arenas; see "Boss arenas". |
| `/pvpbots selftest <trainer\|ally\|load N> confirm` | `pvpbots.debug` | Developer self-test. **Ends every live bot session**; runs in temporary `pvpbots_selftest*` worlds, which are unloaded and deleted afterwards. Without `confirm` it only explains this. |

Both permissions default to op.

## Behaviour notes and known limitations

- Bots are hidden: not in the tab list, `Bukkit.getOnlinePlayers()` or join/quit/death messages; they are never saved.
  Other plugins can tell bots apart with `pvpbots.bukkit.PvpBotsApi.isBot(player)`.
- Bots ignore sleeping (they don't block skipping the night) and don't count for mob spawning.
- **A world cannot unload while ally bots are in their 10 s leave-grace** (they are still in the world). Wait for the
  grace to end, or run `/pvpbots clear`, before unloading a world.
- If a bot fails repeatedly (an internal error, e.g. a broken server-version adapter), its session stops after 3
  consecutive failures: the trainer comes back when the player re-enters the region; a squad stops refilling that
  slot. The first error is logged with a stack trace, repeats as one summary line per minute.
- **Known:** a real player's death message may name the trainer bot (for example "was slain by Trainer_7f3a").
