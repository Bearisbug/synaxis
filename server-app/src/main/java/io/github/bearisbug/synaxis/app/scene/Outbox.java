package io.github.bearisbug.synaxis.app.scene;

import io.github.bearisbug.synaxis.game.protocol.MsgBuf;
import io.github.bearisbug.synaxis.game.protocol.play.PlayMessage;
import io.github.bearisbug.synaxis.game.protocol.play.S2CScene;
import io.github.bearisbug.synaxis.game.protocol.play.Widget;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * What one connection is still to be sent on {@code synaxis:play}. Skeleton: it queues nothing.
 */
final class Outbox {

    void scene(S2CScene scene) {
    }

    void set(String owner, String widgetId, Widget widget) {
    }

    void remove(String owner, String widgetId) {
    }

    void clearOwner(String owner) {
    }

    void holdUntil(CompletableFuture<?> move) {
    }

    int flush(Consumer<byte[]> sink) {
        return 0;
    }

    static byte[] bytes(PlayMessage message) {
        final MsgBuf buf = MsgBuf.writing();
        message.write(buf);
        return buf.toByteArray();
    }
}
