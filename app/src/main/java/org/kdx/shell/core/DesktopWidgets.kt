package org.kdx.shell.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The widgets (Plasma calls them plasmoids) that can be put on the desktop. */
enum class WidgetType(val title: String, val description: String, val multiple: Boolean = false) {
    CLOCK("Digital Clock", "The time and date in big numbers"),
    ANALOG_CLOCK("Analog Clock", "A clock with hands"),
    CALENDAR("Calendar", "This month, with today marked"),
    SYSTEM("System Load", "Memory and battery use"),
    BATTERY("Battery", "Charge level, charging and temperature"),
    NOTE("Sticky Note", "A note you type on – add as many as you like", multiple = true),
}

/**
 * A widget on the desktop. A sticky note's [text] is its own; null means it's the note shared with the Notes app
 * (KNotes) – the desktop's first note, as before widgets could be added and removed.
 */
class DesktopWidget(val id: Int, val type: WidgetType, text: String? = null) {
    var text by mutableStateOf(text)

    companion object {
        /** Saved form: one entry per widget, "TYPE" or "NOTE:<base64 text>", separated by '|'. */
        fun serialize(list: List<DesktopWidget>): String = list.joinToString("|") { w ->
            val t = w.text
            if (w.type == WidgetType.NOTE && t != null) "NOTE:" + java.util.Base64.getEncoder().encodeToString(t.toByteArray())
            else w.type.name
        }

        /** Reads [saved]; unknown entries are skipped. Ids start at [firstId]. */
        fun parse(saved: String, firstId: Int = 1): List<DesktopWidget> {
            var id = firstId
            return saved.split('|').filter { it.isNotBlank() }.mapNotNull { e ->
                val type = runCatching { WidgetType.valueOf(e.substringBefore(':')) }.getOrNull() ?: return@mapNotNull null
                val text = if (type == WidgetType.NOTE && e.contains(':'))
                    runCatching { String(java.util.Base64.getDecoder().decode(e.substringAfter(':'))) }.getOrDefault("") else null
                DesktopWidget(id++, type, text)
            }
        }

        /** The widgets of a KDX from before widgets could be added: its three on/off switches. */
        fun fromOldSwitches(clock: Boolean, system: Boolean, note: Boolean): List<DesktopWidget> =
            listOfNotNull(
                WidgetType.CLOCK.takeIf { clock }, WidgetType.SYSTEM.takeIf { system }, WidgetType.NOTE.takeIf { note },
            ).mapIndexed { i, t -> DesktopWidget(i + 1, t) }
    }
}
