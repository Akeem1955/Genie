package com.akimy.genie.service

import android.graphics.Rect

/**
 * Transient store for Set-of-Mark (SoM) bounded box coordinates.
 * Populated during take_screenshot and consumed later by click_element_by_id.
 */
object ScreenSomStore {
    private val elementMap = mutableMapOf<Int, Rect>()

    fun clear() {
        elementMap.clear()
    }

    fun store(id: Int, rect: Rect) {
        elementMap[id] = rect
    }

    fun getRect(id: Int): Rect? {
        return elementMap[id]
    }

    fun getAll(): Map<Int, Rect> = elementMap.toMap()
}
