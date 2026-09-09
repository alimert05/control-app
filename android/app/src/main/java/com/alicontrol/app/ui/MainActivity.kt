package com.alicontrol.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.alicontrol.app.AliControlApp
import com.alicontrol.app.R
import com.alicontrol.app.actions.ActionDispatcher
import com.alicontrol.app.ble.AliControlCommands
import com.alicontrol.app.ble.ProtocolMessage
import com.alicontrol.app.dashboard.Dashboard
import com.alicontrol.app.dashboard.DashboardEditor
import com.alicontrol.app.dashboard.DashboardPage
import com.alicontrol.app.dashboard.DashboardStore
import com.alicontrol.app.dashboard.EditRejection
import com.alicontrol.app.dashboard.EditResult
import com.alicontrol.app.dashboard.ShortcutDestination
import com.alicontrol.app.dashboard.SharedPreferencesLayoutPersistence
import com.alicontrol.app.dashboard.WidgetConfig
import com.alicontrol.app.dashboard.WidgetType
import com.alicontrol.app.databinding.ActivityMainBinding
import com.alicontrol.app.kiosk.KioskController
import com.alicontrol.app.live.DesktopState
import com.alicontrol.app.live.DesktopStateStore
import com.alicontrol.app.transport.BluetoothTransport
import com.alicontrol.app.transport.TransportListener
import com.alicontrol.app.transport.TransportManager
import com.alicontrol.app.ui.dashboard.DashboardEditDialogs
import com.alicontrol.app.ui.dashboard.DashboardEditListener
import com.alicontrol.app.ui.dashboard.DashboardEditState
import com.alicontrol.app.ui.dashboard.DashboardPageRenderer
import com.alicontrol.app.ui.dashboard.DashboardPagerAdapter
import com.alicontrol.app.ui.dashboard.DashboardWidgetViewFactory
import com.alicontrol.app.ui.dashboard.DotsIndicator

/**
 * Ali Control's primary screen: the multi-page widget dashboard, and (TASK-008)
 * its editor.
 *
 * The Activity deliberately owns no layout rules of its own. It holds the
 * current [Dashboard] value, routes every user gesture through
 * [DashboardEditor] — which decides whether a move/resize/add/page change is
 * legal — and persists whatever comes back through [DashboardStore]. Anything
 * rejected surfaces as a short explanation and leaves the layout untouched.
 *
 * Editing is entirely local: it never needs (or touches) the BLE connection,
 * so a disconnected tablet is still fully customizable, while remote action
 * widgets stay dimmed until the desktop agent is back.
 */
class MainActivity : AppCompatActivity(), DashboardEditListener, DashboardEditDialogs.PageMenuCallbacks {

    private lateinit var binding: ActivityMainBinding
    private lateinit var transportManager: TransportManager
    private lateinit var kioskController: KioskController
    private lateinit var store: DashboardStore
    private lateinit var desktopState: DesktopStateStore
    private lateinit var pagerAdapter: DashboardPagerAdapter
    private lateinit var dotsIndicator: DotsIndicator
    private lateinit var dialogs: DashboardEditDialogs

    /**
     * Forwards shared live desktop state into the currently bound pages. The
     * Activity never parses a payload or holds a timer — it is a relay
     * between the one application-scoped store and the widgets on screen.
     */
    private val desktopStateListener = DesktopStateStore.Listener { state -> pagerAdapter.submitLiveState(state) }

    private var dashboard: Dashboard = Dashboard(emptyList())
    private var editState = DashboardEditState()

    /**
     * The connection state the pages were last rendered with. Re-rendering is
     * a full page rebuild, so a repeated BLE error (or any status update that
     * doesn't change connectedness) must not trigger one.
     */
    private var renderedConnected: Boolean? = null

    /** Last BLE error, shown alongside the disconnected state until the next connect attempt. */
    private var lastErrorMessage: String? = null

