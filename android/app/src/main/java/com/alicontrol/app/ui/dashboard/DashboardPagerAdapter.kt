package com.alicontrol.app.ui.dashboard

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.alicontrol.app.dashboard.DashboardPage
import com.alicontrol.app.live.DesktopState

/**
 * Feeds [DashboardPage]s into a `ViewPager2`.
 *
 * Binding a page renders it from scratch (see [DashboardPageRenderer]).
 * That's deliberate: a page is a handful of small cards, so a full rebuild
 * after an edit costs little, and it removes a whole class of bugs where a
 * stale view keeps a widget's old position, connection state or selection
 * highlight. The model is the only source of truth for what's on screen.
 *
 * Live desktop data is the one thing that does *not* rebuild a page.
 * [submitLiveState] pushes new values through the per-page
 * [LiveWidgetBindings] collected at render time, so a CPU reading arriving
 * every two seconds updates a couple of `TextView`s rather than re-inflating
 * the dashboard.
 */
class DashboardPagerAdapter(
    private val renderer: DashboardPageRenderer,
    private val editListener: DashboardEditListener
) : RecyclerView.Adapter<DashboardPagerAdapter.PageViewHolder>() {

    private var pages: List<DashboardPage> = emptyList()
    private var isConnected: Boolean = false
    private var editState: DashboardEditState = DashboardEditState()
    private var liveState: DesktopState = DesktopState.EMPTY

    /** The page views currently bound, so live updates reach exactly them. */
    private val boundHolders = mutableSetOf<PageViewHolder>()

    class PageViewHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container) {
        val bindings = LiveWidgetBindings()
    }

    /** Replaces everything the adapter renders. Any change to pages, connection or edit state comes through here. */
    @SuppressLint("NotifyDataSetChanged")
    fun submit(pages: List<DashboardPage>, isConnected: Boolean, editState: DashboardEditState) {
        this.pages = pages
        this.isConnected = isConnected
        this.editState = editState
        notifyDataSetChanged()
    }

    /**
     * Pushes the latest desktop state into every bound page. No page is
     * re-inflated and no widget is recreated — this only writes new text and
     * bar widths into views that already exist.
     */
    fun submitLiveState(state: DesktopState) {
        liveState = state
        for (holder in boundHolders) holder.bindings.apply(state)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val container = FrameLayout(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        return PageViewHolder(container)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.container.removeAllViews()
        // Old closures point at views that are about to be discarded.
        holder.bindings.clear()

        holder.container.addView(
            renderer.render(
                LayoutInflater.from(holder.container.context),
                holder.container,
                pages[position],
                isConnected,
                editState,
                editListener,
                holder.bindings
            )
        )
        boundHolders.add(holder)
        // A page arriving mid-session (a swipe, or a rebuild after an edit)
        // starts from current state rather than an empty one.
        holder.bindings.apply(liveState)
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        boundHolders.remove(holder)
        holder.bindings.clear()
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = pages.size
}
