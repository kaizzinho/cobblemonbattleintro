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

        String label = "PlaySoundS2CPacket[POKE_BALL_THROW,x=" + x
                + ",y=" + y + ",z=" + z + "]";

        if (isPlayerOwned) {
            BattleIntroOverlay.INSTANCE.addPendingPlayerPacket(label, replay);
        } else {
            BattleIntroOverlay.INSTANCE.addPendingOpponentPacket(label, replay);
        }
    }
}
