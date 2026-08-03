package klumpler.recursivecraft.client.planner;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

public record ItemAmount(Item item, int amount) {
    public ItemAmount {
        if (item == null) {
            throw new NullPointerException("item cannot be null");
        }

        if (amount < 0) {
            throw new IllegalArgumentException("amount cannot be negative");
        }
    }

    public String display() {
        return BuiltInRegistries.ITEM.getKey(item).getPath() + " x" + amount;
    }

    public boolean is(Item other) {
        return item == other;
    }
}
