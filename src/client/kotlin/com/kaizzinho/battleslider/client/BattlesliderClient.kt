package com.kaizzinho.battleslider.client

import net.fabricmc.api.ClientModInitializer

class BattlesliderClient : ClientModInitializer {

    override fun onInitializeClient() {
        BattleHandler.register()      // subscribe to CobblemonEvents
        BattleIntroOverlay.register() // register HudRenderCallback
    }
}