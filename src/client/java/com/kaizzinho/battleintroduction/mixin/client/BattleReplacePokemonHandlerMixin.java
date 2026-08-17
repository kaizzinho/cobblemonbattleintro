package com.kaizzinho.battleintroduction.mixin.client;

import com.cobblemon.mod.common.client.net.battle.BattleReplacePokemonHandler;
import com.cobblemon.mod.common.net.messages.client.battle.BattleReplacePokemonPacket;
import com.kaizzinho.battleintroduction.client.BattleIntroOverlay;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BattleReplacePokemonHandler.class, remap = false)
public abstract class BattleReplacePokemonHandlerMixin {

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private void onHandle(BattleReplacePokemonPacket packet, MinecraftClient client, CallbackInfo ci) {
        if (!BattleIntroOverlay.INSTANCE.isAnimating()) return;

        ci.cancel();
        BattleIntroOverlay.INSTANCE.addPendingCorePacket(
            "BattleReplacePokemonPacket",
            () -> client.execute(() -> BattleReplacePokemonHandler.INSTANCE.handle(packet, client))
        );
    }
}
