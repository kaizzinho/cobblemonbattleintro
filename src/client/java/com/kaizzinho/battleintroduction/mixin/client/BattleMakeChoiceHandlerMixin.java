package com.kaizzinho.battleintroduction.mixin.client;

import com.cobblemon.mod.common.client.net.battle.BattleMakeChoiceHandler;
import com.cobblemon.mod.common.net.messages.client.battle.BattleMakeChoicePacket;
import com.kaizzinho.battleintroduction.client.BattleIntroOverlay;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BattleMakeChoiceHandler.class, remap = false)
public abstract class BattleMakeChoiceHandlerMixin {

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private void onHandle(BattleMakeChoicePacket packet, MinecraftClient client, CallbackInfo ci) {
        if (!BattleIntroOverlay.INSTANCE.isAnimating()) return;

        ci.cancel();
        BattleIntroOverlay.INSTANCE.addPendingCorePacket(
            "BattleMakeChoicePacket",
            () -> client.execute(() -> BattleMakeChoiceHandler.INSTANCE.handle(packet, client))
        );
    }
}
