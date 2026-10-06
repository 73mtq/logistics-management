package com.logisticsmanagement.app

import com.logisticsmanagement.app.data.LogisticsRepository
import com.logisticsmanagement.app.model.Platform
import org.junit.Assert.*
import org.junit.Test

class IdentityTest {
    @Test fun platformKeysAreStableAndIndependent() {
        val keys = Platform.entries.map { LogisticsRepository.stableId(it.name) }
        assertEquals(Platform.entries.size, keys.toSet().size)
        Platform.entries.forEach { platform ->
            assertEquals(64, LogisticsRepository.stableId(platform.name).length)
        }
    }
    @Test fun sourceComponentsPreserveOrder() {
        assertNotEquals(
            LogisticsRepository.stableId(Platform.JD.name, Platform.PDD.name),
            LogisticsRepository.stableId(Platform.PDD.name, Platform.JD.name),
        )
        assertNotEquals(
            LogisticsRepository.stableId(Platform.JD.name, Platform.PDD.name),
            LogisticsRepository.stableId(Platform.JD.name + Platform.PDD.name),
        )
    }
}
