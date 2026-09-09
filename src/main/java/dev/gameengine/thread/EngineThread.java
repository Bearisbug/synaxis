package dev.gameengine.thread;

import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
@ApiStatus.NonExtendable
public class EngineThread extends Thread {
    public EngineThread(String name) {
        super(name);
    }
}
