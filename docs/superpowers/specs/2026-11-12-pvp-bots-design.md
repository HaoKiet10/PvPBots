# PvP Bots — Design Spec

- **Date:** 2026-11-12
- **Status:** Draft for review
- **Type:** Paper plugin (new project)

> **Review note:** §1–§5 (goal, decisions, scope, virtual-controller idea, architecture) were discussed and agreed in chat.
> §6–§10 (lifecycle, combat brain, config, build, testing) were drafted when writing this file and
> **have not been reviewed yet**. Items marked **[assumption]** are my defaults — please confirm or change them.

---

## 1. Goal

A Paper plugin that runs **automated, player-like PvP bots** for two jobs:

1. **Trainer** — a personal sparring opponent that teaches a real player modern PvP.
2. **Ally** — a squad that fights alongside real players in boss areas.

Bots must **feel like real players in a fight**, and the system must support **large numbers of bots**.

### Success criteria

- A player who walks into a training arena gets a personal 1v1 opponent within a few seconds, at the arena's difficulty.
- On "pro" difficulty, a competent PvP player finds the bot a real challenge; on "easy", a beginner can win.
- Bosses and mobs (vanilla or plugin) target ally bots the same way they target players.
- 100 active bots near players keep the server at a healthy tick rate (target: bot work ≤ 10 ms per tick total). **[assumption]**
- One plugin jar runs on every supported Minecraft version (1.21.11 → 26.2).
- No bot ever survives its session, is saved to disk, or shows up in chat / tab list.

---

## 2. Decisions (agreed in chat)

| # | Decision | Reason |
|---|---|---|
| 1 | **Fake players inside the server** (NMS `ServerPlayer`, no network connection) | Bosses/plugins see real players; real player hitbox & combat rules; scales without a client per bot |
| 2 | ~~Mineflayer (external clients)~~ rejected | Each bot = full client + connection; too heavy for large numbers |
| 3 | ~~Citizens / disguised mobs~~ rejected | Not players → bosses ignore them; player combat rules would have to be faked |
| 4 | **Modern 1.9+ combat** | Attack cooldown, crits, sprint-hits, shields, axe shield-disable, totems, eating |
| 5 | **Fully automated** — players never command bots | Admins only configure areas |
| 6 | **Bots can be in any world** | Wherever real players are |
| 7 | **Bots only exist near real players** | Cost scales with player count, not bot count; no lonely bots loading chunks |
| 8 | **Mining / block interaction out of scope** | Can be added later |
| 9 | **Place-based triggers** — training arenas spawn trainers, boss areas spawn allies | |
| 10 | **Areas are WorldGuard regions**, configured with plugin-registered **custom flags** | Server already uses WorldGuard; admins use familiar `/rg flag` |
| 11 | **Fixed difficulty per arena** (flag) | Predictable; players choose challenge by choosing arena |
| 12 | **One personal trainer per player** — bot fights only its owner | Private 1v1 even in a shared arena |
| 13 | **Fixed ally count per boss area** (flag), shared by everyone inside | |
| 14 | **Allies attack every hostile mob in the area**, focusing the highest max health | Works with vanilla and any boss plugin, no dependency |
| 15 | **Support Minecraft 1.21.11 → 26.2** via per-version adapters | One jar, small version-specific layer |
| 16 | **Bots hidden** — not in tab list, no join/leave/death messages | Skins still work via "unlisted" player info |
| 17 | **Server-wide cap** `max-bots`; trainers queue when full, allies fill what fits | Prevents lag |

---

## 3. Out of scope (v1)

- Mining, building, block placing, crafting, survival behaviour
- Adaptive difficulty
- Player commands to control bots
- Bots wandering without nearby players / persistence across restarts
- Tab list or chat visibility
- Team fights / free-for-all arenas (trainers are strictly 1v1)
- 1.8-style combat
- Ranged PvP for **trainers** (allies may use bows — see §7.4)
- Folia support

---

## 4. Key technical idea: bots are virtual controllers

A real player's *client* normally simulates movement and the server mostly trusts it. A fake player has no client,
so instead of writing our own physics, each bot is driven like a **virtual controller**:

