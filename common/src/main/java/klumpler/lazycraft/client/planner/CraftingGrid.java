package klumpler.lazycraft.client.planner;

import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractCraftingMenu;

import java.util.Optional;

public record CraftingGrid(int width, int height) {
    public static final CraftingGrid CRAFTING_TABLE = new CraftingGrid(3, 3);

    public static Optional<CraftingGrid> current() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !(client.player.containerMenu instanceof AbstractCraftingMenu menu)) {
            return Optional.empty();
        }
        return Optional.of(new CraftingGrid(menu.getGridWidth(), menu.getGridHeight()));
    }
}
