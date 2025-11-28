package com.maenmax.heartratemonitor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.maenmax.heartratemonitor.data.HeartRateDatabase
import com.maenmax.heartratemonitor.data.HeartRateMeasurement
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class HistoryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var avgBpmText: TextView
    private lateinit var minBpmText: TextView
    private lateinit var maxBpmText: TextView
    private lateinit var totalMeasurementsText: TextView
    private lateinit var clearAllButton: FloatingActionButton
    private lateinit var backButton: View

    private lateinit var database: HeartRateDatabase
    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        database = HeartRateDatabase.getDatabase(this)

        // Initialize views
        recyclerView = findViewById(R.id.historyRecyclerView)
        emptyView = findViewById(R.id.emptyView)
        avgBpmText = findViewById(R.id.avgBpmText)
        minBpmText = findViewById(R.id.minBpmText)
        maxBpmText = findViewById(R.id.maxBpmText)
        totalMeasurementsText = findViewById(R.id.totalMeasurementsText)
        clearAllButton = findViewById(R.id.clearAllButton)
        backButton = findViewById(R.id.backButton)

        // Setup RecyclerView
        adapter = HistoryAdapter { measurement ->
            showDeleteDialog(measurement)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // Back button
        backButton.setOnClickListener {
            finish()
        }

        // Clear all button
        clearAllButton.setOnClickListener {
            showClearAllDialog()
        }

        // Observe measurements
        observeMeasurements()

        // Load stats
        loadStats()
    }

    private fun observeMeasurements() {
        lifecycleScope.launch {
            database.heartRateDao().getAllMeasurements().collectLatest { measurements ->
                adapter.submitList(measurements)
                if (measurements.isEmpty()) {
                    recyclerView.visibility = View.GONE
                    emptyView.visibility = View.VISIBLE
                } else {
                    recyclerView.visibility = View.VISIBLE
                    emptyView.visibility = View.GONE
                }
                loadStats()
            }
        }
    }

    private fun loadStats() {
        lifecycleScope.launch {
            // Get stats for last 7 days
            val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)

            val avgBpm = database.heartRateDao().getAverageBpmSince(sevenDaysAgo)
            val minBpm = database.heartRateDao().getMinBpmSince(sevenDaysAgo)
            val maxBpm = database.heartRateDao().getMaxBpmSince(sevenDaysAgo)
            val total = database.heartRateDao().getTotalMeasurements()

            avgBpmText.text = avgBpm?.toInt()?.toString() ?: "--"
            minBpmText.text = minBpm?.toString() ?: "--"
            maxBpmText.text = maxBpm?.toString() ?: "--"
            totalMeasurementsText.text = total.toString()
        }
    }

    private fun showDeleteDialog(measurement: HeartRateMeasurement) {
        AlertDialog.Builder(this, R.style.AlertDialogTheme)
            .setTitle("Delete Measurement")
            .setMessage("Are you sure you want to delete this measurement?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    database.heartRateDao().deleteMeasurement(measurement)
                    Toast.makeText(this@HistoryActivity, "Measurement deleted", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showClearAllDialog() {
        AlertDialog.Builder(this, R.style.AlertDialogTheme)
            .setTitle("Clear All History")
            .setMessage("Are you sure you want to delete all measurements? This cannot be undone.")
            .setPositiveButton("Clear All") { _, _ ->
                lifecycleScope.launch {
                    database.heartRateDao().deleteAllMeasurements()
                    Toast.makeText(this@HistoryActivity, "All history cleared", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

class HistoryAdapter(
    private val onItemLongClick: (HeartRateMeasurement) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    private var measurements = listOf<HeartRateMeasurement>()

    fun submitList(list: List<HeartRateMeasurement>) {
        measurements = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(measurements[position])
    }

    override fun getItemCount() = measurements.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val bpmText: TextView = itemView.findViewById(R.id.itemBpmText)
        private val confidenceText: TextView = itemView.findViewById(R.id.itemConfidenceText)
        private val dateText: TextView = itemView.findViewById(R.id.itemDateText)
        private val timeText: TextView = itemView.findViewById(R.id.itemTimeText)

        fun bind(measurement: HeartRateMeasurement) {
            bpmText.text = measurement.bpm.toString()

            // Confidence badge
            confidenceText.text = measurement.confidence
            val confidenceColor = when (measurement.confidence) {
                "High confidence" -> "#00FF88"
                "Good" -> "#FFD700"
                else -> "#FF6B6B"
            }
            confidenceText.setTextColor(android.graphics.Color.parseColor(confidenceColor))

            // Format date and time
            val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
            val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
            val date = Date(measurement.timestamp)

            dateText.text = dateFormat.format(date)
            timeText.text = timeFormat.format(date)

            itemView.setOnLongClickListener {
                onItemLongClick(measurement)
                true
            }
        }
    }
}
