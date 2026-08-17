package com.kaizzinho.battleintroduction.client.compat

import com.kaizzinho.battleintroduction.client.config.BattleIntroductionConfigScreen
import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi


// loaded only when mod menu is around
class BattleIntroductionModMenuIntegration : ModMenuApi {

    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> =
        ConfigScreenFactory { parent ->
            BattleIntroductionConfigScreen(parent)
        }
}
