package life.ortho.ortholink.model

import com.google.gson.annotations.SerializedName

data class Patient(
    @SerializedName("id") val id: String,
    @SerializedName("first_name") val firstName: String?,
    @SerializedName("last_name") val lastName: String?,
    @SerializedName("phone") val phone: String?,
    @SerializedName("uhid") val uhid: String?
)
