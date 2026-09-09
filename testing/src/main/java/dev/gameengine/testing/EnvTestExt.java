package dev.gameengine.testing;

import dev.gameengine.ServerConfig;
import dev.gameengine.ServerProcess;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

final class EnvTestExt implements
        AfterEachCallback,
        ParameterResolver {

    private static final String ENV_KEY = "gameengine.env";

    /**
     * The configuration every {@link Env} runs with. It starts from the production defaults and only
     * changes what a test fixture genuinely needs, so every difference from production is visible
     * here rather than buried in a JVM argument.
     */
    static final ServerConfig CONFIG = ServerConfig.builder()
            // Lets the fixture join the spawn future and keeps the registries writable after start()
            .insideTest(true)
            // Viewable packets make tracking harder. Could be re-enabled later.
            .viewablePacket(false)
            .build();

    @Override
    public Env resolveParameter(ParameterContext parameterContext,
                                ExtensionContext extensionContext) {
        return extensionContext.getStore(ExtensionContext.Namespace.create(getClass()))
                .computeIfAbsent(ENV_KEY,
                        _ -> new EnvImpl(ServerProcess.of(CONFIG)),
                        EnvImpl.class);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        ExtensionContext.Store store = context.getStore(ExtensionContext.Namespace.create(getClass()));
        EnvImpl env = store.remove(ENV_KEY, EnvImpl.class);
        if (env != null) env.cleanup();
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext,
                                     ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType() == Env.class;
    }
}
