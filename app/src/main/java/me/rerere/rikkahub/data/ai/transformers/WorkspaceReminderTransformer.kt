package me.rerere.rikkahub.data.ai.transformers

import android.util.Log
import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.PromptComposer
import me.rerere.rikkahub.data.ai.PromptSchemes
import me.rerere.rikkahub.data.ai.PromptSegments
import me.rerere.rikkahub.data.ai.SystemPromptOverride
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceLayout
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellStatus
import java.io.ByteArrayOutputStream
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/** 工作区提醒在覆盖表里的键 */
internal const val WORKSPACE_REMINDER_ID = "segment.workspace_reminder"

/**
 * Workspace 系统提示注入转换器
 *
 * 当助手绑定了一个 shell 已就绪的 workspace 时, 在系统提示词中追加一段引导,
 * 让模型了解 workspace 环境与 workspace_* 工具的使用方式。
 * 提示中包含实际使用的 shell 与发行版, 避免模型默认按 bash / apt 来写命令。
 * 本地工作区同时读取 ~/.agents、/workspace 和会话当前目录中的 AGENTS.md 作为工作区指令。
 * 远程工作区不读取 AGENTS.md, shell 与发行版读到一次后缓存 (见 [RemoteEnvironmentCache]),
 * 连不上时只注入一段说明, 不阻塞对话。
 *
 * 三种情况的正文都走骨架渲染：说明文字按当前方案取，环境值（名称、地址、目录、发行版、
 * shell、AGENTS.md）按现状填，所以这些段既能整段改写，也不会把环境信息写死。
 */
class WorkspaceReminderTransformer(
    private val workspaceRepository: WorkspaceRepository,
) : InputMessageTransformer {
    private val remoteEnvironments = RemoteEnvironmentCache()

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val workspaceId = ctx.assistant.workspaceId?.toString() ?: return messages
        val workspace = workspaceRepository.getById(workspaceId) ?: return messages
        // 与 ChatToolFactory.createWorkspaceToolsIfReady 保持一致: 仅在 shell 就绪时注入
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) return messages
        val layout = workspaceRepository.layout(workspaceId) ?: return messages
        val override = ctx.settings.systemPromptOverrides
            .firstOrNull { it.blockId == WORKSPACE_REMINDER_ID }
        val schemeId = ctx.settings.promptSchemeId

        val prompt = if (workspace.isSsh()) {
            val environment = remoteEnvironments.get(workspaceId, workspace.sshConfig) {
                // shell 探测需要连接, 失败时不再读发行版, 以免再等一次超时
                RemoteEnvironment(
                    shell = workspaceRepository.shell(workspaceId),
                    distro = readDistroName(workspaceRepository, workspaceId),
                )
            }.getOrElse { e ->
                Log.w("WorkspaceReminder", "Workspace unreachable: $workspaceId", e)
                return messages.withPrompt(
                    renderUnreachableWorkspaceReminder(
                        workspace = workspace,
                        layout = layout,
                        error = e,
                        schemeId = schemeId,
                        override = override,
                    ),
                )
            }
            renderSshWorkspaceReminder(
                workspace = workspace,
                layout = layout,
                shell = environment.shell,
                distro = environment.distro,
                cwd = ctx.workspaceCwd,
                schemeId = schemeId,
                override = override,
            )
        } else {
            renderWorkspaceReminder(
                repository = workspaceRepository,
                workspaceId = workspaceId,
                workspace = workspace,
                cwd = ctx.workspaceCwd,
                schemeId = schemeId,
                override = override,
            )
        }
        return messages.withPrompt(prompt)
    }

    // 追加到第一条 system 消息; 若不存在则插入一条
    private fun List<UIMessage>.withPrompt(prompt: String): List<UIMessage> {
        val systemIndex = indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            toMutableList().apply {
                this[systemIndex] = this[systemIndex]
                    .appendText("\n\n$prompt")
                    .copy(isSynthetic = true)
            }
        } else {
            listOf(UIMessage.system(prompt).copy(isSynthetic = true)) + this
        }
    }
}

/**
 * 渲染本地工作区提醒。
 *
 * 骨架固定（[PromptSegments.WORKSPACE]），`«»` 里的说明文字按方案取，
 * `‹›` 里的环境值按当前状态填。没有发行版就没有那一行，没有工作目录就没有那一行。
 * 预览与实发都走这里，保证两边一致。
 */
