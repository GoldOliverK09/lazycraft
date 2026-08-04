package klumpler.lazycraft.client.planner;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public final class RecipePlanner {
    private static final Logger LOGGER = LoggerFactory.getLogger("lazycraft");
    private RecipePlanner() {
    }

    public static boolean log() {
        Player player = Minecraft.getInstance().player;

        if (player == null) {
            LOGGER.info("Cannot log an inventory without an active player.");
            return false;
        }

        LOGGER.info("Player: {}", player);
        LOGGER.info("Inventory size: {}", player.getInventory().getContainerSize());

        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);

            if (!stack.isEmpty()) {
                LOGGER.info("Slot {}: {}", slot, stack);
            }
        }

        InventorySnapshot inventory = InventorySnapshot.from(player);
        LOGGER.info(inventory.display());
        return true;
    }
    public boolean plan(Item target) {
        InventorySnapshot inventory = InventorySnapshot.from(Minecraft.getInstance().player);
        List<RecipeTree.RecipeOption> recipes = RecipeTree.build(target, 1).recipes();
        for (RecipeTree.RecipeOption recipe : recipes) {
            for (RecipeTree.IngredientOption ingredientOption : recipe.ingredients()) {
                if (inventory.canSatisfy(ingredientOption.ingredient())) {
                    return true;    // Obviously doesn't work, this is just temporary
                }
            }
        }
        return false;
    }
}
