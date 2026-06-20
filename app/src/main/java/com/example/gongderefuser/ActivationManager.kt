package com.example.gongderefuser

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

data class ActivationResult(
    val success: Boolean,
    val message: String,
    val expiresAtMillis: Long? = null
)

object ActivationManager {
    private const val TAG = "ACTIVATION"
    private const val COLLECTION = "activation_codes"
    private const val DEFAULT_DURATION_DAYS = 7L
    private const val DAY_MILLIS = 24L * 60L * 60L * 1000L

    suspend fun activate(context: Context, inputCode: String): ActivationResult {
        val appContext = context.applicationContext
        val code = inputCode.trim().uppercase(Locale.US)
        val deviceId = DeviceIdManager.getDeviceId(appContext)
        Log.d(TAG, "activate inputCode=$code deviceId=$deviceId")

        if (code.isBlank()) {
            Log.d(TAG, "activation failed: blank code")
            return ActivationResult(false, "请输入激活码")
        }
        if (!ActivationLocalStore.isActivationRequired(appContext)) {
            return ActivationResult(true, "测试版不需要激活", null)
        }
        if (!NetworkUtil.isNetworkAvailable(appContext)) {
            Log.d(TAG, "activation failed: network unavailable")
            return ActivationResult(false, "需要网络才能激活，请连接网络后重试")
        }

        val firestore = FirebaseFirestore.getInstance()
        val docRef = firestore.collection(COLLECTION).document(code)
        val snapshot = try {
            docRef.get().awaitTask()
        } catch (throwable: Throwable) {
            Log.d(TAG, "firestore get failed: ${throwable.message}", throwable)
            return ActivationResult(false, "激活失败，请稍后重试")
        }

        Log.d(TAG, "firestore doc exists=${snapshot.exists()}")
        if (!snapshot.exists()) {
            return ActivationResult(false, "激活码不存在")
        }

        val used = snapshot.getBoolean("used") ?: false
        val remoteDeviceId = snapshot.getString("deviceId").orEmpty()
        val expiresAtMillis = snapshot.expiresAtMillis()
        Log.d(TAG, "query used=$used remoteDeviceId=$remoteDeviceId expiresAt=$expiresAtMillis")

        if (used && remoteDeviceId != deviceId) {
            Log.d(TAG, "activation failed: used by another device")
            return ActivationResult(false, "此激活码已被其他设备使用")
        }
        if (used && remoteDeviceId == deviceId) {
            if (expiresAtMillis > System.currentTimeMillis()) {
                val activatedAtMillis = snapshot.activatedAtMillis().ifZero { System.currentTimeMillis() }
                ActivationLocalStore.saveActivation(appContext, code, deviceId, activatedAtMillis, expiresAtMillis)
                Log.d(TAG, "activation restored expiresAt=$expiresAtMillis")
                return ActivationResult(true, "已恢复激活状态，有效期至 ${formatDateTime(expiresAtMillis)}", expiresAtMillis)
            }
            Log.d(TAG, "activation failed: code expired")
            return ActivationResult(false, "此激活码已过期，请使用新的激活码", expiresAtMillis.takeIf { it > 0L })
        }

        return try {
            val transactionResult = firestore.runTransaction { transaction ->
                val current = transaction.get(docRef)
                if (!current.exists()) throw ActivationFailure("激活码不存在")

                val transactionUsed = current.getBoolean("used") ?: false
                val transactionDeviceId = current.getString("deviceId").orEmpty()
                Log.d(TAG, "transaction used=$transactionUsed deviceId=$transactionDeviceId")
                if (transactionUsed) {
                    if (transactionDeviceId == deviceId) {
                        val currentExpiresAt = current.expiresAtMillis()
                        if (currentExpiresAt > System.currentTimeMillis()) {
                            return@runTransaction TransactionActivation(
                                code = code,
                                deviceId = deviceId,
                                activatedAtMillis = current.activatedAtMillis().ifZero { System.currentTimeMillis() },
                                expiresAtMillis = currentExpiresAt,
                                restored = true
                            )
                        }
                        throw ActivationFailure("此激活码已过期，请使用新的激活码")
                    }
                    throw ActivationFailure("此激活码已被其他设备使用")
                }

                val now = System.currentTimeMillis()
                val durationDays = (current.get("durationDays") as? Number)?.toLong()
                    ?: DEFAULT_DURATION_DAYS
                val expiresAt = now + durationDays.coerceAtLeast(1L) * DAY_MILLIS
                transaction.update(
                    docRef,
                    mapOf(
                        "code" to code,
                        "used" to true,
                        "deviceId" to deviceId,
                        "activatedAt" to FieldValue.serverTimestamp(),
                        "expiresAt" to Timestamp(Date(expiresAt)),
                        "appPackage" to appContext.packageName,
                        "usedByAppVersion" to appVersionLabel(appContext)
                    )
                )
                TransactionActivation(
                    code = code,
                    deviceId = deviceId,
                    activatedAtMillis = now,
                    expiresAtMillis = expiresAt,
                    restored = false
                )
            }.awaitTask()

            ActivationLocalStore.saveActivation(
                context = appContext,
                currentCode = transactionResult.code,
                deviceId = transactionResult.deviceId,
                activatedAtMillis = transactionResult.activatedAtMillis,
                expiresAtMillis = transactionResult.expiresAtMillis
            )
            val messagePrefix = if (transactionResult.restored) "已恢复激活状态" else "激活成功"
            Log.d(TAG, "$messagePrefix expiresAt=${transactionResult.expiresAtMillis}")
            ActivationResult(
                success = true,
                message = "$messagePrefix，有效期至 ${formatDateTime(transactionResult.expiresAtMillis)}",
                expiresAtMillis = transactionResult.expiresAtMillis
            )
        } catch (failure: ActivationFailure) {
            Log.d(TAG, "activation failed: ${failure.message}")
            ActivationResult(false, failure.message.orEmpty())
        } catch (throwable: Throwable) {
            Log.d(TAG, "transaction failed: ${throwable.message}", throwable)
            ActivationResult(false, "激活失败，请稍后重试")
        }
    }

    fun formatDateTime(millis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
    }

    private fun DocumentSnapshot.expiresAtMillis(): Long {
        return getTimestamp("expiresAt")?.toDate()?.time ?: 0L
    }

    private fun DocumentSnapshot.activatedAtMillis(): Long {
        return getTimestamp("activatedAt")?.toDate()?.time ?: 0L
    }

    private fun appVersionLabel(context: Context): String {
        return runCatching {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "unknown"
        }.getOrDefault("unknown")
    }

    private inline fun Long.ifZero(defaultValue: () -> Long): Long {
        return if (this == 0L) defaultValue() else this
    }

    private class ActivationFailure(message: String) : RuntimeException(message)

    private data class TransactionActivation(
        val code: String,
        val deviceId: String,
        val activatedAtMillis: Long,
        val expiresAtMillis: Long,
        val restored: Boolean
    )
}

private suspend fun <T> Task<T>.awaitTask(): T {
    return suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result ->
            continuation.resume(result)
        }
        addOnFailureListener { exception ->
            continuation.resumeWithException(exception)
        }
        addOnCanceledListener {
            continuation.cancel()
        }
    }
}