internal suspend fun renderWorkspaceReminder(
    repository: WorkspaceRepository,
    workspaceId: String,
    workspace: WorkspaceEntity,
    cwd: String?,
    schemeId: String = PromptSchemes.ENGLISH.id,
    override: SystemPromptOverride? = null,
): String {
    val shell = repository.shell(workspaceId)
    val distro = readDistroName(repository, workspaceId)
    val agents = readAgentsSources(repository, workspaceId, cwd)

    val active = buildSet {
        add("head")
        add("body")
        add("close")
        if (distro != null) add("distro")
        add(if (isBashShell(shell)) "shell_bash" else "shell_posix")
        if (!cwd.isNullOrBlank()) add("cwd")
        if (agents.isNotEmpty()) add("agents")
    }

    return PromptComposer.compose(
        groups = PromptSegments.WORKSPACE,
        activeGroupIds = active,
        schemeTexts = PromptSchemes.of(schemeId).texts,
        slotOverrides = override?.slots.orEmpty(),
        tokenValues = mapOf(
            "workspace_name" to workspace.name,
            "distro" to (distro ?: ""),
            "shell" to shell,
            "cwd" to (cwd ?: ""),
            "agents_path" to agents.joinToString(" | ") { it.first },
            "agents_content" to agents.joinToString("\n\n") { it.second },
        ),
    )
}

/** 渲染远程（SSH）工作区提醒：同一套骨架思路，正文是远程专用的那一段 */
internal fun renderSshWorkspaceReminder(
    workspace: WorkspaceEntity,
    layout: WorkspaceLayout,
    shell: String,
    distro: String?,
    cwd: String?,
    schemeId: String = PromptSchemes.ENGLISH.id,
    override: SystemPromptOverride? = null,
): String {
    val address = workspace.sshSettings()?.let { "${it.username}@${it.host}" } ?: "the remote host"
    val active = buildSet {
        add("head")
        add("tools")
        add("close")
        if (distro != null) add("distro")
        add(if (isBashShell(shell)) "shell_bash" else "shell_posix")
        if (!cwd.isNullOrBlank()) add("cwd")
    }
    return PromptComposer.compose(
        groups = PromptSegments.WORKSPACE_SSH,
        activeGroupIds = active,
        schemeTexts = PromptSchemes.of(schemeId).texts,
        slotOverrides = override?.slots.orEmpty(),
        tokenValues = mapOf(
            "workspace_name" to workspace.name,
            "workspace_address" to address,
            "workspace_dir" to layout.workspaceDir,
            "distro" to (distro ?: ""),
            "shell" to shell,
            "cwd" to (cwd ?: ""),
        ),
    )
}

/** 渲染「远程工作区连不上」的说明 */
internal fun renderUnreachableWorkspaceReminder(
    workspace: WorkspaceEntity,
    layout: WorkspaceLayout,
    error: Throwable,
    schemeId: String = PromptSchemes.ENGLISH.id,
    override: SystemPromptOverride? = null,
): String {
    val address = workspace.sshSettings()?.let { "${it.username}@${it.host}" }
    val active = buildSet {
        add("head")
        add("tail")
        if (address != null) add("address")
    }
    return PromptComposer.compose(
        groups = PromptSegments.WORKSPACE_UNREACHABLE,
        activeGroupIds = active,
        schemeTexts = PromptSchemes.of(schemeId).texts,
        slotOverrides = override?.slots.orEmpty(),
        tokenValues = mapOf(
            "workspace_name" to workspace.name,
            "workspace_address" to (address ?: ""),
            "workspace_dir" to layout.workspaceDir,
            "unreachable_reason" to (error.message ?: error.javaClass.simpleName),
        ),
    )
}

/** 读取 AGENTS.md 的来源与内容，顺序固定：用户级、工作区根、会话当前目录 */
internal suspend fun readAgentsSources(
    repository: WorkspaceRepository,
    workspaceId: String,
    cwd: String?,
): List<Pair<String, String>> {
    // ProotShellRunner 将 HOME 固定为 /root；相对 PWD 按工作区目录解析。
    val workingDirectory = Paths.get(WorkspaceManager.ROOTFS_WORKSPACE_DIR)
        .resolve(cwd?.takeIf { it.isNotBlank() } ?: ".")
        .normalize()
    val paths = linkedSetOf(
        "${WorkspaceManager.ROOTFS_HOME_DIR}/.agents/AGENTS.md",
        "${WorkspaceManager.ROOTFS_WORKSPACE_DIR}/AGENTS.md",
        workingDirectory.resolve("AGENTS.md").toString(),
    )
    return paths.mapNotNull { path ->
        readRootfsText(repository, workspaceId, path, MAX_AGENTS_BYTES)?.let { path to it }
    }
}

