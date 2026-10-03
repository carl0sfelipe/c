package run.bestmodel.dictate

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Optional. When the user turns it on, the transcript is typed straight into whatever text
 * field has focus in any app. It reads nothing else: no events are processed.
 */
class InsertService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Returns false when there is no focused editable field. */
    fun insert(text: String): Boolean {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!node.isEditable) return false

        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("ditado", text))
        if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true

        // Some fields refuse paste: rebuild the text around the cursor instead.
        val current = if (Build.VERSION.SDK_INT >= 26 && node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val start = node.textSelectionStart.takeIf { it in 0..current.length } ?: current.length
        val end = node.textSelectionEnd.takeIf { it in start..current.length } ?: start
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                current.substring(0, start) + text + current.substring(end),
            )
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    companion object {
        @Volatile var instance: InsertService? = null
            private set
    }
}
