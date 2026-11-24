package dev.dronchik.irlfog;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;

public class IrlFogClient implements ClientModInitializer {
    private static int ticks;

    @Override
    public void onInitializeClient() {
        FogStore.loadSettings();
        ClientPlayConnectionEvents.JOIN.register((h, sender, mc) -> FogStore.load(mc));
        ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> {
            FogStore.save();
            FogStore.clear();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
            if (FogStore.dirty) FogStore.save();
            SmokeRenderer.close();
        });
        ClientTickEvents.END_CLIENT_TICK.register(IrlFogClient::tick);
    }

    private static void tick(Minecraft mc) {
        if (FogStore.dirty && ++ticks % 100 == 0) FogStore.save();
    }
}
