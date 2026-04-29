package com.alvin.jdtoptimizer.mixin.jdt;

import com.alvin.jdtoptimizer.api.IFePerTickOverride;
import com.alvin.jdtoptimizer.client.gui.JdtOptSaveButton;
import com.alvin.jdtoptimizer.network.FePerTickOverridePayload;
import com.direwolf20.justdirethings.client.screens.EnergyTransmitterScreen;
import com.direwolf20.justdirethings.client.screens.basescreens.BaseMachineScreen;
import com.direwolf20.justdirethings.common.blockentities.EnergyTransmitterBE;
import com.direwolf20.justdirethings.common.containers.EnergyTransmitterContainer;
import com.direwolf20.justdirethings.setup.Config;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a per-transmitter FE/tick override input box (plus a "Save" button) to the
 * Energy Transmitter GUI.
 *
 * <h2>How it works</h2>
 * <ol>
 *   <li>On {@code init()} we insert a digits-only {@link EditBox} pre-filled with the
 *       transmitter's current effective FE/tick — either the BE's override (via
 *       {@link IFePerTickOverride}) or the {@code Config.ENERGY_TRANSMITTER_T1_RF_PER_TICK}
 *       default if no override is set.</li>
 *   <li>A tiny "Save" {@link Button} sits to the right of the box. It stays disabled
 *       (greyed out) while the typed value matches what's saved on the BE, and activates
 *       the moment the text diverges. Clicking it sends a {@link FePerTickOverridePayload}
 *       and updates our cached "last saved" value so the button greys out again.</li>
 *   <li>Text validation: digits only, max 9 chars (≈ 999,999,999 FE/t). Empty counts as
 *       0, which the server handler treats as "reset to config default".</li>
 * </ol>
 *
 * <p>All of this is cosmetic-only plumbing; the runtime hot path
 * ({@code providePower → fePerTick}) reads a single int field and never touches the
 * network or the GUI. The feature adds <b>zero</b> per-tick CPU cost.
 */
@Mixin(EnergyTransmitterScreen.class)
public abstract class EnergyTransmitterScreenMixin extends BaseMachineScreen<EnergyTransmitterContainer> {

    @Unique
    private EditBox jdtopt$fePerTickField;

    @Unique
    private JdtOptSaveButton jdtopt$saveButton;

    /**
     * The value currently persisted on the BE (or the config default if no override is
     * set). The save button is disabled while {@link #jdtopt$fePerTickField}'s parsed
     * value matches this, and re-disabled after a successful server push.
     */
    @Unique
    private int jdtopt$savedValue;

    // Dummy constructor required by the Mixin class signature (we extend a parameterized
    // abstract class). Never actually invoked at runtime — Mixin merges our methods into
    // EnergyTransmitterScreen, which has its own real constructor.
    private EnergyTransmitterScreenMixin(EnergyTransmitterContainer container, Inventory inv, Component name) {
        super(container, inv, name);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void jdtopt$addFePerTickField(CallbackInfo ci) {
        int current = jdtopt$readCurrentValue();
        this.jdtopt$savedValue = current;

        int x = getGuiLeft() + 8;
        int y = this.topSectionTop + 42;

        // Digits-only EditBox.
        this.jdtopt$fePerTickField = new EditBox(
                this.font, x, y, 52, 14, Component.literal("FE/t"));
        this.jdtopt$fePerTickField.setMaxLength(10);
        this.jdtopt$fePerTickField.setFilter(s -> s.isEmpty() || s.chars().allMatch(c -> c >= '0' && c <= '9'));
        this.jdtopt$fePerTickField.setValue(Integer.toString(current));
        this.jdtopt$fePerTickField.setHint(Component.literal("FE/t"));
        this.jdtopt$fePerTickField.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.literal("Max FE/tick this transmitter sends to each machine.\n0 = use config default.")));
        this.jdtopt$fePerTickField.setResponder(v -> jdtopt$refreshSaveButton());
        addRenderableWidget(this.jdtopt$fePerTickField);

