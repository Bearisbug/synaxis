package dev.gameengine.event;

import dev.gameengine.MinecraftServer;
import dev.gameengine.event.trait.CancellableEvent;

public final class EventDispatcher {

    public static void call(Event event) {
        MinecraftServer.getGlobalEventHandler().call(event);
    }

    public static <E extends Event> ListenerHandle<E> getHandle(Class<E> handleType) {
        return MinecraftServer.getGlobalEventHandler().getHandle(handleType);
    }

    public static void callCancellable(CancellableEvent event, Runnable successCallback) {
        MinecraftServer.getGlobalEventHandler().callCancellable(event, successCallback);
    }
}
