package com.example.gongderefuser

import android.content.Intent

object CaptureHolder {
    var resultCode: Int = 0
    var data: Intent? = null

    fun clear() {
        resultCode = 0
        data = null
    }
}
