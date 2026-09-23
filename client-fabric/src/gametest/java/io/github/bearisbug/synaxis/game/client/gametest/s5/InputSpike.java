package io.github.bearisbug.synaxis.game.client.gametest.s5;

import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import org.lwjgl.glfw.GLFW;

/**
 * S5, input half: one click on the ground and one box drag, through {@link SelectionScreen} and
 * through {@link MouseCapture}, all of it by TestInput, which calls MouseHandler's own onMove and
 * onButton. Per path the report says which events arrived with which GUI coordinates, whether
 * vanilla considered the mouse grabbed, whether the attack key went down, how far the player's
 * rotation moved between before the press and after the release, and whether the chat key still
 * opens chat. A vanilla control line first shows what a cursor move does with neither path.
 */
public final class InputSpike {

    private InputSpike() {}

    public static void run(ClientGameTestContext context, List<String> report) {
        final TestInput input = context.getInput();

        // Control: no screen, vanilla MouseHandler. A cursor move turns the player.
        // The first move after MouseHandler#grabMouse only sets the reference point (ignoreFirstMove).
        context.setScreen(() -> null);
        input.moveCursor(1, 0);
        context.waitTicks(2);
        final float[] control = rotation(context);
        input.moveCursor(100, 0);
        context.waitTicks(2);
        report.add("S5 input vanilla grabbed " + grabbed(context) + " cursor +100px x turned player by "
                + delta(control, rotation(context)));
        context.runOnClient(client -> client.player.setYRot(control[0]));

        // Path A: the transparent screen.
        final SelectionScreen screen = context.computeOnClient(_ -> new SelectionScreen());
        context.setScreen(() -> screen);
        context.waitTicks(2);
        final float[] beforeA = rotation(context);
        final boolean grabbedA = grabbed(context);
        final boolean attackA = gestures(context, input, "s5-input-screen-drag");
        context.waitTicks(5);
        report.add("S5 input screen grabbed " + grabbedA + " events " + screen.events + " player_rotation_change "
                + delta(beforeA, rotation(context)) + " attack_down " + attackA);
        input.pressKey(options -> options.keyChat);
        context.waitTicks(2);
        report.add("S5 input screen chat_key_opens_chat " + context.computeOnClient(client -> client.gui.screen() instanceof ChatScreen)
                + " screen_after " + context.computeOnClient(client -> String.valueOf(client.gui.screen())));
        context.setScreen(() -> null);
        context.waitTicks(2);
        report.add("S5 input screen closed: grabbed " + grabbed(context) + " cursor " + cursor(context));

        // Path B: MouseHandler mixin, no screen at all.
        context.runOnClient(client -> {
            MouseCapture.events.clear();
            MouseCapture.active = true;
            client.mouseHandler.releaseMouse();
        });
        context.waitTicks(2);
        final float[] beforeB = rotation(context);
        final boolean grabbedB = grabbed(context);
        final boolean attackB = gestures(context, input, null);
        context.waitTicks(5);
        report.add("S5 input mixin grabbed " + grabbedB + " events " + MouseCapture.events + " player_rotation_change "
                + delta(beforeB, rotation(context)) + " attack_down " + attackB);
        input.pressKey(options -> options.keyChat);
        context.waitTicks(2);
        final boolean chatB = context.computeOnClient(client -> client.gui.screen() instanceof ChatScreen);
        context.setScreen(() -> null);
        context.waitTicks(2);
        report.add("S5 input mixin chat_key_opens_chat " + chatB + " after chat closed: grabbed " + grabbed(context));
        context.runOnClient(client -> {
            MouseCapture.active = false;
            client.mouseHandler.grabMouse();
        });
    }

    /**
     * A click at the middle of the lower half, then a drag from (200, 150) by ten steps of (+20, +10)
     * window pixels, one tick apart so that each step is its own frame.
     *
     * @return whether the attack key was down while the drag held the button
     */
    private static boolean gestures(ClientGameTestContext context, TestInput input, String screenshot) {
        input.setCursorPos(427, 360);
        input.pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        context.waitTicks(2);
        input.setCursorPos(200, 150);
        input.holdMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        for (int i = 0; i < 10; i++) {
            input.moveCursor(20, 10);
            context.waitTick();
        }
        final boolean attack = context.computeOnClient(client -> client.options.keyAttack.isDown());
        if (screenshot != null) context.takeScreenshot(screenshot);
        input.releaseMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        return attack;
    }

    private static boolean grabbed(ClientGameTestContext context) {
        return context.computeOnClient(client -> client.mouseHandler.isMouseGrabbed());
    }

    private static String cursor(ClientGameTestContext context) {
        return context.computeOnClient(client -> String.format("%.1f,%.1f", client.mouseHandler.xpos(), client.mouseHandler.ypos()));
    }

    private static float[] rotation(ClientGameTestContext context) {
        return context.computeOnClient((Minecraft client) -> new float[] {client.player.getYRot(), client.player.getXRot()});
    }

    private static String delta(float[] before, float[] after) {
        return String.format("yaw %+.2f pitch %+.2f", after[0] - before[0], after[1] - before[1]);
    }
}
