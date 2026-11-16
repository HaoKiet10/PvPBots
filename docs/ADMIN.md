# PvpBots: admin guide

PvpBots adds hidden fake-player bots to WorldGuard regions: personal 1v1 **trainers** in arenas and **ally squads**
that help players fight mobs in boss areas. Players never control bots; admins configure regions and `config.yml`.

## Install

- Server: Paper **1.21.11**, **26.1.x** or **26.2.x** (other versions: the plugin logs the supported list and disables itself).
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
| `pvpbot-difficulty` | `easy` \| `medium` \| `hard` \| `pro` | `medium` | both |
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
