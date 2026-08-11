package com.kaizzinho.battleslider.mixin.client;

import com.cobblemon.mod.common.client.net.battle.BattleInitializeHandler;
import com.cobblemon.mod.common.net.messages.client.battle.BattleInitializePacket;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(value = BattleInitializeHandler.class, remap = false)
public abstract class BattleInitializeHandlerMixin {

    @Inject(method = "handle", at = @At("HEAD"))
    private void allowImmediateInitialization(
            BattleInitializePacket packet,
            MinecraftClient client,
            CallbackInfo ci
    ) {

    }
}
