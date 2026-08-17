package com.kaizzinho.battleintroduction.mixin.client;

import com.cobblemon.mod.common.client.net.battle.BattleSwitchPokemonHandler;
import com.cobblemon.mod.common.net.messages.client.battle.BattleSwitchPokemonPacket;
import com.kaizzinho.battleintroduction.client.BattleIntroOverlay;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BattleSwitchPokemonHandler.class, remap = false)
public abstract class BattleSwitchPokemonHandlerMixin {

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private void onHandle(BattleSwitchPokemonPacket packet, MinecraftClient client, CallbackInfo ci) {
        if (!BattleIntroOverlay.INSTANCE.isAnimating()) return;

        ci.cancel();
        BattleIntroOverlay.INSTANCE.addPendingCorePacket(
            "BattleSwitchPokemonPacket",
            () -> client.execute(() -> BattleSwitchPokemonHandler.INSTANCE.handle(packet, client))
        );
    }
}
