package dev.dronchik.irlfog.mixin;

import dev.dronchik.irlfog.FogPanel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Окно тумана живёт в том же кадре ImGui, что и редактор света: общий ввод, свободная камера и ImGuizmo.beginFrame
@Pseudo
@Mixin(targets = "org.qualet.irlredactor.editor.LightEditorPanel", remap = false)
public class LightEditorPanelMixin {
    @Inject(method = "draw", at = @At("TAIL"), remap = false)
    private void irlfog$drawFog(CallbackInfo ci) {
        FogPanel.draw();
    }
}