internal suspend fun readDistroName(repository: WorkspaceRepository, workspaceId: String): String? =
    OS_RELEASE_PATHS.firstNotNullOfOrNull { path ->
        readRootfsText(repository, workspaceId, path, MAX_OS_RELEASE_BYTES)?.let(::parseOsReleaseName)
    }

/** 文件不存在、过大或不可读时返回 null, 不影响提示词的其余部分 */
internal suspend fun readRootfsText(
    repository: WorkspaceRepository,
    workspaceId: String,
    path: String,
    maxBytes: Long,
): String? = try {
    val size = repository.absoluteFileSize(workspaceId, path)
    require(size <= maxBytes) { "$path exceeds $maxBytes bytes" }
    val content = ByteArrayOutputStream().use { output ->
        repository.exportAbsoluteFile(workspaceId, path, output)
        output.toString(Charsets.UTF_8.name())
    }
    content.takeIf { it.isNotBlank() }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.d("WorkspaceReminder", "Skipping workspace file: $path", e)
    null
}

/** shell 是不是 bash：按最后一段判断，`/usr/bin/bash` 也算 */
internal fun isBashShell(shell: String): Boolean = shell.substringAfterLast('/') == "bash"

/** 远程工作区提示词里描述的执行环境 */
internal data class RemoteEnvironment(val shell: String, val distro: String?)

/**
 * 按工作区缓存远程执行环境。
 *
 * 读一次要好几次网络往返, 而每一步请求都要构建提示词; 远程环境很少变化, 读到后就一直沿用, 直到连接配置改变。
 * 连不上的结果只保留 [failureTtlMillis]: 期间的后续步骤不必每步都等连接超时, 过期后再重新尝试。
 */
internal class RemoteEnvironmentCache(
    private val failureTtlMillis: Long = DEFAULT_FAILURE_TTL_MS,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private class Entry(val config: String?, val result: Result<RemoteEnvironment>, val loadedAt: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    suspend fun get(
        workspaceId: String,
        config: String?,
        load: suspend () -> RemoteEnvironment,
    ): Result<RemoteEnvironment> {
        entries[workspaceId]
            ?.takeIf { it.config == config && (it.result.isSuccess || now() - it.loadedAt < failureTtlMillis) }
            ?.let { return it.result }
        val result = try {
            Result.success(load())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        entries[workspaceId] = Entry(config, result, now())
        return result
    }

    private companion object {
        const val DEFAULT_FAILURE_TTL_MS = 60_000L
    }
}

private const val MAX_AGENTS_BYTES = 64L * 1024
private const val MAX_OS_RELEASE_BYTES = 16L * 1024
private val OS_RELEASE_PATHS = listOf("/etc/os-release", "/usr/lib/os-release")

private const val MAX_DISTRO_NAME_LENGTH = 80

/** 从 os-release 内容中取发行版名称, 优先 PRETTY_NAME, 其次 NAME + VERSION_ID */
internal fun parseOsReleaseName(content: String): String? {
    val values = content.lineSequence()
        .map { it.trim() }
        .filter { !it.startsWith("#") && it.indexOf('=') > 0 }
        .associate { line ->
            line.substringBefore('=') to line.substringAfter('=').trim().removeSurrounding("\"").removeSurrounding("'")
        }
    val name = values["PRETTY_NAME"]?.takeIf { it.isNotBlank() }
        ?: listOfNotNull(values["NAME"], values["VERSION_ID"]).joinToString(" ")
    // 内容来自 Rootfs 内的文件, 拼进系统提示前去掉控制字符并限制长度
    return name.filterNot { it.isISOControl() }.trim().take(MAX_DISTRO_NAME_LENGTH).ifBlank { null }
}

private fun UIMessage.appendText(extra: String): UIMessage {
    val updatedParts = parts.toMutableList()
    val firstTextIndex = updatedParts.indexOfFirst { it is UIMessagePart.Text }
    if (firstTextIndex >= 0) {
        val text = updatedParts[firstTextIndex] as UIMessagePart.Text
        updatedParts[firstTextIndex] = text.copy(text = text.text + extra)
    } else {
        updatedParts.add(UIMessagePart.Text(extra))
    }
    return copy(parts = updatedParts)
}
