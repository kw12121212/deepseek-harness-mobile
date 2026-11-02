package app.deepseek.harness.mobile

import org.json.JSONObject

internal data class GoalInfo(
    val id: String,
    val revision: Long,
    val phase: String,
    val objective: String,
    val maxGoalRounds: Int,
    val roundsStarted: Int,
    val activation: String,
)

internal fun parseGoal(value: Any?): GoalInfo? {
    if (value == null || value === JSONObject.NULL) return null
    val goal = value as? JSONObject ?: throw IllegalArgumentException("Invalid goal response")
    return GoalInfo(
        id = goal.getString("id"),
        revision = goal.getLong("revision"),
        phase = goal.getString("phase"),
        objective = goal.getString("objective"),
        maxGoalRounds = goal.getInt("maxGoalRounds"),
        roundsStarted = goal.getInt("roundsStarted"),
        activation = goal.getString("activation"),
    )
}

internal class GoalState {
    var sessionId: String? = null
        private set
    var goal: GoalInfo? = null
        private set
    private var generation = 0

    fun select(session: String?): Int {
        sessionId = session
        goal = null
        return ++generation
    }

    fun beginRefresh(): Int = ++generation

    fun accept(session: String, request: Int, value: GoalInfo?) {
        if (sessionId == session && generation == request) goal = value
    }
}
