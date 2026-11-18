# PvpBots: admin guide

PvpBots adds hidden fake-player bots to WorldGuard regions: personal 1v1 **trainers** in arenas and **ally squads**
that help players fight mobs in boss areas. Players never control bots; admins configure regions and `config.yml`.
Since v2 the plugin can also **build arenas itself** (`/pvpbots arena create`) and trainers **adapt to each player's
skill** automatically.

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
| `pvpbot-role` | `trainer` \| `ally` | unset = no bots | both |
| `pvpbot-difficulty` | `easy` \| `medium` \| `hard` \| `pro` | unset: **adaptive** for `trainer` (new in v2), `medium` for `ally` | both |
| `pvpbot-count` | integer >= 1 | `5` | ally |

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

All presets have 5-high walls, an open roof (everything inside is cleared up to 3 blocks above the walls) and light
blocks in the floor so mobs cannot spawn inside. The trainer spawns on the nearest free team B pad, across the arena
(if all are taken, the normal spawn search is used). Materials (floor, wall, light, pads, cover) come from
`arenas.materials` in `config.yml`. Today every preset gives each player a personal 1v1 trainer; the extra pads are
stored for a later team mode.

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

**`remove`** ends every fight in the arena, hands kit-arena players their items back, deletes the region, then
**restores the original terrain** from the snapshot and deletes the record and the snapshot. Players standing in the
arena (floor, walls) may find the ground gone when the blocks revert; ask them to leave first. If the region was
deleted by hand, the arena stays listed as `(region missing)` and `remove` still works.

**`<name>.snapshot.orphan-<ms>` files** in `plugins/PvpBots/arenas/` are snapshots that had no arena record when
an arena with the same name was created (for example after `arenas.yml` was lost or hand-edited); the plugin moves
them aside instead of overwriting, because they may hold original terrain. They are safe to keep and to inspect;
delete one only when you know that terrain is not needed.

**Kit arenas exist only for plugin-built arenas.** Hand-drawn regions never touch inventories.

## Kit arenas (fixed kit for player and bot)

When an arena has a kit (`arena create ... <kit>` or `arena kit <name> <kit>`, one of the `kits:` in `config.yml`),
both the player and the trainer fight in it, so the fight is about skill, not gear.

- **Entering** (survival or adventure mode only; creative and spectator are left alone): the player's whole
  inventory (main, armor, off-hand) is saved to `plugins/PvpBots/stash/<uuid>.dat`, written and flushed **before**
  anything changes; then the inventory is cleared, the kit given and health and hunger set to full. Each new round
  gives the kit again fresh (used apples, arrows and broken shields are refilled).
- **Getting items back:** the saved items are restored and the file deleted when the player walks out of the arena,
  respawns after dying, or logs out (restored before the server saves them), and when the plugin is disabled or the
  arena is removed or its kit changed. Dropping items is blocked inside, and dying drops nothing (XP is kept).
- **Crash safety:** after a server crash the stash file is still there; it is restored when the player next joins.
  The file is deleted only after a successful restore.
- **If the stash cannot be saved** when entering, nothing is changed: the player keeps their gear and is told.
- **A stash that cannot be restored:** the plugin keeps the file, leaves the player's inventory as it is, tells the
  player ("Your items could not be restored automatically; an admin has been notified.") and logs a `SEVERE` line
  naming the file: `Inventory stash of <name> (<uuid>) could not be restored; the file is kept: .../stash/<uuid>.dat`,
  with the cause. A marker `plugins/PvpBots/stash/<uuid>.failed` remembers the failure across restarts.
  **Never delete the `.dat` while the player does not have their items back.** To recover: read the cause in the
  log (a corrupt or half-copied file, or an item the server version cannot read); fix or replace the `.dat` (for
  example from a backup, or by starting the server version that wrote it) and have the player rejoin. The next
  successful restore also hands back whatever the player gathered meanwhile (what does not fit is dropped at their
  feet), then removes the file and the marker. If it cannot be repaired, keep the file and ask a developer: it is the
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


## config.yml

| Key | Default | Meaning |
|---|---|---|
| `max-bots` | 100 | Server-wide cap on live bots. Further requests wait in a queue ("All trainers busy, please wait…"). |
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
| `skill.win-bonus` / `skill.loss-penalty` | 4.0 / 2.0 | Extra nudge after a player win / a bot win (a draw: none). |
| `arenas.blocks-per-tick` | 4000 | Blocks changed per tick while an arena is built or removed (min 1). |
| `arenas.materials.<floor\|wall\|light\|pad-a\|pad-b\|cover>` | `SMOOTH_STONE`, `STONE_BRICKS`, `SEA_LANTERN`, `BLUE_CONCRETE`, `RED_CONCRETE`, `STONE_BRICKS` | Bukkit block names used by new arenas. |
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