        // Save button: 18x14, directly to the right of the EditBox with a 2px gap.
        // Shift-click sets the override to Integer.MAX_VALUE — effectively uncapped.
        this.jdtopt$saveButton = new JdtOptSaveButton(
                x + 52 + 2, y, 18, 14,
                Component.literal("\u2713"),
                b -> jdtopt$commitSave(),
                this::jdtopt$commitMax
        );
        this.jdtopt$saveButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.literal("Save this FE/tick value to the transmitter.\nShift-click: set to max (" + Integer.MAX_VALUE + ").")));
        this.jdtopt$saveButton.active = false;
        addRenderableWidget(this.jdtopt$saveButton);
    }

    /**
     * Enable the save button exactly when the typed value is a valid non-negative
     * integer different from the currently-saved value. Empty text is treated as
     * {@code 0} (= reset to config default), which is itself a savable change when
     * there's an active override.
     */
    @Unique
    private void jdtopt$refreshSaveButton() {
        if (this.jdtopt$saveButton == null || this.jdtopt$fePerTickField == null) return;
        int typed = jdtopt$parseCurrentInput();
        if (typed < 0) {
            this.jdtopt$saveButton.active = false;
            return;
        }
        this.jdtopt$saveButton.active = typed != this.jdtopt$savedValue;
    }

    /**
     * Send the current typed value to the server and lock the button again. The server
     * will persist the override and re-broadcast the BE state; meanwhile we optimistically
     * update {@link #jdtopt$savedValue} so the button immediately reflects a "saved"
     * state without waiting for a server round-trip.
     */
    @Unique
    private void jdtopt$commitSave() {
        int typed = jdtopt$parseCurrentInput();
        if (typed < 0 || typed == this.jdtopt$savedValue) return;
        PacketDistributor.sendToServer(new FePerTickOverridePayload(typed));
        // If the user typed 0 (= reset), the effective value reverts to the config
        // default; reflect that in the box so the "saved" state lines up visually.
        int effective = (typed <= 0) ? Config.ENERGY_TRANSMITTER_T1_RF_PER_TICK.get() : typed;
        this.jdtopt$savedValue = effective;
        this.jdtopt$fePerTickField.setValue(Integer.toString(effective));
        this.jdtopt$saveButton.active = false;
    }

    /**
     * Shift-click handler: push {@link Integer#MAX_VALUE} to the server and update the
     * box + button to reflect the "saved" state. Lets the user one-click uncap this
     * transmitter without manually typing 10 digits.
     */
    @Unique
    private void jdtopt$commitMax() {
        final int max = Integer.MAX_VALUE;
        if (this.jdtopt$savedValue == max) return;
        PacketDistributor.sendToServer(new FePerTickOverridePayload(max));
        this.jdtopt$savedValue = max;
        this.jdtopt$fePerTickField.setValue(Integer.toString(max));
        this.jdtopt$saveButton.active = false;
    }

    /**
     * @return the parsed value from the EditBox, or {@code -1} if the text doesn't
     *         parse. Empty string returns {@code 0} (the "reset to default" sentinel
     *         that the server handler understands).
     */
    @Unique
    private int jdtopt$parseCurrentInput() {
        final String text = this.jdtopt$fePerTickField.getValue();
        if (text.isEmpty()) return 0;
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * @return the FE/tick value this transmitter currently uses — either the active
     *         override, or the config default if none is set.
     */
    @Unique
    private int jdtopt$readCurrentValue() {
        if (this.baseMachineBE instanceof EnergyTransmitterBE && this.baseMachineBE instanceof IFePerTickOverride ov) {
            int override = ov.jdtopt_getFePerTickOverride();
            if (override > 0) return override;
        }
        return Config.ENERGY_TRANSMITTER_T1_RF_PER_TICK.get();
    }
}
