package org.polyfrost.smartsearch.event

import org.polyfrost.oneconfig.api.event.v1.EventManager

object EventListener {
    fun register() {
        EventManager.INSTANCE.register(this)
    }
}