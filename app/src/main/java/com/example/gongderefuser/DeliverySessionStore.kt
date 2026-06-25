package com.example.gongderefuser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object DeliverySessionStore {
    private const val PREF_NAME = "delivery_sessions"
    private const val KEY_SESSIONS = "sessions"
    private const val STATUS_ACCEPTED = "ACCEPTED"
    private const val STATUS_COMPLETED = "COMPLETED"
    private const val DEDUPE_WINDOW_MS = 30_000L
    private const val MAX_SESSIONS = 20

    data class Result(
        val changed: Boolean,
        val sessionId: Long = 0L,
        val status: String = "",
        val expectedDeliveryCount: Int = 0,
        val completedPickupCount: Int = 0,
        val completedDeliveryCount: Int = 0,
        val opportunityCount: Int = 0,
        val completedRecordTimestamps: List<Long> = emptyList(),
        val reason: String = ""
    )

    fun addAcceptedOpportunity(
        context: Context,
        recordTimestamp: Long,
        acceptedAt: Long,
        orderSignature: String,
        price: Int,
        minutes: Int,
        distance: Double,
        merchant: String,
        address: String,
        deliveryCount: Int
    ): Result {
        val sessions = load(context).toMutableList()
        val active = sessions.firstOrNull { it.status != STATUS_COMPLETED }
        val session = active ?: Session(
            sessionId = System.currentTimeMillis(),
            startedAt = acceptedAt,
            status = STATUS_ACCEPTED
        ).also { sessions.add(0, it) }
        if (session.opportunities.any { it.recordTimestamp == recordTimestamp }) {
            save(context, sessions)
            return session.toResult(changed = false, reason = "OPPORTUNITY_ALREADY_EXISTS")
        }
        session.opportunities.add(
            Opportunity(
                recordTimestamp = recordTimestamp,
                acceptedAt = acceptedAt,
                orderSignature = orderSignature,
                price = price,
                minutes = minutes,
                distance = distance,
                merchant = merchant,
                address = address,
                deliveryCount = deliveryCount.coerceAtLeast(1)
            )
        )
        session.expectedDeliveryCount = session.opportunities.sumOf { it.deliveryCount }
        save(context, sessions)
        return session.toResult(changed = true, reason = if (active == null) "NEW_SESSION" else "JOIN_ACTIVE_SESSION")
    }

    fun markPickupCompleted(context: Context, timestamp: Long): Result {
        val sessions = load(context).toMutableList()
        val session = sessions.firstOrNull { it.status != STATUS_COMPLETED }
            ?: return Result(changed = false, reason = "NO_ACTIVE_SESSION")
        if (timestamp - session.lastPickupCompletedAt < DEDUPE_WINDOW_MS) {
            return session.toResult(changed = false, reason = "PICKUP_DEDUPE")
        }
        if (session.completedPickupCount >= session.expectedDeliveryCount) {
            return session.toResult(changed = false, reason = "PICKUP_COUNT_FULL")
        }
        session.completedPickupCount += 1
        session.lastPickupCompletedAt = timestamp
        save(context, sessions)
        return session.toResult(changed = true, reason = "PICKUP_COMPLETED")
    }

    fun markDeliveryCandidate(context: Context, flow: String, timestamp: Long): Result {
        val sessions = load(context).toMutableList()
        val session = sessions.firstOrNull { it.status != STATUS_COMPLETED }
            ?: return Result(changed = false, reason = "NO_ACTIVE_SESSION")
        session.lastDeliveryCandidateFlow = flow
        session.lastDeliveryCandidateAt = timestamp
        save(context, sessions)
        return session.toResult(changed = true, reason = "DELIVERY_CANDIDATE")
    }

    fun activeDeliveryCandidate(context: Context, now: Long): Pair<String, Long>? {
        val session = load(context).firstOrNull { it.status != STATUS_COMPLETED } ?: return null
        val at = session.lastDeliveryCandidateAt
        val flow = session.lastDeliveryCandidateFlow
        if (at <= 0L || flow.isBlank()) return null
        if (now - at > 120_000L) return null
        return flow to at
    }

    fun markDeliveryCompleted(context: Context, timestamp: Long): Result {
        val sessions = load(context).toMutableList()
        val session = sessions.firstOrNull { it.status != STATUS_COMPLETED }
            ?: return Result(changed = false, reason = "NO_ACTIVE_SESSION")
        if (timestamp - session.lastDeliveryCompletedAt < DEDUPE_WINDOW_MS) {
            return session.toResult(changed = false, reason = "DELIVERY_DEDUPE")
        }
        if (session.completedDeliveryCount >= session.expectedDeliveryCount) {
            return session.toResult(changed = false, reason = "DELIVERY_COUNT_FULL")
        }
        session.completedDeliveryCount += 1
        session.lastDeliveryCompletedAt = timestamp
        session.lastDeliveryCandidateFlow = ""
        session.lastDeliveryCandidateAt = 0L
        if (session.completedDeliveryCount >= session.expectedDeliveryCount) {
            session.status = STATUS_COMPLETED
            session.completedAt = timestamp
        }
        save(context, sessions)
        return session.toResult(
            changed = true,
            reason = if (session.status == STATUS_COMPLETED) "SESSION_COMPLETED" else "DELIVERY_COMPLETED",
            completedRecordTimestamps = if (session.status == STATUS_COMPLETED) {
                session.opportunities.map { it.recordTimestamp }
            } else {
                emptyList()
            }
        )
    }

    private fun load(context: Context): List<Session> {
        val raw = prefs(context).getString(KEY_SESSIONS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(Session.fromJson(item))
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun save(context: Context, sessions: List<Session>) {
        val array = JSONArray()
        sessions.take(MAX_SESSIONS).forEach { array.put(it.toJson()) }
        prefs(context).edit().putString(KEY_SESSIONS, array.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private data class Session(
        val sessionId: Long,
        val startedAt: Long,
        var expectedDeliveryCount: Int = 0,
        var completedPickupCount: Int = 0,
        var completedDeliveryCount: Int = 0,
        var status: String = STATUS_ACCEPTED,
        var completedAt: Long = 0L,
        var lastPickupCompletedAt: Long = 0L,
        var lastDeliveryCompletedAt: Long = 0L,
        var lastDeliveryCandidateFlow: String = "",
        var lastDeliveryCandidateAt: Long = 0L,
        val opportunities: MutableList<Opportunity> = mutableListOf()
    ) {
        fun toResult(changed: Boolean, reason: String, completedRecordTimestamps: List<Long> = emptyList()): Result {
            return Result(
                changed = changed,
                sessionId = sessionId,
                status = status,
                expectedDeliveryCount = expectedDeliveryCount,
                completedPickupCount = completedPickupCount,
                completedDeliveryCount = completedDeliveryCount,
                opportunityCount = opportunities.size,
                completedRecordTimestamps = completedRecordTimestamps,
                reason = reason
            )
        }

        fun toJson(): JSONObject {
            return JSONObject()
                .put("sessionId", sessionId)
                .put("startedAt", startedAt)
                .put("expectedDeliveryCount", expectedDeliveryCount)
                .put("completedPickupCount", completedPickupCount)
                .put("completedDeliveryCount", completedDeliveryCount)
                .put("status", status)
                .put("completedAt", completedAt)
                .put("lastPickupCompletedAt", lastPickupCompletedAt)
                .put("lastDeliveryCompletedAt", lastDeliveryCompletedAt)
                .put("lastDeliveryCandidateFlow", lastDeliveryCandidateFlow)
                .put("lastDeliveryCandidateAt", lastDeliveryCandidateAt)
                .put("opportunities", JSONArray().also { array ->
                    opportunities.forEach { array.put(it.toJson()) }
                })
        }

        companion object {
            fun fromJson(item: JSONObject): Session {
                val opportunities = mutableListOf<Opportunity>()
                val array = item.optJSONArray("opportunities") ?: JSONArray()
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { opportunities.add(Opportunity.fromJson(it)) }
                }
                return Session(
                    sessionId = item.optLong("sessionId"),
                    startedAt = item.optLong("startedAt"),
                    expectedDeliveryCount = item.optInt("expectedDeliveryCount"),
                    completedPickupCount = item.optInt("completedPickupCount"),
                    completedDeliveryCount = item.optInt("completedDeliveryCount"),
                    status = item.optString("status", STATUS_ACCEPTED),
                    completedAt = item.optLong("completedAt"),
                    lastPickupCompletedAt = item.optLong("lastPickupCompletedAt"),
                    lastDeliveryCompletedAt = item.optLong("lastDeliveryCompletedAt"),
                    lastDeliveryCandidateFlow = item.optString("lastDeliveryCandidateFlow"),
                    lastDeliveryCandidateAt = item.optLong("lastDeliveryCandidateAt"),
                    opportunities = opportunities
                )
            }
        }
    }

    private data class Opportunity(
        val recordTimestamp: Long,
        val acceptedAt: Long,
        val orderSignature: String,
        val price: Int,
        val minutes: Int,
        val distance: Double,
        val merchant: String,
        val address: String,
        val deliveryCount: Int
    ) {
        fun toJson(): JSONObject {
            return JSONObject()
                .put("recordTimestamp", recordTimestamp)
                .put("acceptedAt", acceptedAt)
                .put("orderSignature", orderSignature)
                .put("price", price)
                .put("minutes", minutes)
                .put("distance", distance)
                .put("merchant", merchant)
                .put("address", address)
                .put("deliveryCount", deliveryCount)
        }

        companion object {
            fun fromJson(item: JSONObject): Opportunity {
                return Opportunity(
                    recordTimestamp = item.optLong("recordTimestamp"),
                    acceptedAt = item.optLong("acceptedAt"),
                    orderSignature = item.optString("orderSignature"),
                    price = item.optInt("price"),
                    minutes = item.optInt("minutes"),
                    distance = item.optDouble("distance"),
                    merchant = item.optString("merchant"),
                    address = item.optString("address"),
                    deliveryCount = item.optInt("deliveryCount", 1)
                )
            }
        }
    }
}
