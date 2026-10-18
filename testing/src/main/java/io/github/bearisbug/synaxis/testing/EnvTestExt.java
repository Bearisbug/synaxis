package io.github.bearisbug.synaxis.testing;

import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

final class EnvTestExt implements
        AfterAllCallback,
        AfterEachCallback,
        ParameterResolver {

    private static final String ENV_KEY = "synaxis.env";

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
            // The engine settings the JVM may override: the multi-threaded CI rounds run the whole
            // suite with -Dsynaxis.dispatcher-threads=4 (REQ-PROD-019), one of them with
            // -Dsynaxis.thread-provider=grid (REQ-PROD-020)
            .dispatcherThreads(ServerConfig.fromSystemProperties().dispatcherThreads())
            .threadProvider(ServerConfig.fromSystemProperties().threadProvider())
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
        cleanup(context);
    }

    /**
     * An {@link Env} injected into a {@code @BeforeAll} method is created in the class level store,
     * which {@link #afterEach} cannot take it out of: removal never walks up to the parent store.
     */
    @Override
    public void afterAll(ExtensionContext context) {
        cleanup(context);
    }

    private void cleanup(ExtensionContext context) {
        ExtensionContext.Store store = context.getStore(ExtensionContext.Namespace.create(getClass()));
        EnvImpl env = store.remove(ENV_KEY, EnvImpl.class);
        if (env != null) env.close();
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext,
                                     ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType() == Env.class;
    }
}
