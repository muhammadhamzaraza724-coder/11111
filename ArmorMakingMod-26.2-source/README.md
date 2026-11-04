# Armor Making Mod — starter source, NOT a working JAR

Requested game version: **26.2**. This source is **not validated against 26.2**.

The included Java source is a proof-of-concept for a K-key ON/OFF toggle using older Yarn/Fabric API names. The `fabric.mod.json` Minecraft dependency is deliberately marked **1.21.2 as an example**, NOT 26.2. It must be replaced after confirming the official 26.2 Fabric mappings and APIs. Do not put this ZIP in your mods folder.

## Current status
- K key toggle logic: source provided; uncompiled and untested.
- Automatic armorer trading, librarian trades, enchantment combining, anvil use, enchanting table, XP farming, Ender Chest management: **NOT implemented**.
- Gradle build files / dependency versions: **not provided**, because current 26.2 compatible versions could not be verified offline.
- JAR: **not built**.

## Desired enchantment goals
- Helmet/chestplate/leggings: Protection IV, Unbreaking III, Mending.
- Boots: same plus Feather Falling IV.
- Sword: Sharpness V, Unbreaking III, Mending.
- Axe: Sharpness V, Efficiency V, Unbreaking III, Mending.
- Pickaxe: Efficiency V, Silk Touch, Unbreaking III, Mending.

## Next steps
1. Verify official Minecraft 26.2 Fabric loader, API, mappings, Java version, and build tooling.
2. Update source imports and mod metadata to match those APIs.
3. Build and test a K-toggle JAR.
4. Add one GUI interaction at a time, with manual confirmations, error messages, and SMP rules compliance.
5. Build and test a complete JAR on a test world before use on an SMP.
