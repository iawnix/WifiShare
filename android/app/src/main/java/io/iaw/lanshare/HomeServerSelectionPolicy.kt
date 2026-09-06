package io.iaw.lanshare

enum class HomeServerSelectionDecision {
    SELECT,
    ALREADY_ACTIVE,
    BLOCKED_BY_TRANSFER,
}

object HomeServerSelectionPolicy {
    fun decide(
        activeProfileId: String?,
        requestedProfileId: String,
        transferActive: Boolean,
    ): HomeServerSelectionDecision {
        if (activeProfileId == requestedProfileId) {
            return HomeServerSelectionDecision.ALREADY_ACTIVE
        }
        if (transferActive) {
            return HomeServerSelectionDecision.BLOCKED_BY_TRANSFER
        }
        return HomeServerSelectionDecision.SELECT
    }
}
