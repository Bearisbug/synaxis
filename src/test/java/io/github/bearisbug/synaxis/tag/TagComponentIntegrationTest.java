package io.github.bearisbug.synaxis.tag;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@EnvTest
public class TagComponentIntegrationTest {

    @Test
    public void get(Env env) {
        var component = Component.text("Hey");
        var tag = Tag.Component("component", env.process());
        var handler = TagHandler.newHandler();
        handler.setTag(tag, component);
        assertEquals(component, handler.getTag(tag));
    }

    @Test
    public void empty(Env env) {
        var tag = Tag.Component("component", env.process());
        var handler = TagHandler.newHandler();
        assertNull(handler.getTag(tag));
    }

    @Test
    public void invalidTag(Env env) {
        var tag = Tag.Component("entry", env.process());
        var handler = TagHandler.newHandler();
        handler.setTag(Tag.Integer("entry"), 1);
        assertNull(handler.getTag(tag));
    }

    @Test
    public void nbtFallback(Env env) {
        var component = Component.text("Hey");
        var tag = Tag.Component("component", env.process());
        var handler = TagHandler.newHandler();
        handler.setTag(tag, component);
        handler = TagHandler.fromCompound(handler.asCompound());
        assertEquals(component, handler.getTag(tag));
    }
}
