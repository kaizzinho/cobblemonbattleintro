package com.kaizzinho.battleslider.client.compat

import com.kaizzinho.battleslider.client.config.BattleSliderConfigScreen
import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi


// loaded only when mod menu is around
class BattlesliderModMenuIntegration : ModMenuApi {

    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> =
        ConfigScreenFactory { parent ->
            BattleSliderConfigScreen(parent)
        }
}
