package com.kaizzinho.battleslider.mixin.client;

import com.cobblemon.mod.common.client.net.spawn.SpawnExtraDataEntityHandler;
import com.cobblemon.mod.common.net.messages.client.spawn.SpawnExtraDataEntityPacket;
import com.cobblemon.mod.common.net.messages.client.spawn.SpawnPokemonPacket;
import com.kaizzinho.battleslider.client.BattleIntroOverlay;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;


@Mixin(value = SpawnExtraDataEntityHandler.class, remap = false)
public abstract class SpawnExtraDataEntityHandlerMixin {

    @Inject(
            method = "handle",
            at = @At("HEAD"),
            cancellable = true
    )
    private void onHandle(
            SpawnExtraDataEntityPacket<?, ?> packet,
            MinecraftClient client,
            CallbackInfo ci
    ) {
        BattleIntroOverlay overlay = BattleIntroOverlay.INSTANCE;
        if (!overlay.isAnimating()) {
            return;
        }

        EntitySpawnS2CPacket vanillaSpawnPacket =
                packet.getVanillaSpawnPacket();


        boolean isPlayerOwned = overlay.isSoundNearPlayer(
                vanillaSpawnPacket.getX(),
                vanillaSpawnPacket.getY(),
                vanillaSpawnPacket.getZ()
        );

        int pokemonEntityId = -1;

        if (packet instanceof SpawnPokemonPacket spawnPacket) {
            UUID pokemonUUID = spawnPacket.getPokemonUUID();
            boolean isLocalPokemon = overlay.isLocalPokemon(pokemonUUID);
            boolean isOpponentPokemon = overlay.isOpponentPokemon(pokemonUUID);


            if (!isLocalPokemon && !isOpponentPokemon) {
                return;
            }

            isPlayerOwned = isLocalPokemon;
            pokemonEntityId = vanillaSpawnPacket.getEntityId();

            overlay.registerBattlePokemonSpawn(
                    pokemonEntityId,
                    isPlayerOwned,
                    vanillaSpawnPacket.getX(),
                    vanillaSpawnPacket.getZ()
            );
        }

        ci.cancel();

        final boolean queuedForPlayer = isPlayerOwned;
        final int queuedPokemonEntityId = pokemonEntityId;

        Runnable replay = () -> client.execute(() -> {
            if (packet instanceof SpawnPokemonPacket spawnPacket) {
                Float desiredYaw =
                        overlay.getDesiredBattlePokemonYaw(queuedPokemonEntityId);

                if (desiredYaw != null) {


                    spawnPacket.setSpawnYaw(desiredYaw);
                }
            }

            packet.spawnAndApply(client);

            if (queuedPokemonEntityId >= 0) {
                overlay.onBattlePokemonSpawned(queuedPokemonEntityId);
            }
        });

        String label = packet.getClass().getSimpleName();
        if (packet instanceof SpawnPokemonPacket spawnPacket) {
            label += "[pokemon=" + spawnPacket.getPokemonUUID()
                    + ",entityId=" + queuedPokemonEntityId + "]";
        }

        if (queuedForPlayer) {
            overlay.addPendingPlayerPacket(label, replay);
        } else {
            overlay.addPendingOpponentPacket(label, replay);
        }
    }
}
