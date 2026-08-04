package klumpler.lazycraft.client;

import klumpler.lazycraft.client.planner.RecipePlanner;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.network.chat.Component;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public class LazyCraftClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			dispatcher.register(literal("lazycraft").executes(context -> {
				if (RecipePlanner.log()) {
					context.getSource().sendFeedback(Component.literal("LazyCraft inventory snapshot written to the log."));
				} else {
					context.getSource().sendFeedback(Component.literal("LazyCraft needs an active player to read an inventory."));
				}

				return 1;
			}))
		);
	}
}
