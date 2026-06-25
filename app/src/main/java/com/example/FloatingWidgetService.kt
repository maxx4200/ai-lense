package com.example

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class FloatingWidgetService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private lateinit var collapsedView: View
    private lateinit var expandedView: View
    private lateinit var extractedTextView: TextView
    private lateinit var suggestionsProgress: ProgressBar
    private lateinit var suggestionsContainer: LinearLayout

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "FloatingWidgetService onCreate")
        instance = this

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Inflate the floating widget layout
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_widget_layout, null)

        // Set up WindowManager parameters
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 150
            y = 250
        }

        // Add the view to the WindowManager
        windowManager.addView(floatingView, params)

        // Find view components
        collapsedView = floatingView!!.findViewById(R.id.collapsed_view)
        expandedView = floatingView!!.findViewById(R.id.expanded_view)
        extractedTextView = floatingView!!.findViewById(R.id.extracted_message_text)
        suggestionsProgress = floatingView!!.findViewById(R.id.suggestions_progress)
        suggestionsContainer = floatingView!!.findViewById(R.id.suggestions_container)

        val closeBtn = floatingView!!.findViewById<ImageView>(R.id.close_btn)

        // Handle Close button click
        closeBtn.setOnClickListener {
            collapseToBubble()
        }

        // Set up Drag & Drop for the collapsed bubble
        collapsedView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params!!.x
                    initialY = params!!.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()

                    if (Math.abs(deltaX) > 10 || Math.abs(deltaY) > 10) {
                        isDragging = true
                    }

                    if (isDragging) {
                        params!!.x = initialX + deltaX
                        params!!.y = initialY + deltaY
                        windowManager.updateViewLayout(floatingView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        // User dropped the magnifier bubble. Trigger Text Extraction
                        val bubbleWidth = collapsedView.width
                        val bubbleHeight = collapsedView.height
                        val centerX = params!!.x + (bubbleWidth / 2)
                        val centerY = params!!.y + (bubbleHeight / 2)

                        Log.d(TAG, "Dropped bubble at center: ($centerX, $centerY)")

                        val accessService = ChatAccessibilityService.instance
                        if (accessService != null) {
                            Toast.makeText(this, "Extracting text...", Toast.LENGTH_SHORT).show()
                            accessService.extractTextAt(centerX, centerY)
                        } else {
                            Toast.makeText(
                                this,
                                "Accessibility Service is not running. Please enable it in Settings.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } else {
                        // Click event: If not dragging, we can expand to reveal previous instructions or input
                        Toast.makeText(this, "Drag and drop over a message to analyze!", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Called by ChatAccessibilityService when screen text is successfully extracted.
     */
    fun onTextExtracted(text: String) {
        if (text.isBlank()) {
            Toast.makeText(this, "No text found under magnifier. Try dragging precisely over a chat bubble.", Toast.LENGTH_LONG).show()
            return
        }

        Log.d(TAG, "onTextExtracted: $text")

        // Switch layout state to Expanded
        expandCard()
        extractedTextView.text = "\"$text\""

        // Load Gemini de-escalation suggestions
        fetchDeescalation(text)
    }

    private fun fetchDeescalation(text: String) {
        // Clear previous suggestions
        suggestionsContainer.removeAllViews()
        suggestionsProgress.visibility = View.VISIBLE

        serviceScope.launch {
            try {
                val suggestions = GeminiAIEngine.getDeescalationSuggestions(text)
                suggestionsProgress.visibility = View.GONE
                displaySuggestions(suggestions)
            } catch (e: Exception) {
                Log.e(TAG, "Error generating suggestions", e)
                suggestionsProgress.visibility = View.GONE
                Toast.makeText(this@FloatingWidgetService, "AI Generation failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                collapseToBubble()
            }
        }
    }

    private fun displaySuggestions(suggestions: List<String>) {
        suggestionsContainer.removeAllViews()
        val scale = resources.displayMetrics.density
        val paddingHorizontal = (16 * scale + 0.5f).toInt()
        val paddingVertical = (12 * scale + 0.5f).toInt()
        val bulletPadding = (12 * scale + 0.5f).toInt()

        for (suggestion in suggestions) {
            val textView = TextView(this).apply {
                this.text = suggestion
                this.setTextColor(android.graphics.Color.parseColor("#1C1B1F"))
                this.textSize = 13.5f
                this.setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical)
                this.background = ContextCompat.getDrawable(context, R.drawable.suggestion_item_bg)
                
                // Add the tiny purple circle bullet to the left of the suggestion
                val bullet = ContextCompat.getDrawable(context, R.drawable.bullet_dot)
                this.setCompoundDrawablesWithIntrinsicBounds(bullet, null, null, null)
                this.compoundDrawablePadding = bulletPadding
                
                this.isClickable = true
                this.isFocusable = true
                
                val layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, (6 * scale + 0.5f).toInt(), 0, (6 * scale + 0.5f).toInt())
                }
                this.layoutParams = layoutParams

                setOnClickListener {
                    onSuggestionClicked(suggestion)
                }
            }
            suggestionsContainer.addView(textView)
        }
    }

    private fun onSuggestionClicked(suggestion: String) {
        Log.d(TAG, "Selected suggestion: $suggestion")

        // Paste selection into active field using Accessibility Service
        val accessService = ChatAccessibilityService.instance
        if (accessService != null) {
            val pasted = accessService.pasteText(suggestion)
            if (pasted) {
                Toast.makeText(this, "Suggestion pasted!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Failed to paste. Try clicking inside the typing field first.", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "Accessibility Service is not active.", Toast.LENGTH_SHORT).show()
        }

        // Collapse back to magnifier bubble
        collapseToBubble()
    }

    private fun expandCard() {
        collapsedView.visibility = View.GONE
        expandedView.visibility = View.VISIBLE

        // Change layout dimensions to wrap content but larger max constraints
        params?.width = WindowManager.LayoutParams.WRAP_CONTENT
        params?.height = WindowManager.LayoutParams.WRAP_CONTENT
        
        // Remove Flag Not Focusable when expanded so we can scroll or select if needed,
        // but wait: keeping it NOT_FOCUSABLE is safer so the user can still type below.
        // Let's add FLAG_NOT_TOUCH_MODAL so we don't consume touches outside our window.
        params?.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        
        floatingView?.let { windowManager.updateViewLayout(it, params) }
    }

    private fun collapseToBubble() {
        expandedView.visibility = View.GONE
        collapsedView.visibility = View.VISIBLE

        params?.width = WindowManager.LayoutParams.WRAP_CONTENT
        params?.height = WindowManager.LayoutParams.WRAP_CONTENT
        params?.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

        floatingView?.let { windowManager.updateViewLayout(it, params) }
    }

    override fun onDestroy() {
        Log.d(TAG, "FloatingWidgetService onDestroy")
        serviceScope.cancel()
        floatingView?.let {
            windowManager.removeView(it)
        }
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FloatingWidgetService"
        var instance: FloatingWidgetService? = null
            private set
    }
}
