# ??? MatterWorks: Technical Architecture & Assumptions (v3.0)

**Project:** MatterWorks Core (Hytale Server)
**Stack:** Java 21, MariaDB (Docker), Gradle
**Architecture:** Headless Core, Server-Authoritative, Event-Driven
**Status:** Design Finalized (UML v32.0)

---

## 1. General Architecture: "Headless Core"

The system adopts a pure **Server-Authoritative** approach.
* **Java Core:** The "Brain". Maintains the `Shadow Grid` in RAM and manages all logic (Tick Loop, Economy, Crafting).
* **Hytale:** The visualizer ("Muscles"). Receives rendering commands via Adapter interfaces.
* **Database:** MariaDB is the single source of persistent truth.

---

## 2. Data Persistence (Advanced JSON Strategy)

### Single Table Polymorphism
Machines are saved in a monolithic table `mw_plot_objects` to allow fast chunk loading.
* **Schema:** `uuid | owner_id | x,y,z | type | metadata (JSON)`.
* **Metadata:** The JSON field contains specific data (e.g., buffer inventory, selected color, tier).

### The Machine Factory
Upon loading from DB, we avoid scattered switch-cases. A central **`MachineFactory`**:
1.  Reads the `type` field from the JSON.
2.  Instantiates the correct class (e.g., `DrillMachine`).
3.  Invokes `deserializeSpecific(json)` on the new instance to populate unique fields.

### Autosave System
A **`GridSaverService`** runs a periodic task (e.g., every 5 minutes).
* Iterates over all active plots in RAM.
* Serializes into JSON only the machines marked as `isDirty` (modified).
* Executes a batch update on MariaDB.

---

## 3. Grid Management & Hytale Integration

### Shadow Grid & Bounding Box
The Core maintains a `Map<GridPosition, IGridComponent>` representing the logical world.
Each machine has a `Vector3Int dimensions` defined in Java code to handle collisions for structures > 1x1 (e.g., Nexus 7x7).

### The Lazy Registry (Hybrid Integration)
To manage native Hytale decorative blocks without hardcoding dimensions in the Core:
* We use a **`BlockRegistry`**.
* When a native block is placed, the Core checks its cache.
* If dimensions are unknown, it queries the Hytale API (`fetchExternalBlockDimensions`) **once** and caches the result for future use.

---

## 4. Visuals & Networking (Dirty Flags)

To minimize network traffic in a 20 TPS environment:
* Each `PlacedMachine` has an `isDirty` flag.
* The `tick()` method updates the logical state (e.g., crafting progress).
* Only if the visible state changes (e.g., animation start/stop), the flag is raised, and a packet is sent to the client via `syncVisuals()`.

**Conveyor Movement:**
The server manages "snapped" logic (Teleport item A -> B), but sends a `playAnimation` command to the client at the start of the movement, delegating fluid visual interpolation to Hytale.

---

## 5. Gameplay Implementation Details

### Event-Driven System
Instead of heavy cyclic checks, we use a Listener system (`IGameEventListener`).
* **Inventory Guard:** Listens to `onBlockPlaced`. If the player places the last belt, it checks funds and restocks if necessary.
* **Plot Cleaning:** Listens to `onBlockBroken` to refund items and clean the Shadow Grid.

### Vertical Logistics
No ramps. We use **Elevators** (1x1 Blocks) that logically teleport matter between different Y levels, drastically simplifying pathfinding and collision management.

---

## 6. Player Lifecycle (Session Service)

The **`PlayerSessionService`** orchestrates player entry:
1.  **Login (`onPlayerJoin`):** Intercepts the event.
2.  **Provisioning:** If the player has no DB profile, creates one and calls `PlotManager`.
3.  **Plot Assignment:** The `PlotManager` calculates the coordinates of the next free "hole" in the void (e.g., Plot N at `N*1000, 0`) and assigns it.
4.  **Teleport:** Orders Hytale to teleport the player to the center of their new plot.
5.  **Logout (`onPlayerQuit`):** Forces immediate save of profile and plot, then unloads data from RAM.