- Every tick the bot's brain sets **inputs**: forward/back, strafe left/right, jump, sprint, sneak, yaw/pitch,
  plus actions (attack, swing, use item, release item, select hotbar slot).
- The adapter then runs **Minecraft's own player tick** for the bot, so vanilla code handles gravity, friction,
  collision, step-up, swimming, knockback, potion effects, item use timing and totems.

This is the same technique used by well-known fake-player mods. It must be **proven by a spike on 1.21.11 and 26.2
before anything else is built** (see §10, Milestone 0).

---

## 5. Architecture

```
┌─────────────────────────── core (Kotlin, Paper API, JVM 21) ──────────────────────┐
│                                                                                   │
│  RegionWatcher ──enter/leave──► AreaDirector ──requests──► BotManager             │
│  (WorldGuard flags)             • TrainerSession (1 per player)  • max-bots cap   │
│                                 • AllySquad (1 per boss area)    • queue          │
│                                                                  • spawn / remove │
│                                                                        │          │
│  Each bot, every tick:   CombatBrain ──► Pathfinder ──► inputs         │          │
│                          (difficulty profile)                          ▼          │
│                                                               BotAdapter (interface)
└───────────────────────────────────────────────────────────────────────┬───────────┘
                         chosen at startup by server version            │
              ┌─────────────────────────┬───────────────────────────────┤
        adapter-v1_21_11          adapter-v26_1                  adapter-v26_2
```

### Units

| Unit | Does | Depends on |
|---|---|---|
| **RegionWatcher** | Registers WorldGuard flags (in `onLoad`); reports player enter/leave of flagged regions | WorldGuard API |
| **AreaDirector** | Turns enter/leave into sessions; owns `TrainerSession`s and `AllySquad`s; ends them | RegionWatcher, BotManager |
| **TrainerSession** | State machine for one player + one trainer bot | BotManager, CombatBrain |
| **AllySquad** | State machine for one boss area + N ally bots | BotManager, CombatBrain |
| **BotManager** | The *only* place bots are created/removed; enforces `max-bots`; queue; guarantees no orphan bots | BotAdapter |
| **CombatBrain** | Per-bot decision logic each tick, parameterised by a difficulty profile | Perception data, Pathfinder |
| **Pathfinder** | Short-range A* over blocks inside the area (passable / standable checks via Paper API) | Paper API |
| **BotAdapter** | Version-specific: spawn/remove fake player, set inputs, tick, attack, use/release item, select slot, hide from counts | NMS (per version) |
| **Config** | `max-bots`, difficulty profiles, gear kits, skins, timings | — |
| **PvpBotsApi** | Tiny public API: `isBot(Player)`, `getBots()` so other plugins can ignore bots | BotManager |

### BotAdapter interface (draft)

```kotlin
interface BotAdapter {
    fun spawn(spec: BotSpec, at: Location): BotHandle      // name, UUID, skin, gear
    fun remove(bot: BotHandle)                             // no save, no death message
    fun setInput(bot: BotHandle, input: BotInput)          // applied during the bot's own vanilla player tick
    fun attack(bot: BotHandle, target: Entity)             // vanilla player attack (cooldown, crit, sweep, knockback)
    fun swing(bot: BotHandle)
    fun startUsing(bot: BotHandle, hand: EquipmentSlot)    // shield / eat / draw bow
    fun stopUsing(bot: BotHandle)
    fun selectSlot(bot: BotHandle, slot: Int)
    fun swapHands(bot: BotHandle)                          // totem swap
}
interface BotHandle { val id: UUID; val bukkit: Player }  // Bukkit view for everything else
```

Everything that *can* be done via the Paper API (inventory, health, location, potion effects, events) is done in
**core**, so adapters stay small.

---

## 6. Lifecycle & failure handling  *(not yet reviewed)*

### 6.1 Trainer session

```
player enters arena ──► QUEUED ──slot free──► SPAWNING ──► COUNTDOWN (3 s) ──► FIGHTING ──► ENDED
                          │                                                     │
                          └── "All trainers busy, please wait…" (action bar)    └── bot removed
```

- **Spawn point:** a safe standable block inside the region ~6–10 blocks from the player, facing them.
  Fallback: highest safe block at the region centre. **[assumption]**
