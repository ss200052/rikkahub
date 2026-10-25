package me.rerere.rikkahub.data.ai

/**
 * 请求体各段的骨架。
 *
 * 骨架是中性的结构层：`«slot»` 是可编辑槽位（只有标识），`‹token›` 是数据块，
 * 其余字符全是固定结构——`##`、`- `、`**`、反引号、XML 标签、字段名。
 * 骨架里不出现任何语言，所以它不属于任何一套方案；英文与中文都是搭在这副骨架上的填充。
 *
 * 组表达条件内容：组 id 出现在当前数据里才参与拼装，与选哪套方案无关。
 */
object PromptSegments {
    /** 时间提醒：注入在首条用户消息之前 */
    val TIME_REMINDER: List<PromptGroup> = PromptSkeleton.groups(
        "time" to "<time_reminder>«time.prefix»‹time›«time.separator»‹weekday›",
        "gap" to "«gap.prefix»‹gap›«gap.suffix»",
        "tail" to "«time.dot»</time_reminder>",
    )

    /** 工作区提醒：追加到系统提示词末尾 */
    val WORKSPACE: List<PromptGroup> = PromptSkeleton.groups(
        "head" to "<workspace>\n" +
            "«workspace.intro.lead»‹workspace_name›«workspace.intro.tail»\n" +
            "- «workspace.mount.lead»`/workspace`«workspace.mount.tail»\n" +
            "- «workspace.paths.lead»`/workspace/notes.md`«workspace.paths.tail»\n",
        "distro" to "- «workspace.distro.lead»‹distro›«workspace.distro.tail»\n",
        "shell_bash" to "- `workspace_shell`«workspace.shell.bash.lead»`‹shell›`«workspace.shell.bash.tail»\n",
        "shell_posix" to "- `workspace_shell`«workspace.shell.posix.lead»`‹shell›`" +
            "«workspace.shell.posix.middle»`<<<`«workspace.shell.posix.tail»\n",
        "body" to "- «workspace.tools.lead»\n" +
            "  - `workspace_read_file`«workspace.read.tail»\n" +
            "  - `workspace_write_file`«workspace.write.lead»`workspace_edit_file`«workspace.write.tail»\n" +
            "  - `workspace_shell`«workspace.shell.tail»\n" +
            "- «workspace.prefer.lead»`workspace_shell`«workspace.prefer.middle»" +
            "`workspace_edit_file`«workspace.prefer.tail»\n" +
            "- «workspace.skills.lead»`/skills`«workspace.skills.a»`/skills/<skill-name>/`" +
            "«workspace.skills.b»`SKILL.md`«workspace.skills.c»`name`«workspace.skills.d»" +
            "`description`«workspace.skills.e»`SKILL.md`«workspace.skills.f»\n" +
            "- «workspace.builtin.lead»`/builtin_skills/<skill-name>/`«workspace.builtin.a»" +
            "`/builtin_skills`«workspace.builtin.b»`/skills`«workspace.builtin.c»\n" +
            "- «workspace.upload.lead»`/upload`«workspace.upload.a»`/upload`" +
            "«workspace.upload.b»`/upload/<file-name>`«workspace.upload.c»" +
            "`/workspace`«workspace.upload.tail»\n",
        "cwd" to "- «workspace.cwd.lead»`‹cwd›`«workspace.cwd.tail»\n",
        "close" to "</workspace>",
        "agents" to "<workspace_instructions>\n" +
            "«agents.lead»\n" +
            "«agents.source»‹agents_path›\n" +
            "\n" +
            "‹agents_content›\n" +
            "</workspace_instructions>",
    )

    /**
     * 技能清单：`use_skill` 工具自带的系统提示。
     *
     * 这一段没有条件内容，所以是单组；原始骨架字符串同时提供给工具，
     * 让请求组装器按当前方案渲染它。
     */
    val SKILLS_SKELETON: String = "**«skills.title»**\n" +
        "«skills.intro.lead»`use_skill`«skills.intro.tail»\n" +
        "<available_skills>\n" +
        "‹skills_lines›\n" +
        "</available_skills>"

