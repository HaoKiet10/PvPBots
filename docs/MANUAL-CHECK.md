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

# v2 manual checks (arenas, kit arenas, adaptive skill)

Needs a person with a real client on the same Minecraft version as the dev server (`./gradlew :plugin:runServer`,
or any supported Paper; 26.3 is the newest). Op yourself, then use survival mode for fights unless a step says
otherwise.

## A. Arena build and remove on real terrain

1. Find uneven terrain (hills, trees, a stream) with no chests, signs or furnaces within about 40 blocks. Stand on the
   block that should be the arena floor and look the way the arena should face.
2. `/pvpbots arena create test1 team` (no `confirm`). Expect a preview: size, block count, kit "own gear", no
   `Cannot build` line.
3. Refusals (nothing may change): place a chest inside the footprint and repeat the preview, which must list the
   chest position under `Cannot build`. Remove the chest. Run `/rg define pvpbots_test1` somewhere, check the preview
   refuses, then `/rg remove pvpbots_test1`.
4. `/pvpbots arena create test1 team confirm`. The arena appears within seconds; `/pvpbots arena list` shows
   `test1 ... building NN%` then `ready`. Check: floor flush with your block, 5-high walls, entrance behind you,
   blue pads on your side, red on the far side, 8 cover pillars, sea lanterns in the floor, nothing inside
   the walls, open roof.
5. Walk in. A `Trainer_xxxx` spawns on a red pad across the arena and fights; you cannot break blocks inside.
   `/rg info pvpbots_test1` shows priority 10, `pvpbot-role trainer`, no `pvpbot-difficulty`.
6. Restart test: run `/pvpbots arena create test2 squad confirm` somewhere else and `stop` the server within a second
   or two. Start it again: the log must say `resuming build` and the arena becomes `ready`; no half-built arena.
7. `/pvpbots arena remove test1` (preview), then `/pvpbots arena remove test1 confirm` while you stand outside. The
   terrain must look exactly as before (compare screenshots from step 1: trees, water, holes). `arena list` no longer
   shows it, `plugins/PvpBots/arenas/test1.snapshot` is gone, `/rg info pvpbots_test1` says no such region.
8. Remove `test2` too.

## B. Kit arena with a real client

1. Build `/pvpbots arena create kit1 duel iron confirm`. Put distinctive items in your inventory, off-hand and armor
   slots (a named item, an enchanted book, a full stack of something).
2. Walk in. You are told your items are saved and that you fight with the iron kit; the inventory is the iron kit,
   health and hunger full. Check `plugins/PvpBots/stash/<your-uuid>.dat` exists. The trainer wears the same kit.
3. Win or lose a round: you get a fresh kit for the next round (apples, arrows, shield refilled). Press Q: nothing
   drops.
4. **Leave:** walk out. "your own items are back"; everything is exactly where it was (including off-hand and armor);
   the `.dat` file is gone.
5. **Logout:** walk in again, log out inside, log back in: your original items are back before you can act, the
   stash file is gone.
6. **Death:** walk in, let the trainer (or a second player) kill you. Nothing drops from the body, XP is kept. After
   respawn (outside the arena) your original items are back.
7. **Crash:** walk in, then kill the server process hard (no `stop`). Start it and join: your original items come
   back on join.
8. Optional: `/pvpbots arena kit kit1 none` while standing inside returns your items at once; `arena remove kit1
   confirm` while inside does too.
9. Failed-restore check (use a throwaway account): walk into a kit arena, `stop` the server, damage the stash `.dat`
   (cut it in half), start the server and join. Expected: you keep your current items, you are told "Your items could
   not be restored automatically; an admin has been notified.", the log has a `SEVERE ... the file is kept` line and
   `<uuid>.dat` plus `<uuid>.failed` remain. Put the original file back and rejoin: items are back and both files
   are gone.

10. Items from outside: walk in, open `/enderchest` (op) or any trade/mail GUI and move one of your own items into
    your inventory. Win a round: the item is still there next to the fresh kit. Walk out: it is back with your
    original items, and no kit item remains. Repeat, but lose the round by dying: nothing drops; after the respawn the
    item is back with your original items.
11. Kit change mid-fight: while fighting in `kit1`, `/pvpbots arena kit kit1 diamond`. Your items come back and you
    are given the diamond kit at once; after the current round the next trainer wears diamond and you are refilled
    with diamond.

## B2. Crash drill (needs the ability to kill the server process)

1. Inventory: walk into a kit arena and stay there past one autosave (`ticks-per.autosave` in `bukkit.yml`, by
   default 6000 ticks = 5 min, or run `save-all` while inside). Walk out (items back, `.dat` gone), then **at once**
   kill the server process hard (no `stop`). Start it and join: your own items are intact (not the kit), no `.dat`
   file is left, nothing duplicated.
2. Terrain: build a small arena (`duel`) on recognisable terrain and wait for `ready`. `/pvpbots arena remove <name>
   confirm`, and as soon as `arena list` no longer shows it, kill the server hard (well before the next autosave).
   Start it: the original terrain is back (no floor or wall blocks left), `arenas.yml` has no entry and no
   `<name>.snapshot` is left. If the kill came before the removal finished, the log says the removal is resumed
   and the same end state follows.
3. Remove with a player inside: stand in the middle of an arena and remove it. You are moved just outside the
   entrance, standing on the ground, not inside a block.

## C. Feel test (adaptive skill)

Needs two testers: a beginner and a strong PvP player, each with a fresh rating (new account, or remove the
`pvpbots:skill` tag by using another account).

1. Build an adaptive arena: `/pvpbots arena create feel duel confirm` (no kit, so everyone uses their own gear;
   give both testers comparable gear, for example full iron).
