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

/**
 * Intercepts PlayPosableAnimationHandler.handle() -- carries the Pokemon CRY
 * trigger. This packet only has an entity ID, no owner info, so we look it
 * up in BattleIntroOverlay's ownership map (populated when the matching
 * SpawnPokemonPacket was queued) to route it into the correct player/opponent
 * queue -- keeping the whole send-out sequence (throw, beam, cry) grouped and
 * staggered per side, rather than everything firing together.
 *
 * The 1.5s delay before the cry actually plays is preserved within each
 * side's own sequence (so ball-open and cry still don't overlap for a given
 * trainer); the player-vs-opponent stagger itself happens in
 * BattleIntroOverlay's flush logic.
 */
@Mixin(value = PlayPosableAnimationHandler.class, remap = false)
public abstract class PlayPosableAnimationHandlerMixin {

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private void onHandle(PlayPosableAnimationPacket packet, MinecraftClient client, CallbackInfo ci) {
        if (!BattleIntroOverlay.INSTANCE.isAnimating()) return;
        ci.cancel();

        Boolean ownedByPlayer = BattleIntroOverlay.INSTANCE.isPlayerOwnedEntity(packet.getEntityId());
        boolean isPlayerOwned = ownedByPlayer == null || ownedByPlayer; // unknown -> default to player's batch

        Runnable replay = () -> SchedulingFunctionsKt.afterOnClient(1.5f, () -> {
            client.execute(() -> PlayPosableAnimationHandler.INSTANCE.handle(packet, client));
            return Unit.INSTANCE;
        });

        if (isPlayerOwned) {
            BattleIntroOverlay.INSTANCE.addPendingPlayerPacket(replay);
        } else {
            BattleIntroOverlay.INSTANCE.addPendingOpponentPacket(replay);
        }
    }
}
