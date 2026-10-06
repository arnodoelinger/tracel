# 1.0.0 Alpha

## Highlights

- Lookup / inspect system
- Item provenance ledger
- Crash-safe and exact rollback system
- Rollback preview and undo
- Compact custom storage (~200 B per event)
- `CoreProtect` migrator
- Export / import
- Auto-purge system
- Status and health monitoring
- First-run setup wizard
- Context-aware command suggestions
- Flags and presets for lookups and rollbacks
- `Paper` and `Folia` support (26.1–26.3)
- `WorldEdit` and `FAWE` integration
- English and Russian language support

## Server

### Features

- Log blocks, items and containers, entities, projectiles, chat, commands, sessions and deaths
- Track every item through splits, crafts and containers with a provenance ledger
- Lookup with paged, folded results, hover details, click-to-teleport and paste export
- `/tracel inspect` to read the history of any block or container with a click
- `/tracel near` and `/tracel player` for quick checks and one-screen player reports
- Filter by player, time, radius, world, action, block and item, with date and range support
- Rollback to the state at the start of the time window, following items to where they are now
- Rollback preview with blinking blocks, shown only to you
- Undo for every rollback, per player
- Rollbacks are journaled, resume after a crash and never duplicate items
- Rollback safety limits for radius and entity restores
- Named flag presets, per player or for the whole server
- `/tracel status` health screen with a disk guard for critically low space
- Export and import of the whole history as a compressed snapshot
- Purge by category, age, world or player with a preview, plus configurable auto-purge
- `CoreProtect` history migration, read-only
- `WorldEdit` and `FAWE` edits are logged
- First-run wizard for retention, safety limits and logging
- Tab completion with tooltips that hides flags that do not fit together
- English and Russian messages, overridable per key
- `config.toml` with automatic migration and safe fallbacks for bad values
- And more...