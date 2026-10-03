package run.bestmodel.dictate

import android.content.Context

class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("dictate", Context.MODE_PRIVATE)

    var autoInsert: Boolean
        get() = prefs.getBoolean("auto_insert", true)
        set(value) = prefs.edit().putBoolean("auto_insert", value).apply()

    var removeFillers: Boolean
        get() = prefs.getBoolean("remove_fillers", true)
        set(value) = prefs.edit().putBoolean("remove_fillers", value).apply()

    var voiceCommands: Boolean
        get() = prefs.getBoolean("voice_commands", true)
        set(value) = prefs.edit().putBoolean("voice_commands", value).apply()

    var bubbleX: Int
        get() = prefs.getInt("bubble_x", -1)
        set(value) = prefs.edit().putInt("bubble_x", value).apply()

    var bubbleY: Int
        get() = prefs.getInt("bubble_y", 400)
        set(value) = prefs.edit().putInt("bubble_y", value).apply()

    val polishOptions get() = TextPolisher.Options(removeFillers = removeFillers, voiceCommands = voiceCommands)
}
