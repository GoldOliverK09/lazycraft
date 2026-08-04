package klumpler.lazycraft.client;

import klumpler.lazycraft.client.planner.RecipePlanner;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public class LazyCraftClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			dispatcher.register(literal("lazycraft").executes(context -> {
				var player = Minecraft.getInstance().player;
				if (player == null) {
					context.getSource().sendFeedback(Component.literal("LazyCraft needs an active player to create a plan."));
					return 0;
				}

				ItemStack selectedStack = player.getMainHandItem();
				if (selectedStack.isEmpty()) {
					context.getSource().sendFeedback(Component.literal("Hold an item in your selected hotbar slot first."));
					return 0;
				}

				var plan = RecipePlanner.plan(selectedStack.getItem());
				if (plan.isPresent()) {
					var selectedPlan = plan.get();
					RecipePlanner.logPlan(selectedPlan);
					context.getSource().sendFeedback(Component.literal(
							"LazyCraft plan written to the log (" + selectedPlan.steps().size() + " steps)."
					));
					return 1;
				}

				context.getSource().sendFeedback(Component.literal(
						"LazyCraft could not find a crafting-table plan for the selected item."
				));
				return 0;
			}))
		);
	}
}
