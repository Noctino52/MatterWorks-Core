# 🏭 GDD: MatterWorks

**Title:** MatterWorks\
**Genre:** Grid-Based Factory Tycoon\
**Platform:** Hytale Server (Java Backend)\
**Target Audience:** 16-35 years old (Fans of Factorio, Satisfactory,
Mekanism).

## 1. High Concept & Lore

You are an employee of **MatterWorks Inc.**, a disgustingly neutral
interdimensional corporation (think Switzerland, but in space). You are
assigned a **Void Plot** (Void Dimension).

The world of Hytale is at war. Five great factions (Kweebec, Trork,
Scarak, Feran, Outlanders) fight for dominance. Your job is to extract
raw matter from the Void, refine it, and **sell it to the highest
bidder**.

### The Sales Philosophy

The **Nexus** is your only point of sale. When you sell a resource, you
are entrusting it to a specific faction (the currently active buyer or
one selected via contract).

-   **Necessity:** If you sell a faction what they *desire*, they pay a
    > premium.

-   **Strategic Denial:** If you sell a faction what they *hate* or
    > don\'t need, they buy it anyway (at a reduced price) based on the
    > logic of **\"Denying resources to the enemy\"**.

    -   *Example:* Trorks buy technology useful to Kweebecs just to burn
        > it so their enemies can\'t have it.

## 2. Game Objects: \"The Matter\"

We do not transport classic items, but **Pure Matter** defined by 3
properties. The final value is a multiplicative combination of these
three.

### A. SHAPE

The physical geometry.

-   📦 **CUBE** (Base)

-   🔮 **SPHERE** (Advanced - Requires smoothing)

-   🔺 **PYRAMID** (Elite - Requires cutting)

### B. COLOR

The visual property.

-   🌫️ **RAW** (Default gray/Debug texture - Minimum value)

-   🔴 **RED**, 🔵 **BLUE**, 🟡 **YELLOW** (Primary)

-   🟣 **PURPLE**, 🟠 **ORANGE**, 🟢 **GREEN** (Secondary)

-   ⚪ **WHITE** (Complex: requires mixing Secondary + Primary)

### C. EFFECTS (Buffs) - Advanced Logic

Particle/shader effects applied over the matter.

-   ✨ **SHINY - \[Early-Mid Game\]**

    -   *Description:* Sparkles visibly.

    -   *Market:* Loved by \"simple\" or greedy factions (Trork, Feran).

-   🔥 **BLAZING - \[Mid-Late Game\]**

    -   *Description:* Emits smoke and flames, unstable.

    -   *Synergy:* Doubles the value of RED/ORANGE. Destroys the value
        > of GREEN.

-   🌌 **GLITCH (Quantum) - \[Late-Post Game\]**

    -   *Description:* The matter frizzes and distorts reality.

    -   *Risk Mechanic (Gambling):* When the **Nexus** sells a Glitched
        > item, the system calculates a risk factor.

        -   The system has the authority to directly modify the
            > player\'s wallet (modifyMoney), bypassing the standard
            > sales transaction.

        -   **20% Jackpot:** The player\'s total balance is instantly
            > doubled.

        -   **80% Crash:** The total balance is halved.

        -   *Feedback:* A sound and visual effect on the UI communicate
            > the immediate outcome.

## 3. Machinery

Machines have variable dimensions (from **2x2** to **7x7**), making
layout \"tetris\" a fundamental part of the gameplay.

### Generation & Sales

There is an **Hard Limit**: You can only have **1 Drill** and **1
Nexus** per plot.

-   *Technical Note:* The system maintains a real-time count
    > (machineCounts). The check happens instantly (O(1)) the moment the
    > player attempts to place the block, preventing the action if the
    > limit is reached.

-   **Matter Bore (Drill)** 🔩

    -   *Size:* Large (e.g., 5x5).

    -   *Function:* Generates raw matter cyclically (Deadline Pattern).

-   **The Nexus (Seller)** 💠

    -   *Size:* Massive (e.g., 7x7).

    -   *Function:* Sells to the active buyer.

### Logistics (Belts)

-   **Conveyor Belt:** Moves items with fluid client-side interpolation.

-   **Splitter & Merger:** Logic flow management.

-   **Elevator:** The only vertical transport method (Teleports Y -\>
    > Y+n).

### Processors (Modifiers)

They have a placement cost and use internal buffers for crafting.

-   **Chromator, Mixer, Shaper.**

-   **Decorator:** Applies Buffs (Shiny, Blazing, Glitch) and interacts
    > with the player profile for advanced statistics.

> **Anti-Softlock System (Inventory Guard):** This is not a passive
> check. A dedicated service (\"Inventory Guard\") listens to every
> block placement event. If the player places the last available belt
> and has no funds to buy more, the system immediately intervenes by
> injecting emergency belts into the inventory and sending a \"Corporate
> Subsidy\" notification.

## 4. Progression & Tiering System

### Tech Unlock (Tech Nodes)

The technology tree is structured as a directed graph of **Nodes**. Each
Node (TechNode) possesses:

1.  \*\*Cost:\*\*Standard Currency.

2.  **Parents:** List of required nodes (Dependencies).

3.  **Unlocks:** List of unlocked machine IDs. *The system validates the
    > purchase by recursively checking for the presence of parent nodes
    > in the player\'s profile.*

### Vertical Tiers (Global)

Upgrades are **Global**: Unlocking \"Drill MK2\" instantly updates the
logic of all placed drills, changing production ratios and forcing a
factory redesign.

## 5. Limits & Optimization (Greedy Monetization)

There is an **Item Cap** (maximum number of items on belts) to prevent
lag and excessive spaghetti factories. If the cap is reached, the drill
enters a forced pause. The cap increases with Prestige or by paying.

## 6. Prestige (Rebirth)

When the player reaches a certain earnings threshold, they can perform
**Prestige**.

-   **Effect:** Calls the clearPlot() function which physically resets
    > the grid and resets technological progress.

-   **Gain:** Permanent multipliers, increased Item Cap, Premium
    > Currency.

## 7. Business Model

### Void Coins Usage:

1.  **Instant Prestige:** Prestige without resetting the plot.

2.  **Cap Breaker:** Massive increase in item/space limits.

3.  **Temporal Boosters (Active Management):**

    -   Effects like \"Overclock\" (+Speed) are objects (ActiveBooster)
        > with a precise expiration (Tick).

    -   Every Server Tick, the profile checks active boosters. If
        > currentTick \>= expirationTick, the booster is removed, and
        > multipliers are recalculated instantly.

## 8. Factions (The Clients)

  -----------------------------------------------------------------------
  **Faction**             **Preference (High      **Denial (Low Value)**
                          Value)**                
  ----------------------- ----------------------- -----------------------
  **Kweebec**             Spheres, Green.         Blazing, Glitch.

  **Trork**               Cubes, Red, Shiny.      Pyramids.

  **Scarak**              Blue/Purple, Glitch.    Raw.

  **Feran**               Pyramids, Orange,       Blue.
                          Shiny.                  

  **Outlanders**          Glitch, Purple/Cyan.    Shiny.
  -----------------------------------------------------------------------

## 9. Target

**Core Target:** 20-30 years old (Engineers/STEM Students). They love
optimization and numbers. **UI Design:** Dark Dashboard style, precise
numbers, no childish aesthetics.
