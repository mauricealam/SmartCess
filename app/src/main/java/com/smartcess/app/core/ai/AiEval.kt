package com.smartcess.app.core.ai

import javax.inject.Inject
import javax.inject.Singleton

/** AI eval hook: samples recorded for the eval suites (M7: unenforced threshold constants removed). */
data class EvalSample(
    val feature: String,
    val promptVersion: String,
    val success: Boolean,
    val latencyMillis: Long,
    /** Metadata only (field names, confidence); never raw text or images. */
    val metadata: Map<String, String> = emptyMap(),
)

interface AiEvalLogger {
    fun log(sample: EvalSample)
    fun samples(): List<EvalSample>
}

@Singleton
class InMemoryAiEvalLogger @Inject constructor() : AiEvalLogger {
    private val lock = Any()
    private val buffer = ArrayDeque<EvalSample>(200)

    override fun log(sample: EvalSample) = synchronized(lock) {
        if (buffer.size >= 200) buffer.removeFirst()
        buffer.addLast(sample)
    }

    override fun samples(): List<EvalSample> = synchronized(lock) { buffer.toList() }
}
