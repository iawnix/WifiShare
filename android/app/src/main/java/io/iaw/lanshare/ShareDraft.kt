package io.iaw.lanshare

class ShareDraft<T>(items: Collection<T> = emptyList()) {
    private val mutableItems = items.toMutableList()

    val size: Int get() = mutableItems.size
    val isEmpty: Boolean get() = mutableItems.isEmpty()

    fun snapshot(): List<T> = mutableItems.toList()

    fun replace(items: Collection<T>) {
        mutableItems.clear()
        mutableItems.addAll(items)
    }

    fun removeAt(index: Int): T? {
        if (index !in mutableItems.indices) {
            return null
        }
        return mutableItems.removeAt(index)
    }

    fun clear() {
        mutableItems.clear()
    }
}
