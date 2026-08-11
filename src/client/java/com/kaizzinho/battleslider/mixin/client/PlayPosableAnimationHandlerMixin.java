package com.kaizzinho.battleslider.mixin.client;

import com.cobblemon.mod.common.api.scheduling.SchedulingFunctionsKt;
import com.cobblemon.mod.common.client.net.animation.PlayPosableAnimationHandler;
import com.cobblemon.mod.common.net.messages.client.animation.PlayPosableAnimationPacket;
import com.kaizzinho.battleslider.client.BattleIntroOverlay;
import kotlin.Unit;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(value = PlayPosableAnimationHandler.class, remap = false)
public abstract class PlayPosableAnimationHandlerMixin {

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private void onHandle(PlayPosableAnimationPacket packet, MinecraftClient client, CallbackInfo ci) {
        if (!BattleIntroOverlay.INSTANCE.isAnimating()) return;
        ci.cancel();

        Boolean ownedByPlayer = BattleIntroOverlay.INSTANCE.isPlayerOwnedEntity(packet.getEntityId());
        boolean isPlayerOwned = ownedByPlayer == null || ownedByPlayer;

        Runnable replay = () -> SchedulingFunctionsKt.afterOnClient(1.5f, () -> {
            client.execute(() -> {
                PlayPosableAnimationHandler.INSTANCE.handle(packet, client);


                BattleIntroOverlay.INSTANCE.refreshBattlePokemonFacing();
            });
            return Unit.INSTANCE;
        });

        String label = "PlayPosableAnimationPacket[entityId=" + packet.getEntityId()
                + ",ownerKnown=" + (ownedByPlayer != null) + "]";

        if (isPlayerOwned) {
            BattleIntroOverlay.INSTANCE.addPendingPlayerPacket(label, replay);
        } else {
            BattleIntroOverlay.INSTANCE.addPendingOpponentPacket(label, replay);
        }
    }
}