- **Gear:** from the difficulty's kit (§8). Full health, no lingering effects.
- **Session ends when:** the player leaves the region, dies, disconnects, or changes world; the bot dies
  (player wins); the arena flag is removed; or a time limit passes (default 5 min). **[assumption]**
- **Rematch:** after the end, the player gets a new bot if they're still in the arena after a 5 s pause. **[assumption]**
- **Isolation:** a trainer only damages, and can only be damaged by, its owner. All other damage
  between that bot and other players/bots is cancelled.
- **Win/loss feedback:** short title / action-bar message to the player ("You won!" / "Trainer won"). **[assumption]**

### 6.2 Ally squad

```
first player enters boss area ──► ACTIVE (spawn up to N allies, within cap)
                                     │  ally dies → respawns after 15 s while ACTIVE   [assumption]
                                     │  cap slots free → top up to N
last player leaves (10 s grace) ──► ENDED (all allies removed)
```

- No hostile mobs in the area → allies stay near players (follow the closest one).
- Allies **never** damage players or other bots (including sweep attacks). Players hitting allies is cancelled.
  Mobs damaging allies is allowed — that's the point.
- Allies stay inside the region. If pushed out they path back; if outside or stuck for 10 s they are
  teleported back to a safe point inside.
- Note: decision 13 originally said "or when the boss dies". Because allies don't identify bosses (decision 14),
  a squad simply idles once nothing hostile is left and ends when players leave.

### 6.3 Death handling (both roles)

- Bot death: cancel drops and XP, suppress death message, remove the bot immediately. Totems work normally
  (vanilla) if the bot holds one.
- Real players dying to trainers follow the server's normal rules (admins should set keep-inventory in arenas
  via WorldGuard / gamerule if they want — documented, not enforced).

### 6.4 Failure handling

| Situation | Behaviour |
|---|---|
| No adapter for the server version | Log a clear error listing supported versions; disable the plugin |
| WorldGuard missing | Plugin won't load (`depend: [WorldGuard]`) |
| Exception in one bot's tick | Catch per bot, log once with context, remove that bot, end its session cleanly |
| Bot stuck (no progress 5 s) | Try jump / alternate path; after 10 s teleport near its target or a safe point in the region |
| Plugin disable / reload / server stop | Remove every bot synchronously before returning |
| Player data saving | Adapter guarantees fake players are never written to `playerdata`, stats or advancements |
| Name/UUID clash | Random UUID per spawn; names like `Trainer_7f3a` that can't match a real online player |

### 6.5 Hiding bots

- Tab list: send player info with **listed = false** (skin still loads). No join/quit/death messages.
- `/list`, server-list player count, and plugins that loop online players: **to be decided by the Milestone 0
  spike.** Preferred: bots live in the world but are kept out of the server's online-player list. Fallback:
  filter them from `/list` and `PaperServerListPingEvent`, and expose `PvpBotsApi.isBot()` for other plugins.
- Bots must remain visible to mob targeting so bosses attack them.

---

## 7. Combat brain  *(not yet reviewed)*

### 7.1 Tick loop (per bot, every server tick)

1. **Perceive** — only every *reaction-time* ticks (difficulty): target position, velocity, health,
   blocking/eating state, own health, attack cooldown, line of sight. Between perceptions the bot acts on
   its last snapshot — this is what makes easier bots feel slower.
2. **Decide** — highest-priority behaviour that applies (7.2).
3. **Aim** — turn toward the aim point at most *turn-speed* degrees per tick, plus *aim error* noise.
4. **Move** — Pathfinder gives the next waypoint; brain converts it to forward/strafe/jump/sprint inputs.
5. **Act** — attack / use item via the adapter.

### 7.2 Behaviour priorities (trainer)

1. **Survive** — health below threshold: back off, eat golden apple / food from kit, keep shield up while eating if able.
2. **Break shield** — target is blocking and bot has an axe: switch to axe, hit to disable shield, switch back.
3. **Defend** — target close, own attack cooldown not ready: raise shield (hard/pro).
4. **Attack** — in reach and cooldown ≥ profile threshold → hit. Higher levels add:
   - **Crit:** jump and hit while falling.
   - **Sprint reset (W-tap):** briefly release forward/sprint between hits for full sprint knockback.
