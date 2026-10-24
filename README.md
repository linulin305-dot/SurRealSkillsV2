# SurRealSkills

**SurRealSkills** is an original Minecraft skills plugin by **surrealsmpofficial** for Paper and Purpur. It is inspired by the general idea of RPG skill progression, but does not use AuraSkills code or branding.

## Skills

- **Mining** — gain XP from ores and stone-type blocks.
- **Foraging** — gain XP from logs and wood blocks. Every 20 naturally broken Oak Logs levels up Foraging and awards 5 Minecraft XP levels by default.
- **Farming** — gain XP by breaking mature crops.
- **Fishing** — gain XP when catching fish.
- **Excavation** — gain XP from dirt, sand, gravel, clay and related blocks.
- **Enchanting** — gain XP when enchanting an item.

Players have a separate total XP and level for each skill. XP is saved in `plugins/SurRealSkills/data.yml`. When players progress a skill by breaking blocks, a configurable boss bar and hotbar/action-bar indicator show their progress. The boss bar hides after a short period without further progress. Oak Log progress is saved between restarts.

## Commands

| Command | Permission | Description |
|---|---|---|
| `/skills` | `surrealskills.use` | Open the skills inventory |
| `/skills stats` | `surrealskills.use` | Show your skill levels and XP |
| `/skills stats <online-player>` | `surrealskills.use` | Show an online player's stats |
| `/skills top <skill>` | `surrealskills.use` | Show the top 10 for a skill |
| `/skills help` | none | Show commands |
| `/skills reload` | `surrealskills.admin` | Reload `config.yml` |

`surrealskills.use` defaults to true. `surrealskills.admin` defaults to operators.

## Requirements and compatibility

- Paper or Purpur
- Java 21 or newer to compile this source
- Maven 3.9 or newer

This source compiles against Paper API 1.21.1 and targets Java 21 bytecode. Newer Java runtimes can run Java 21 bytecode, but the Minecraft 26.x server API/runtime has changed over time. Compatibility with every version from 1.21 through 26.3 is **not guaranteed without testing each target server**. Use a Java runtime supported by the particular server version (Minecraft 26.x may require a newer runtime than 1.21.x).

## Build locally

1. Install a JDK 21 and Maven 3.9+.
2. Open a terminal in this repository's root folder.
3. Run:

   ```bash
   mvn clean package
   ```

4. The plugin JAR will be created at:

   ```text
   target/SurRealSkills-1.0.0.jar
   ```

5. Copy that JAR into your server's `plugins` folder and restart the server.
6. Run `/skills` in-game.

## GitHub setup

1. Create a new repository named `SurRealSkills` on GitHub.
2. Upload all files from this project, including the `.github` folder.
3. Commit the files to the default branch.
4. The included GitHub Actions workflow automatically builds the plugin on each push and pull request.
5. In GitHub, open **Actions**, select the latest successful **Build SurRealSkills** run, and download the `SurRealSkills` artifact.

## Configuration

Edit `plugins/SurRealSkills/config.yml` to change XP rewards and level scaling. Then run `/skills reload`.

- `progression.base-xp`: XP required to go from level 1 to level 2.
- `progression.xp-per-level-increase`: extra XP required at each subsequent level.
- `progression.max-total-xp-per-skill`: safety cap for each skill.
- `xp.*`: XP rewarded by each activity.

## Safety notes

- XP is never negative and is capped per skill.
- Creative and Spectator players do not gain skill XP.
- Blocks placed by players are marked and do not award block-breaking XP, including across a normal restart because placed-block keys are saved in `data.yml`.
- Fishing XP is only awarded for the successful `CAUGHT_FISH` event state.
- Enchanting XP is capped per enchant action.
- Progress data is written on the server thread every five minutes and on shutdown.

## Important limitation

The placed-block prevention list is stored in YAML. On a large server with lots of block placement, `data.yml` can grow. For a high-population production server, consider replacing the placed-block tracking with a database or a more targeted anti-farming system.
