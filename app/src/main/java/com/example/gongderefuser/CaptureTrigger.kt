package com.example.gongderefuser

object CaptureTrigger {

    // 是否触发截屏
    @Volatile
    var shouldCapture: Boolean = false

    // 一次訂單畫面变化后连续尝试几次，避免太早截到首页而错过订单弹窗
    @Volatile
    var pendingCaptureCount: Int = 0

    // 防止连续触发
    @Volatile
    var lastTriggerTime: Long = 0
}
