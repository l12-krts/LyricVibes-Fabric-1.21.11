package dev.krts.lyricvibes.mixin;

import dev.krts.lyricvibes.input.Keybinds;
import net.minecraft.client.Keyboard;
import net.minecraft.client.input.KeyInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Keyboard.class)
public class KeyboardMixin {
    @Inject(method = "onKey", at = @At("HEAD"), cancellable = true)
    private void lyricvibes$onKey(long window, int action, KeyInput input, CallbackInfo ci) {
        if (Keybinds.onKey(window, action, input)) ci.cancel();
    }
}
