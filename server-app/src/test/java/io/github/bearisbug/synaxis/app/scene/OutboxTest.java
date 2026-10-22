package io.github.bearisbug.synaxis.app.scene;

import io.github.bearisbug.synaxis.protocol.MsgBuf;
import io.github.bearisbug.synaxis.protocol.play.EncodedComponent;
import io.github.bearisbug.synaxis.protocol.play.PlayMessage;
import io.github.bearisbug.synaxis.protocol.play.PlayMessages;
import io.github.bearisbug.synaxis.protocol.play.PlayProtocol;
import io.github.bearisbug.synaxis.protocol.play.PropValue;
import io.github.bearisbug.synaxis.protocol.play.S2CHud;
import io.github.bearisbug.synaxis.protocol.play.S2CScene;
import io.github.bearisbug.synaxis.protocol.play.Widget;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per connection queue of the scene layer ({@code docs/DESIGN-PLAY.md} §6.4.3, {@code API-PLAY-002}): one
 * exit, the scene first, {@code S2C_HUD} split on op boundaries within both 8 KiB and 32 ops, and the whole
 * queue held while a move is under way. It takes a byte sink, so no process is needed.
 */
class OutboxTest {

    private static final S2CScene LOBBY = scene(1, "probe:lobby");

    private static S2CScene scene(int sceneSeq, String sceneId) {
        return new S2CScene(sceneSeq, sceneId, text("title"), S2CScene.CAMERA_VANILLA, S2CScene.CURSOR_LOCKED,
                List.of(new S2CScene.Action(0, "probe:queue", text("queue"), 2_000)));
    }

    private static EncodedComponent text(String opaque) {
        return new EncodedComponent(opaque.getBytes(StandardCharsets.UTF_8));
    }

    private static Widget line(String text) {
        return Widget.text(Widget.TOP_LEFT, 0, text(text), 0);
    }

    /** What the sink got, each payload read back. */
    private record Sent(List<byte[]> payloads) {

        List<PlayMessage> messages() {
            return payloads.stream().map(bytes -> PlayMessages.read(MsgBuf.reading(bytes))).toList();
        }

        List<S2CHud.Op> ops() {
            return messages().stream().filter(S2CHud.class::isInstance).map(S2CHud.class::cast)
                    .flatMap(hud -> hud.ops().stream()).toList();
        }
    }

    private static Sent flush(Outbox outbox) {
        final List<byte[]> payloads = new ArrayList<>();
        final int handed = outbox.flush(payloads::add);
        assertEquals(payloads.size(), handed, "the count flush returned");
        return new Sent(payloads);
    }

    @Test
    void theSceneGoesFirstAndAChangeTheSceneOwnerQueuedBeforeItIsGone() {
        final Outbox outbox = new Outbox();
        outbox.set("probe", "hint", line("hint"));
        // What entering a scene queues: the header, and a clear of the owner in place of its earlier changes
        outbox.scene(LOBBY);
        outbox.clearOwner("probe");
        outbox.set("probe", "timer", line("timer"));

        final List<PlayMessage> messages = flush(outbox).messages();

        assertEquals(List.of(LOBBY, new S2CHud(List.of(new S2CHud.ClearOwner("probe"),
                new S2CHud.SetWidget("probe", "timer", line("timer"))))), messages);
    }

    @Test
    void thirtyTwoScoreboardsOfAboutOneKibSplitOnTheByteCap() {
        final Outbox outbox = new Outbox();
        final List<PropValue.Row> rows = new ArrayList<>();
        for (int row = 0; row < 16; row++) rows.add(new PropValue.Row(text("n".repeat(40)), "v".repeat(16), false));
        for (int i = 0; i < 32; i++)
            outbox.set("probe", "board" + i, Widget.scoreboard(Widget.RIGHT, i, text("scores"), rows));

        final Sent sent = flush(outbox);

        assertTrue(sent.payloads().size() >= 4, "32 widgets of about 1 KiB went out in " + sent.payloads().size());
        for (int i = 0; i < sent.payloads().size(); i++) {
            final byte[] payload = sent.payloads().get(i);
            assertTrue(payload.length <= PlayProtocol.MAX_MESSAGE_BYTES, "message " + i + " is " + payload.length + " bytes");
            if (i + 1 < sent.payloads().size()) {
                // Cut on the byte cap, not earlier: the first op of the next message would not have fitted
                final S2CHud next = (S2CHud) sent.messages().get(i + 1);
                final int nextOp = new S2CHud(List.of(next.ops().getFirst())).encode().length
                        - new S2CHud(List.of()).encode().length;
                assertTrue(payload.length + nextOp > PlayProtocol.MAX_MESSAGE_BYTES, "message " + i + " was cut early");
            }
        }
        final List<S2CHud.Op> ops = sent.ops();
        assertEquals(32, ops.size());
        for (int i = 0; i < 32; i++) assertEquals("board" + i, ops.get(i).widgetId());
    }

    @Test
    void thirtyThreeSmallTextsSplitOnTheOpCap() {
        final Outbox outbox = new Outbox();
        for (int i = 0; i < 33; i++) outbox.set("probe", "t" + i, line("x"));

        final Sent sent = flush(outbox);

        assertEquals(2, sent.payloads().size(), "33 ops in one tick");
        assertEquals(32, ((S2CHud) sent.messages().get(0)).ops().size());
        assertEquals(1, ((S2CHud) sent.messages().get(1)).ops().size());
        assertEquals("t32", sent.ops().getLast().widgetId());
        for (byte[] payload : sent.payloads()) assertTrue(payload.length <= PlayProtocol.MAX_MESSAGE_BYTES);
    }

