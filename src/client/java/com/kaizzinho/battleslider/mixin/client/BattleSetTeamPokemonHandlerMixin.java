package com.kaizzinho.battleslider.mixin.client;

import com.cobblemon.mod.common.client.net.battle.BattleSetTeamPokemonHandler;
import com.cobblemon.mod.common.net.messages.client.battle.BattleSetTeamPokemonPacket;
import com.kaizzinho.battleslider.client.BattleIntroOverlay;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BattleSetTeamPokemonHandler.class, remap = false)
public abstract class BattleSetTeamPokemonHandlerMixin {

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private void onHandle(BattleSetTeamPokemonPacket packet, MinecraftClient client, CallbackInfo ci) {
        if (!BattleIntroOverlay.INSTANCE.isAnimating()) return;

        ci.cancel();
        BattleIntroOverlay.INSTANCE.addPendingCorePacket(
            "BattleSetTeamPokemonPacket",
            () -> client.execute(() -> BattleSetTeamPokemonHandler.INSTANCE.handle(packet, client))
        );
    }
}
