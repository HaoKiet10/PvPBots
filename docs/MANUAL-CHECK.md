# Manual in-game check (pending)

Still to be done by a person with a Minecraft client: confirms the skin shows, bots stay out of the tab list, and no chat messages appear.


Run these from the repo root on branch `feat/pvp-bots`.

1. Start the dev server with `./gradlew :plugin:runServer`.
   - It downloads Paper 1.21.11, WorldEdit and WorldGuard, writes `eula=true` into `plugin/run/`, and starts on port 25565 with Java 25.
   - Wait for `Done (…)!`. The log must show `[PvpBots] Enabling PvpBots` and no `Unsupported Minecraft version` line.
2. In the server console, run `op <yourname>`, then join `localhost:25565` with a **1.21.11** client. Use creative mode to build if needed, but switch to `/gamemode survival` before testing fights.
3. Create the arena:
   - Stand somewhere flat, take a wooden axe (`//wand`), and select two opposite corners about 20 blocks apart, from a couple of blocks below the floor to about 10 above.
   - Run `/rg define arena_1`, `/rg flag arena_1 pvpbot-role trainer` and `/rg flag arena_1 pvpbot-difficulty hard`.
   - If the world has PvP disabled, also run `/rg flag arena_1 pvp allow`.
4. Walk out of the region, then walk in. Expect:
   - (a) A bot named `Trainer_xxxx` appears 6-10 blocks away, facing you, wearing diamond armour.
   - (b) It stands still for 3 s (countdown), then fights you: it approaches, strafes, uses its shield, and hits you only when its crosshair is on you.
   - (c) **Hold Tab: the bot is NOT in the tab list.**
   - (d) **No chat message** when it appears, dies or disappears. `/list` shows only you.
   - (e) The bot is visible with a default skin (`skins: []`).
   - Optional: kill it. You should see the title "You won!", no item or XP drops, and no death message, and a new bot about 5 s later.
5. Walk out of `arena_1`. The bot vanishes immediately.
6. Overlap test:
   - Select a larger area around `arena_1` and run `/rg define boss_1`, `/rg flag boss_1 pvpbot-role ally`, `/rg setpriority boss_1 0` and `/rg setpriority arena_1 10`.
   - Inside `boss_1` but outside `arena_1`, 5 `Ally_xxxx` bots appear (the default count) and follow you. Spawn a zombie there (spawn egg) and the allies kill it.
   - Your hits on allies do nothing, and allies never hurt you.
   - Walk into `arena_1`: you get a trainer, not allies, and the allies vanish about 10 s later.
7. Optional: `/pvpbots status` shows bots used/cap, queue, sessions and average tick ms. `/pvpbots clear` removes all bots, and they come back within about 1 s if you are still inside a region.
8. Stop the server with `stop`. Afterwards check that `plugin/run/world/playerdata/` contains only your own UUID's `.dat` file.

Please report any failure of (c) or (d), and whether the skin and tab hiding work. That is the M0 Step 6 check, still pending.

---

# Fix round 1 (commit `e7278f9`)

