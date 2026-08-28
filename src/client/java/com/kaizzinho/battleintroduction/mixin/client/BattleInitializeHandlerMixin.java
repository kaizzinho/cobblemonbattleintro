package com.kaizzinho.battleintroduction.mixin.client;

import com.cobblemon.mod.common.client.net.battle.BattleInitializeHandler;
import com.cobblemon.mod.common.net.messages.client.battle.BattleInitializePacket;
import com.kaizzinho.battleintroduction.client.BattleIntroClientCoordinator;
import com.kaizzinho.battleintroduction.client.BattleIntroOverlay;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BattleInitializeHandler.class, remap = false)
public abstract class BattleInitializeHandlerMixin {

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private void battleIntroduction$stageInitialize(
            BattleInitializePacket packet,
            MinecraftClient client,
            CallbackInfo ci
    ) {
        boolean introStarted =
                BattleIntroClientCoordinator.INSTANCE.onBattleInitialize(
                        packet,
                        client
                );

        if (!introStarted) {
            return;
        }

        ci.cancel();
        BattleIntroOverlay.INSTANCE.setPendingBattlePacket(
                () -> client.execute(
                        () -> BattleInitializeHandler.INSTANCE.handle(
                                packet,
                                client
                        )
                )
        );
    }
}
