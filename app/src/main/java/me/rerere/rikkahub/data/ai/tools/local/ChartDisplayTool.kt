package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.PromptRuntime

private val CHART_STYLES = listOf("line", "bar", "scatter")
private val AXIS_SCALES = listOf("linear", "log")
private const val MAX_SERIES = 12
private const val MAX_SERIES_POINTS = 2000
private val HEX_COLOR_REGEX = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$")

internal fun buildChartDisplayTool(): Tool = Tool(
    name = "chart_display",
    description = """
        Display a line, bar, or scatter chart to the user inside the chat.
        For line and bar charts, every series must use 'values' (numbers aligned by position with x_axis.data)
        and must not use 'points'. For scatter charts, every series must use 'points' ({x, y} numbers)
        and must not use 'values'.
        Bar charts always start from 0, so axis 'min'/'max' are not allowed for bar charts.
        For line and bar charts the X axis is categorical: x_axis only supports 'title' and 'data',
        so x_axis 'min'/'max' and log scale are not allowed; use y_axis for range and log scale.
        Axis 'data' labels are only allowed on x_axis for line and bar charts.
        At most $MAX_SERIES series, and at most $MAX_SERIES_POINTS values/points per series.
        The chart is rendered for the user directly; you don't need to repeat the raw data in your reply.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("style", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { CHART_STYLES.forEach { add(it) } })
                    put("description", "Chart type.")
                })
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "Chart title, shown at the top.")
                })
                put("x_axis", buildAxisSchema())
                put("y_axis", buildAxisSchema())
                put("series", buildJsonObject {
                    put("type", "array")
                    put("description", "One or more data series (1-$MAX_SERIES).")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("name", buildJsonObject {
                                put("type", "string")
                                put("description", "Series name; used for the legend when there are multiple series.")
                            })
                            put("color", buildJsonObject {
                                put("type", "string")
                                put("description", "Hex color such as '#FF8800' or '#F80'. Some clients may ignore it.")
                            })
                            put("values", buildJsonObject {
                                put("type", "array")
                                put("items", buildJsonObject { put("type", "number") })
                                put(
                                    "description",
                                    "1D data for line/bar charts, aligned by position with x_axis.data."
                                )
                            })
                            put("points", buildJsonObject {
                                put("type", "array")
                                put("description", "2D data points for scatter charts.")
                                put("items", buildJsonObject {
                                    put("type", "object")
                                    put("properties", buildJsonObject {
                                        put("x", buildJsonObject { put("type", "number") })
                                        put("y", buildJsonObject { put("type", "number") })
                                    })
                                    put("required", buildJsonArray {
                                        add("x")
                                        add("y")
                                    })
                                })
                            })
                        })
                    })
                })
            },
            required = listOf("style", "series")
        )
    },
    execute = { args ->
        val error = validateChartArgs(args.jsonObject)
        val payload = if (error != null) {
            buildJsonObject {
                put("error", "INVALID_CHART")
                put("message", error)
            }
        } else {
            buildJsonObject {
                put("success", true)
                put("message", PromptRuntime.text("chart.displayed"))
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

private fun buildAxisSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        put("title", buildJsonObject {
            put("type", "string")
            put("description", "Axis title, usually including the unit.")
        })
        put("data", buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject { put("type", "string") })
            put(
                "description",
                "Category labels (e.g. months) for line/bar charts, aligned with series values. " +
                    "Only allowed on x_axis, and not for scatter charts."
            )
        })
        put("min", buildJsonObject {
            put("type", "number")
            put(
                "description",
                "Lower bound of the display range. Allowed on y_axis for line charts and on both axes for scatter charts."
            )
        })
        put("max", buildJsonObject {
            put("type", "number")
            put(
                "description",
                "Upper bound of the display range. Allowed on y_axis for line charts and on both axes for scatter charts."
            )
        })
        put("scale", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { AXIS_SCALES.forEach { add(it) } })
            put(
                "description",
                "Scale type, default linear. Log is allowed on y_axis for all charts and on x_axis for scatter charts only."
            )
        })
        put("format", buildJsonObject {
            put("type", "string")
            put(
                "description",
                "Label format: f-style for numbers (e.g. '.1f'), strftime-style for dates (e.g. '%Y-%m'). " +
                    "Some clients may ignore it."
            )
        })
    })
}

private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun validateChartArgs(params: JsonObject): String? {
    val style = params.string("style")
    if (style !in CHART_STYLES) {
        return PromptRuntime.text("chart.styleInvalid", CHART_STYLES.joinToString())
    }

    val axes = mutableMapOf<String, JsonObject>()
    for (axisName in listOf("x_axis", "y_axis")) {
        val element = params[axisName] ?: continue
        val axis = element as? JsonObject
            ?: return PromptRuntime.text("chart.axisNotObject", axisName)
        validateAxis(axisName, axis, style!!)?.let { return it }
        axes[axisName] = axis
    }

    val series = params["series"] as? JsonArray
    if (series.isNullOrEmpty()) return PromptRuntime.text("chart.seriesEmpty")
    if (series.size > MAX_SERIES) {
        return PromptRuntime.text("chart.seriesTooMany", MAX_SERIES.toString())
    }

    val xLabels = axes["x_axis"]?.get("data") as? JsonArray
    val xLog = axes["x_axis"]?.string("scale") == "log"
    val yLog = axes["y_axis"]?.string("scale") == "log"

    series.forEachIndexed { index, element ->
        val item = element as? JsonObject
            ?: return PromptRuntime.text("chart.seriesNotObject", index.toString())
        val color = item["color"]
        if (color != null) {
            val colorText = (color as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            if (colorText == null || !HEX_COLOR_REGEX.matches(colorText)) {
                return PromptRuntime.text("chart.colorInvalid", index.toString())
            }
        }

        if (style == "scatter") {
            if ("values" in item) {
                return PromptRuntime.text("chart.scatterNoValues", index.toString())
            }
            val points = item["points"] as? JsonArray
                ?: return PromptRuntime.text("chart.pointsRequired", index.toString())
            if (points.size > MAX_SERIES_POINTS) {
                return PromptRuntime.text(
                    "chart.pointsTooMany",
                    index.toString(),
                    MAX_SERIES_POINTS.toString(),
                )
            }
            points.forEachIndexed { pointIndex, point ->
                val obj = point as? JsonObject
                val x = obj?.number("x")
                val y = obj?.number("y")
                if (x == null || y == null) {
                    return PromptRuntime.text(
                        "chart.pointsNumeric",
                        index.toString(),
                        pointIndex.toString(),
                    )
                }
                if (xLog && x <= 0) {
                    return PromptRuntime.text(
                        "chart.pointXLog",
                        index.toString(),
                        pointIndex.toString(),
                    )
                }
                if (yLog && y <= 0) {
                    return PromptRuntime.text(
                        "chart.pointYLog",
                        index.toString(),
                        pointIndex.toString(),
                    )
                }
            }
        } else {
            if ("points" in item) {
                return PromptRuntime.text("chart.lineNoPoints", index.toString(), style!!)
            }
            val values = item["values"] as? JsonArray
                ?: return PromptRuntime.text("chart.valuesRequired", index.toString(), style!!)
            if (values.size > MAX_SERIES_POINTS) {
                return PromptRuntime.text(
                    "chart.valuesTooMany",
                    index.toString(),
                    MAX_SERIES_POINTS.toString(),
                )
            }
            values.forEachIndexed { valueIndex, value ->
                val number = (value as? JsonPrimitive)?.doubleOrNull
                    ?: return PromptRuntime.text(
                        "chart.valueNumeric",
                        index.toString(),
                        valueIndex.toString(),
                    )
                if (yLog && number <= 0) {
                    return PromptRuntime.text(
                        "chart.valueYLog",
                        index.toString(),
                        valueIndex.toString(),
                    )
                }
            }
            if (xLabels != null && xLabels.size != values.size) {
                return PromptRuntime.text(
                    "chart.labelCountMismatch",
                    index.toString(),
                    values.size.toString(),
                    xLabels.size.toString(),
                )
            }
        }
    }
    return null
}

private fun validateAxis(axisName: String, axis: JsonObject, style: String): String? {
    val hasMin = "min" in axis
    val hasMax = "max" in axis
    // 折线图/柱状图的 X 轴是类目轴, 没有可映射的数值范围或对数刻度
    if (axisName == "x_axis" && style != "scatter" && (hasMin || hasMax || axis.string("scale") == "log")) {
        return PromptRuntime.text("chart.xAxisCategorical", style)
    }
    if (style == "bar" && (hasMin || hasMax)) {
        return PromptRuntime.text("chart.barNoMinMax", axisName)
    }
    val min = axis.number("min")
    val max = axis.number("max")
    if (hasMin && min == null) return PromptRuntime.text("chart.axisMinNotNumber", axisName)
    if (hasMax && max == null) return PromptRuntime.text("chart.axisMaxNotNumber", axisName)
    if (min != null && max != null && min >= max) {
        return PromptRuntime.text("chart.axisOrder", axisName)
    }

    val scale = axis["scale"]
    if (scale != null && axis.string("scale") !in AXIS_SCALES) {
        return PromptRuntime.text("chart.axisScaleInvalid", axisName, AXIS_SCALES.joinToString())
    }
    if (axis.string("scale") == "log" && min != null && min <= 0) {
        return PromptRuntime.text("chart.axisLogMin", axisName)
    }

    val data = axis["data"]
    // 标签只用于折线图/柱状图的类目 X 轴, 其余情况渲染时会被忽略
    if (data != null && (axisName == "y_axis" || style == "scatter")) {
        return if (axisName == "y_axis") {
            PromptRuntime.text("chart.yAxisData")
        } else {
            PromptRuntime.text("chart.scatterData")
        }
    }
    if (data != null) {
        val labels = data as? JsonArray
            ?: return PromptRuntime.text("chart.axisDataNotArray", axisName)
        if (labels.any { (it as? JsonPrimitive)?.isString != true }) {
            return PromptRuntime.text("chart.axisDataNotArray", axisName)
        }
    }
    return null
}
