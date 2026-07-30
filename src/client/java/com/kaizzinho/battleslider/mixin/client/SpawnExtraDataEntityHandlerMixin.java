package com.kaizzinho.battleslider.mixin.client;

import com.cobblemon.mod.common.client.net.spawn.SpawnExtraDataEntityHandler;
import com.cobblemon.mod.common.net.messages.client.spawn.SpawnExtraDataEntityPacket;
import com.cobblemon.mod.common.net.messages.client.spawn.SpawnPokemonPacket;
import com.kaizzinho.battleslider.client.BattleIntroOverlay;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Intercepts SpawnExtraDataEntityHandler.handle().
 *
 * This covers both Poké Ball entity spawns and the actual Pokémon entity spawn
 * represented by SpawnPokemonPacket.
 *
 * SpawnPokemonPacket instances are classified using their Pokémon UUID against
 * the party UUIDs captured by BattleIntroOverlay when the battle starts.
 *
 * The spawned entity's vanilla network ID is also recorded so later packets
 * that only contain an entity ID, such as animation or cry packets, can still
 * be associated with the correct trainer.
 */
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
        if (!BattleIntroOverlay.INSTANCE.isAnimating()) {
            return;
        }

        ci.cancel();

        // Unknown packet types use the player's queue as a fallback.
        boolean isPlayerOwned = true;

        if (packet instanceof SpawnPokemonPacket spawnPacket) {
            UUID pokemonUUID = spawnPacket.getPokemonUUID();

            isPlayerOwned =
                    !BattleIntroOverlay.INSTANCE.isOpponentPokemon(pokemonUUID);

            int entityId = spawnPacket
                    .getVanillaSpawnPacket()
                    .getEntityId();

            BattleIntroOverlay.INSTANCE.registerPokemonOwnership(
                    entityId,
                    isPlayerOwned
            );
        }

        Runnable replay = () ->
                client.execute(() -> packet.spawnAndApply(client));

        String label = packet.getClass().getSimpleName();
        if (packet instanceof SpawnPokemonPacket spawnPacket) {
            label += "[pokemon=" + spawnPacket.getPokemonUUID()
                    + ",entityId=" + spawnPacket.getVanillaSpawnPacket().getEntityId() + "]";
        }

        if (isPlayerOwned) {
            BattleIntroOverlay.INSTANCE.addPendingPlayerPacket(label, replay);
        } else {
            BattleIntroOverlay.INSTANCE.addPendingOpponentPacket(label, replay);
        }
    }
}