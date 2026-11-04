package com.armormaking;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/** Starter only: toggles a local state. Does not automate gameplay. */
public class ArmorMakingMod implements ClientModInitializer {
    private boolean enabled = false;
    @Override public void onInitializeClient() {
        KeyBinding toggle = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.armormaking.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K,
            "category.armormaking.general"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggle.wasPressed()) {
                enabled = !enabled;
                if (client.player != null) {
                    client.player.sendMessage(net.minecraft.text.Text.literal(
                        "Armor Making: " + (enabled ? "ON (starter only)" : "OFF")), true);
                }
            }
        });
    }
}
