package me.rerere.rikkahub.data.ai

/**
 * 中文方案里工具描述与参数说明的文字。
 *
 * 键与运行期一致，由 [toolDescriptionId] / [toolParamDescriptionId] 生成；
 * 这些是纯文本段的槽位——整段就是一个可编辑字符串，不存在固定结构与数据块。
 *
 * 切到中文方案时用这里的文字，切回英文方案就用源码里的原文，两边都不残留对方的语言。
 */
object PromptToolTexts {
    private fun desc(tool: String, text: String) = toolDescriptionId(tool) to text

    private fun param(tool: String, name: String, text: String) =
        toolParamDescriptionId(tool, name) to text

    val ZH: Map<String, String> = mapOf(
        // 对话检索
        desc(
            "conversation_read",
            "按位置完整读取某一个对话的消息。当给你的消息被截断、或检索出的片段需要前后文时使用。" +
                "位置就是每条消息旁边显示的 #编号。",
        ),
        param("conversation_read", "conversation_id", "对话的 id"),
        param("conversation_read", "offset", "从第几条消息开始返回(默认 0)"),
        param("conversation_read", "limit", "最多返回多少条消息(默认 20,上限 40)"),

        // 联网检索
        desc(
            "search_web",
            """
                联网检索最新或特定的信息。
                当用户询问最新消息、当前事实,或需要核实时使用。
                不要把结果的排列顺序当成新鲜度的证明。优先采用一手来源,并在下判断前逐个查看
                每条结果的标题、URL、发布日期与正文。
                只有在与问题相关时才使用可选的发布日期与域名过滤。
                如果缺少日期或一手来源,或来源之间互相矛盾,请再换一组聚焦的关键词检索,
                或用 scrape_web 核实最相关的那一条来源,然后再回答。
                请自行生成聚焦的关键词,必要时多检索几轮。

                返回格式:
                - retrievedAt 是本地检索时间,永远不是发布日期
                - items[].id(短 id)、index、title、url、publishedDate(有则给)、highlights(有则给)、text
                - images[]:与该查询相关的图片链接(可能为空)

                引用:
                - 使用结果后,在句子后面加上 [citation,domain](id)。
                - 允许一次引用多个来源。
                - 没有引用任何结果时就省略引用标记。

                图片:
                - 当图片有助于用户理解答案时,用 Markdown 嵌入相关图片。
                - 嵌入 2 到 4 张,且只使用 images[] 里的链接,绝不编造或改写链接。
                - 通常放在回答最开头;没有相关图片就完全不放。
            """.trimIndent(),
        ),
        param("search_web", "query", "要检索的关键词"),
        desc(
            "scrape_web",
            "抓取某个网址的详细页面内容。当用户要求某个具体页面的内容、检索片段不够用," +
                "或需要针对某个特定来源核实当前事实时使用。普通问题不要用它,除非用户主动要求。",
        ),

        // 技能
        desc(
            "use_skill",
            "加载并应用一个技能,以获得专门的指令或能力。当用户的请求与某个可用技能匹配时调用本工具。",
        ),
        param("use_skill", "name", "要使用的技能名称"),
        param(
            "use_skill",
            "path",
            "技能目录内某个文件的相对路径,可省略。省略时读取默认的 SKILL.md 指令。" +
                "只能使用 SKILL.md 正文中 Markdown 链接里出现的路径,不要猜测或推断路径。",
        ),

        // 记忆
        desc("memory_read", "读取一个或多个记忆文件,返回每个文件的内容与版本号。"),
        param("memory_read", "path", """要读取的文件路径列表,例如 ["/topics/food.md"]"""),
        desc(
            "memory_write",
            "新建一个记忆文件,或把已有文件整体覆盖。content 是完整的文件内容,没有写进去的行会被删除。\n" +
                "if_version:新文件传 \"new\",已有文件传最近一次读取或写入返回的版本号。",
        ),
        param("memory_write", "path", "文件路径,例如 /topics/food.md"),
        param("memory_write", "content", "完整的文件内容"),
        param("memory_write", "if_version", "新文件传 \"new\",否则传该文件当前的版本号"),
        desc(
            "memory_str_replace",
            "把记忆文件里 old_str 唯一出现的那一处替换成 new_str。new_str 为空表示删除这一处。\n" +
                "当 old_str 匹配到零处或多处时会被拒绝。",
        ),
        param("memory_str_replace", "path", "文件路径"),
        param("memory_str_replace", "old_str", "要被替换的原文,必须在文件中恰好出现一次"),
        param("memory_str_replace", "new_str", "替换后的文本,留空表示删除"),
        param("memory_str_replace", "if_version", "新文件传 \"new\",否则传该文件当前的版本号"),
        desc("memory_append", "在记忆文件末尾追加一行。"),
        param("memory_append", "path", "文件路径"),
        param("memory_append", "content", "要追加的这一行"),
        param("memory_append", "if_version", "新文件传 \"new\",否则传该文件当前的版本号"),
        desc("memory_delete", "删除整个记忆文件。仅在用户明确要求忘掉某个话题时使用。"),
        param("memory_delete", "path", "文件路径"),
        param("memory_delete", "if_version", "新文件传 \"new\",否则传该文件当前的版本号"),
        desc("memory_list", "列出记忆文件(路径、大小、更新时间),可以按路径前缀筛选。"),
        param("memory_list", "path_prefix", "只列出路径以该前缀开头的文件,例如 /people/"),

        // 工作区
        desc(
            "workspace_read_file",
            "用助手绑定的工作区 Rootfs 读取文件。路径必须是 Rootfs 内的绝对路径," +
                "工作区文件区请用 /workspace。支持 UTF-8 文本文件与图片文件" +
                "(png、jpg、jpeg、gif、webp、bmp、svg、heic、heif、avif、ico)。",
        ),
        param("workspace_read_file", "path", "Rootfs 内的绝对路径,工作区文件区请用 /workspace。"),
        desc(
            "workspace_write_file",
            "用助手绑定的工作区 Rootfs 写入一个 UTF-8 文本文件。路径必须是 Rootfs 内的绝对路径," +
                "工作区文件区请用 /workspace。",
        ),
        param("workspace_write_file", "path", "Rootfs 内的绝对路径,工作区文件区请用 /workspace。"),
        param("workspace_write_file", "text", "要写入的 UTF-8 文本内容"),
        param("workspace_write_file", "overwrite", "是否覆盖已存在的文件,默认为是。"),
        desc(
            "workspace_edit_file",
            "用助手绑定的工作区 Rootfs 编辑一个 UTF-8 文本文件。路径必须是 Rootfs 内的绝对路径," +
                "工作区文件区请用 /workspace。\n" +
                "需要提供 old_text 与 new_text。默认情况下 old_text 必须在文件中恰好出现一次;" +
                "把 replace_all 设为 true 可以替换所有出现位置。\n" +
                "找不到完全一致的匹配时,会自动尝试忽略空白差异的按行匹配。",
        ),
        param("workspace_edit_file", "path", "Rootfs 内的绝对路径,工作区文件区请用 /workspace。"),
        param("workspace_edit_file", "old_text", "要被替换的原文,必须完全一致"),
        param("workspace_edit_file", "new_text", "替换后的文本"),
        param("workspace_edit_file", "replace_all", "是否替换所有出现位置,默认为否。"),
        desc(
            "workspace_shell",
            "在助手绑定的工作区 Rootfs 中执行 shell 命令,工作区文件区挂载在 /workspace。" +
                "cwd 请给相对于工作区文件根目录的路径。需要 Rootfs 已安装并处于就绪状态。",
        ),
        param("workspace_shell", "command", "要执行的 shell 命令"),
        param(
            "workspace_shell",
            "cwd",
            "相对于工作区文件根目录的工作目录。省略时使用助手设定的默认目录,没设定就是根目录。",
        ),
        param("workspace_shell", "timeout", "命令超时的秒数。默认为 30 秒。"),

        // 向用户提问
        desc(
            "ask_user",
            "当你需要澄清、补充信息或确认时,向用户提出一个或多个问题。\n" +
                "每个问题都可以附带一组候选选项供用户选择。\n" +
                "所有问题都允许用户自行输入文本作答,单选与多选问题也一样。\n" +
                "多选问题里,用户的自定义文本可以与所选选项同时存在。\n" +
                "作答结果会以 JSON 对象返回,把问题 id 映射到用户的回答。",
        ),
        param("ask_user", "questions", "要向用户提出的问题列表"),

        // 日历
        desc(
            "calendar_query",
            "查询用户设备上某个时间段内的日历事件。可以用 begin/end 指定具体时间范围," +
                "也可以使用 range 预设(today/week/month)。返回事件列表,包含标题、描述、地点、" +
                "起止时间与所属日历信息。时间按设备本地时区解释,未显式带时区偏移的时间都按这个时区解释。" +
                "需要 Calendar 权限;若未授权会返回错误,并自动触发授权请求。",
        ),
        param(
            "calendar_query",
            "begin",
            "开始时间(含)。可以写 ISO-8601 日期 yyyy-MM-dd、本地日期时间 yyyy-MM-ddTHH:mm:ss、" +
                "带偏移的日期时间,或毫秒时间戳。一旦提供,range 会被忽略。",
        ),
        param("calendar_query", "end", "结束时间(不含),格式同 begin。默认到现在。"),
        param("calendar_query", "range", "便捷预设,仅在省略 begin 时生效:today、week 或 month。默认 today。"),
        param("calendar_query", "query", "可选关键词,按标题过滤事件(不区分大小写的子串匹配)。"),
        param("calendar_query", "limit", "最多返回多少个事件。默认 20。"),
        desc(
            "calendar_create",
            "在用户设备上新建一个日历事件。至少需要标题与开始时间,结束时间默认为开始时间之后 1 小时。" +
                "时间按设备本地时区解释,未显式带时区偏移的时间都按这个时区解释。" +
                "需要 Calendar 权限;若未授权会返回错误,并自动触发授权请求。",
        ),
        param("calendar_create", "title", "事件标题。"),
        param("calendar_create", "description", "事件描述或备注。"),
        param("calendar_create", "location", "事件地点。"),
        param(
            "calendar_create",
            "start",
            "开始时间。可以写 ISO-8601 日期 yyyy-MM-dd、本地日期时间 yyyy-MM-ddTHH:mm:ss、" +
                "带偏移的日期时间,或毫秒时间戳。",
        ),
        param("calendar_create", "end", "结束时间,格式同 start。默认是开始时间之后 1 小时。"),
        param("calendar_create", "all_day", "是否为全天事件。默认否。"),

        // 图表
        desc(
            "chart_display",
            "在对话里给用户显示折线图、柱状图或散点图。\n" +
                "折线图与柱状图的每条数据系列必须使用 values(与 x_axis.data 按位置对齐的数字)," +
                "不能使用 points;散点图的每条数据系列必须使用 points({x, y} 数字),不能使用 values。\n" +
                "柱状图总是从 0 起,因此柱状图不允许设置轴的 min/max。\n" +
                "折线图与柱状图的 X 轴是分类轴:x_axis 只支持 title 与 data," +
                "因此 x_axis 的 min/max 与对数刻度都不允许;需要用 y_axis 设置范围与对数刻度。\n" +
                "轴的 data 标签只允许出现在折线图与柱状图的 x_axis 上。\n" +
                "系列条数与每条系列的数据点数量都有上限。\n" +
                "图表会直接渲染给用户,你不必在回复里重复原始数据。",
        ),
        param("chart_display", "style", "图表类型。"),
        param("chart_display", "title", "图表标题,显示在顶部。"),
        param("chart_display", "series", "一条或多条数据系列。"),

        // 设备能力
        desc(
            "clipboard_tool",
            "读取或写入设备剪贴板中的纯文本。用 action 指定 read 或 write;写入时需要提供 text。" +
                "除非用户明确要求,否则不要写入剪贴板。",
        ),
        param("clipboard_tool", "action", "要执行的操作:read 或 write"),
        param("clipboard_tool", "text", "要写入剪贴板的文本(write 时必填)"),
        desc(
            "eval_javascript",
            "使用 QuickJS 引擎执行 JavaScript 代码(ES2020)。返回结果是代码中最后一个表达式的值。" +
                "小数计算请用 toFixed() 控制精度。控制台输出(log/info/warn/error)会被捕获并放在 logs " +
                "字段返回。没有 DOM 与 Node.js API。例如:1 + 2 返回 3;const x = 5; x * 2 返回 10。",
        ),
        param("eval_javascript", "code", "要执行的 JavaScript 代码"),
        desc(
            "get_screen_time",
            "获取用户一段时间内的应用屏幕使用时长。可以用 begin/end 指定具体时间范围," +
                "也可以使用 range 预设(today/week)。返回前台总时长,以及按使用时长降序排列的逐应用明细。" +
                "时间按设备本地时区解释,未显式带时区偏移的时间都按这个时区解释。" +
                "需要「使用情况访问」这一特殊权限;若未授权,会自动打开系统的使用情况访问设置页并返回错误。",
        ),
        param(
            "get_screen_time",
            "begin",
            "开始时间(含)。可以写 ISO-8601 日期 yyyy-MM-dd、本地日期时间 yyyy-MM-ddTHH:mm:ss、" +
                "带偏移的日期时间,或毫秒时间戳。一旦提供,range 会被忽略。",
        ),
        param("get_screen_time", "end", "结束时间(不含),格式同 begin。默认到现在。"),
        param("get_screen_time", "range", "便捷预设,仅在省略 begin 时生效:today 或 week。默认 today。"),
        param("get_screen_time", "top", "最多返回多少个应用,按使用时长排序。默认 10。"),
        desc(
            "text_to_speech",
            "用设备的语音合成引擎把文本读给用户听。当用户要求朗读、或适合用语音输出时使用。" +
                "工具会立即返回,音频在设备后台播放。请提供自然、可直接朗读的文本,不要带 Markdown 格式。",
        ),
        param("text_to_speech", "text", "要朗读的文本"),
        desc(
            "get_time_info",
            "读取设备当前的本地日期与时间信息。返回年、月、日、星期、ISO 日期与时间字符串、时区以及时间戳。",
        ),
    )
}
