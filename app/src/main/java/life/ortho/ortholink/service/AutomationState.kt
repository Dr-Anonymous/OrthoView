package life.ortho.ortholink.service

object AutomationState {
    var shouldSend: Boolean = false
    var currentPhoneNumber: String? = null
    var currentMessage: String? = null
    var hasLink: Boolean = false
    var retryCount: Int = 0
}
