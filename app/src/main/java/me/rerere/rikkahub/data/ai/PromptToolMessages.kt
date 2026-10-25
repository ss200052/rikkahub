package me.rerere.rikkahub.data.ai

/**
 * 当前正在组装请求所用的方案。
 *
 * 工具执行时手里只有参数，拿不到设置；而它返回的提示语（权限缺失、格式非法之类）
 * 是给人读的自然语言，必须跟着方案语言走。组装请求前把方案记在这里，
 * 工具执行时按它取文案。批量并发生成的几路用的是同一份设置，因此写同一个值不会冲突。
 */
object PromptRuntime {
    @Volatile
    private var currentSchemeId: String = PromptSchemes.ENGLISH.id

    fun setScheme(id: String) {
        currentSchemeId = id
    }

    fun schemeId(): String = currentSchemeId

    /** 按当前方案取一条工具提示语，[args] 依次替换文案里的 `{0}`、`{1}` */
    fun text(key: String, vararg args: String): String = PromptToolMessages.of(schemeId()).text(key, *args)
}

/**
 * 工具返回给模型的提示语。
 *
 * 这些句子是自然语言：说明发生了什么、让模型怎么办。它们随方案语言变化，
 * 而像状态码、字段名、数值这些数据不进这张表。
 */
object PromptToolMessages {
    private fun format(template: String, args: Array<out String>): String {
        var result = template
        args.forEachIndexed { index, value -> result = result.replace("{$index}", value) }
        return result
    }

    class Texts(private val entries: Map<String, String>) {
        fun text(key: String, vararg args: String): String =
            entries[key]?.let { format(it, args) } ?: key
    }

