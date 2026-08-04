package klumpler.lazycraft.client.planner;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.*;
import java.util.function.Predicate;

public final class InventorySnapshot {
    private final List<ItemStack> stacks;

    private InventorySnapshot(List<ItemStack> stacks) {
        this.stacks = new ArrayList<>(stacks);
    }

    public static Optional<InventorySnapshot> fromCurrentPlayer() {
        Player player = Minecraft.getInstance().player;
        return player == null ? Optional.empty() : Optional.of(from(player));
    }

    public static InventorySnapshot from(Player player) {
        List<ItemStack> stacks = new ArrayList<>();
        if (player == null) {
            throw new IllegalArgumentException("Player cannot be null");
        }

        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);

            if (!stack.isEmpty()) {
                stacks.add(stack.copy());
            }
        }
        return new InventorySnapshot(stacks);
    }

    public InventorySnapshot copy() {
        return new InventorySnapshot(stacks.stream().map(ItemStack::copy).toList());
    }

    public int availableItems(Collection<Item> acceptedItems) {
        Objects.requireNonNull(acceptedItems, "acceptedItems cannot be null");
        return stacks.stream()
                .filter(stack -> acceptedItems.contains(stack.getItem()))
                .mapToInt(ItemStack::getCount)
                .sum();
    }

    public int getAmount(Item item) {
        Objects.requireNonNull(item, "item cannot be null");
        return stacks.stream()
                .filter(stack -> stack.is(item))
                .mapToInt(ItemStack::getCount)
                .sum();
    }

    public boolean has(Item item, int amount) {
        validateQuantity(amount);
        return getAmount(item) >= amount;
    }

    public void add(ItemStack stack) {
        Objects.requireNonNull(stack, "stack cannot be null");
        if (stack.isEmpty()) {
            return;
        }

        ItemStack copy = stack.copy();
        stacks.add(copy);
    }

    public boolean consumeItems(Collection<Item> acceptedItems, int quantity) {
        Objects.requireNonNull(acceptedItems, "acceptedItems cannot be null");
        return consumeMatching(stack -> acceptedItems.contains(stack.getItem()), quantity);
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
            remaining -= consumed;

            if (stack.isEmpty()) {
                iterator.remove();
            }
        }

        return true;
    }
    private static void validateQuantity(int quantity) {
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity cannot be negative");
        }
    }
}
