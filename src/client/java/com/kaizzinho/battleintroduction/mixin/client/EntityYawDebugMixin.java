package com.kaizzinho.battleintroduction.mixin.client;

import com.kaizzinho.battleintroduction.client.BattleIntroOverlay;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(Entity.class)
public abstract class EntityYawDebugMixin {

    @Inject(
            method = "setYaw",
            at = @At("HEAD"),
            require = 0
    )
    private void battleIntroduction$traceSetYaw(
            float yaw,
            CallbackInfo ci
    ) {
        Entity self = (Entity) (Object) this;
        BattleIntroOverlay overlay = BattleIntroOverlay.INSTANCE;

        if (!overlay.shouldTraceBattlePokemon(self.getId())) {
            return;
        }

        overlay.traceRotationSetter(
                self.getId(),
                "yaw",
                self.getYaw(),
                yaw
        );
    }
}
