package io.github.bearisbug.synaxis.game.client.gametest.s5;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The MouseHandler path of S5's input half. While {@link #active}, {@code MouseHandlerMixin} keeps
 * the cursor released, takes every button event away from vanilla (no attack, no use, no grab) and
 * logs it together with the cursor moves made while a button is down, in GUI coordinates.
 */
public final class MouseCapture {

    public static volatile boolean active;
    public static final List<String> events = new CopyOnWriteArrayList<>();

    private MouseCapture() {}
}
