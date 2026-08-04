package klumpler.lazycraft.client.planner;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

public class InventorySnapshot {
    private final Map<Item, Integer> items;
    private final List<ItemStack> stacks;

    public String display() {
        return items.toString();
    }

    public static InventorySnapshot from(Player player) {
        Map<Item, Integer> items = new HashMap<>();
        List<ItemStack> stacks = new ArrayList<>();
        if (player == null) {
            throw new IllegalArgumentException("Player cannot be null");
        }

        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);

            if (!stack.isEmpty()) {
                items.merge(stack.getItem(), stack.getCount(), Integer::sum);
                stacks.add(stack.copy());
            }
        }
        return new InventorySnapshot(items, stacks);
    }

    public static Optional<InventorySnapshot> fromCurrentPlayer() {
        Player player = Minecraft.getInstance().player;
        return player == null ? Optional.empty() : Optional.of(from(player));
    }

    private InventorySnapshot(Map<Item, Integer> items, List<ItemStack> stacks) {
        this.items = new HashMap<>(items);
        this.stacks = new ArrayList<>(stacks);
    }

    public InventorySnapshot copy() {
        return new InventorySnapshot(
                items,
                stacks.stream().map(ItemStack::copy).toList()
        );
    }

    public boolean canSatisfy(Ingredient ingredient) {
        return canSatisfy(ingredient, 1);
    }

    public boolean canSatisfy(Ingredient ingredient, int quantity) {
        validateQuantity(quantity);
        return available(ingredient) >= quantity;
    }

    public int available(Ingredient ingredient) {
        Objects.requireNonNull(ingredient, "ingredient cannot be null");
        return stacks.stream()
                .filter(ingredient)
                .mapToInt(ItemStack::getCount)
                .sum();
    }

    public int getAmount(Item item) {
        return items.getOrDefault(item, 0);
    }

    public boolean has(Item item, int amount) {
        validateQuantity(amount);
        return getAmount(item) >= amount;
    }

    public void add(Item item, int amount) {
        validateQuantity(amount);
        add(new ItemStack(item, amount));
    }

    public void add(ItemStack stack) {
        Objects.requireNonNull(stack, "stack cannot be null");
        if (stack.isEmpty()) {
            return;
        }

        ItemStack copy = stack.copy();
        stacks.add(copy);
        items.merge(copy.getItem(), copy.getCount(), Integer::sum);
    }

    public boolean remove(Item item, int amount) {
        Objects.requireNonNull(item, "item cannot be null");
        return consumeMatching(stack -> stack.is(item), amount);
    }

    public boolean consume(Ingredient ingredient, int quantity) {
        Objects.requireNonNull(ingredient, "ingredient cannot be null");
        return consumeMatching(ingredient, quantity);
    }

    private boolean consumeMatching(Predicate<ItemStack> matcher, int quantity) {
        validateQuantity(quantity);
        if (quantity == 0) {
            return true;
        }

        if (stacks.stream().filter(matcher).mapToInt(ItemStack::getCount).sum() < quantity) {
            return false;
        }

        int remaining = quantity;
        Iterator<ItemStack> iterator = stacks.iterator();
        while (iterator.hasNext() && remaining > 0) {
            ItemStack stack = iterator.next();
            if (!matcher.test(stack)) {
                continue;
            }

            int consumed = Math.min(stack.getCount(), remaining);
            stack.shrink(consumed);
            removeFromItemTotal(stack.getItem(), consumed);
            remaining -= consumed;

            if (stack.isEmpty()) {
                iterator.remove();
            }
        }

        return true;
    }

    private void removeFromItemTotal(Item item, int amount) {
        int current = getAmount(item);
        if (current == amount) {
            items.remove(item);
        } else {
            items.put(item, current - amount);
        }
    }

    private static void validateQuantity(int quantity) {
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity cannot be negative");
        }
    }
}
