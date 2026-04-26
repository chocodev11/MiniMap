# MiniMap

MiniMap is a Paper `1.21+` proof-of-concept minimap HUD plugin for Java 21 servers. It builds a resource pack at runtime, serves the generated zip over HTTP, and displays a glyph-based HUD that updates from player position and yaw.

It currently targets experimentation rather than a finished production plugin. The default generated assets are placeholders meant to be replaced or extended.

## Features

- Runtime resource-pack build pipeline
- Built-in HTTP server for pack delivery
- Automatic pack send on join
- Player HUD toggle with `/minimap hud`
- Two HUD pipelines:
  - `legacy`: Bukkit boss bar based
  - `modern`: PacketEvents-backed packet pipeline

## Requirements

- Java 21
- Paper `1.21+`
- A client-reachable HTTP URL for the generated pack

## Build

```bash
mvn package
```

Use `target/MiniMap-0.0.1-shaded.jar`.

## Setup

1. Put `target/MiniMap-0.0.1-shaded.jar` in your server `plugins/` folder.
2. Start the server once to generate `plugins/MiniMap/config.yml`.
3. Set `pack.host.publicBaseUrl` so players can download the pack from outside the server process.
4. Adjust `pack.host.port`, `pack.host.path`, and `hud.pipeline.mode` if needed.
5. Restart the server or run `/minimap reload`.

If `pack.host.publicBaseUrl` is left empty, MiniMap falls back to the server bind address. That is usually fine for local testing, but not for most public deployments.

## Commands

| Command | Description | Permission |
| --- | --- | --- |
| `/minimap reload` | Reload config, rebuild the pack, restart the HTTP server, and resend the pack. | `minimap.admin` |
| `/minimap pack rebuild` | Rebuild the generated resource pack. | `minimap.admin` |
| `/minimap pack send <player\|all>` | Resend the current pack to one player or everyone online. | `minimap.admin` |
| `/minimap hud <on\|off>` | Enable or disable the HUD for yourself. | `minimap.hud` |

## Important Config

See [src/main/resources/config.yml](src/main/resources/config.yml) for the full file.

- `pack.pipeline.*`: Controls where pack contents are read from and where the generated zip is written.
- `pack.host.*`: Controls the embedded HTTP server and the URL sent to players.
- `hud.pipeline.mode`: `legacy` or `modern`. `modern` requires PacketEvents to load correctly.
- `hud.updateIntervalTicks`: HUD refresh interval.
- `hud.map.*`: Center point, radius, side, pan inversion, yaw offset, and optional EMA yaw smoothing.
- `hud.glyphs.*`: Private-use glyphs used by the HUD font providers.

## Runtime Files

After first startup, MiniMap writes its working files under `plugins/MiniMap/`:

- `contents/`: source assets merged into the generated pack
- `output/generated.zip`: current pack delivered to players
- `output/output_uncompressed/`: unpacked export when enabled

