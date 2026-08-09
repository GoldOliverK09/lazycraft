package klumpler.lazycraft.client.planner;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;

/**
 * Detached item/count inventory used by the pure planning search.
 * Entries stay in player-slot order because alternative ingredients are consumed in that order.
 */
final class PlanningInventory {
    private Item[] items;
    private int[] counts;
    private boolean[] originalItems;
    private int size;

    private PlanningInventory(
            Item[] items,
            int[] counts,
            boolean[] originalItems,
            int size
    ) {
        this.items = items;
        this.counts = counts;
        this.originalItems = originalItems;
        this.size = size;
    }

    static PlanningInventory from(Player player) {
        Objects.requireNonNull(player, "player cannot be null");
        int inventorySize = player.getInventory().getContainerSize();
        Item[] items = new Item[inventorySize];
        int[] counts = new int[inventorySize];
        boolean[] originalItems = new boolean[inventorySize];
        int size = 0;

        for (int slot = 0; slot < inventorySize; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) {
                items[size] = stack.getItem();
                counts[size] = stack.getCount();
                originalItems[size] = true;
                size++;
            }
        }

        return new PlanningInventory(items, counts, originalItems, size);
    }

    PlanningInventory copy() {
        return new PlanningInventory(
                Arrays.copyOf(items, size),
                Arrays.copyOf(counts, size),
                Arrays.copyOf(originalItems, size),
                size
        );
    }

    int availableItems(Collection<Item> acceptedItems) {
        Objects.requireNonNull(acceptedItems, "acceptedItems cannot be null");
        int available = 0;
        for (int index = 0; index < size; index++) {
            if (acceptedItems.contains(items[index])) {
                available += counts[index];
            }
        }
        return available;
    }

    void add(Item item, int count) {
        Objects.requireNonNull(item, "item cannot be null");
        if (count <= 0) {
            return;
        }

        ensureCapacity(size + 1);
        items[size] = item;
        counts[size] = count;
        originalItems[size] = false;
        size++;
    }

    /**
     * Consumes in stable entry order. A failed attempt may have consumed part of this
     * inventory, so callers must use a disposable branch copy when success is uncertain.
     */
    Consumption consumeItems(Collection<Item> acceptedItems, int quantity) {
        Objects.requireNonNull(acceptedItems, "acceptedItems cannot be null");
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity cannot be negative");
        }
        if (quantity == 0) {
            return Consumption.SUCCESS_WITHOUT_ITEMS;
        }

        int remaining = quantity;
        int originalItemsConsumed = 0;
        int writeIndex = 0;

        for (int readIndex = 0; readIndex < size; readIndex++) {
            int count = counts[readIndex];
            if (remaining > 0 && acceptedItems.contains(items[readIndex])) {
                int consumed = Math.min(count, remaining);
                count -= consumed;
                remaining -= consumed;
                if (originalItems[readIndex]) {
                    originalItemsConsumed = Math.addExact(originalItemsConsumed, consumed);
                }
            }

            if (count > 0) {
                items[writeIndex] = items[readIndex];
                counts[writeIndex] = count;
                originalItems[writeIndex] = originalItems[readIndex];
                writeIndex++;
            }
        }

        Arrays.fill(items, writeIndex, size, null);
        size = writeIndex;
        return new Consumption(remaining == 0, originalItemsConsumed);
    }

    private void ensureCapacity(int requiredCapacity) {
        if (requiredCapacity <= items.length) {
            return;
        }

        int expandedCapacity = Math.max(requiredCapacity, items.length + (items.length >> 1) + 1);
        items = Arrays.copyOf(items, expandedCapacity);
        counts = Arrays.copyOf(counts, expandedCapacity);
        originalItems = Arrays.copyOf(originalItems, expandedCapacity);
    }

    record Consumption(boolean successful, int originalItemsConsumed) {
        private static final Consumption SUCCESS_WITHOUT_ITEMS = new Consumption(true, 0);
    }
}
