package me.rerere.rikkahub.data.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 一段提示词的骨架里的一个单元。
 *
 * 骨架是中性的结构层：它只说明这一段由哪些单元按什么顺序排列，不含任何语言。
 * - [Text]：一个可编辑槽位，只有标识，没有文字——文字由方案提供；
 * - [Fixed]：结构标记（XML 标签、`##`、`- `、`**`、反引号、字段名），不可改，必须显示；
 * - [Token]：运行期数据（时间、星期、路径、清单、记忆正文），不可改，必须显示。
 *
 * 骨架先于方案存在：英文方案、中文方案都是搭在同一副骨架上的填充，
 * 因此文本框个数、以及每个文本框与相邻固定结构、数据块的位置关系在任何方案下都相同。
 */
@Serializable
sealed interface PromptPart {
    /** 可编辑槽位 */
    @Serializable
    @SerialName("slot")
    data class Text(val slot: String) : PromptPart

    /** 不可编辑的结构标记 */
    @Serializable
    @SerialName("fixed")
    data class Fixed(val text: String) : PromptPart

    /** 不可编辑的数据块 */
    @Serializable
    @SerialName("token")
    data class Token(val id: String) : PromptPart
}

/** 一组部件。「组」只表达条件内容，是否出现由数据决定，与选哪套方案无关 */
data class PromptGroup(
    val id: String,
    val parts: List<PromptPart>,
)

/**
 * 骨架解析。
 *
 * 写法：`«slotId»` 是槽位（只有标识），`‹tokenId›` 是数据块，其余字符全是固定结构。
 *
 * 骨架里出现不了任何语言，因此不同方案的差别只可能是「同一个槽位填了不同文字」。
 */
object PromptSkeleton {
    private val MARKER = Regex("""«([^»]*)»|‹([^›]*)›""")

    fun parse(skeleton: String): List<PromptPart> {
        val parts = mutableListOf<PromptPart>()
        var cursor = 0
        MARKER.findAll(skeleton).forEach { match ->
            if (match.range.first > cursor) {
                parts += PromptPart.Fixed(skeleton.substring(cursor, match.range.first))
            }
            val slot = match.groups[1]
            if (slot != null) {
                parts += PromptPart.Text(slot.value.trim())
            } else {
                parts += PromptPart.Token(match.groups[2]!!.value.trim())
            }
            cursor = match.range.last + 1
        }
        if (cursor < skeleton.length) {
            parts += PromptPart.Fixed(skeleton.substring(cursor))
        }
        return parts
    }

    fun groups(vararg groups: Pair<String, String>): List<PromptGroup> =
        groups.map { (id, skeleton) -> PromptGroup(id, parse(skeleton)) }
}

/**
 * 一套方案：给某个骨架的每个槽位填文字。
 *
 * 方案只碰槽位；固定结构与数据块由骨架决定，方案动不了，这也是两套方案必然同构的原因。
 */
data class PromptScheme(
    val id: String,
    val texts: Map<String, String>,
) {
    /** 校验这套方案是否给骨架里的每个槽位都填了文字 */
    fun requireCovers(name: String, groups: List<PromptGroup>) {
        val slots = PromptComposer.slots(groups)
        val missing = slots.filter { texts[it] == null }
        require(missing.isEmpty()) { "$name 的槽位没有填文字：$missing" }
    }
}

/** 把骨架按方案与当前数据拼成最终文字 */
object PromptComposer {
    fun compose(
        groups: List<PromptGroup>,
        activeGroupIds: Set<String>,
        schemeTexts: Map<String, String>,
        slotOverrides: Map<String, String>,
        tokenValues: Map<String, String>,
    ): String = buildString {
        groups.forEach { group ->
            if (group.id !in activeGroupIds) return@forEach
            group.parts.forEach { part ->
                when (part) {
                    is PromptPart.Text -> append(slotOverrides[part.slot] ?: schemeTexts[part.slot].orEmpty())
                    is PromptPart.Fixed -> append(part.text)
                    is PromptPart.Token -> append(tokenValues[part.id].orEmpty())
                }
            }
        }
    }

    /** 骨架里全部槽位标识，按出现顺序 */
    fun slots(groups: List<PromptGroup>): List<String> =
        groups.flatMap { group -> group.parts.filterIsInstance<PromptPart.Text>().map { it.slot } }

    /** 骨架里全部数据块标识，按出现顺序 */
    fun tokens(groups: List<PromptGroup>): List<String> =
        groups.flatMap { group -> group.parts.filterIsInstance<PromptPart.Token>().map { it.id } }

    /** 方案文字叠加用户改写：改写优先 */
    fun mergedTexts(
        schemeTexts: Map<String, String>,
        overrides: Map<String, String>,
    ): Map<String, String> = schemeTexts + overrides
}
