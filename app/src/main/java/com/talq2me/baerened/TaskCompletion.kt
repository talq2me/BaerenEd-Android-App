package com.talq2me.baerened

import android.content.Context

/**
 * Write path for task/game completion: ordered RPCs + single DB refetch (see [DailyProgressManager.applyRpcChainThenRefetch]).
 * Thin facade so Activities can depend on a dedicated name instead of the large [DailyProgressManager] surface.
 */
object TaskCompletion {

    suspend fun applyRpcChainThenRefetch(
        context: Context,
        updates: List<DailyProgressManager.SingleItemUpdate>
    ): Result<Unit> {
        return DailyProgressManager(context).applyRpcChainThenRefetch(updates)
    }
}
