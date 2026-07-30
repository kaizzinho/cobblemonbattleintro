package com.kaizzinho.battleslider.mixin.client;

import com.cobblemon.mod.common.CobblemonSounds;
import com.kaizzinho.battleslider.client.BattleIntroOverlay;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.sound.SoundEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts the VANILLA PlaySoundS2CPacket handler -- this is how the
 * pokeball throw sound (CobblemonSounds.POKE_BALL_THROW) actually reaches
 * the client. It's played server-side via plain ServerWorld.playSound(),
 * completely bypassing every Cobblemon-specific packet we've been
 * intercepting elsewhere, which is why it was firing immediately (during
 * the flicker) instead of being staggered with everything else.
 *
 * This packet carries no owner/entity info, only a position -- so we
 * classify it by proximity to each trainer's actual position (the sound is
 * always played at the throwing trainer's location) rather than by ID.
 *
 * NOTE: unlike the Cobblemon-class mixins in this project, this targets a
 * real vanilla class, so it needs normal remapping (no remap=false).
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class PlaySoundS2CPacketMixin {

    @Inject(method = "onPlaySound", at = @At("HEAD"), cancellable = true)
    private void onPlaySound(PlaySoundS2CPacket packet, CallbackInfo ci) {
        if (!BattleIntroOverlay.INSTANCE.isAnimating()) return;

        SoundEvent sound = packet.getSound().value();
        if (sound != CobblemonSounds.POKE_BALL_THROW) return;

        ci.cancel();

        double x = packet.getX();
        double y = packet.getY();
        double z = packet.getZ();
        boolean isPlayerOwned = BattleIntroOverlay.INSTANCE.isSoundNearPlayer(x, y, z);

        Runnable replay = () -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world != null) {
                client.world.playSound(client.player, x, y, z, packet.getSound().value(),
                        packet.getCategory(), packet.getVolume(), packet.getPitch());
            }
        };

        if (isPlayerOwned) {
            BattleIntroOverlay.INSTANCE.addPendingPlayerPacket(replay);
        } else {
            BattleIntroOverlay.INSTANCE.addPendingOpponentPacket(replay);
        }
    }
}
