package dev.gameengine.instance;

import dev.gameengine.event.instance.InstanceRegisterEvent;
import dev.gameengine.event.instance.InstanceUnregisterEvent;
import dev.gameengine.testing.Env;
import dev.gameengine.testing.EnvTest;
import org.junit.jupiter.api.Test;

@EnvTest
public class InstanceEventsIntegrationTest {
    @Test
    public void registerAndUnregisterInstance(Env env) {
        var registerListener = env.listen(InstanceRegisterEvent.class);
        var unregisterListener = env.listen(InstanceUnregisterEvent.class);

        registerListener.followup();
        Instance instance = env.process().instance().createInstanceContainer();

        unregisterListener.followup();
        env.destroyInstance(instance);
    }
}
