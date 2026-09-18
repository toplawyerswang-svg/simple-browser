package com.trae.simplebrowser.core

/**
 * 多标签页状态管理（纯 Kotlin，可 JVM 单元测试）。
 * WebView 实例池与 UI 由 MainActivity 维护，本类只负责标签集合与当前选中项的一致性。
 */
class TabManager(private val maxTabs: Int = DEFAULT_MAX_TABS) {

    data class Tab(
        val id: Long,
        var title: String,
        var url: String,
        /** 本标签所属身份（Profile 名）；同一身份内的标签共享登录态 */
        val profile: String = ProfileManager.DEFAULT_PROFILE
    )

    private var nextId: Long = 1L
    private val _tabs = mutableListOf<Tab>()

    val tabs: List<Tab> get() = _tabs

    var currentId: Long? = null
        private set

    val current: Tab? get() = _tabs.firstOrNull { it.id == currentId }
    val size: Int get() = _tabs.size
    fun canCreate(): Boolean = _tabs.size < maxTabs

    /** 新建标签并设为当前；超出上限抛异常（调用方须先判 canCreate） */
    fun create(
        title: String = DEFAULT_TITLE,
        url: String = "",
        profile: String = ProfileManager.DEFAULT_PROFILE
    ): Tab {
        check(canCreate()) { "tab limit reached: $maxTabs" }
        val tab = Tab(nextId++, title, url, profile)
        _tabs.add(tab)
        currentId = tab.id
        return tab
    }

    /** 关闭标签；若关闭的是当前标签，优先右邻、其次左邻。返回新的 currentId（无标签时 null） */
    fun close(id: Long): Long? {
        val index = _tabs.indexOfFirst { it.id == id }
        if (index < 0) return currentId
        val wasCurrent = currentId == id
        _tabs.removeAt(index)
        if (wasCurrent) {
            currentId = when {
                _tabs.isEmpty() -> null
                index < _tabs.size -> _tabs[index].id
                else -> _tabs[index - 1].id
            }
        }
        return currentId
    }

    /** 关闭除指定标签外的全部，且指定标签成为当前 */
    fun closeOthers(id: Long): Boolean {
        val keep = _tabs.firstOrNull { it.id == id } ?: return false
        _tabs.clear()
        _tabs.add(keep)
        currentId = id
        return true
    }

    fun select(id: Long): Boolean {
        if (_tabs.none { it.id == id }) return false
        currentId = id
        return true
    }

    fun indexOf(id: Long): Int = _tabs.indexOfFirst { it.id == id }

    fun update(id: Long, title: String? = null, url: String? = null) {
        _tabs.firstOrNull { it.id == id }?.let {
            title?.let { t -> if (t.isNotBlank()) it.title = t }
            url?.let { u -> if (u.isNotBlank()) it.url = u }
        }
    }

    companion object {
        const val DEFAULT_MAX_TABS = 8
        const val DEFAULT_TITLE = "新标签页"
    }
}
