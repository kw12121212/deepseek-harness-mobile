package app.deepseek.harness.mobile

internal enum class SessionMenuDecision { HIDE, RESOLVE, SHOW }

internal fun sessionMenuDecision(origin: String?, addressKind: String?): SessionMenuDecision = when {
    origin == "subagent" || addressKind == "subagent" -> SessionMenuDecision.HIDE
    addressKind == null -> SessionMenuDecision.RESOLVE
    else -> SessionMenuDecision.SHOW
}
