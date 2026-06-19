package com.example.gongderefuser.model

/**
 * 訂單资料模型
 */
data class OrderData(

    // 订单金额
    val price: Int = 0,

    // 预计时间（分钟）
    val minutes: Int = 0,

    // 总距离（公里）
    val distance: Double = 0.0,

    // 是否叠单
    val isStackOrder: Boolean = false,

    // 是否取货或配送地点相同的夹单
    val isSameLocationStack: Boolean = false,

    // 同地點配送 OCR 原文
    val sameDropoffText: String = "",

    // 是否命中同地點配送文字
    val sameDropoffMatched: Boolean = false,

    // 外送数量，普通单为1，夹单通常为2以上
    val deliveryCount: Int = 1,

    // 是否独享订单
    val isExclusive: Boolean = false,

    // 是否识别到接單弹窗特征
    val isTargetOffer: Boolean = false,

    // 是否为已接单后的新增外送订单
    val isAddOnOrder: Boolean = false,

    // 店家名称
    val storeName: String = "",

    // 店家地址
    val address: String = "",

    // 地址最终来源：ADDRESS / ADDRESS_WIDE / ADDRESS_LOWER
    val addressSource: String = "",

    val priceStatus: String = "OK",
    val tripStatus: String = "OK",
    val merchantStatus: String = "OK",
    val addressStatus: String = "OK",
    val typeStatus: String = "OK"
)
