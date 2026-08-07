package klumpler.lazycraft.client.planner;

import net.minecraft.client.Minecraft;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;

import java.util.Optional;

/**
 * The crafting grid currently available to the player.
 */
public record CraftingGrid(int width, int height) {
    public static final CraftingGrid CRAFTING_TABLE = new CraftingGrid(3, 3);

    public static Optional<CraftingGrid> current() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !(client.player.containerMenu instanceof AbstractCraftingMenu menu)) {
            return Optional.empty();
        }
        return Optional.of(new CraftingGrid(menu.getGridWidth(), menu.getGridHeight()));
    }

    public boolean supports(RecipeDisplayEntry recipe, ContextMap context) {
        if (width >= 3 && height >= 3) {
            return recipe.display().craftingStation().resolveForStacks(context).stream()
                    .anyMatch(stack -> stack.is(Items.CRAFTING_TABLE));
        }

        var display = recipe.display();
        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            return shaped.width() <= width && shaped.height() <= height;
        }
        if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
            return shapeless.ingredients().size() <= width * height;
        }
        return false;
    }
}
