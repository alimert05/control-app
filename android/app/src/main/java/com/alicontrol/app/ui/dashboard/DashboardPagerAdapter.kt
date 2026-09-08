package com.alicontrol.app.ui.dashboard

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.alicontrol.app.dashboard.DashboardPage

/**
 * Feeds [DashboardPage]s into a `ViewPager2`.
 *
 * Binding a page renders it from scratch (see [DashboardPageRenderer]).
 * That's deliberate: a page is a handful of small cards, so a full rebuild
 * after an edit costs little, and it removes a whole class of bugs where a
 * stale view keeps a widget's old position, connection state or selection
 * highlight. The model is the only source of truth for what's on screen.
 */
class DashboardPagerAdapter(
    private val renderer: DashboardPageRenderer,
    private val editListener: DashboardEditListener
) : RecyclerView.Adapter<DashboardPagerAdapter.PageViewHolder>() {

    private var pages: List<DashboardPage> = emptyList()
    private var isConnected: Boolean = false
    private var editState: DashboardEditState = DashboardEditState()

    class PageViewHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container)

    /**
     * Replaces everything the adapter renders. Any change to pages,
     * connection or edit state comes through here.
     *
     * `notifyDataSetChanged` is deliberate rather than lazy: a single edit can
     * change the page count (create/delete), the order (reorder), one page's
     * widgets, or the state every page renders with, and there are at most a
     * handful of tiny pages. A blanket rebuild is both cheaper to reason about
     * and immune to the stale-view bugs finer-grained notifications invite.
     */
    @SuppressLint("NotifyDataSetChanged")
    fun submit(pages: List<DashboardPage>, isConnected: Boolean, editState: DashboardEditState) {
        this.pages = pages
        this.isConnected = isConnected
        this.editState = editState
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val container = FrameLayout(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        return PageViewHolder(container)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.container.removeAllViews()
        val page = pages[position]
        holder.container.addView(
            renderer.render(
                LayoutInflater.from(holder.container.context),
                holder.container,
                page,
                isConnected,
                editState,
                editListener
            )
        )
    }

    override fun getItemCount(): Int = pages.size
}