    @Test
    void aMoveUnderWayHoldsTheWholeQueueUntilEveryMoveIsDone() {
        final Outbox outbox = new Outbox();
        final CompletableFuture<Boolean> first = new CompletableFuture<>();
        final CompletableFuture<Boolean> second = new CompletableFuture<>();
        outbox.holdUntil(first);
        outbox.scene(LOBBY);
        outbox.set("probe", "timer", line("timer"));

        assertEquals(0, flush(outbox).payloads().size(), "sent while the move was under way");
        outbox.holdUntil(second);
        first.complete(true);
        assertEquals(0, flush(outbox).payloads().size(), "sent while the second move was under way");
        second.complete(false);

        final List<PlayMessage> messages = flush(outbox).messages();
        assertEquals(2, messages.size());
        assertEquals(LOBBY, messages.get(0));
        assertInstanceOf(S2CHud.class, messages.get(1));
    }

    @Test
    void theLaterMoveDoneFirstStillHoldsTheQueueUntilTheEarlierIsDone() {
        final Outbox outbox = new Outbox();
        final CompletableFuture<Boolean> first = new CompletableFuture<>();
        final CompletableFuture<Boolean> second = new CompletableFuture<>();
        outbox.holdUntil(first);
        outbox.holdUntil(second);
        outbox.scene(LOBBY);
        outbox.set("probe", "timer", line("timer"));

        second.complete(true);
        assertEquals(0, flush(outbox).payloads().size(), "sent while the first move was under way");
        first.complete(false);

        final List<PlayMessage> messages = flush(outbox).messages();
        assertEquals(2, messages.size());
        assertEquals(LOBBY, messages.get(0));
        assertInstanceOf(S2CHud.class, messages.get(1));
    }

    @Test
    void anEarlierMoveThatFailsStillHoldsTheQueueUntilTheLaterIsDone() {
        final Outbox outbox = new Outbox();
        final CompletableFuture<Boolean> first = new CompletableFuture<>();
        final CompletableFuture<Boolean> second = new CompletableFuture<>();
        outbox.holdUntil(first);
        outbox.holdUntil(second);
        outbox.scene(LOBBY);

        // The way a move ends when moving the player into the instance failed
        first.completeExceptionally(new IllegalStateException("setInstance failed"));
        assertEquals(0, flush(outbox).payloads().size(), "sent while the second move was under way");
        second.complete(true);

        assertEquals(List.of(LOBBY), flush(outbox).messages());
    }

    @Test
    void aLaterMoveThatFailsStillHoldsTheQueueUntilTheEarlierIsDone() {
        final Outbox outbox = new Outbox();
        final CompletableFuture<Boolean> first = new CompletableFuture<>();
        final CompletableFuture<Boolean> second = new CompletableFuture<>();
        outbox.holdUntil(first);
        outbox.holdUntil(second);
        outbox.scene(LOBBY);

        second.completeExceptionally(new IllegalStateException("setInstance failed"));
        assertEquals(0, flush(outbox).payloads().size(), "sent while the first move was under way");
        first.complete(true);

        assertEquals(List.of(LOBBY), flush(outbox).messages());
    }

    @Test
    void twoChangesOfOneWidgetInOneTickReachTheClientAsTheLaterOne() {
        final Outbox outbox = new Outbox();
        outbox.set("probe", "timer", line("first"));
        outbox.set("x", "banner", line("banner"));
        outbox.set("probe", "timer", line("second"));
        outbox.set("x", "gone", line("gone"));
        outbox.remove("x", "gone");

        assertEquals(List.of(new S2CHud.SetWidget("x", "banner", line("banner")),
                new S2CHud.SetWidget("probe", "timer", line("second")),
                new S2CHud.RemoveWidget("x", "gone")), flush(outbox).ops());
    }

    @Test
    void aClearOfOneOwnerKeepsTheOtherOwnersChanges() {
        final Outbox outbox = new Outbox();
        outbox.set("probe", "timer", line("timer"));
        outbox.set("x", "banner", line("banner"));
        outbox.clearOwner("probe");

        assertEquals(List.of(new S2CHud.SetWidget("x", "banner", line("banner")), new S2CHud.ClearOwner("probe")),
                flush(outbox).ops());
    }

    @Test
    void ofTwoScenesInOneTickOnlyTheLaterGoesOut() {
        final Outbox outbox = new Outbox();
        final S2CScene round = scene(2, "probe:round");
        outbox.scene(LOBBY);
        outbox.scene(round);

        assertEquals(List.of(round), flush(outbox).messages());
    }

    @Test
    void anEmptyQueueSendsNothingAndAFlushEmptiesIt() {
        final Outbox outbox = new Outbox();
        assertEquals(0, flush(outbox).payloads().size());
        outbox.set("probe", "timer", line("timer"));
        assertEquals(1, flush(outbox).payloads().size());
        assertEquals(0, flush(outbox).payloads().size(), "a flushed change went out twice");
    }
}
