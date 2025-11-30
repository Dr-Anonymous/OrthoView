package life.ortho.ortholink.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class WhatsAppAutomationService : AccessibilityService() {

    companion object {
        private const val TAG = "WhatsAppAutoService"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"
        private const val SEND_BUTTON_ID = "com.whatsapp:id/send" // This ID might change!
        private const val SEND_BUTTON_DESC = "Send"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "WhatsApp Automation Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName != WHATSAPP_PACKAGE && event.packageName != WHATSAPP_BUSINESS_PACKAGE) {
            return
        }

        // CRITICAL FIX: Only proceed if we initiated the action
        if (!AutomationState.shouldSend) {
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            
            val rootNode = rootInActiveWindow ?: return
            
            // Check for "Not on WhatsApp" dialog
            val errorNode = findNodeByText(rootNode, "on WhatsApp")
            if (errorNode != null) {
                handleErrorAndFallback(rootNode, "Not on WhatsApp")
                return
            }

            // Check for "Couldn't connect" dialog
            val connectionErrorNode = findNodeByText(rootNode, "Couldn't connect")
            if (connectionErrorNode != null) {
                Log.d(TAG, "Detected Connection Error")
                
                if (AutomationState.retryCount < 1) {
                    Log.d(TAG, "Retrying... (Count: ${AutomationState.retryCount})")
                    AutomationState.retryCount++
                    
                    // Click OK to dismiss
                    val okButton = findNodeByText(rootNode, "OK")
                    okButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    
                    // Back out to exit
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    try { Thread.sleep(500) } catch (e: Exception) {}
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    
                    // Trigger Retry Broadcast
                    val intent = android.content.Intent("life.ortho.ortholink.ACTION_RETRY")
                    intent.setPackage(packageName)
                    sendBroadcast(intent)
                    
                    AutomationState.shouldSend = false
                    return
                } else {
                    Log.d(TAG, "Retry limit reached. Falling back to SMS.")
                    handleErrorAndFallback(rootNode, "Couldn't connect")
                    return
                }
            }

            // Check for "No Internet" dialog/message
            val internetErrorNode = findNodeByText(rootNode, "internet")
            if (internetErrorNode != null) {
                Log.d(TAG, "Detected Internet Error")
                
                if (AutomationState.retryCount < 1) {
                    Log.d(TAG, "Retrying... (Count: ${AutomationState.retryCount})")
                    AutomationState.retryCount++
                    
                    // Back out to exit
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    try { Thread.sleep(500) } catch (e: Exception) {}
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    
                    // Trigger Retry Broadcast (Optional, can be implemented if needed)
                    // val intent = android.content.Intent("life.ortho.ortholink.ACTION_RETRY")
                    // intent.setPackage(packageName)
                    // sendBroadcast(intent)
                    
                    AutomationState.shouldSend = false
                    return
                } else {
                    Log.d(TAG, "Retry limit reached. Falling back to SMS.")
                    handleErrorAndFallback(rootNode, "No Internet")
                    return
                }
            }

            // Attempt to find and click the send button
            val sendButton = findSendButton(rootNode)
            if (sendButton != null) {
                Log.d(TAG, "Send button found...")
                
                // Wait a brief moment to ensure UI is ready
                try {
                    var delay = 500L
                    if (AutomationState.hasLink) {
                        Log.d(TAG, "Link detected, waiting 2.5s for preview...")
                        delay = 2500L // 2.5 seconds for link preview
                    }
                    Thread.sleep(delay) 
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                }

                Log.d(TAG, "Clicking send button now")
                val clicked = sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (clicked) {
                    Log.d(TAG, "Send button clicked successfully")
                    
                    // Reset the flag immediately to prevent double clicks or accidental clicks
                    AutomationState.shouldSend = false
                    
                    // Wait for message to send, then go back
                    try {
                        Thread.sleep(1000)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    Log.d(TAG, "Performed first BACK action")

                    // Wait for chat to close, then go back again to exit app
                    try {
                        Thread.sleep(500)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    Log.d(TAG, "Performed second BACK action")
                } else {
                    Log.e(TAG, "Failed to click send button")
                }
            }
        }
    }

    private fun findNodeByText(rootNode: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val list = rootNode.findAccessibilityNodeInfosByText(text)
        if (list != null && list.isNotEmpty()) {
            return list[0]
        }
        return null
    }

    private fun handleErrorAndFallback(rootNode: AccessibilityNodeInfo, reason: String) {
        Log.d(TAG, "Handling Error: $reason")
        
        // Click OK if present (to dismiss dialogs)
        val okButton = findNodeByText(rootNode, "OK")
        okButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        
        // Go back to exit
        performGlobalAction(GLOBAL_ACTION_BACK)
        try { Thread.sleep(500) } catch (e: Exception) {}
        performGlobalAction(GLOBAL_ACTION_BACK)
        
        // Send SMS Fallback
        val phone = AutomationState.currentPhoneNumber
        val msg = AutomationState.currentMessage
        if (phone != null && msg != null) {
            sendSMS(phone, msg)
        } else {
            Log.e(TAG, "Cannot send SMS: Phone or Message is null")
        }
        
        // Reset state
        AutomationState.shouldSend = false
        AutomationState.retryCount = 0 // Reset retry count after fallback
    }
    
    private fun sendSMS(phoneNumber: String, message: String) {
        try {
            Log.d(TAG, "Attempting to send SMS to $phoneNumber")
            val smsManager = android.telephony.SmsManager.getDefault()
            smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            Log.d(TAG, "SMS sent successfully")
            android.widget.Toast.makeText(this, "Sent via SMS", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun findSendButton(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 1. Try by Resource ID
        val nodesById = rootNode.findAccessibilityNodeInfosByViewId(SEND_BUTTON_ID)
        if (nodesById != null && nodesById.isNotEmpty()) {
            return nodesById[0]
        }

        // 2. Try by Content Description (Fallback)
        val nodesByDesc = rootNode.findAccessibilityNodeInfosByText(SEND_BUTTON_DESC)
        if (nodesByDesc != null && nodesByDesc.isNotEmpty()) {
            for (node in nodesByDesc) {
                if (node.className == "android.widget.ImageButton" || node.className == "android.widget.ImageView") {
                     return node
                }
            }
        }
        
        return null
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service Interrupted")
    }
}
