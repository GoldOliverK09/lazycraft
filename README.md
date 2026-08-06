# LazyCraft

LazyCraft is a collection of crafting quality-of-life features designed to make Minecraft’s recipe book faster and more
intuitive to use.

## Recursive Crafting

Double-click a recipe that vanilla considers uncraftable, and LazyCraft will attempt to craft all of its required
components in order before placing the final item in your inventory.

A single click still behaves similarly to vanilla and places the selected recipe into the crafting grid. When a recipe
is recursively craftable, its output can also be taken from the result slot as though all the required ingredients were
already available.

For example, crafting a dispenser can automatically craft the required sticks and bow first, provided you have all the
necessary base ingredients.

## Inventory Space

Some free inventory space is recommended.

LazyCraft temporarily places intermediate items into your inventory so they can be used by the following crafting steps.
If your inventory is completely full, Minecraft may briefly display or drop ghost items during the process. These do not
replace or consume any additional ingredients, but crafting is more reliable when a few slots are available.

## Features

- Recursive crafting through the recipe book
- Direct crafting from recipe-book entries
- Crafting-table and player-inventory crafting
- Support for modded crafting recipes
- Multiplayer server support

Because LazyCraft performs several normal crafting actions in quick succession, some servers with strict anti-cheat
systems may interfere with recursive crafting.

## Planned Features

- Dynamic support for additional crafting stations
- Recipe-book highlighting for recursively craftable recipes
- Further crafting quality-of-life options

## Development Status

LazyCraft is currently in early development. Features, behavior, configuration options, and compatibility may change
between releases.

Bug reports and feedback are welcome.

## License

LazyCraft is licensed under the MIT License.