    val SKILLS: List<PromptGroup> = PromptSkeleton.groups("all" to SKILLS_SKELETON)

    /** 记忆段：说明文字进槽位，记忆正文与版本号是数据块，「##」与「- 」等是固定结构 */
    val MEMORY_SKELETON: String = "<memory_system>\n" +
        "«memory.intro»\n" +
        "\n" +
        "## «memory.title»\n" +
        "\n" +
        "<profile version=\"‹profile_version›\">\n" +
        "‹profile_content›\n" +
        "</profile>\n" +
        "\n" +
        "<preferences version=\"‹preferences_version›\">\n" +
        "‹preferences_content›\n" +
        "</preferences>\n" +
        "\n" +
        "<memory_listing>\n" +
        "‹memory_listing›\n" +
        "</memory_listing>\n" +
        "\n" +
        "«memory.rules.lead»\n" +
        "«memory.rules.hint»\n" +
        "\n" +
        "## «memory.rules.read.title»\n" +
        "\n" +
        "- «memory.rules.read.1»\n" +
        "- «memory.rules.read.2»\n" +
        "- «memory.rules.read.3»\n" +
        "- «memory.rules.read.4»\n" +
        "\n" +
        "## «memory.rules.apply.title»\n" +
        "\n" +
        "- «memory.rules.apply.1»\n" +
        "- «memory.rules.apply.2»\n" +
        "- «memory.rules.apply.3»\n" +
        "- «memory.rules.apply.4»\n" +
        "- «memory.rules.apply.5»\n" +
        "- «memory.rules.apply.6»\n" +
        "- «memory.rules.apply.7»\n" +
        "- «memory.rules.apply.8»\n" +
        "\n" +
        "## «memory.rules.write.title»\n" +
        "\n" +
        "- «memory.rules.write.1»\n" +
        "- «memory.rules.write.2»\n" +
        "  - «memory.rules.write.2a»\n" +
        "  - «memory.rules.write.2b»\n" +
        "  - «memory.rules.write.2c»\n" +
        "  - «memory.rules.write.2d»\n" +
        "- «memory.rules.write.3»\n" +
        "- «memory.rules.write.4»\n" +
        "\n" +
        "«memory.rules.formatLead»<memory_format>«memory.rules.formatTail»\n" +
        "</memory_system>\n" +
        "\n" +
        "<memory_format>\n" +
        "## «memory.format.structure.title»\n" +
        "\n" +
        "    ---\n" +
        "    name: <«memory.format.structure.name»>\n" +
        "    title: <«memory.format.structure.displayName»>\n" +
        "    description: <«memory.format.structure.description»>\n" +
        "    aliases: [«memory.format.structure.aliases»]\n" +
        "    ---\n" +
        "\n" +
        "    - [stated] «memory.format.structure.entry»\n" +
        "\n" +
        "«memory.format.structure.language»\n" +
        "\n" +
        "## «memory.format.layout.title»\n" +
        "\n" +
        "- /profile.md: «memory.format.layout.profile»\n" +
        "- /preferences.md: «memory.format.layout.preferences»\n" +
        "- /topics/<«memory.format.layout.topics.name»>.md: «memory.format.layout.topics.desc»\n" +
        "- /areas/<«memory.format.layout.areas.name»>.md: «memory.format.layout.areas.desc»\n" +
        "- /people/<«memory.format.layout.people.name»>.md: «memory.format.layout.people.desc»\n" +
        "\n" +
        "«memory.format.layout.grouping»\n" +
        "\n" +
        "## «memory.format.stated.title»\n" +
        "\n" +
        "«memory.format.stated.lead»\n" +
        "- «memory.format.stated.1»\n" +
        "- «memory.format.stated.2»\n" +
        "- «memory.format.stated.3»\n" +
        "- «memory.format.stated.4»\n" +
        "- «memory.format.stated.5»\n" +
        "- «memory.format.stated.6»\n" +
        "\n" +
        "«memory.format.stated.note»\n" +
        "\n" +
        "## «memory.format.calibration.title»\n" +
        "\n" +
        "- «memory.format.calibration.1»\n" +
        "- «memory.format.calibration.2»\n" +
        "- «memory.format.calibration.3»\n" +
        "- «memory.format.calibration.4»\n" +
        "- «memory.format.calibration.5»\n" +
        "- «memory.format.calibration.6»\n" +
        "\n" +
        "## «memory.format.sensitive.title»\n" +
        "\n" +
        "- «memory.format.sensitive.1»\n" +
        "- «memory.format.sensitive.2»\n" +
        "- «memory.format.sensitive.3»\n" +
        "- «memory.format.sensitive.4»\n" +
        "- «memory.format.sensitive.5»\n" +
        "\n" +
        "## «memory.format.editing.title»\n" +
        "\n" +
        "- «memory.format.editing.1»\n" +
        "- «memory.format.editing.2»\n" +
        "- «memory.format.editing.3»\n" +
        "- «memory.format.editing.4»\n" +
        "</memory_format>"

