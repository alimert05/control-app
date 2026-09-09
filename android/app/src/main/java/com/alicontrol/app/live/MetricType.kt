package com.alicontrol.app.live

/**
 * Which live reading a METRIC/GAUGE widget shows.
 *
 * Resolved from the widget's configured label rather than being stored as a
 * separate field, deliberately: the widget picker offers a fixed set of
 * readings ("CPU", "GPU", "RAM") and that label *is* the reading's identity.
 * Deriving it here means the persisted dashboard schema is untouched by
 * TASK-009 — layout state and live state stay completely separate concerns,
 * with no migration and no telemetry leaking into the saved layout.
 */
enum class MetricType(val unit: MetricUnit) {
    CPU(MetricUnit.PERCENT),
    MEMORY(MetricUnit.PERCENT),
    GPU(MetricUnit.PERCENT),
    CPU_TEMPERATURE(MetricUnit.CELSIUS),
    GPU_TEMPERATURE(MetricUnit.CELSIUS),

    /** A label this build has no live source for — always renders unavailable. */
    UNKNOWN(MetricUnit.PERCENT);

    /** The current value for this reading, or null when the desktop can't measure it. */
    fun valueIn(metrics: SystemMetrics): Int? = when (this) {
        CPU -> metrics.cpuPercent
        MEMORY -> metrics.memoryPercent
        GPU -> metrics.gpuPercent
        CPU_TEMPERATURE -> metrics.cpuTemperatureCelsius
        GPU_TEMPERATURE -> metrics.gpuTemperatureCelsius
        UNKNOWN -> null
    }

    companion object {
        fun forLabel(label: String): MetricType = when (label.trim().uppercase()) {
            "CPU" -> CPU
            "RAM", "MEMORY" -> MEMORY
            "GPU" -> GPU
            "CPU TEMP", "CPU TEMPERATURE" -> CPU_TEMPERATURE
            "GPU TEMP", "GPU TEMPERATURE" -> GPU_TEMPERATURE
            else -> UNKNOWN
        }
    }
}

enum class MetricUnit {
    PERCENT,
    CELSIUS
}
