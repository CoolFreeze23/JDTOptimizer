package com.alvin.jdtoptimizer.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * A {@link Button} specialization with two click behaviors:
 *
 * <ul>
 *   <li><b>Normal click</b> fires the standard {@link OnPress} handler — and is gated by
 *       the usual {@code active} flag, so the button greys out when there's nothing to
 *       commit (same UX as a typical "Save" button).</li>
 *   <li><b>Shift + click</b> fires a separate {@code onShiftClick} handler and works
 *       <em>regardless</em> of the {@code active} flag. This lets us keep the visual
 *       "grey when saved" state while still letting the user shift-click for a shortcut
 *       action (e.g. "save at the maximum allowed value").</li>
 * </ul>
 *
 * <p>We bypass {@link Button}'s normal {@code mouseClicked} only when shift is held and
 * the click is inside the widget's bounds. All other paths fall back to the parent
 * implementation, so keyboard activation (Enter / Space) and non-shift clicks behave
 * exactly like a stock {@link Button}.
 */
public final class JdtOptSaveButton extends Button {
    private final Runnable onShiftClick;

    public JdtOptSaveButton(int x, int y, int width, int height,
                            Component message,
                            OnPress onPress,
                            Runnable onShiftClick) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        this.onShiftClick = onShiftClick;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Shift-click path: fire even when the button is logically "inactive".
        // We still gate on visibility, valid click button, and mouse-within-bounds so
        // we don't swallow clicks meant for other widgets.
        if (Screen.hasShiftDown()
                && this.visible
                && this.isValidClickButton(button)
                && this.clicked(mouseX, mouseY)) {
            this.playDownSound(Minecraft.getInstance().getSoundManager());
            this.onShiftClick.run();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
