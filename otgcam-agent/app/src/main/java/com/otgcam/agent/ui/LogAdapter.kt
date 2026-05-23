package com.otgcam.agent.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.otgcam.agent.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RecyclerView adapter that displays timestamped log entries for the Agent status screen.
 *
 * Maintains a capped list of the last [MAX_ENTRIES] entries to avoid unbounded memory use.
 */
class LogAdapter : RecyclerView.Adapter<LogAdapter.LogViewHolder>() {

    private val entries = ArrayDeque<LogEntry>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    /**
     * Appends a new log message with the current timestamp.
     * Removes the oldest entry if the list exceeds [MAX_ENTRIES].
     */
    fun addEntry(message: String) {
        if (entries.size >= MAX_ENTRIES) {
            entries.removeFirst()
            notifyItemRemoved(0)
        }
        entries.addLast(LogEntry(System.currentTimeMillis(), message))
        notifyItemInserted(entries.size - 1)
    }

    /** Removes all log entries. */
    fun clear() {
        val size = entries.size
        entries.clear()
        notifyItemRangeRemoved(0, size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log_entry, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val entry = entries[position]
        holder.timeView.text = timeFormat.format(Date(entry.timestamp))
        holder.messageView.text = entry.message
    }

    override fun getItemCount(): Int = entries.size

    class LogViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val timeView: TextView = view.findViewById(R.id.tvLogTime)
        val messageView: TextView = view.findViewById(R.id.tvLogMessage)
    }

    private data class LogEntry(val timestamp: Long, val message: String)

    companion object {
        private const val MAX_ENTRIES = 50
    }
}
