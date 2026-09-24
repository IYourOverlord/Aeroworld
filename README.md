# AeroWorld

A complete world generation overhaul for Minecraft 1.21.1 (NeoForge).

AeroWorld replaces the traditional Overworld with a massive vertical world composed of multiple distinct layers separated by vast empty spaces. Exploration is no longer about traveling thousands of blocks horizontally — it is about conquering altitude.

Designed for airships, long-distance aerial travel, Distant Horizons, and large-scale exploration.

---

## Features

### Four Unique World Layers

#### Layer 1 — Surface World

**Y: -64 → 300**

The foundation of the world.

* Fully custom terrain generator
* Continents, oceans, mountain ranges and river valleys
* Sea level at Y=0
* Massive underground cave system beneath most landmasses
* Custom biome mapping
* Ancient Cities and vanilla structures adapted to AeroWorld

---

#### Layer 2 — Floating Archipelagos

**Y: 400 → 500**

The first sky layer.

* Floating cone-shaped islands
* Procedural archipelagos
* Hanging stalactites
* Bridges between neighboring islands
* Ring-distributed trees
* Vaults and Trial Chambers

Designed as the player's first major aerial destination.

---

#### Layer 3 — Celestial Bodies

**Y: 1000 → 1100**

A high-altitude layer containing two types of structures:

* Hollow meteorites with impact craters
* Solid planets surrounded by asteroid rings

Every generated planet contains a centrally positioned End City.

---

#### Layer 4 — Jellyfish Islands

**Y: 1900 → 2031**

The highest layer of AeroWorld.

* Massive floating jellyfish-shaped structures
* Large dome-shaped bodies
* Procedural tentacles extending hundreds of blocks downward
* Endgame exploration zone

---

## Custom Chunk Generator

AeroWorld uses its own chunk generator rather than vanilla terrain generation.

The project extends Minecraft's `NoiseBasedChunkGenerator` only for compatibility with structures, codecs and ecosystem integrations. Terrain generation itself is fully custom.

Major systems include:

* Custom terrain pipeline
* Analytical column model
* Custom biome resolution
* Structure validation system
* Island generation framework
* Multi-layer world architecture

---

## Distant Horizons Integration

AeroWorld contains a dedicated Distant Horizons implementation.

Instead of generating chunks and converting them into LOD data, AeroWorld can generate LOD information directly from world seed data using an analytical model.

Benefits:

* Faster distant terrain generation
* Reduced chunk generation overhead
* Improved scalability for extremely large worlds
* Specialized optimizations for Distant Horizons 3.3.x

The integration is optional and automatically disables itself when Distant Horizons is not installed.

---

## Technical Details

| Property          | Value                   |
| ----------------- | ----------------------- |
| Minecraft Version | 1.21.1                  |
| Loader            | NeoForge 21.1.228       |
| Java Version      | 21                      |
| Mod Version       | 1.0.12                  |
| Package Root      | `org.example.aeroworld` |
| World Height      | 2096 blocks             |
| Minimum Y         | -64                     |
| Sea Level         | 0                       |

---

## World Height Layout

```text
2031 ───────────── Layer 4 (Jellyfish Islands)
1900 ─────────────

1100 ───────────── Layer 3 (Planets & Meteorites)
1000 ─────────────

 500 ───────────── Layer 2 (Floating Archipelagos)
 400 ─────────────

 300 ───────────── Surface Ceiling
   0 ───────────── Sea Level
 -64 ───────────── World Bottom
```

---

## Commands

### Find Layer 2 Islands

```mcfunction
/aeroworld findIsland2
```

### Find Layer 3 Bodies

```mcfunction
/aeroworld findIsland3
```

### Find Layer 4 Islands

```mcfunction
/aeroworld findIsland4
```

### Validate Analytical Generation

```mcfunction
/aeroworld validateSeedGen
```

### Force Pending Structure Placement

```mcfunction
/aeroworld forcePlacePending
```

---

## Compatibility

### Supported

* NeoForge 1.21.1
* Distant Horizons 3.3.x
* Physical Structures

### Optional

* Distant Horizons

---

## Architecture Highlights

* Analytical world model (`AeroColumnModel`)
* Multi-layer procedural generation
* Custom biome source
* Custom structure validation
* Thread-safe island caches
* Seed-driven LOD generation
* Distant Horizons SeedGen override
* High-performance chunk writing pipeline

---

## Development Status

AeroWorld is actively developed and continues to evolve as a large-scale vertical exploration project.

Current goals include:

* Further expansion of sky layers
* Additional island variants
* Improved structure distribution
* Enhanced Distant Horizons integration
* More exploration content

---

## License

See repository license for details.
