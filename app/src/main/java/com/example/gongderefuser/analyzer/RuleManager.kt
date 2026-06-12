package com.example.gongderefuser.analyzer

/**
 * 接單规则管理器
 */
object RuleManager {

    /**
     * 每公里营运总成本：电费 + 车损。
     */
    const val COST_PER_KM = 1.5

    /**
     * 台湾法定基本时薪。
     */
    const val LEGAL_MIN_HOURLY = 196

    /**
     * 个人目标时薪预设值。
     */
    const val DEFAULT_TARGET_HOURLY = 220
}
