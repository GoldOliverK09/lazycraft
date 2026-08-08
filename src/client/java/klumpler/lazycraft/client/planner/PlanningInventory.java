package klumpler.lazycraft.client.planner;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.*;

/**
 * Detached item/count inventory used by the pure planning search.
 * Entries stay in player-slot order because alternative ingredients are consumed in that order.
 */
final class PlanningInventory {
    private final List<StackAmount> stacks;

    private PlanningInventory(List<StackAmount> stacks) {
        this.stacks = stacks;
    }

    static PlanningInventory from(Player player) {
        Objects.requireNonNull(player, "player cannot be null");
        int inventorySize = player.getInventory().getContainerSize();
        List<StackAmount> stacks = new ArrayList<>(inventorySize);
        for (int slot = 0; slot < inventorySize; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) {
                stacks.add(new StackAmount(stack.getItem(), stack.getCount()));
            }
        }
        return new PlanningInventory(stacks);
    }

    PlanningInventory copy() {
        List<StackAmount> copy = new ArrayList<>(stacks.size());
        for (StackAmount stack : stacks) {
            copy.add(stack.copy());
        }
        return new PlanningInventory(copy);
    }

    int availableItems(Collection<Item> acceptedItems) {
        Objects.requireNonNull(acceptedItems, "acceptedItems cannot be null");
        int available = 0;
        for (StackAmount stack : stacks) {
            if (acceptedItems.contains(stack.item)) {
                available += stack.count;
            }
        }
        return available;
    }

    int getAmount(Item item) {
        Objects.requireNonNull(item, "item cannot be null");
        int amount = 0;
        for (StackAmount stack : stacks) {
            if (stack.item == item) {
                amount += stack.count;
            }
        }
        return amount;
    }

    void add(Item item, int count) {
        Objects.requireNonNull(item, "item cannot be null");
        if (count <= 0) {
            return;
        }
        stacks.add(new StackAmount(item, count));
    }

    boolean consumeItems(Collection<Item> acceptedItems, int quantity) {
        Objects.requireNonNull(acceptedItems, "acceptedItems cannot be null");
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity cannot be negative");
        }
        if (quantity == 0) {
            return true;
        }
        if (availableItems(acceptedItems) < quantity) {
            return false;
        }

        int remaining = quantity;
        Iterator<StackAmount> iterator = stacks.iterator();
        while (iterator.hasNext() && remaining > 0) {
            StackAmount stack = iterator.next();
            if (!acceptedItems.contains(stack.item)) {
                continue;
            }

            int consumed = Math.min(stack.count, remaining);
            stack.count -= consumed;
            remaining -= consumed;

            if (stack.count == 0) {
                iterator.remove();
            }
        }
        return true;
    }

    private static final class StackAmount {
        private final Item item;
        private int count;

        private StackAmount(Item item, int count) {
            this.item = item;
            this.count = count;
        }

        private StackAmount copy() {
            return new StackAmount(item, count);
        }
    }
}
