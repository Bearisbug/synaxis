package probe.a;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.plugin.Admission;
import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.PluginContext;
import io.github.bearisbug.synaxis.plugin.PluginDescriptor;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;
import io.github.bearisbug.synaxis.timer.TaskSchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The probe plugin "a" of docs/TEST.md TC-GAME-053 to 055; -Dprobe.a.mode=throw for TC-GAME-054. */
public final class ProbePlugin implements SynaxisPlugin {

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor("a", "1.0.0", PluginApi.API_VERSION);
    private final boolean throwing = "throw".equals(System.getProperty("probe.a.mode"));
    private Logger logger = LoggerFactory.getLogger(ProbePlugin.class);

    @Override
    public PluginDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void onEnable(PluginContext context) {
        logger = context.logger();
        logger.info("probe-a onEnable plugin_id=a data_directory={} mode={}", context.dataDirectory(),
                throwing ? "throw" : "print");
        context.events().addListener(AsyncPlayerConfigurationEvent.class, event -> {
            if (throwing) throw new RuntimeException("probe-a listener throws for " + event.getPlayer().getUsername());
            logger.info("probe-a listener plugin_id=a player joined: {}", event.getPlayer().getUsername());
        });
        context.scheduler().scheduleTask(() -> {
            if (throwing) throw new RuntimeException("probe-a task throws");
            logger.info("probe-a task plugin_id=a ran");
        }, TaskSchedule.tick(100), TaskSchedule.tick(100));
        context.channels().register("probe", (Player player, byte[] payload) -> {
            if (throwing) throw new RuntimeException("probe-a channel throws, " + payload.length + " bytes");
            logger.info("probe-a channel plugin_id=a {} bytes from {}", payload.length, player.getUsername());
        });
        if (!throwing) {
            try {
                context.admission().register(request -> new Admission.Reject("synaxis:match-unavailable"));
                logger.info("probe-a admission provider registered plugin_id=a");
            } catch (IllegalStateException refused) {
                logger.warn("probe-a admission provider not registered plugin_id=a: {}", refused.getMessage());
            }
        }
    }

    @Override
    public void onDisable() {
        logger.info("probe-a onDisable plugin_id=a");
    }
}