    val MEMORY: List<PromptGroup> = PromptSkeleton.groups("all" to MEMORY_SKELETON)

    /**
     * 远程（SSH）工作区提醒。
     *
     * 与本地版结构相近，另加两条只有远程才成立的说明：这不是沙箱、没有写文件工具。
     * 发行版、shell、工作目录都是「有值才出现」的组。
     */
    val WORKSPACE_SSH: List<PromptGroup> = PromptSkeleton.groups(
        "head" to "<workspace>\n" +
            "«workspace.ssh.intro.lead»‹workspace_name›«workspace.ssh.intro.middle»" +
            "`‹workspace_address›`«workspace.ssh.intro.tail»\n" +
            "- «workspace.ssh.dir.lead»`‹workspace_dir›`«workspace.ssh.dir.tail»\n" +
            "- «workspace.ssh.paths.lead»`‹workspace_dir›/notes.md`«workspace.ssh.paths.tail»\n" +
            "- «workspace.ssh.notSandbox»\n",
        "distro" to "- «workspace.ssh.distro.lead»‹distro›«workspace.ssh.distro.tail»\n",
        "shell_bash" to "- `workspace_shell`«workspace.ssh.shell.lead»`‹shell›`«workspace.ssh.shell.tail»\n",
        "shell_posix" to "- `workspace_shell`«workspace.ssh.shell.lead»`‹shell›`" +
            "«workspace.ssh.shell.posix.middle»`<<<`«workspace.ssh.shell.posix.tail»\n",
        "tools" to "- «workspace.ssh.tools.lead»\n" +
            "  - `workspace_read_file`«workspace.ssh.read.tail»\n" +
            "  - `workspace_shell`«workspace.ssh.tool.shell.tail»\n" +
            "- «workspace.ssh.noWriteTools»\n",
        "cwd" to "- «workspace.ssh.cwd.lead»`‹cwd›`«workspace.ssh.cwd.tail»\n",
        "close" to "</workspace>",
    )

    /** 远程工作区连不上时注入的一段说明：告诉模型现在是断的，别假装能用 */
    val WORKSPACE_UNREACHABLE: List<PromptGroup> = PromptSkeleton.groups(
        "head" to "<workspace>\n" +
            "«unreachable.intro.lead»‹workspace_name›«unreachable.intro.tail»",
        "address" to "«unreachable.address.lead»`‹workspace_address›`«unreachable.address.tail»",
        "tail" to "«unreachable.dir.lead»`‹workspace_dir›`" +
            "«unreachable.reason.lead»‹unreachable_reason›\n" +
            "- «unreachable.hint»\n" +
            "</workspace>",
    )
}
