package com.alicontrol.app.ui.dashboard

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.alicontrol.app.dashboard.DashboardPage

/**
 * Feeds [DashboardPage]s into a `ViewPager2`. A connection-state change
 * rebinds only the currently attached pages; pages outside ViewPager2's
 * normal cache are rendered with the latest state when first shown.
 */
class DashboardPagerAdapter(
    private val pages: List<DashboardPage>,
    private val renderer: DashboardPageRenderer
) : RecyclerView.Adapter<DashboardPagerAdapter.PageViewHolder>() {

    var isConnected: Boolean = false
        private set

    fun updateConnectionState(connected: Boolean) {
        if (isConnected == connected) return
        isConnected = connected
        notifyItemRangeChanged(0, itemCount, CONNECTION_STATE_PAYLOAD)
    }

    class PageViewHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val container = FrameLayout(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        return PageViewHolder(container)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.container.removeAllViews()
        val page = pages[position]
        val pageView = renderer.render(LayoutInflater.from(holder.container.context), holder.container, page, isConnected)
        holder.container.addView(pageView)
    }

    override fun getItemCount(): Int = pages.size

    private companion object {
        const val CONNECTION_STATE_PAYLOAD = "connection_state"
    }
}
