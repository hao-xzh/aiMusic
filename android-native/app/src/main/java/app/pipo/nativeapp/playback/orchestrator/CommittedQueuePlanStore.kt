package app.pipo.nativeapp.playback.orchestrator

object CommittedQueuePlanStore {
    @Volatile
    private var current: CommittedQueuePlan? = null

    fun set(plan: CommittedQueuePlan) {
        current = plan
    }

    fun clear(queueVersion: Long? = null) {
        if (queueVersion == null || current?.queueVersion == queueVersion) {
            current = null
        }
    }

    fun current(): CommittedQueuePlan? = current

    fun transitionFor(
        queueVersion: Long,
        fromTrackId: String,
        toTrackId: String,
    ): TransitionPlan? {
        val plan = current ?: return null
        if (plan.queueVersion != queueVersion) return null
        return plan.transitionPlans.firstOrNull {
            it.queueVersion == queueVersion &&
                it.fromTrackId == fromTrackId &&
                it.toTrackId == toTrackId
        }
    }
}
