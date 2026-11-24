package dev.dronchik.irlfog.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import dev.dronchik.irlfog.SmokeRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Smoke is drawn right after the level: after a shader pack's final pass (Iris), before the hand and the GUI. */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Unique private long irlfog$last;

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V"))
    private void irlfog$level(LevelRenderer self, GraphicsResourceAllocator alloc, DeltaTracker delta, boolean outline, Camera camera,
                              Matrix4f modelView, Matrix4f projection, Matrix4f culling, GpuBufferSlice fog, Vector4f fogColor, boolean sky,
                              Operation<Void> original) {
        original.call(self, alloc, delta, outline, camera, modelView, projection, culling, fog, fogColor, sky);
        long now = System.nanoTime();
        float dt = irlfog$last == 0 ? 0 : Math.min(0.25f, (now - irlfog$last) / 1e9f);
        irlfog$last = now;
        SmokeRenderer.afterLevel(modelView, projection, dt);
    }
}
