package org.polyfrost.smartsearch.entry

import net.fabricmc.api.ClientModInitializer
import org.polyfrost.smartsearch.SmartSearchClient

class FabricEntryPoint : ClientModInitializer {
    override fun onInitializeClient() {
        SmartSearchClient.init()
    }
}