2. Each tester fights **5 rounds** in a row (the bot rematches 5 s after each round). Write down, per round, who won
   and roughly how much health the winner had left.
3. Pass criteria: from round 3 on, fights are close for both testers (the winner finishes with little health left,
   and the results are mixed rather than 5-0 either way); the beginner's bot is visibly softer than the strong
   player's; **the bot never visibly stalls, freezes, or runs away to let the player win**, and nothing about the
   adjustment is announced.
4. Leave and rejoin the server between rounds 3 and 4: the trainer should still match (the rating is remembered).
5. Pinned check: `/rg flag pvpbots_feel pvpbot-difficulty easy`; fights now use the fixed easy profile for everyone.
   Remove the flag again (`/rg flag pvpbots_feel pvpbot-difficulty`) to return to adaptive.
6. Report any case where the bot clearly mismatches (always wins or always loses after round 3), with the starting
   gear, so `skill.*` in `config.yml` can be tuned.

---

# v3 manual checks (team rounds, boss arenas)

Needs real clients on the same Minecraft version as the dev server. For C you need 2-4 testers (a second account
or friends); for D you need groups of 1, 2 and 4. Op yourself; use survival mode.

## C2. Team-round walkthrough (2-4 real players)

1. Build `/pvpbots arena create teams team confirm` (a `squad` arena also works with 3 per side). Optional second
   run with a kit: `/pvpbots arena kit teams iron`.
2. **One player:** the first tester walks in: the usual personal trainer (3 s countdown, then a 1v1). Nothing team-like.
3. **Second player enters during the 1v1.** The 1v1 ends at once with no title and no rating change, and a team
   round starts: both players are teleported onto pads (one per side where possible), the countdown runs, and each side
   is filled with bots to 5 (squad: 3). Check: the two humans are on opposite
   sides and both sides have the same number of fighters.
4. **No friendly fire:** hit a teammate (human or bot): no damage. Enemies take damage. Bots fight bots as well as you.
5. **Waiting:** a third tester walks in mid-fight: action bar "Next round starts soon", no trainer for them. After the
   round ends and the rematch delay (5 s), they are seated in the new split (waiting players first).
6. **Death:** die in a round: you are out for that round (respawn outside; walking back in makes you wait).
7. **Result:** when a side is gone you get the title "Your team won!" or "Your team lost" (dead players too); a draw
   at the time limit shows the draw title. The skill rating moves accordingly (win nudge up, loss nudge down).
8. **Leave mid-round:** you get no title and are out. If everyone leaves, the bots vanish.
9. **Bot cap:** set `max-bots: 4` in `config.yml`, `/pvpbots reload`, walk in with 2 players: after about 10 s the round
   shrinks to the largest equal size that fits (not 5v5) instead of waiting forever. Restore `max-bots`.
10. Kit arena (if set up): each player is stashed, given the kit, and gets items back on leaving, dying or logging out,
    as in section B.

## D. Boss feel test (groups of 1, 2 and 4)

Setup: `/pvpbots boss create maw ashblade confirm` (and later `thornbow`). Testers wear full diamond with a sword,
bow and golden apples, as in a normal survival server. Record for each group size: did the fight feel fair, how many
deaths, how long it took.

1. **Spawn:** walk in. After 3 s the boss appears at the centre with a roar and the boss bar; `/pvpbots boss list`
   shows `fighting`. Check that the boss bar max grows with the group (1 player = base, each extra +60%).
2. **Tells readable:** every damaging move is preceded by a visible wind-up (the boss stops, raises its weapon,
   particles and a sound) of at least half a second. The boss has **no** plain hit without a tell. Dodge the combo,
   jump the shockwave ring, run from the leap slam, break line of sight for the charged shot. Note any hit you did not
   see coming.
3. **Punish windows:** after a move the boss is slow for a moment and takes more damage; attacking then should clearly
   shorten the fight.
4. **Phase change** at 50%: the boss roars (invulnerable for about 2 s, pushes you back), the bar changes, and new
   moves appear (ashblade: 5-hit combo; thornbow: double volleys and leap slam).
5. **Deaths feel earned:** you should be able to name the move that killed you. Big moves take about 4-5 hits on
   full diamond. Report any death you could not attribute.
6. **Cheese hunt (try hard):** stand on the entrance wall top or a pillar, hide in a corner, hug the wall, shoot from
   outside the region, lure the boss into the entrance, use water/lava/fall tricks, out-heal by eating gold apples
   while trading. Expected: the boss is never stuck, never takes damage from outside, uses a leap or ranged move when it
   cannot reach you, and trading hits without dodging loses.
7. **Group of 2 and 4:** the boss focuses the player doing the most damage; a second player standing next to it should
   draw attention away. Players cannot hurt each other. A late joiner raises the boss health (current health keeps its
   fraction).
8. **Wipe:** everyone dies or leaves: after about 10 s the boss and bar disappear; walking in again starts a full-health
   boss.
9. **Victory:** each winner (dealt damage, inside or died inside) gets the loot, XP and any `commands`; a tester
   who only stood at the door gets nothing. Then the arena is quiet: action bar "The arena is quiet... returns in
   m:ss", and the boss returns after `cooldown-seconds`. Restart the server or `/pvpbots reload` during the cooldown:
   the cooldown is **not** remembered; the boss is available at once (expected, documented).
10. **Busy server:** with `max-bots` already used up by trainers, walk into the boss arena: the boss bar says it is
    waiting for a free bot slot, and the boss spawns when one frees.
11. **Remove:** `/pvpbots boss remove maw confirm` during a fight: the boss and bar vanish, you are moved just outside
    the entrance, and the terrain is restored exactly.
12. Report: group size, boss type, deaths, time to kill, any move that felt unfair or unreadable, any cheese found,
    so `bosses:` numbers can be tuned.