    val ENGLISH: Texts = Texts(
        mapOf(
            "calendar.noReadPermission" to
                "Calendar read permission is not granted. Please ask the user to enable the calendar " +
                "permission in the assistant's local tools settings.",
            "calendar.noWritePermission" to
                "Calendar write permission is not granted. Please ask the user to enable the calendar " +
                "permission in the assistant's local tools settings.",
            "calendar.invalidTime" to "Invalid time format for begin/end.",
            "calendar.invalidRange" to "begin must be earlier than end.",
            "calendar.missingRequired" to "Both 'title' and 'start' are required.",
            "screentime.noPermission" to
                "Usage access permission is not granted. The system settings page has been opened; " +
                "please ask the user to enable 'Usage access' for this app and try again.",
            "screentime.invalidTime" to "Invalid time format for begin/end.",
            "screentime.invalidRange" to "begin must be earlier than end.",
            "chart.displayed" to "Chart displayed to the user.",
            "chart.styleInvalid" to "style must be one of {0}",
            "chart.axisNotObject" to "{0} must be an object",
            "chart.seriesEmpty" to "series must be a non-empty array",
            "chart.seriesTooMany" to "series can contain at most {0} items",
            "chart.seriesNotObject" to "series[{0}] must be an object",
            "chart.pointsRequired" to "series[{0}].points is required for scatter charts",
            "chart.pointsTooMany" to "series[{0}].points can contain at most {1} items",
            "chart.pointsNumeric" to "series[{0}].points[{1}] must have numeric x and y",
            "chart.pointXLog" to "series[{0}].points[{1}].x must be > 0 on a log x_axis",
            "chart.pointYLog" to "series[{0}].points[{1}].y must be > 0 on a log y_axis",
            "chart.scatterNoValues" to "series[{0}] must not use 'values' for scatter charts; use 'points'",
            "chart.lineNoPoints" to "series[{0}] must not use 'points' for {1} charts; use 'values'",
            "chart.valuesRequired" to "series[{0}].values is required for {1} charts",
            "chart.valuesTooMany" to "series[{0}].values can contain at most {1} items",
            "chart.valueNumeric" to "series[{0}].values[{1}] must be a number",
            "chart.valueYLog" to "series[{0}].values[{1}] must be > 0 on a log y_axis",
            "chart.labelCountMismatch" to "series[{0}].values has {1} items but x_axis.data has {2}",
            "chart.xAxisCategorical" to
                "x_axis.min/max and log scale are not supported for {0} charts (the X axis is " +
                "categorical); use y_axis for range and log scale",
            "chart.barNoMinMax" to "{0}.min/max are not supported for bar charts (bars always start from 0)",
            "chart.axisMinNotNumber" to "{0}.min must be a number",
            "chart.axisMaxNotNumber" to "{0}.max must be a number",
            "chart.axisOrder" to "{0}.min must be less than {0}.max",
            "chart.axisScaleInvalid" to "{0}.scale must be one of {1}",
            "chart.axisLogMin" to "{0}.min must be > 0 on a log scale",
            "chart.yAxisData" to "y_axis.data is not supported; category labels belong to x_axis.data",
            "chart.scatterData" to
                "x_axis.data is not supported for scatter charts (the X axis is numeric); use points[].x",
            "chart.axisDataNotArray" to "{0}.data must be an array of strings",
            "chart.colorInvalid" to "series[{0}].color must be a hex color like '#F80' or '#FF8800'",
            "common.fieldRequired" to "{0} is required",
            "common.textRequired" to "text is required",
            "common.queryRequired" to "query is required",
            "common.nameRequired" to "name is required",
            "common.commandRequired" to "command is required",
            "common.actionRequired" to "action is required",
            "workspace.oldTextRequired" to "old_text is required",
            "workspace.newTextRequired" to "new_text is required",
            "workspace.textRequired" to "text is required",
            "workspace.withPath" to "{0} (path: {1})",
            "workspace.timedOut" to "{0} timed out",
            "workspace.exitCode" to "{0} failed with exit code {1}",
            "workspace.outputTooLarge" to "{0} output is too large",
            "workspace.oldTextEmpty" to "old_text must not be empty",
            "memory.pathRequired" to "path is required",
            "memory.contentBlank" to "content must not be blank",
            "skill.notAvailable" to "Skill '{0}' is not available. Available skills: {1}",
            "skill.notFound" to "Skill '{0}' not found",
            "skill.fileNotFound" to "File '{0}' not found in skill '{1}'",
            "skill.pathOutside" to "Path '{0}' is outside the skill directory",
            "js.codeRequired" to "JavaScript code is required",
            "js.timeout" to "JavaScript execution timed out after {0}ms",
            "js.failed" to "JavaScript execution failed",
            "js.logsTruncated" to "[Logs truncated]",
            "tts.textRequired" to "text is required",
        ),
    )

