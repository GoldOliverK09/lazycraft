package klumpler.lazycraft.client.planner;

import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.RecipeBookMenu;

import java.util.Optional;

public record CraftingGrid(int width, int height) {
    public static final CraftingGrid CRAFTING_TABLE = new CraftingGrid(3, 3);

    public static Optional<CraftingGrid> current() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return Optional.empty();
        }

        if (!(client.player.containerMenu instanceof CraftingMenu)
                && !(client.player.containerMenu instanceof InventoryMenu)) {
            return Optional.empty();
        }

        RecipeBookMenu<?, ?> menu = (RecipeBookMenu<?, ?>) client.player.containerMenu;
        return Optional.of(new CraftingGrid(menu.getGridWidth(), menu.getGridHeight()));
    }
}
