package io.github.bearisbug.synaxis.instance;

import io.github.bearisbug.synaxis.event.instance.InstanceRegisterEvent;
import io.github.bearisbug.synaxis.event.instance.InstanceUnregisterEvent;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
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
