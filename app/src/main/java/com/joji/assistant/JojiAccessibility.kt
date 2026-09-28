package com.joji.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class JojiAccessibility : AccessibilityService() {

    companion object {
        @Volatile
        var instance: JojiAccessibility? = null
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun dumpScreen(): String {
        val root = rootInActiveWindow ?: return "(صفحه در دسترس نیست)"
        val sb = StringBuilder("app: ").append(root.packageName).append('\n')
        var count = 0
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || count >= 80) return
            val t = n.text?.toString()?.trim().orEmpty()
            val d = n.contentDescription?.toString()?.trim().orEmpty()
            if (t.isNotEmpty() || d.isNotEmpty() || n.isEditable) {
                val flags = buildString {
                    if (n.isClickable) append("C")
                    if (n.isEditable) append("E")
                    if (n.isScrollable) append("S")
                }
                sb.append("- ").append((if (t.isNotEmpty()) t else d).take(60))
                    .append(" [").append(flags).append("]\n")
                count++
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return sb.toString()
    }

    private fun clickableAncestor(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var cur = n
        var depth = 0
        while (cur != null && depth < 8) {
            if (cur.isClickable) return cur
            cur = cur.parent
            depth++
        }
        return null
    }

    fun clickText(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        for (n in root.findAccessibilityNodeInfosByText(query)) {
            val c = clickableAncestor(n)
            if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }

    private fun findEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isEditable) return n
        for (i in 0 until n.childCount) {
            val r = findEditable(n.getChild(i))
            if (r != null) return r
        }
        return null
    }

    fun typeText(t: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val target = (if (focused != null && focused.isEditable) focused else null) ?: findEditable(root) ?: return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t)
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findScrollable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isScrollable) return n
        for (i in 0 until n.childCount) {
            val r = findScrollable(n.getChild(i))
            if (r != null) return r
        }
        return null
    }

    fun scroll(down: Boolean): Boolean {
        val s = findScrollable(rootInActiveWindow) ?: return false
        return s.performAction(
            if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        )
    }

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
}
