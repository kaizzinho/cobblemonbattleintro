package com.kaizzinho.battleslider.mixin.client;

import com.cobblemon.mod.common.client.net.battle.BattleChallengeNotificationHandler;
import com.cobblemon.mod.common.net.messages.client.battle.BattleChallengeNotificationPacket;
import com.kaizzinho.battleslider.client.BattleIntroOverlay;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BattleChallengeNotificationHandler.class, remap = false)
public abstract class BattleChallengeNotificationHandlerMixin {

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private void onHandle(BattleChallengeNotificationPacket packet, MinecraftClient client, CallbackInfo ci) {
        System.out.println("[Battleslider] Intercepted " + packet.getClass().getSimpleName() + ", animating=" + BattleIntroOverlay.INSTANCE.isAnimating());
        if (!BattleIntroOverlay.INSTANCE.isAnimating()) return;
        ci.cancel();
        BattleIntroOverlay.INSTANCE.addPendingPacket(() ->
            client.execute(() -> BattleChallengeNotificationHandler.INSTANCE.handle(packet, client))
        );
    }
}
