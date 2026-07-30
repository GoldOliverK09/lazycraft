package klumpler.recursivecraft.planner;

import net.minecraft.core.Holder;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.ItemStackTemplate;

import java.util.List;
import java.util.Optional;

public class RecursivePlanner {

    public static Item canCraft(final RecipeDisplayEntry recipeEntry, final StackedItemContents inventory) {
        Optional<List<Ingredient>> ingredients = recipeEntry.craftingRequirements();
        SlotDisplay result = recipeEntry.display().result();
        return null;
    }

    private static Item getItem(final SlotDisplay item) {
        if (item instanceof SlotDisplay.ItemStackSlotDisplay(ItemStackTemplate stack)) {
            return stack.item().value();
        } else if (item instanceof SlotDisplay.ItemSlotDisplay(Holder<Item> itemHolder)) {
            return itemHolder.value();
        }
        return null;
    }
}
