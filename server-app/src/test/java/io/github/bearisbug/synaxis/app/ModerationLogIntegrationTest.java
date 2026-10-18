package io.github.bearisbug.synaxis.app;

import ch.qos.logback.classic.Level;
import io.github.bearisbug.synaxis.app.moderation.HostModeration;
import io.github.bearisbug.synaxis.app.moderation.ModerationCommands;
import io.github.bearisbug.synaxis.app.moderation.ModerationStore;
import io.github.bearisbug.synaxis.app.permission.HostPermissions;
import io.github.bearisbug.synaxis.app.store.AuthMode;
import io.github.bearisbug.synaxis.app.store.Database;
import io.github.bearisbug.synaxis.app.store.Migrations;
import io.github.bearisbug.synaxis.app.store.RedisLink;
import io.github.bearisbug.synaxis.app.store.TestDatabase;
import io.github.bearisbug.synaxis.command.ConsoleSender;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The line a GM command that fails on the database logs ({@code docs/DESIGN-PROD.md} {@code API-PROD-009},
 * {@code API-PROD-011}): the command line is the client's text, escaped onto one line, so that a reason holding a
 * line feed forges no line of its own.
 */
@EnvTest
class ModerationLogIntegrationTest {

    private TestDatabase database;
    private Database pool;

    @BeforeEach
    void create() throws SQLException {
        database = TestDatabase.create();
        pool = new Database(database.settings());
        Migrations.migrate(pool.pool(), AuthMode.OFFLINE);
    }

    @AfterEach
    void drop() {
        if (pool != null) pool.close();
        if (database != null) database.close();
    }

    /** The console, recording the translate key of every answer. */
    private static final class RecordingConsole extends ConsoleSender {
        final List<String> answers = new CopyOnWriteArrayList<>();

        RecordingConsole(Env env) {
            super(env.process());
        }

        @Override
        public void sendMessage(Component message) {
            if (message instanceof TranslatableComponent translatable) answers.add(translatable.key());
        }
    }

    @Test
    void aCommandThatFailsOnTheDatabaseIsLoggedOnOneLine(Env env) throws Exception {
        final HostModeration moderation = new HostModeration(env.process(), new ModerationStore(pool.pool()),
                RedisLink.disabled("moderation-log"), HostModeration.REFRESH, false, (_, _) -> { });
        moderation.start();
        final EventNode<Event> node = EventNode.all("moderation-log");
        env.process().eventHandler().addChild(node);
        moderation.attach(node);
        new ModerationCommands(env.process(), moderation, HostPermissions.withoutDatabase(env.process()), (_, _) -> { })
                .commands().forEach(env.process().command()::register);
        final RecordingConsole console = new RecordingConsole(env);
        final UUID target = UUID.randomUUID();
        pool.close();

        try (LogCapture commands = LogCapture.of(ModerationCommands.class)) {
            env.process().command().execute(console, "ban " + target + " perm spam\nforged");
            final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (console.answers.isEmpty() && System.nanoTime() - deadline < 0) {
                env.tick();
                Thread.sleep(10);
            }
            assertEquals(List.of("synaxis:moderation-unavailable"), console.answers);
            assertEquals(List.of("A moderation command failed: command=ban " + target
                    + " perm spam\\u000aforged (1 moderation command failures since the last report)"),
                    commands.messagesAt(Level.WARN));
        }
    }
}