    val CHINESE: Texts = Texts(
        mapOf(
            "calendar.noReadPermission" to
                "没有日历读取权限。请让用户在助手的本地工具设置里开启日历权限。",
            "calendar.noWritePermission" to
                "没有日历写入权限。请让用户在助手的本地工具设置里开启日历权限。",
            "calendar.invalidTime" to "begin/end 的时间格式无效。",
            "calendar.invalidRange" to "begin 必须早于 end。",
            "calendar.missingRequired" to "title 与 start 都是必填的。",
            "screentime.noPermission" to
                "没有「使用情况访问」权限。已打开系统设置页;请让用户为本应用开启使用情况访问后重试。",
            "screentime.invalidTime" to "begin/end 的时间格式无效。",
            "screentime.invalidRange" to "begin 必须早于 end。",
            "chart.displayed" to "图表已显示给用户。",
            "chart.styleInvalid" to "style 必须是以下之一:{0}",
            "chart.axisNotObject" to "{0} 必须是对象",
            "chart.seriesEmpty" to "series 不能为空数组",
            "chart.seriesTooMany" to "series 最多只能有 {0} 项",
            "chart.seriesNotObject" to "series[{0}] 必须是对象",
            "chart.pointsRequired" to "散点图的 series[{0}] 必须提供 points",
            "chart.pointsTooMany" to "series[{0}].points 最多只能有 {1} 项",
            "chart.pointsNumeric" to "series[{0}].points[{1}] 的 x 与 y 都必须是数字",
            "chart.pointXLog" to "x 轴为对数刻度时,series[{0}].points[{1}].x 必须大于 0",
            "chart.pointYLog" to "y 轴为对数刻度时,series[{0}].points[{1}].y 必须大于 0",
            "chart.scatterNoValues" to "散点图的 series[{0}] 不能使用 values,请用 points",
            "chart.lineNoPoints" to "{1} 图的 series[{0}] 不能使用 points,请用 values",
            "chart.valuesRequired" to "{1} 图的 series[{0}] 必须提供 values",
            "chart.valuesTooMany" to "series[{0}].values 最多只能有 {1} 项",
            "chart.valueNumeric" to "series[{0}].values[{1}] 必须是数字",
            "chart.valueYLog" to "y 轴为对数刻度时,series[{0}].values[{1}] 必须大于 0",
            "chart.labelCountMismatch" to
                "series[{0}].values 有 {1} 项,但 x_axis.data 有 {2} 项",
            "chart.xAxisCategorical" to
                "{0} 图不支持 x_axis 的 min/max 与对数刻度(X 轴是分类轴);请用 y_axis 设置范围与对数刻度",
            "chart.barNoMinMax" to "柱状图不支持 {0}.min/max(柱子总是从 0 起)",
            "chart.axisMinNotNumber" to "{0}.min 必须是数字",
            "chart.axisMaxNotNumber" to "{0}.max 必须是数字",
            "chart.axisOrder" to "{0}.min 必须小于 {0}.max",
            "chart.axisScaleInvalid" to "{0}.scale 必须是以下之一:{1}",
            "chart.axisLogMin" to "对数刻度下 {0}.min 必须大于 0",
            "chart.yAxisData" to "不支持 y_axis.data;分类标签应放在 x_axis.data",
            "chart.scatterData" to "散点图不支持 x_axis.data(X 轴是数值轴);请用 points[].x",
            "chart.axisDataNotArray" to "{0}.data 必须是字符串数组",
            "chart.colorInvalid" to "series[{0}].color 必须是十六进制颜色,例如 '#F80' 或 '#FF8800'",
            "common.fieldRequired" to "{0} 是必填的",
            "common.textRequired" to "缺少 text",
            "common.queryRequired" to "缺少 query",
            "common.nameRequired" to "缺少 name",
            "common.commandRequired" to "缺少 command",
            "common.actionRequired" to "缺少 action",
            "workspace.oldTextRequired" to "缺少 old_text",
            "workspace.newTextRequired" to "缺少 new_text",
            "workspace.textRequired" to "缺少 text",
            "workspace.withPath" to "{0}(路径:{1})",
            "workspace.timedOut" to "{0} 超时",
            "workspace.exitCode" to "{0} 失败,退出码 {1}",
            "workspace.outputTooLarge" to "{0} 的输出过大",
            "workspace.oldTextEmpty" to "old_text 不能为空",
            "memory.pathRequired" to "缺少 path",
            "memory.contentBlank" to "content 不能为空",
            "skill.notAvailable" to "技能「{0}」不可用。可用技能:{1}",
            "skill.notFound" to "找不到技能「{0}」",
            "skill.fileNotFound" to "在技能「{1}」里找不到文件「{0}」",
            "skill.pathOutside" to "路径「{0}」在技能目录之外",
            "js.codeRequired" to "缺少 JavaScript 代码",
            "js.timeout" to "JavaScript 执行在 {0} 毫秒后超时",
            "js.failed" to "JavaScript 执行失败",
            "js.logsTruncated" to "[日志已截断]",
            "tts.textRequired" to "缺少 text",
        ),
    )

    fun of(schemeId: String): Texts =
        if (schemeId == PromptSchemes.CHINESE.id) CHINESE else ENGLISH
}
