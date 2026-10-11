package io.github.bearisbug.synaxis.app.play;

import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.entity.ItemEntity;
import io.github.bearisbug.synaxis.entity.LivingEntity;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.testing.Env;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * What the tests of the default play layer share: a host node standing where {@code PluginHost}'s does, which the
 * layer attaches to, and the nodes the global scope plugin listeners of one priority land in below it
 * ({@code docs/DESIGN-PROD.md} {@code API-PROD-018}).
 */
final class PlayFixture {

    private PlayFixture() {
    }

    /**
     * A node named and placed as the plugin host's: a child of the root node at the default priority 0.
     *
     * @param env the test environment
     * @return the node
     */
    static EventNode<Event> hostNode(Env env) {
        final EventNode<Event> hostNode = EventNode.all("synaxis-host");
        env.process().eventHandler().addChild(hostNode);
        return hostNode;
    }

    /**
     * Where a global scope plugin listener lands: a child of the host node at the priority its tier maps to, −2..2 for
     * {@code LOWEST..HIGHEST} and 4 for {@code MONITOR} ({@code PluginEventsImpl#tier}).
     *
     * @param hostNode the host node
     * @param priority the node priority
     * @return the node
     */
    static EventNode<Event> pluginTier(EventNode<Event> hostNode, int priority) {
        final EventNode<Event> tier = EventNode.all("plugin/test/" + priority).setPriority(priority);
        hostNode.addChild(tier);
        return tier;
    }

    /**
     * Attaches the layer with every character counting as new.
     *
     * @param env       the test environment
     * @param hostNode  the host node
     * @param features  the features to attach
     * @param coreWorld the world of the training dummy, null without one
     */
    static void attach(Env env, EventNode<Event> hostNode, Set<DefaultPlay.Feature> features,
                       @Nullable Instance coreWorld) {
        DefaultPlay.attach(env.process(), hostNode, features, _ -> true, coreWorld);
    }

    /**
     * The item entities of an instance.
     *
     * @param instance the instance to look in
     * @return the item entities in it
     */
    static List<ItemEntity> items(Instance instance) {
        return instance.getEntities().stream().filter(ItemEntity.class::isInstance).map(ItemEntity.class::cast).toList();
    }

    /**
     * The zombies of an instance, which only the training dummy is in these tests.
     *
     * @param instance the instance to look in
     * @return the zombies in it
     */
    static List<LivingEntity> zombies(Instance instance) {
        return instance.getEntities().stream().filter(entity -> entity.getEntityType() == EntityType.ZOMBIE)
                .map(LivingEntity.class::cast).toList();
    }

    /**
     * Whether an entity is still in an instance.
     *
     * @param entity the entity
     * @return false once it is removed
     */
    static boolean present(Entity entity) {
        return !entity.isRemoved() && entity.getInstance() != null;
    }
}
