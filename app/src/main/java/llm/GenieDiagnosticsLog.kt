package com.example.cafe_kiosk_ai.llm

import android.util.Log
import kotlinx.coroutines.CancellationException

internal class GenieStageException(val stage: String, cause: Throwable) :
    IllegalStateException("${cause.javaClass.simpleName}: ${cause.message.orEmpty()}", cause)

internal inline fun <T> genieStage(stage: String, action: () -> T): T {
    Log.i(GENIE_TAG, "$stage START")
    try {
        return action().also { Log.i(GENIE_TAG, "$stage PASS") }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        throw GenieStageException(stage, error)
    } catch (error: LinkageError) {
        throw GenieStageException(stage, error)
    }
}
