# OnlyMace — Paper 1.21.11

GitHub Actions builds the plugin automatically on every push to `main`.

## Download the compiled JAR
Open the repository's **Actions** tab, select the latest successful **Build OnlyMace** run, and download the `OnlyMace-1.0.0` artifact. Extract the artifact ZIP to get the plugin JAR.

## Local build (optional)
Requires JDK 21+ and Maven 3.9+:
```sh
mvn clean package
```
The JAR is created at `target/OnlyMace-1.0.0.jar`.

## Features
- Brewing is cancelled.
- Drinking a potion clears the drinker's active potion effects.
- Splash potions are cancelled and clear the thrower's effects.
- The first successful mace recipe click claims the single global mace and begins a 10-minute ritual.
- Duplicate mace crafting is blocked; Heavy Cores and Breeze Rods in the crafting grid are returned.
- Ritual uses a floating, rotating 3x ItemDisplay, particle aura, timer display and boss bar with coordinates.
- A mace hit after at least 3 blocks of fall distance gives the attacker a 30-second mace cooldown.

The global claim and ritual end time are saved in the plugin config so they survive server restarts.
