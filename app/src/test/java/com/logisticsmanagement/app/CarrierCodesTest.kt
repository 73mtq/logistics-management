package com.logisticsmanagement.app

import com.logisticsmanagement.app.network.CarrierCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarrierCodesTest {
    @Test fun resolvesCarriersShownOnLogisticsPage() {
        assertEquals("yunda", CarrierCodes.resolve("韵达快递"))
        assertEquals("yunda", CarrierCodes.resolve("韵达"))
        assertEquals("shunfeng", CarrierCodes.resolve("顺丰速运"))
        assertEquals("zhongtong", CarrierCodes.resolve("中通快递"))
        assertEquals("yuantong", CarrierCodes.resolve("圆通速递"))
        assertEquals("jtexpress", CarrierCodes.resolve("极兔速递"))
        assertNull(CarrierCodes.resolve("尚未支持的承运商"))
    }
}
