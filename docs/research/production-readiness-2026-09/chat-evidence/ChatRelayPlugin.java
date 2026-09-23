package chatrelay;

import io.github.bearisbug.synaxis.event.player.PlayerChatEvent;
import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.PluginContext;
import io.github.bearisbug.synaxis.plugin.PluginDescriptor;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;

/** Relays a chat message to every player in the sender's instance, the sender included. */
public final class ChatRelayPlugin implements SynaxisPlugin {
    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("chatrelay", "0.0.1", PluginApi.API_VERSION);
    }

    @Override
    public void onEnable(PluginContext context) {
        context.events().addListener(PlayerChatEvent.class,
                event -> event.getRecipients().addAll(event.getInstance().getPlayers()));
    }

    @Override
    public void onDisable() {
    }
}
