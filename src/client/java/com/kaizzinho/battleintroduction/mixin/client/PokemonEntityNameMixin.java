package com.kaizzinho.battleintroduction.mixin.client;

import com.cobblemon.mod.common.client.render.pokemon.PokemonRenderer;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.kaizzinho.battleintroduction.client.BattleIntroOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = PokemonRenderer.class, remap = false)


public abstract class PokemonEntityNameMixin {

    @Inject(
        method = "shouldRenderLabel(Lcom/cobblemon/mod/common/entity/pokemon/PokemonEntity;)Z",
        at = @At("HEAD"),
        cancellable = true,
        require = 0,
        remap = false
    )
    private void battleintroduction$hidePokemonNameDuringIntro(
        PokemonEntity entity,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (BattleIntroOverlay.INSTANCE.isVisualTransitionActive()) {
            cir.setReturnValue(false);
        }
    }
}