5. **Position** — approach with sprint; at range, strafe around the target with randomised direction changes;
   stay inside the arena.

### 7.3 Fair hits

The adapter's `attack()` doesn't check aim, so core does: an attack is only allowed if a ray from the bot's eyes
along its **current look direction** hits the target's hitbox within vanilla reach (3.0 blocks). Aim error
therefore really makes bots miss.

### 7.4 Ally behaviour differences

- **Targets:** any entity implementing Bukkit's hostile-mob interface (`Enemy`) inside the region.
- **Focus:** highest max health — **except** a hostile actively attacking a player within 4 blocks, which is
  peeled first.
- **Unreachable targets** (flying, >4 blocks above): switch to a **bow** if the kit has one; aim with
  gravity drop compensation. **[assumption]**
- No shield-breaking logic (mobs don't block); shield used against projectiles/melee by profile.

### 7.5 Difficulty profiles (starting values — to be tuned in testing)

| Setting | easy | medium | hard | pro |
|---|---|---|---|---|
| Reaction time (ticks) | 8 | 5 | 3 | 2 |
| Aim error (°) | 12 | 7 | 3 | 1 |
| Turn speed (°/tick) | 15 | 25 | 40 | 60 |
| Attack at cooldown ≥ | random 0.6–1.0 | 0.85 | 0.95 | 1.0 |
| Crits | never | sometimes | often | whenever possible |
| W-tap | no | no | yes | yes |
| Strafing | none | simple | yes | yes, randomised |
| Shield | no | basic | yes | yes + axe breaks |
| Heal (gapple) at | — | 30% | 40% | 40% |
| Totem in offhand | no | no | yes | yes |

All values live in `config.yml` so admins can tune them.

---

## 8. Configuration  *(not yet reviewed)*

### 8.1 WorldGuard flags

| Flag | Values | Default | Used by |
|---|---|---|---|
| `pvpbot-role` | `trainer` \| `ally` | — (unset = no bots) | both |
| `pvpbot-difficulty` | `easy` \| `medium` \| `hard` \| `pro` | `medium` | trainer, ally |
| `pvpbot-count` | integer ≥ 1 | `5` | ally |

```
/rg flag arena_1 pvpbot-role trainer
/rg flag arena_1 pvpbot-difficulty hard
/rg flag dragon_lair pvpbot-role ally
/rg flag dragon_lair pvpbot-count 10
```

### 8.2 config.yml (sketch)

```yaml
max-bots: 100

trainer:
  countdown-seconds: 3
  time-limit-seconds: 300
  rematch-delay-seconds: 5

ally:
  respawn-seconds: 15
  leave-grace-seconds: 10

bot:
  view-distance: 2          # minimum chunks kept loaded around a bot
  simulation-distance: 2
  stuck-teleport-seconds: 10
  spawns-per-tick: 2        # (added in the final review, R11) max bots spawned per server tick; the rest wait FIFO

skins:                       # signed texture value + signature pairs
  - value: "..."
    signature: "..."

difficulties:              # each profile also holds the remaining §7.5 values (crits, w-tap, shield, heal, totem)
  easy:   { reaction-ticks: 8, aim-error: 12, turn-speed: 15, kit: starter }
  medium: { reaction-ticks: 5, aim-error: 7,  turn-speed: 25, kit: iron }
  hard:   { reaction-ticks: 3, aim-error: 3,  turn-speed: 40, kit: diamond }
  pro:    { reaction-ticks: 2, aim-error: 1,  turn-speed: 60, kit: netherite }

kits:                      # starter, diamond, netherite follow the same shape
  iron:
    armor: [IRON_HELMET, IRON_CHESTPLATE, IRON_LEGGINGS, IRON_BOOTS]
    hotbar: [IRON_SWORD, IRON_AXE, GOLDEN_APPLE x4]
    offhand: SHIELD
```

### 8.3 Admin commands (admins only — players never control bots)

- `/pvpbots status` — active bots, sessions, queue, cap, average bot tick time
- `/pvpbots reload` — reload config (ends all sessions first)
- `/pvpbots clear` — remove every bot now (bots come back for players still inside bot regions)
- `/pvpbots selftest <trainer|ally|load N> confirm` — developer self-test (`pvpbots.debug`); ends all live sessions

The admin-facing documentation (install, flags, every config key, commands, permissions, limitations) is
[docs/ADMIN.md](../../ADMIN.md).

---

## 9. Build & project layout  *(not yet reviewed)*

Gradle multi-project; adapters built with **paperweight-userdev** against each exact server version.

```
pvp-bots/
├─ core/               Kotlin, Paper API only, JVM 21 bytecode
├─ adapter-api/        BotAdapter, BotHandle, BotInput, BotSpec
├─ adapter-v1_21_11/   NMS for 1.21.11
├─ adapter-v26_1/      NMS for 26.1.x
├─ adapter-v26_2/      NMS for 26.2
└─ plugin/             paper-plugin entry point; shades everything into one jar
```

- Adapter selected at startup from the server's Minecraft version; unknown version → disable with clear error.
- **Language: Kotlin** for every module (core, adapter-api, adapters). Core and adapter-api target JVM 21 so one
  jar loads on all versions; 26.x adapters may need a newer JDK to compile (to confirm in Milestone 0).
- **Kotlin standard library** is not bundled: `plugin.yml` lists it under `libraries:` so Paper downloads it from
  Maven Central on first start (keeps the jar small, no clash with other Kotlin plugins). Fallback if the server
  has no internet: bundle and relocate it.
- No coroutines for bot logic — all bot work is plain tick-driven code on the main server thread.
- Dependencies: WorldGuard (required).

---

## 10. Testing & milestones  *(not yet reviewed)*

### 10.1 Tests

- **Unit (JUnit, no server):** difficulty profiles, target selection & peeling, cap/queue logic, session state
  machines, aim / fair-hit ray maths, bow drop compensation, Pathfinder on a fake block grid.
  Core logic is written against small interfaces so it can be tested without Bukkit.
- **In-server (per supported version, using `run-paper` dev servers):** a scripted smoke test — create a test
  arena and boss area, walk a test player in, assert bot spawns, fights, ends, and is removed; restart server
  and assert no bot data was saved.
- **Performance:** 100 bots across several worlds near test players; measure with spark; target ≤ 10 ms/tick
  for all bot work.
- **Feel test:** real players spar at each difficulty; tune profile values.

### 10.2 Milestones

| # | Milestone | Done when |
|---|---|---|
| **M0** | **Spike** (throwaway): fake player driven by inputs with vanilla physics on 1.21.11 **and** 26.2; hidden from tab list; decide §6.5 | Bot walks, jumps, takes knockback, attacks, eats, blocks — on both versions |
| M1 | Core skeleton: BotManager + cap/queue, RegionWatcher + flags, TrainerSession, one adapter | Walk into arena → easy bot spawns, fights crudely, is removed on leave |
| M2 | Full trainer combat brain + difficulty profiles + kits | All four difficulties playable and distinct |
| M3 | AllySquad + ally brain (peeling, bow) | Allies help kill a Wither in a boss area |
| M4 | All three adapters + in-server smoke tests per version | Same jar passes smoke tests on 1.21.11, 26.1, 26.2 |
| M5 | Performance pass + feel tuning | 100 bots within budget; players agree difficulties feel right |

---

## 11. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| "Virtual controller" approach doesn't give vanilla physics on some version | Core idea fails | M0 spike before any real work; fallback = own physics layer in core |
| Minecraft updates break adapters | Upkeep each release | Keep adapter interface tiny; one adapter per minor version |
| Bot AI on main thread hurts TPS | Lag | Perception throttled by reaction time; cap; per-bot timing in `/pvpbots status` |
| Bots counted by other plugins as online players | Odd counts, plugin side effects | §6.5 spike decision + `PvpBotsApi.isBot()` |
| Chunk cost | Lag | Bots only near players; minimum view/simulation distance |

---

## 12. Open questions for review

1. Trainer time limit (5 min), rematch delay (5 s), countdown (3 s) — OK?
2. Ally respawn after 15 s while the squad is active — or should dead allies stay dead?
3. Should allies use bows against flying bosses (Wither, Dragon)?
4. Win/loss messages to the player — wanted?
5. Kits per difficulty — use the sketch, or do you have specific gear in mind?
6. Performance target: 100 bots at ≤ 10 ms/tick — is 100 the right number for your server?
