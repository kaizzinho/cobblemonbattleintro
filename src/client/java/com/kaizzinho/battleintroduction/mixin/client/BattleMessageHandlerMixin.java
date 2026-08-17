package com.kaizzinho.battleintroduction.mixin.client;

import com.cobblemon.mod.common.client.net.battle.BattleMessageHandler;
import com.cobblemon.mod.common.net.messages.client.battle.BattleMessagePacket;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(value = BattleMessageHandler.class, remap = false)
public abstract class BattleMessageHandlerMixin {

    @Inject(method = "handle", at = @At("HEAD"))
    private void allowImmediateMessage(
            BattleMessagePacket packet,
            MinecraftClient client,
            CallbackInfo ci
    ) {

    }
}
