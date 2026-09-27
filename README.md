# BuildFFA

> ## ⚠️ This plugin is no longer supported
>
> **BuildFFA is unmaintained. It will not receive bug fixes, security patches,
> compatibility updates, or any other changes.**
>
> **It is NOT recommended to use this plugin.** It is no longer developed, no
> longer tested against current Spigot/Paper builds, and it is known to have
> issues (see [Known problems](#known-problems)). It is published here for
> archival purposes only.
>
> - ❌ No bug fixes
> - ❌ No updates or new features
> - ❌ No support — [open an issue](https://github.com/ObvGreen/BuildFFA/issues)
>   and nobody will answer
> - ❌ No Spigot/Paper compatibility work
>
> If you need a build-FFA arena, pick a maintained alternative or fork this
> yourself under the [MIT license](LICENSE). You are welcome to do so — that is
> the main reason this code is public.

A build-FFA (build, fight, die) arena plugin for Spigot 1.8 servers.

---

## Features

- **Arena gameplay** — join/leave commands, survivable kit, kill rewards and
  killstreak escalation.
- **Build mode** — admins toggle free block editing outside of the arena.
- **Timed block rollback** — placed blocks are removed again after a
  configurable delay, and broken blocks are restored to their original state.
- **Spawn protection** — a configurable world region where players cannot build,
  break, attack, or be attacked.
- **Combat logging** — quitting while in combat still credits the kill.
- **Cosmetics** — kill-message packs, kill effects and death cries, all
  permission-gated and configurable.
- **Hotbar manager** — players can rearrange their kit slots via a GUI.
- **SQLite statistics** — kills, deaths, killstreaks and daily counters,
  persisted locally with leaderboards and killstreak announcements.
- **PlaceholderAPI integration** — stats and leaderboard placeholders.

## Requirements

| | |
| --- | --- |
| Server | Spigot **1.8.x** (compiled against `spigot-api:1.8.8-R0.1-SNAPSHOT`) |
| Java | 8+ |
| Build tool | Maven 3.6+ |
| Soft dependency | [PlaceholderAPI](https://spigotmc.org/resources/6245/) 2.11.x — optional |

> PlaceholderAPI is a *soft* depend. The plugin enables fine without it; only
> the placeholders are unavailable.

## Building from source

1. Clone the repository.
2. Drop the PlaceholderAPI jar into `libs/` (see [`libs/README.md`](libs/README.md)):

   ```bash
   mkdir -p libs
   curl -L -o libs/PlaceholderAPI-2.11.3.jar \
     https://github.com/PlaceholderAPI/PlaceholderAPI/releases/download/2.11.3/PlaceholderAPI-2.11.3.jar
   ```

3. Build:

   ```bash
   mvn clean package
   ```

The plugin lands in `target/BuildFFA-1.0.jar`. `sqlite-jdbc` is resolved from
Maven Central and shaded into that jar, so the build output is self-contained —
you do not need to install SQLite separately on your server.

## Installation

1. Copy `BuildFFA-1.0.jar` into your server's `plugins/` folder.
2. (Optional) install PlaceholderAPI.
3. Start the server once to generate `plugins/BuildFFA/`.
4. Create a world named `buildffa` (and `lobby`, if you use `/buildffa leave`).
5. Set the spawn-protection corners with `/buildffa pos1` and `/buildffa pos2`.

## Commands and permissions

All commands live under `/buildffa` (alias `/bffa`).

| Command | Permission | Description |
| --- | --- | --- |
| `/buildffa join` | `buildffa.user` | Teleport into the arena and receive your kit |
| `/buildffa leave` | `buildffa.user` | Return to the lobby |
| `/buildffa stats [player]` | `buildffa.user` | Show kill/death/killstreak statistics |
| `/buildffa cosmetics` | `buildffa.user` | Open the cosmetics menu |
| `/buildffa hotbarmanager` | `buildffa.user` | Open the hotbar layout editor |
| `/buildffa hotbarorder [player]` | `buildffa.user` / `buildffa.admin` | Show a hotbar layout |
| `/buildffa build` | `buildffa.admin` | Toggle free block editing |
| `/buildffa reload` | `buildffa.admin` | Reload all config files |
| `/buildffa wipe <player>` | `buildffa.admin` | Erase a player's statistics |
| `/buildffa modify <stat> <amount> <player>` | `buildffa.admin` | Adjust kills, deaths, killstreak or max_streak |
| `/buildffa pos1 [x y z] [world x y z]` | `buildffa.admin` | Set spawn-protection corner 1 |
| `/buildffa pos2 [x y z] [world x y z]` | `buildffa.admin` | Set spawn-protection corner 2 |

Additional permissions declared in `plugin.yml`:

| Permission | Default | Description |
| --- | --- | --- |
| `buildffa.user` | `true` | Access to the player-facing commands |
| `buildffa.admin` | `op` | Access to the admin commands |
| `buildffa.cosmetics.toxic` | `op` | Unlocks the "Toxic" kill-message pack |
| `buildffa.cosmetics.killeffect.lightning` | `op` | Unlocks the "Lightning Strike" kill effect |

## Configuration

| File | Purpose |
| --- | --- |
| `config.yml` | Arena settings, spawns, combat, fireballs, double-jump feather, killstreak announcements, kill rewards |
| `cosmetics.yml` | Kill-message packs, kill effects and death cries |
| `cosmetics_data.yml` | Auto-generated player cosmetic selections (do not edit by hand) |

Reload with `/buildffa reload`.

The arena and lobby world names, and both spawn points, are configurable under
`settings:` in `config.yml` — nothing is hardcoded to a world name at runtime.

## PlaceholderAPI placeholders

Identifier: `buildffa`.

| Placeholder | Example value |
| --- | --- |
| `%buildffa_kills%` | `42` |
| `%buildffa_deaths%` | `17` |
| `%buildffa_killstreak%` | `5` |
| `%buildffa_daily_kills%` | `3` |
| `%buildffa_playing%` | `2` (players currently in the arena) |
| `%buildffa_pos_<stat>%` | `1` (leaderboard position of the player) |
| `%buildffa_<stat>_name_<n>%` | Player name at leaderboard position `n` |
| `%buildffa_<stat>_value_<n>%` | Stat value at leaderboard position `n` |
| `%buildffa_current_highest_killstreak%` | `12` |
| `%buildffa_current_highest_killstreak_player%` | `Notch` |
| `%buildffa_leaderboard_remainingtime%` | `09:58` |

Valid `<stat>` values: `kills`, `deaths`, `highest_killstreak`, `daily_kills`,
`daily_highest_killstreak`.

## Known problems

These are unresolved and are **not** being fixed, for the reasons stated above.

- **A hard crash loses up to 30 minutes of stats.** `StatsManager` writes dirty
  entries on a 30-minute repeating task. `shutdown()` does flush on a clean stop,
  but a JVM crash or `kill -9` discards everything since the last flush. The
  same applies to hotbar layouts (`hotbars.db`) and cosmetics.
- **The build limit is a Y-coordinate only.** `max-build-height` caps how high a
  player may build, but nothing bounds horizontal placement — players can build
  the full world border radius in every direction, and world borders are never
  consulted.
- **The YAML-to-SQLite migration deletes first.** `migrateYamlToDatabaseIfPresent()`
  runs `DELETE FROM stats` before importing `buildffastats.yml`. If that file is
  still in the data folder — for example because the automatic rename failed on
  a locked file — every restart wipes current SQLite stats and restores the
  stale YAML. Delete `buildffastats.yml` manually after the first migration.
- **Leaving the arena wipes your killstreak.** `FFALeave()` calls
  `resetKillstreak(uuid)`, so `/buildffa leave` costs you your current streak
  even if you were not in combat.
- **No message localisation.** Every player-facing string is a hardcoded `§`
  literal in Java. `messages.yml` was removed as dead code, so rewording or
  translating any message requires recompiling the plugin.
- **1.8 API only.** The plugin is compiled against `spigot-api:1.8.8-R0.1-SNAPSHOT`
  and is not remapped. It will not load on 1.9+ without a remap, and has never
  been tested there.

## Project layout

```
BuildFFA/
├── libs/                     # PlaceholderAPI jar (not committed)
├── src/main/java/me/obvgreen/buildffa/
│   ├── BuildFFAPlugin.java   # Plugin entry point, config + world helpers
│   ├── commands/             # /buildffa command, tab completion
│   ├── cosmetics/            # Cosmetics manager + GUI
│   ├── listeners/            # Combat, fireball, drop, spawn protection
│   │   └── block/            # Block place/break + rollback
│   ├── managers/             # Stats, combat, hotbar, region (SQLite)
│   └── placeholders/         # PlaceholderAPI expansion
├── src/main/resources/       # plugin.yml, config.yml, cosmetics.yml
├── pom.xml
└── LICENSE
```

## License

[MIT](LICENSE) © ObvGreen.

Bundled third-party code:

- `org.xerial:sqlite-jdbc` — Apache License 2.0, shaded into the release jar.
