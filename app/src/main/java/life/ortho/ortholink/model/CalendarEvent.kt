package life.ortho.ortholink.model

import com.google.gson.annotations.SerializedName

data class CalendarEvent(
    @SerializedName("start") val start: String,
    @SerializedName("description") val description: String,
    @SerializedName("attachments") val attachments: String?
)

data class CalendarEventResponse(
    @SerializedName("calendarEvents") val calendarEvents: List<CalendarEvent>
)
