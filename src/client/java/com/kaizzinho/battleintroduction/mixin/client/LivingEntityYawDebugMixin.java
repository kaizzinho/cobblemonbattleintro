package com.kaizzinho.battleintroduction.mixin.client;

import com.kaizzinho.battleintroduction.client.BattleIntroOverlay;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(LivingEntity.class)
public abstract class LivingEntityYawDebugMixin {

    @Inject(
            method = "setBodyYaw",
            at = @At("HEAD"),
            require = 0
    )
    private void battleIntroduction$traceSetBodyYaw(
            float yaw,
            CallbackInfo ci
    ) {
        LivingEntity self = (LivingEntity) (Object) this;
        BattleIntroOverlay overlay = BattleIntroOverlay.INSTANCE;

        if (!overlay.shouldTraceBattlePokemon(self.getId())) {
            return;
        }

        overlay.traceRotationSetter(
                self.getId(),
                "body",
                self.getBodyYaw(),
                yaw
        );
    }


    @Inject(
            method = "setHeadYaw",
            at = @At("HEAD"),
            require = 0
    )
    private void battleIntroduction$traceSetHeadYaw(
            float yaw,
            CallbackInfo ci
    ) {
        LivingEntity self = (LivingEntity) (Object) this;
        BattleIntroOverlay overlay = BattleIntroOverlay.INSTANCE;

        if (!overlay.shouldTraceBattlePokemon(self.getId())) {
            return;
        }

        overlay.traceRotationSetter(
                self.getId(),
                "head",
                self.getHeadYaw(),
                yaw
        );
    }
}