    private val transportListener = object : TransportListener {
        override fun onConnected() {
            lastErrorMessage = null
            refreshStatus()
        }

        override fun onDisconnected() = refreshStatus()

        override fun onDataReceived(data: ByteArray) {
            val message = ProtocolMessage.fromBytes(data) ?: return
            if (message.type != ProtocolMessage.TYPE_STATE) return
            val action = message.action ?: return
            val status = message.status ?: return
            Toast.makeText(this@MainActivity, getString(R.string.mac_ack_format, action, status), Toast.LENGTH_SHORT).show()
        }

        override fun onError(message: String) {
            lastErrorMessage = message
            refreshStatus()
        }
    }

    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) = updatePageChrome(position)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        transportManager = (application as AliControlApp).transportManager
        desktopState = (application as AliControlApp).desktopStateStore
        kioskController = KioskController(this)
        dialogs = DashboardEditDialogs(this)
        store = DashboardStore(SharedPreferencesLayoutPersistence(this))
        dashboard = store.load()

        setupDashboard()
        setupControls()

        kioskController.attachEscapeHatch(binding.headerTextColumn, window)
    }

    override fun onResume() {
        super.onResume()
        kioskController.applyImmersiveMode(window)
        kioskController.applyKeepAwake(window)
        transportManager.bluetooth.addListener(transportListener)
        // Subscribing pushes the current state immediately, so a screen
        // returning to the foreground shows live values right away instead of
        // waiting for the next desktop update.
        desktopState.addListener(desktopStateListener)
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        transportManager.bluetooth.removeListener(transportListener)
        desktopState.removeListener(desktopStateListener)
    }

    override fun onDestroy() {
        binding.dashboardPager.unregisterOnPageChangeCallback(pageChangeCallback)
        binding.dashboardPager.adapter = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) kioskController.applyImmersiveMode(window)
    }

    /**
     * Kiosk: this is the app's home screen, so back press must not exit it —
     * use the escape hatch (long-press the connection status text) to reach
     * Home/Recents/Settings during development. In edit mode, back is the
     * natural "stop editing" gesture rather than a dead key.
     */
    override fun onBackPressed() {
        if (editState.isEditing) setEditing(false)
    }

    // -- setup --

    private fun setupDashboard() {
        val widgetFactory = DashboardWidgetViewFactory(this, ActionDispatcher(this, transportManager))
        pagerAdapter = DashboardPagerAdapter(DashboardPageRenderer(widgetFactory), this)
        binding.dashboardPager.adapter = pagerAdapter
        binding.dashboardPager.registerOnPageChangeCallback(pageChangeCallback)
        dotsIndicator = DotsIndicator(binding.pageIndicator)
        renderDashboard()
    }

    private fun setupControls() {
        binding.connectButton.setOnClickListener { startActivity(Intent(this, ConnectActivity::class.java)) }
        binding.disconnectButton.setOnClickListener { transportManager.bluetooth.disconnect() }
        binding.deviceSettingsButton.setOnClickListener { startActivity(Intent(this, DeviceSettingsActivity::class.java)) }
        binding.allActionsButton.setOnClickListener { startActivity(Intent(this, ActionsActivity::class.java)) }

        binding.editButton.setOnClickListener { setEditing(true) }
        binding.doneButton.setOnClickListener { setEditing(false) }
        binding.addWidgetButton.setOnClickListener { showWidgetPicker() }
        binding.pagesButton.setOnClickListener { dialogs.showPageMenu(this) }
        binding.previousPageButton.setOnClickListener { stepPage(-1) }
        binding.nextPageButton.setOnClickListener { stepPage(1) }
    }

    // -- edit mode --

    private fun setEditing(enabled: Boolean) {
        editState = DashboardEditState(isEditing = enabled, selectedWidgetId = null)
        binding.normalControls.visibility = if (enabled) View.GONE else View.VISIBLE
        binding.editControls.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.editHint.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.previousPageButton.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.nextPageButton.visibility = if (enabled) View.VISIBLE else View.GONE
        // Horizontal drags belong to the widget being moved while editing, so
        // paging moves to the explicit arrows instead of a swipe.
        binding.dashboardPager.isUserInputEnabled = !enabled
        updateConnectionButtons()
        renderDashboard()
    }

    private fun stepPage(offset: Int) {
        val target = (binding.dashboardPager.currentItem + offset)
            .coerceIn(0, (dashboard.pages.size - 1).coerceAtLeast(0))
        binding.dashboardPager.setCurrentItem(target, false)
    }

    // -- DashboardEditListener --

    override fun onWidgetTapped(widgetId: String) {
        if (!editState.isEditing) return
        val next = if (editState.selectedWidgetId == widgetId) null else widgetId
        editState = editState.copy(selectedWidgetId = next)
        renderDashboard()
    }

    override fun onRemoveWidget(widgetId: String) {
        val page = currentPage() ?: return
        editState = editState.copy(selectedWidgetId = null)
        applyPageEdit(DashboardEditor.removeWidget(page, widgetId))
    }

    override fun onMoveWidget(widgetId: String, x: Int, y: Int) {
        val page = currentPage() ?: return
        applyPageEdit(DashboardEditor.moveWidget(page, widgetId, x, y))
    }

    override fun onResizeWidget(widgetId: String, width: Int, height: Int) {
        val page = currentPage() ?: return
        applyPageEdit(DashboardEditor.resizeWidget(page, widgetId, width, height))
    }

    override fun canMoveWidget(widgetId: String, x: Int, y: Int): Boolean =
        currentPage()?.let { DashboardEditor.moveWidget(it, widgetId, x, y).isSuccess } ?: false

    override fun canResizeWidget(widgetId: String, width: Int, height: Int): Boolean =
        currentPage()?.let { DashboardEditor.resizeWidget(it, widgetId, width, height).isSuccess } ?: false

    // -- add widget --

    private fun showWidgetPicker() {
        val page = currentPage() ?: return
        dialogs.showWidgetPicker(page) { type -> startAddFlow(page.id, type) }
    }

    /**
     * Turns a chosen widget type into a configured widget, asking for the one
     * extra decision each type needs (which action, which reading, which
     * preset) and nothing more. Every action id involved comes from
     * [com.alicontrol.app.actions.ActionLibrary]; none is ever typed in.
     */
    private fun startAddFlow(pageId: String, type: WidgetType) {
        when (type) {
            WidgetType.ACTION -> dialogs.showActionPicker { action -> addWidget(pageId, type, WidgetConfig.Action(action.id)) }

            WidgetType.ACTION_GROUP -> dialogs.showActionGroupPicker { ids ->
                addWidget(pageId, type, WidgetConfig.ActionGroup(ids))
            }

            WidgetType.METRIC -> dialogs.showLabelPicker(R.string.metric_picker_title, READING_LABELS) { label ->
                addWidget(pageId, type, WidgetConfig.Metric(label))
            }

            WidgetType.GAUGE -> dialogs.showLabelPicker(R.string.metric_picker_title, READING_LABELS) { label ->
                addWidget(pageId, type, WidgetConfig.Gauge(label))
            }

            WidgetType.CLOCK -> addWidget(pageId, type, WidgetConfig.Clock())

            WidgetType.DAILY_OVERVIEW -> addWidget(pageId, type, WidgetConfig.DailyOverview())

            WidgetType.MEDIA -> addWidget(
                pageId, type,
                WidgetConfig.Media(
                    AliControlCommands.PREVIOUS_TRACK,
                    AliControlCommands.PLAY_PAUSE,
                    AliControlCommands.NEXT_TRACK
                )
            )

            WidgetType.SLIDER -> addWidget(pageId, type, WidgetConfig.Slider(getString(R.string.label_volume)))

            WidgetType.SHORTCUT -> {
                val labels = listOf(getString(R.string.device_settings_title), getString(R.string.action_all_actions))
                dialogs.showLabelPicker(R.string.widget_picker_title, labels) { label ->
                    val destination = if (label == labels.first()) {
                        ShortcutDestination.DEVICE_SETTINGS
                    } else {
                        ShortcutDestination.ALL_ACTIONS
                    }
                    addWidget(pageId, type, WidgetConfig.Shortcut(label, destination))
                }
            }
        }
    }

    private fun addWidget(pageId: String, type: WidgetType, config: WidgetConfig) {
        val page = dashboard.page(pageId) ?: return
        applyPageEdit(DashboardEditor.addWidget(page, type, config))
    }

    // -- page management --

    override fun onCreatePage() {
        val result = DashboardEditor.createPage(dashboard, getString(R.string.page_new_default_name))
        if (applyDashboardEdit(result)) {
            binding.dashboardPager.setCurrentItem(dashboard.pages.size - 1, false)
            updatePageChrome(binding.dashboardPager.currentItem)
        }
    }

    override fun onRenamePage() {
        val page = currentPage() ?: return
        dialogs.showRenamePage(page.name) { name ->
            applyDashboardEdit(DashboardEditor.renamePage(dashboard, page.id, name))
        }
    }

    override fun onMovePageLeft() = movePage(-1)

    override fun onMovePageRight() = movePage(1)

    private fun movePage(offset: Int) {
        val page = currentPage() ?: return
        if (applyDashboardEdit(DashboardEditor.movePage(dashboard, page.id, offset))) {
            val index = dashboard.indexOfPage(page.id)
            if (index >= 0) binding.dashboardPager.setCurrentItem(index, false)
            updatePageChrome(binding.dashboardPager.currentItem)
        }
    }

    override fun onDeletePage() {
        val page = currentPage() ?: return
        if (dashboard.pages.size <= 1) {
            showRejection(EditRejection.LAST_PAGE)
            return
        }
        dialogs.showDeletePageConfirm(page) {
            if (applyDashboardEdit(DashboardEditor.deletePage(dashboard, page.id))) {
                val index = binding.dashboardPager.currentItem.coerceIn(0, dashboard.pages.size - 1)
                binding.dashboardPager.setCurrentItem(index, false)
                updatePageChrome(index)
            }
        }
    }

    override fun onResetToDefault() {
        dialogs.showResetConfirm {
            dashboard = store.resetToDefault()
            editState = editState.copy(selectedWidgetId = null)
            renderDashboard()
            binding.dashboardPager.setCurrentItem(0, false)
            updatePageChrome(0)
        }
    }

    // -- applying edits --

    private fun applyPageEdit(result: EditResult<DashboardPage>) {
        when (result) {
            is EditResult.Success -> {
                dashboard = dashboard.withPage(result.value)
                persistAndRender()
            }
            is EditResult.Rejected -> showRejection(result.reason)
        }
    }

    private fun applyDashboardEdit(result: EditResult<Dashboard>): Boolean = when (result) {
        is EditResult.Success -> {
            dashboard = result.value
            persistAndRender()
            true
        }
        is EditResult.Rejected -> {
            showRejection(result.reason)
            false
        }
    }

    private fun persistAndRender() {
        store.save(dashboard)
        renderDashboard()
    }

    private fun renderDashboard() {
        val connected = transportManager.bluetooth.isConnected
        renderedConnected = connected
        pagerAdapter.submit(dashboard.orderedPages, connected, editState)
        updatePageChrome(binding.dashboardPager.currentItem)
    }

    private fun updatePageChrome(position: Int) {
        val pages = dashboard.orderedPages
        val index = position.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        binding.currentPageLabel.text = pages.getOrNull(index)?.name.orEmpty()
        dotsIndicator.bind(pages.size, index)
    }

    private fun currentPage(): DashboardPage? = dashboard.orderedPages.getOrNull(binding.dashboardPager.currentItem)

    private fun showRejection(reason: EditRejection) {
        val message = when (reason) {
            EditRejection.OUT_OF_BOUNDS -> R.string.edit_rejected_bounds
            EditRejection.OVERLAP -> R.string.edit_rejected_overlap
            EditRejection.SIZE_CONSTRAINT -> R.string.edit_rejected_size
            EditRejection.NO_SPACE -> R.string.edit_rejected_no_space
            EditRejection.UNKNOWN_ACTION -> R.string.edit_rejected_unknown_action
            EditRejection.INVALID_NAME -> R.string.edit_rejected_invalid_name
            EditRejection.LAST_PAGE -> R.string.edit_rejected_last_page
            EditRejection.UNKNOWN_WIDGET, EditRejection.UNKNOWN_PAGE -> R.string.edit_rejected_unknown
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // -- connection --

    private fun refreshStatus() {
        val bluetooth = transportManager.bluetooth
        val connected = bluetooth.isConnected
        val deviceName = bluetooth.remoteName

        binding.deviceNameValue.text = deviceName.orEmpty()
        binding.deviceNameValue.visibility = if (connected && !deviceName.isNullOrBlank()) View.VISIBLE else View.GONE
        binding.connectionStatusValue.text = statusText(bluetooth.connectionState)

        updateConnectionButtons()
        if (renderedConnected != connected) renderDashboard() else updatePageChrome(binding.dashboardPager.currentItem)
    }

    /** Connect/Disconnect share one slot; while editing, neither is on screen. */
    private fun updateConnectionButtons() {
        if (editState.isEditing) return
        val connected = transportManager.bluetooth.isConnected
        binding.connectButton.visibility = if (connected) View.GONE else View.VISIBLE
        binding.disconnectButton.visibility = if (connected) View.VISIBLE else View.GONE
    }

    private fun statusText(state: BluetoothTransport.ConnectionState): String = when (state) {
        BluetoothTransport.ConnectionState.CONNECTED -> getString(R.string.status_connected)
        BluetoothTransport.ConnectionState.PREPARING -> getString(R.string.status_preparing)
        BluetoothTransport.ConnectionState.CONNECTING -> getString(R.string.status_connecting)
        BluetoothTransport.ConnectionState.SCANNING -> getString(R.string.status_scanning)
        BluetoothTransport.ConnectionState.DISCONNECTED -> {
            val error = lastErrorMessage
            if (error != null) getString(R.string.status_connection_error, error) else getString(R.string.status_disconnected)
        }
    }

    private companion object {
        /** The readings a METRIC/GAUGE widget can show — all placeholders until TASK-009 provides real data. */
        val READING_LABELS = listOf("CPU", "GPU", "RAM")
    }
}
