package klumpler.lazycraft.client;

import klumpler.lazycraft.client.config.LazyCraftConfig;
import klumpler.lazycraft.client.planner.RecipePlanner;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigManager;
import me.shedaniel.autoconfig.gui.ConfigScreenProvider;
import me.shedaniel.autoconfig.gui.DefaultGuiProviders;
import me.shedaniel.autoconfig.gui.DefaultGuiTransformers;
import me.shedaniel.autoconfig.gui.registry.GuiRegistry;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public class LazyCraftClient implements ClientModInitializer {
	@SuppressWarnings("unchecked")
	private static ConfigScreenProvider<LazyCraftConfig> configScreenProvider() {
		GuiRegistry registry = new GuiRegistry();
		DefaultGuiProviders.apply(registry);
		DefaultGuiTransformers.apply(registry);

		ConfigManager<LazyCraftConfig> manager = (ConfigManager<LazyCraftConfig>)
				AutoConfig.getConfigHolder(LazyCraftConfig.class);
		return new ConfigScreenProvider<>(manager, registry, null);
	}

	private static net.minecraft.client.gui.screens.Screen createConfigScreen() {
		return configScreenProvider().get();
	}

	@Override
	public void onInitializeClient() {
		AutoConfig.register(LazyCraftConfig.class, GsonConfigSerializer::new);

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

				long start = System.nanoTime();
				var plan = RecipePlanner.plan(selectedStack.getItem());
				if (plan.isPresent()) {
					var selectedPlan = plan.get();
					RecipePlanner.logPlan(selectedPlan, start);
					context.getSource().sendFeedback(Component.literal(
							"LazyCraft plan written to the log (" + selectedPlan.steps().size() + " steps)."
					));
					return 1;
				}

				context.getSource().sendFeedback(Component.literal(
						"LazyCraft could not find a crafting-table plan for the selected item."
				));
				context.getSource().sendFeedback(Component.literal(
						"Took " + ((System.nanoTime() - start) / 1_000_000.0) + " ms"
				));
				return 0;
			}).then(literal("config").executes(context -> {
				Minecraft minecraft = Minecraft.getInstance();
				minecraft.setScreenAndShow(createConfigScreen());
				return 1;
			})))
		);
	}
}
