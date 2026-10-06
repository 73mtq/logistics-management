package com.logisticsmanagement.app.network

import java.util.Locale

object CarrierCodes {
    fun resolve(carrierName: String): String? = codes[carrierName.trim().uppercase(Locale.ROOT)]

    // 编码来自快递100官方快递公司编码表。
    private val codes = mapOf(
        "顺丰" to "shunfeng",
        "顺丰速运" to "shunfeng",
        "顺丰快递" to "shunfeng",
        "SHUNFENG" to "shunfeng",
        "中通" to "zhongtong",
        "中通快递" to "zhongtong",
        "ZHONGTONG" to "zhongtong",
        "圆通" to "yuantong",
        "圆通速递" to "yuantong",
        "圆通快递" to "yuantong",
        "YUANTONG" to "yuantong",
        "申通" to "shentong",
        "申通快递" to "shentong",
        "SHENTONG" to "shentong",
        "韵达" to "yunda",
        "韵达快递" to "yunda",
        "YUNDA" to "yunda",
        "极兔" to "jtexpress",
        "极兔速递" to "jtexpress",
        "极兔快递" to "jtexpress",
        "JTEXPRESS" to "jtexpress",
        "京东" to "jd",
        "京东物流" to "jd",
        "京东快递" to "jd",
        "JD" to "jd",
        "邮政快递包裹" to "youzhengguonei",
        "YOUZHENGGUONEI" to "youzhengguonei",
        "EMS" to "ems",
    )
}
