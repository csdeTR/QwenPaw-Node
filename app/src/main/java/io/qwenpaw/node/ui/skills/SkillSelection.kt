package io.qwenpaw.node.ui.skills

/**
 * 技能选择集的纯函数。
 *
 * 全选、反选、全不选都只对当前已加载的清单生效：池里的技能会因刷新或删除而消失，
 * 选择集里残留的名字不应再参与批量删除或导入。
 */
internal object SkillSelection {

    /** 全选：取清单里的全部名称。 */
    fun selectAll(available: Collection<String>): Set<String> =
        available.filter { it.isNotBlank() }.toSet()

    /** 反选：清单内未选中的变为选中、已选中的取消；清单外的残留项直接丢弃。 */
    fun invert(selected: Set<String>, available: Collection<String>): Set<String> =
        available.filter { it.isNotBlank() && it !in selected }.toSet()

    /** 全不选。 */
    fun clear(): Set<String> = emptySet()

    /** 搜索词为空时全部匹配；否则任一字段包含搜索词即匹配，忽略大小写并忽略首尾空白。 */
    fun matches(query: String, vararg fields: String): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) return true
        return fields.any { it.contains(needle, ignoreCase = true) }
    }

    /** 按搜索词过滤名称清单，保持原有顺序。 */
    fun filterNames(names: List<String>, query: String): List<String> =
        names.filter { matches(query, it) }
}
