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
    const val DEFAULT_TARGET_HOURLY = 245

    /**
     * 个人目标元/公里预设值。
     */
    const val DEFAULT_TARGET_YUAN_PER_KM = 15.0

    /**
     * 个人目标平均单价预设值。
     */
    const val DEFAULT_TARGET_AVERAGE_PRICE = 45.0

    /**
     * 达到目标值时对应的评分基准分。
     */
    const val DEFAULT_SCORE_BASE = 80

    /**
     * 每单加价金额预设值。
     */
    const val DEFAULT_SUBSIDY_PER_ORDER = 0

    /**
     * 肥单最低金额预设值。
     */
    const val DEFAULT_FAT_ORDER_MIN_AMOUNT = 100

}
