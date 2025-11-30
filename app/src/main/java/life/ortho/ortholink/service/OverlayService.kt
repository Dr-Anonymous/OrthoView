package life.ortho.ortholink.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import life.ortho.ortholink.R
import life.ortho.ortholink.model.CalendarEvent
import life.ortho.ortholink.model.CalendarEventResponse
import life.ortho.ortholink.model.Patient
import life.ortho.ortholink.model.PatientDetails
import life.ortho.ortholink.network.SupabaseClient
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var phoneNumber: String? = null

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private val CHANNEL_ID = "OverlayServiceChannel"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = android.app.Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Caller ID Active")
            .setContentText("Identifying caller...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()

        // MUST call startForeground immediately, otherwise the app will crash
        // if started with startForegroundService()
        startForeground(1, notification)

        if (intent?.action == "STOP") {
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == "STOP_WITH_DELAY") {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                stopForeground(true)
                stopSelf()
            }, 5000) // 5 seconds delay
            return START_NOT_STICKY
        }

        phoneNumber = intent?.getStringExtra("PHONE_NUMBER")
        if (phoneNumber != null) {
            // Check if number is in contacts
            if (contactExists(this, phoneNumber!!)) {
                // It's a saved contact, do not show overlay
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }

            // Fetch data FIRST, then show overlay
            fetchData(phoneNumber!!)
        }
        return START_NOT_STICKY
    }

    private fun contactExists(context: Context, number: String): Boolean {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return false // Permission not granted, assume unknown
        }
        
        val lookupUri = android.net.Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(number))
        val projection = arrayOf(android.provider.ContactsContract.PhoneLookup._ID)
        var cur: android.database.Cursor? = null
        try {
            cur = context.contentResolver.query(lookupUri, projection, null, null, null)
            if (cur != null && cur.moveToFirst()) {
                return true // Contact found
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cur?.close()
        }
        return false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = android.app.NotificationChannel(
                CHANNEL_ID,
                "Caller ID Service",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun showOverlay(phone: String, patient: PatientDetails?, calendarEvents: List<CalendarEvent>?) {
        if (overlayView != null) return // Already showing

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )

        // layoutParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL // Not needed for match_parent
        // layoutParams.y = 100 // Not needed

        // Use ContextThemeWrapper to ensure theme attributes (like ?attr/selectableItemBackgroundBorderless) are resolved
        val contextThemeWrapper = android.view.ContextThemeWrapper(this, R.style.Theme_OrthoLink)
        overlayView = LayoutInflater.from(contextThemeWrapper).inflate(R.layout.layout_caller_id, null)

        val tvCallerNumber = overlayView!!.findViewById<TextView>(R.id.tvCallerNumber)
        val tvCallerName = overlayView!!.findViewById<TextView>(R.id.tvCallerName)
        val layoutLocation = overlayView!!.findViewById<LinearLayout>(R.id.layoutLocation)
        val tvLocation = overlayView!!.findViewById<TextView>(R.id.tvLocation)
        val tvDate = overlayView!!.findViewById<TextView>(R.id.tvDate)
        
        val layoutActions = overlayView!!.findViewById<LinearLayout>(R.id.layoutActions)
        val btnReceiveCall = overlayView!!.findViewById<Button>(R.id.btnReceiveCall)
        val btnWhatsApp = overlayView!!.findViewById<Button>(R.id.btnWhatsApp)
        val btnClinic = overlayView!!.findViewById<Button>(R.id.btnClinic)
        val btnLaxmi = overlayView!!.findViewById<Button>(R.id.btnLaxmi)
        val btnBadam = overlayView!!.findViewById<Button>(R.id.btnBadam)

        val layoutDetails = overlayView!!.findViewById<LinearLayout>(R.id.layoutDetails)
        val cardDetails = overlayView!!.findViewById<androidx.cardview.widget.CardView>(R.id.cardDetails)
        val layoutCalendarEvents = overlayView!!.findViewById<LinearLayout>(R.id.layoutCalendarEvents)
        val cardCalendarEvents = overlayView!!.findViewById<androidx.cardview.widget.CardView>(R.id.cardCalendarEvents)

        tvCallerNumber.text = phone
        
        if (patient != null) {
            // Use name from API if available, otherwise construct it
            val displayName = patient.name ?: "${patient.firstName ?: ""} ${patient.lastName ?: ""}".trim()
            tvCallerName.text = if (displayName.isNotEmpty()) displayName else "Unknown Caller"
            
            // Location
            if (!patient.location.isNullOrEmpty()) {
                tvLocation.text = patient.location
                layoutLocation.visibility = View.VISIBLE
            }

            // Date
            if (!patient.createdAt.isNullOrEmpty()) {
                try {
                    // Simple date formatting, assuming ISO string
                    val date = patient.createdAt.substring(0, 10)
                    tvDate.text = date
                    tvDate.visibility = View.VISIBLE
                } catch (e: Exception) {
                    // Ignore parsing errors
                }
            }
            
            // Bind details
            bindDetail(overlayView!!.findViewById(R.id.layoutPersonalNote), overlayView!!.findViewById(R.id.tvPersonalNote), patient.personalNote)
            bindDetail(overlayView!!.findViewById(R.id.layoutComplaints), overlayView!!.findViewById(R.id.tvComplaints), patient.complaints)
            bindDetail(overlayView!!.findViewById(R.id.layoutFindings), overlayView!!.findViewById(R.id.tvFindings), patient.findings)
            bindDetail(overlayView!!.findViewById(R.id.layoutInvestigations), overlayView!!.findViewById(R.id.tvInvestigations), patient.investigations)
            bindDetail(overlayView!!.findViewById(R.id.layoutDiagnosis), overlayView!!.findViewById(R.id.tvDiagnosis), patient.diagnosis)
            bindDetail(overlayView!!.findViewById(R.id.layoutAdvice), overlayView!!.findViewById(R.id.tvAdvice), patient.advice)
            bindDetail(overlayView!!.findViewById(R.id.layoutFollowUp), overlayView!!.findViewById(R.id.tvFollowUp), patient.followup)

            // Parse medications - NAMES ONLY
            var medsText: String? = null
            if (patient.medications != null) {
                if (patient.medications.isJsonArray) {
                    val medsArray = patient.medications.asJsonArray
                    val medsList = mutableListOf<String>()
                    medsArray.forEach { 
                        if (it.isJsonObject) {
                            val obj = it.asJsonObject
                            val name = if (obj.has("name")) obj.get("name").asString else ""
                            if (name.isNotEmpty()) {
                                medsList.add(name)
                            }
                        }
                    }
                    if (medsList.isNotEmpty()) {
                        medsText = medsList.joinToString(", ")
                    }
                } else if (patient.medications.isJsonPrimitive) {
                    medsText = patient.medications.asString
                }
            }
            bindDetail(overlayView!!.findViewById(R.id.layoutMedications), overlayView!!.findViewById(R.id.tvMedications), medsText)

            layoutDetails.visibility = View.VISIBLE
            cardDetails.visibility = View.VISIBLE
        } else {
            tvCallerName.text = "Unknown Caller"
            layoutDetails.visibility = View.GONE
            cardDetails.visibility = View.GONE
        }
        
        // Bind Calendar Events
        if (!calendarEvents.isNullOrEmpty()) {
            val event = calendarEvents[0] // Show first event for now
            val tvEventDate = overlayView!!.findViewById<TextView>(R.id.tvCalendarEventDate)
            val tvEventDesc = overlayView!!.findViewById<TextView>(R.id.tvCalendarEventDescription)
            
            try {
                tvEventDate.text = event.start.replace("T", " ").substring(0, 16)
            } catch (e: Exception) {
                tvEventDate.text = event.start
            }
            // Parse and format description
            val description = event.description
            val lines = description.split("\n")
            val formattedDescription = StringBuilder()
            
            for (line in lines) {
                val parts = line.split(": ", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim()
                    val value = parts[1].trim()
                    
                    if (key.equals("Phone", ignoreCase = true) ||
                        key.equals("SMS", ignoreCase = true) ||
                        key.equals("WhatsApp", ignoreCase = true) ||
                        key.equals("Payment", ignoreCase = true)) {
                        continue
                    }
                    
                    if (key.equals("Patient", ignoreCase = true)) {
                        // If we don't have a patient name from the DB, use this one
                        if (tvCallerName.text == "Unknown Caller") {
                            tvCallerName.text = value
                        }
                        continue // Skip displaying Patient field in description
                    }

                    if (key.equals("DOB", ignoreCase = true)) {
                        try {
                            // Calculate Age
                            val sdf = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
                            val dobDate = sdf.parse(value)
                            if (dobDate != null) {
                                val dobCalendar = java.util.Calendar.getInstance()
                                dobCalendar.time = dobDate
                                val today = java.util.Calendar.getInstance()
                                var age = today.get(java.util.Calendar.YEAR) - dobCalendar.get(java.util.Calendar.YEAR)
                                if (today.get(java.util.Calendar.DAY_OF_YEAR) < dobCalendar.get(java.util.Calendar.DAY_OF_YEAR)) {
                                    age--
                                }
                                formattedDescription.append("Age: $age\n")
                            }
                        } catch (e: Exception) {
                            formattedDescription.append("$key: $value\n")
                        }
                    } else {
                        if (value.isNotEmpty()) {
                            formattedDescription.append("$key: $value\n")
                        }
                    }
                } else {
                     // Handle lines without ": " if necessary, or append as is if not empty
                     if (line.isNotBlank()) {
                         formattedDescription.append("$line\n")
                     }
                }
            }
            
            tvEventDesc.text = formattedDescription.toString().trim()
            
            val tvEventAttachment = overlayView!!.findViewById<TextView>(R.id.tvCalendarEventAttachment)
            val layoutAttachmentPreview = overlayView!!.findViewById<LinearLayout>(R.id.layoutAttachmentPreview)
            val wvAttachment = overlayView!!.findViewById<android.webkit.WebView>(R.id.wvAttachment)
            val btnCloseAttachment = overlayView!!.findViewById<Button>(R.id.btnCloseAttachment)

            if (!event.attachments.isNullOrEmpty()) {
                tvEventAttachment.visibility = View.VISIBLE
                
                // Configure WebView
                wvAttachment.settings.javaScriptEnabled = true
                wvAttachment.webViewClient = android.webkit.WebViewClient()
                
                tvEventAttachment.setOnClickListener {
                    layoutAttachmentPreview.visibility = View.VISIBLE
                    wvAttachment.loadUrl(event.attachments)
                }
                
                btnCloseAttachment.setOnClickListener {
                    layoutAttachmentPreview.visibility = View.GONE
                    wvAttachment.loadUrl("about:blank") // Clear content
                }
            } else {
                tvEventAttachment.visibility = View.GONE
                layoutAttachmentPreview.visibility = View.GONE
            }

            layoutCalendarEvents.visibility = View.VISIBLE
            cardCalendarEvents.visibility = View.VISIBLE
        } else {
            cardCalendarEvents.visibility = View.GONE
        }

        layoutActions.visibility = View.VISIBLE

        val btnSpeaker = overlayView!!.findViewById<ImageButton>(R.id.btnSpeaker)
        val btnRestore = overlayView!!.findViewById<ImageButton>(R.id.btnRestore)
        val mainContent = overlayView!!.findViewById<LinearLayout>(R.id.mainContent)

        btnSpeaker.setOnClickListener {
            toggleSpeaker()
        }

        btnRestore.setOnClickListener {
            restoreOverlay()
        }

        btnReceiveCall.setOnClickListener {
            acceptCall()
        }

        btnWhatsApp.setOnClickListener {
            openWhatsApp(phone, null)
        }

        btnClinic.setOnClickListener {
            val isSunday = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.SUNDAY
            val time = if (isSunday) "After 4 pm" else "After 7:30 pm"
            val message = "Dr Samuel Manoj Cherukuri\n98668 12555\n\n$time at  OrthoLife :\nRoad number 3,\nR R Nagar, near RTO office,\nKakinada\n\nLocation:\nhttps://g.co/kgs/6ZEukv"
            openWhatsApp(phone, message)
        }

        btnLaxmi.setOnClickListener {
            val message = "Dr Samuel Manoj Cherukuri\n98668 12555\n\n9-5 pm at:\nLaxmi Hospital,\nGudarigunta, Kakinada\n\nLocation:\nhttps://g.co/kgs/5Xkr4FU"
            openWhatsApp(phone, message)
        }

        btnBadam.setOnClickListener {
            val message = "Dr Samuel Manoj Cherukuri\n98668 12555\n\n5-7 pm at:\n Badam clinical laboratory \nhttps://g.co/kgs/eAgkp5S"
            openWhatsApp(phone, message)
        }

        try {
            windowManager?.addView(overlayView, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
            return
        }
    }

    private fun toggleSpeaker() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audioManager.mode = android.media.AudioManager.MODE_IN_CALL
        if (!audioManager.isSpeakerphoneOn) {
            audioManager.isSpeakerphoneOn = true
            Toast.makeText(this, "Speaker ON", Toast.LENGTH_SHORT).show()
        } else {
            audioManager.isSpeakerphoneOn = false
            Toast.makeText(this, "Speaker OFF", Toast.LENGTH_SHORT).show()
        }
    }

    private fun minimizeOverlay() {
        if (overlayView == null || windowManager == null) return

        val mainContent = overlayView!!.findViewById<LinearLayout>(R.id.mainContent)
        val btnRestore = overlayView!!.findViewById<ImageButton>(R.id.btnRestore)

        mainContent.visibility = View.GONE
        btnRestore.visibility = View.VISIBLE

        val layoutParams = overlayView!!.layoutParams as WindowManager.LayoutParams
        layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT
        layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
        layoutParams.gravity = Gravity.TOP or Gravity.END
        layoutParams.y = 100 // Offset

        try {
            windowManager!!.updateViewLayout(overlayView, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun restoreOverlay() {
        if (overlayView == null || windowManager == null) return

        val mainContent = overlayView!!.findViewById<LinearLayout>(R.id.mainContent)
        val btnRestore = overlayView!!.findViewById<ImageButton>(R.id.btnRestore)

        mainContent.visibility = View.VISIBLE
        btnRestore.visibility = View.GONE

        val layoutParams = overlayView!!.layoutParams as WindowManager.LayoutParams
        layoutParams.width = WindowManager.LayoutParams.MATCH_PARENT
        layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT
        
        try {
            windowManager!!.updateViewLayout(overlayView, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun acceptCall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
            if (androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ANSWER_PHONE_CALLS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                try {
                    telecomManager.acceptRingingCall()
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to answer call", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Not supported on this Android version", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openWhatsApp(phone: String, message: String?) {
        try {
            val url = if (message != null) {
                "https://api.whatsapp.com/send?phone=$phone&text=${java.net.URLEncoder.encode(message, "UTF-8")}"
            } else {
                "https://api.whatsapp.com/send?phone=$phone"
            }
            val i = Intent(Intent.ACTION_VIEW)
            i.data = Uri.parse(url)
            i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(i)
            
            // Minimize overlay instead of stopping service
            minimizeOverlay()
        } catch (e: Exception) {
            Toast.makeText(this, "WhatsApp not installed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun fetchData(phone: String) {
        // Format phone number: remove +91 or 91 prefix if present
        val cleanPhone = phone.replace(Regex("[^0-9]"), "")
        val queryPhone = if (cleanPhone.startsWith("91") && cleanPhone.length == 12) {
            cleanPhone.substring(2)
        } else {
            cleanPhone
        }

        var patientDetails: PatientDetails? = null
        var calendarEvents: List<CalendarEvent>? = null
        var patientReqDone = false
        var calendarReqDone = false

        fun checkAndShow() {
            if (patientReqDone && calendarReqDone) {
                showOverlay(phone, patientDetails, calendarEvents)
            }
        }

        SupabaseClient.api.searchPatients(
            SupabaseClient.API_KEY,
            "Bearer ${SupabaseClient.API_KEY}",
            life.ortho.ortholink.model.SearchRequest(searchTerm = queryPhone)
        ).enqueue(object : Callback<List<PatientDetails>> {
            override fun onResponse(call: Call<List<PatientDetails>>, response: Response<List<PatientDetails>>) {
                if (response.isSuccessful && !response.body().isNullOrEmpty()) {
                    patientDetails = response.body()!![0]
                }
                patientReqDone = true
                checkAndShow()
            }

            override fun onFailure(call: Call<List<PatientDetails>>, t: Throwable) {
                patientReqDone = true
                checkAndShow()
            }
        })

        SupabaseClient.api.searchCalendarEvents(
            SupabaseClient.API_KEY,
            "Bearer ${SupabaseClient.API_KEY}",
            mapOf("phoneNumber" to queryPhone)
        ).enqueue(object : Callback<CalendarEventResponse> {
            override fun onResponse(call: Call<CalendarEventResponse>, response: Response<CalendarEventResponse>) {
                if (response.isSuccessful && response.body() != null) {
                    calendarEvents = response.body()!!.calendarEvents
                }
                calendarReqDone = true
                checkAndShow()
            }

            override fun onFailure(call: Call<CalendarEventResponse>, t: Throwable) {
                calendarReqDone = true
                checkAndShow()
            }
        })
    }

    private fun bindDetail(layout: View, textView: TextView, value: String?) {
        if (!value.isNullOrEmpty() && value != "-") {
            textView.text = value
            layout.visibility = View.VISIBLE
        } else {
            layout.visibility = View.GONE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (overlayView != null && windowManager != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            overlayView = null
        }
    }
}
