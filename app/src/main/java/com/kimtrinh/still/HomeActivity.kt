package com.kimtrinh.still

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.content.ContentUris
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.tv.TvContract
import android.net.ConnectivityManager
import android.net.Network
import android.media.tv.TvInputManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.UserHandle
import android.provider.MediaStore
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val REQ_DOC = 1
private const val REQ_PERM = 2

// Resting opacity of unfocused items: high enough to read over a bright photo at 3 m.
private const val REST_CONTROL = .8f
private const val REST_GLASS_PHOTO = .84f
private const val REST_GLASS_FLAT = .72f
private const val REST_LIFT = .6f

class HomeActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private lateinit var wallView: WallView
    private lateinit var underLayer: FrameLayout
    private lateinit var plate: PlateLayer
    private lateinit var homeLayer: FrameLayout
    private lateinit var overlayLayer: FrameLayout

    private var apps: List<App> = emptyList()
    private var home = mutableListOf<String>()
    private var inputList: List<Pair<String, String>> = emptyList()
    private var appViews: List<View> = emptyList()
    private var controlViews: List<View> = emptyList()
    private var lastFocusPkg: String? = null
    private var moving: String? = null
    private var moveBackup: List<String> = emptyList()
    private var shownWall: String? = null
    private var wallToken = 0
    private var clockBox: View? = null
    private var status: TextView? = null
    private var dockViews: List<View> = emptyList()
    private var tipShownThisRun = false
    private val sidebarPages = HashSet<View>()
    private var shifted = false

    private class Layer(val view: View, val restore: View?, val onPop: (() -> Unit)?)
    private val stack = ArrayList<Layer>()

    // ---------- Lifecycle ----------

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        Ui.init(this)
        prefs = Prefs(this)
        root = FrameLayout(this)
        wallView = WallView(this); underLayer = FrameLayout(this); plate = PlateLayer(this)
        homeLayer = FrameLayout(this); overlayLayer = FrameLayout(this)
        listOf(wallView, underLayer, plate, homeLayer, overlayLayer).forEach { root.addView(it, MATCH, MATCH) }
        setContentView(root)

        if (prefs[C.material] !in C.material.opts.map { it.first }) prefs[C.material] = C.material.def // "Tinted" was removed
        getSystemService(LauncherApps::class.java)?.registerCallback(appsCallback, Ui.main)
        apps = Apps.load(this, prefs)
        home = (prefs.home ?: Apps.defaults(apps).also { prefs.home = it }).toMutableList()
        inputList = inputs()
        applyPrefs()
        if (saved == null && prefs[C.rotation] == "open") advanceWallpaper()
    }

    override fun onResume() {
        super.onResume()
        scheduleRotation()
        inputList = inputs()
        Ui.io.execute {
            val fresh = Apps.load(this, prefs)
            Ui.main.post { if (fresh != apps) { apps = fresh; if (moving == null) buildHome() } }
        }
    }

    override fun onStart() {
        super.onStart()
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        online = cm.activeNetwork != null
        runCatching { cm.registerDefaultNetworkCallback(netCallback) }
        showStatus()
    }

    override fun onStop() {
        super.onStop()
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(netCallback) }
    }

    override fun onDestroy() {
        getSystemService(LauncherApps::class.java)?.unregisterCallback(appsCallback)
        super.onDestroy()
    }

    // Installs and removals while Home is on screen; bursts (an update touches several callbacks) settle first.
    private val reloadSoon = Runnable { if (moving == null) reloadApps(null) }
    private val appsCallback = object : LauncherApps.Callback() {
        private fun changed() { Ui.main.removeCallbacks(reloadSoon); Ui.main.postDelayed(reloadSoon, 600) }
        override fun onPackageRemoved(packageName: String, user: UserHandle) = changed()
        override fun onPackageAdded(packageName: String, user: UserHandle) = changed()
        override fun onPackageChanged(packageName: String, user: UserHandle) = changed()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = changed()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = changed()
    }

    // ponytail: "has a default network", not "internet validated"; validation probes fail on some regional ROMs
    private var online = true
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { Ui.main.post { online = true; showStatus() } }
        override fun onLost(network: Network) {
            Ui.main.post { online = getSystemService(ConnectivityManager::class.java)?.activeNetwork != null; showStatus() }
        }
    }

    private fun showStatus() { status?.visibility = if (online) View.GONE else View.VISIBLE }

    override fun onPause() {
        super.onPause()
        Ui.main.removeCallbacks(rotate)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (moving != null) { home = moveBackup.toMutableList(); moving = null; buildHome() }
        // Home while already Home: close panels, or return to the top of the list.
        if (stack.isNotEmpty()) closeAll() else if (hasWindowFocus()) { lastFocusPkg = null; focusHome() }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) Icons.trim()
    }

    @Deprecated("TV remotes still deliver Back here")
    override fun onBackPressed() { pop() } // Home itself has nowhere to go back to.

    // ---------- Appearance ----------

    private val ink get() = Glass.ink
    private val dimInk get() = (Glass.ink and 0xFFFFFF) or 0xB3000000.toInt() // 70%: secondary text stays legible on glass
    private val sizeScale get() = when (prefs[C.size]) { "s" -> .82f; "l" -> 1.18f; else -> 1f }
    private fun homeFont(w: Int) = Fonts.get(this, prefs[C.font], w)
    private fun uiFont(w: Int) = Fonts.get(this, "Inter", w)

    private fun applyPrefs() {
        Glass.bg = prefs[C.background]
        Glass.material = prefs[C.material]
        wallView.listLayout = prefs[C.layout] == "list"
        if (Glass.bg == "photo") showWall(prefs.wallpaper)
        else { Glass.wall = null; shownWall = null; wallToken++; wallView.set(null) }
        buildHome()
        invalidateTree(root)
    }

    private fun showWall(id: String) {
        val position = prefs[C.position(id)]
        if ("$id|$position" == shownWall) return
        shownWall = "$id|$position"
        val t = ++wallToken
        val dm = resources.displayMetrics
        val w = max(dm.widthPixels, dm.heightPixels); val h = min(dm.widthPixels, dm.heightPixels)
        Ui.io.execute {
            val l = Wall.load(this, id, w, h, position)
            Ui.main.post {
                if (t != wallToken || Glass.bg != "photo") return@post
                if (l == null) {
                    // Deleted photo or unplugged USB drive: show a built-in photo rather than a black Home.
                    // The choice is kept, so the photo comes back when the drive does.
                    val fallback = Wall.BUILTIN.first().first
                    if (id != fallback) { toast("That wallpaper isn't available right now."); shownWall = null; showWall(fallback) }
                    return@post
                }
                Glass.wall = l; wallView.set(l.photo); invalidateTree(root)
            }
        }
    }

    private fun invalidateTree(v: View) {
        v.invalidate()
        if (v is ViewGroup) for (i in 0 until v.childCount) invalidateTree(v.getChildAt(i))
    }

    private val rotate = Runnable { advanceWallpaper(); scheduleRotation() }

    private fun scheduleRotation() {
        Ui.main.removeCallbacks(rotate)
        prefs[C.rotation].toIntOrNull()?.let { Ui.main.postDelayed(rotate, it * 60_000L) }
    }

    /** A chosen photo folder is the rotation set; otherwise the built-in and added photos. */
    private fun rotationSet() =
        prefs.folder?.let { Wall.folderPhotos(this, it).ifEmpty { null } } ?: Wall.all(this).map { it.first }

    private fun advanceWallpaper() = Ui.io.execute {
        val all = rotationSet() // may scan the photo library, so not on the main thread
        Ui.main.post {
            prefs.wallpaper = all[(all.indexOf(prefs.wallpaper) + 1).mod(all.size)]
            if (Glass.bg == "photo") showWall(prefs.wallpaper)
        }
    }

    // ---------- Home ----------

    private fun homeApps() = prefs.hidden.let { hidden ->
        home.mapNotNull { p -> apps.firstOrNull { it.pkg == p } }.filter { it.pkg !in hidden }
    }

    private fun buildHome() {
        homeLayer.removeAllViews(); underLayer.removeAllViews()
        plate.hide(); plate.lifted = moving != null
        val list = prefs[C.layout] == "list"
        buildClock(list)
        controlViews = buildControls(list)
        appViews = if (list) buildList(homeApps()) else buildDock(homeApps())
        appViews.firstOrNull()?.let { if (list) it.nextFocusUpId = it.id else it.nextFocusLeftId = it.id }
        appViews.lastOrNull()?.let { if (list) it.nextFocusDownId = it.id else it.nextFocusRightId = it.id }
        val target = (appViews.firstOrNull { it.tag == lastFocusPkg } ?: appViews.firstOrNull())?.id ?: View.NO_ID
        controlViews.forEach { c ->
            if (list) { c.nextFocusLeftId = target; c.nextFocusRightId = c.id } else { c.nextFocusDownId = target; c.nextFocusUpId = c.id }
        }
        appViews.forEach { if (list) it.nextFocusRightId = controlViews.first().id else it.nextFocusUpId = controlViews.first().id }
        moving?.let { m -> buildMoveHint(apps.firstOrNull { it.pkg == m }?.label ?: "App", list) }
        if (moving == null) buildTip(list)
        shifted = false; updateShift(animate = false)
        if (stack.isEmpty()) onLayout(homeLayer) { focusHome() }
    }

    private fun onLayout(v: View, then: () -> Unit) = v.viewTreeObserver.addOnGlobalLayoutListener(
        object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() { v.viewTreeObserver.removeOnGlobalLayoutListener(this); then() }
        },
    )

    private fun focusHome() {
        (appViews.firstOrNull { it.tag == lastFocusPkg } ?: appViews.firstOrNull() ?: controlViews.firstOrNull())?.requestFocus()
    }

    /** Clock, plus a quiet note under it when the TV has no network: streaming apps would only fail. */
    private fun buildClock(list: Boolean) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = if (list) Gravity.END else Gravity.START }
        homeLayer.addView(box, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or if (list) Gravity.END else Gravity.START).apply {
            topMargin = 72.u; rightMargin = 136.u; leftMargin = 136.u
        })
        clockBox = box
        val mode = prefs[C.clock]
        if (mode != "off") box.addView(TextClock(this).apply {
            val f = if (mode == "24") "H:mm" else "h:mm"
            format12Hour = f; format24Hour = f
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 60.uf); typeface = homeFont(600); setTextColor(ink)
            letterSpacing = -.02f; fontFeatureSettings = "tnum"; includeFontPadding = false
            if (Glass.bg == "photo") setShadowLayer(10.uf, 0f, 2.uf, 0x52000000)
        })
        status = text("No Internet Connection", 27.uf, uiFont(500), dimInk).apply { setPadding(0, 12.u, 0, 0) }
        box.addView(status)
        showStatus()
    }

    private fun buildControls(list: Boolean): List<View> {
        val items = buildList<Triple<String, Int, () -> Unit>> {
            if (inputList.isNotEmpty()) add(Triple("Inputs", R.drawable.ic_input) { inputsSheet() })
            add(Triple("All Apps", R.drawable.ic_grid) { allApps() })
            add(Triple("Settings", R.drawable.ic_gear) { settings() })
        }
        val box = LinearLayout(this).apply {
            orientation = if (list) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        homeLayer.addView(box, FrameLayout.LayoutParams(WRAP, WRAP, if (list) Gravity.BOTTOM or Gravity.END else Gravity.TOP or Gravity.END).apply {
            rightMargin = 112.u; topMargin = 64.u; bottomMargin = 64.u
        })
        return items.map { (label, icon, action) ->
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                isFocusable = true; isClickable = true; id = View.generateViewId()
                contentDescription = label; alpha = REST_CONTROL
                setPadding(24.u, 0, 24.u, 0)
                addView(text(label, 30.uf, homeFont(500)))
                addView(
                    ImageView(context).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(ink) },
                    LinearLayout.LayoutParams(34.u, 34.u).apply { marginStart = 14.u },
                )
                setOnFocusChangeListener { v, f ->
                    v.animate().alpha(if (f) 1f else REST_CONTROL).setDuration(Ui.T)
                    if (f && stack.isEmpty()) plate.moveTo(v, 36.uf)
                }
                setOnClickListener { press(this, action) }
                box.addView(this, LinearLayout.LayoutParams(WRAP, 72.u).apply {
                    if (list) topMargin = 8.u else marginStart = 8.u
                })
            }
        }
    }

    private fun buildList(shown: List<App>): List<View> {
        dockViews = emptyList()
        val fs = 88.uf * sizeScale
        val rowH = (128.uf * sizeScale).toInt()
        val scroll = VScroll(this, .5f).apply { setFadingEdgeLength(220.u) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false
            setPadding(0, 260.u, 0, 540.u)
        }
        scroll.addView(col)
        homeLayer.addView(scroll, FrameLayout.LayoutParams(1240.u, MATCH).apply { leftMargin = 84.u })
        if (shown.isEmpty()) { col.addView(emptyHome(), LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = 28.u }); return emptyList() }
        return shown.map { app -> appRow(app, fs).also { col.addView(it, LinearLayout.LayoutParams(WRAP, rowH)) } }
    }

    private fun appRow(app: App, fs: Float): View {
        val style = prefs[C.show]
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((28 * sizeScale).toInt().u, 0, (40 * sizeScale).toInt().u, 0)
        }
        if (style != "name") {
            val s = (fs * if (style == "icon") 1f else .62f).toInt()
            row.addView(appIcon(app, s), LinearLayout.LayoutParams(s, s).apply { if (style == "both") marginEnd = (fs * .3f).toInt() })
        }
        if (style != "icon") row.addView(text(app.label, fs, homeFont(600)))
        wireApp(row, app, 38.uf * sizeScale, list = true)
        return row
    }

    private fun buildDock(shown: List<App>): List<View> {
        val tileW = (288.uf * sizeScale).toInt(); val tileH = (200.uf * sizeScale).toInt()
        val w = min(max(shown.size, 3) * tileW + 32.u, 1504.u)
        fun lp() = FrameLayout.LayoutParams(w, tileH + 32.u, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 136.u }
        // The mat sits under the plate layer so the focus plate reads as lying on the glass.
        val mat = GlassBox(this, 48.uf, Glass.Kind.MAT)
        underLayer.addView(mat, lp())
        val scroll = HScroll(this, .5f).apply { setFadingEdgeLength(132.u) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(16.u, 16.u, 16.u, 16.u) }
        scroll.addView(row, FrameLayout.LayoutParams(WRAP, MATCH))
        homeLayer.addView(scroll, lp())
        dockViews = listOf(mat, scroll)
        if (shown.isEmpty()) { row.addView(emptyHome()); return emptyList() }
        val style = prefs[C.show]
        return shown.map { app ->
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                if (style != "name") addView(appIcon(app, (if (style == "icon") 120.uf else 100.uf * sizeScale).toInt()))
                if (style != "icon") addView(
                    text(app.label, if (style == "name") 40.uf * sizeScale else 30.uf * sizeScale, homeFont(if (style == "name") 600 else 500)).apply {
                        gravity = Gravity.CENTER; setPadding(14.u, if (style == "both") 14.u else 0, 14.u, 0)
                    },
                    LinearLayout.LayoutParams(MATCH, WRAP),
                )
                wireApp(this, app, 32.uf, list = false)
                row.addView(this, LinearLayout.LayoutParams(tileW, tileH))
            }
        }
    }

    private fun emptyHome() = text("Nothing on Home yet.\nOpen All Apps and hold OK to add one.", 34.uf, uiFont(500), dimInk).apply {
        isSingleLine = false; maxLines = 3; setLineSpacing(0f, 1.2f)
    }

    private fun buildMoveHint(label: String, list: Boolean) {
        val keys = if (list) "Up and Down" else "Left and Right"
        homeLayer.addView(
            capsule("Moving “$label”. Use $keys, then OK to place it. Back cancels."),
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 168.u },
        )
    }

    /** Teaches hold-OK once per launch until the person has opened app options themselves. */
    private fun buildTip(list: Boolean) {
        if (prefs.learnedOptions || tipShownThisRun || appViews.isEmpty()) return
        tipShownThisRun = true
        val tip = capsule("Tip: hold OK on an app to move, rename or hide it.")
        homeLayer.addView(tip, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or if (list) Gravity.START else Gravity.CENTER_HORIZONTAL).apply {
            leftMargin = 112.u; bottomMargin = if (list) 72.u else 40.u
        })
        tip.alpha = 0f
        tip.animate().alpha(1f).setStartDelay(800).setDuration(Ui.T)
        Ui.main.postDelayed({ tip.animate().alpha(0f).setDuration(400).withEndAction { (tip.parent as? ViewGroup)?.removeView(tip) } }, 9000)
    }

    private fun capsule(s: String) = GlassBox(this, 36.uf, Glass.Kind.PANEL).apply {
        setPadding(32.u, 18.u, 32.u, 18.u)
        addView(text(s, 27.uf, uiFont(500), shadow = false))
    }

    private fun appIcon(app: App, size: Int) = ImageView(this).apply {
        val mono = prefs[C.icons] == "mono"
        Icons.into(this, app.pkg, if (mono) Icons.Kind.MONO else Icons.Kind.COLOR, size, ink = ink)
    }

    private val menuKey = View.OnKeyListener { v, k, e ->
        if (k != KeyEvent.KEYCODE_MENU) false else { if (e.action == KeyEvent.ACTION_UP) v.performLongClick(); true }
    }

    private fun wireApp(v: View, app: App, radius: Float, list: Boolean) {
        v.isFocusable = true; v.isClickable = true; v.id = View.generateViewId(); v.tag = app.pkg
        v.contentDescription = app.label
        val glass = prefs[C.focus] == "glass"
        val rest = if (!glass) REST_LIFT else if (Glass.bg == "photo") REST_GLASS_PHOTO else REST_GLASS_FLAT
        v.alpha = if (app.pkg == moving) 1f else rest
        v.setOnFocusChangeListener { _, f ->
            if (f) {
                lastFocusPkg = app.pkg
                controlViews.forEach { c -> if (list) c.nextFocusLeftId = v.id else c.nextFocusDownId = v.id }
            }
            v.animate().alpha(if (f || app.pkg == moving) 1f else rest).setDuration(Ui.T).setInterpolator(Ui.EASE)
            if (glass || moving != null) {
                if (f) plate.moveTo(v, radius)
            } else {
                if (list) { v.pivotX = 0f; v.pivotY = v.height / 2f }
                if (Glass.bg == "photo") liftShadow(v, f)
                val s = if (f) 1.08f else 1f
                v.animate().scaleX(s).scaleY(s).setDuration(Ui.T).setInterpolator(Ui.EASE)
                if (f) plate.hide()
            }
        }
        v.setOnClickListener { press(v) { launch(app, v) } }
        v.setOnLongClickListener { appOptions(app); true }
        v.setOnKeyListener(menuKey)
    }

    private fun liftShadow(v: View, on: Boolean) {
        if (v is TextView) v.setShadowLayer(if (on) 24.uf else 10.uf, 0f, if (on) 8.uf else 2.uf, if (on) 0x80000000.toInt() else 0x52000000)
        if (v is ViewGroup) for (i in 0 until v.childCount) liftShadow(v.getChildAt(i), on)
    }

    /** tvOS-style press: a brief dip before acting. */
    private fun press(v: View, then: () -> Unit) {
        val s = v.scaleX
        v.animate().scaleX(s * .95f).scaleY(s * .95f).setDuration(90).withEndAction {
            v.animate().scaleX(s).scaleY(s).setDuration(160).setInterpolator(Ui.EASE)
            then()
        }
    }

    private fun launch(app: App, from: View?) {
        val i = Apps.launchIntent(this, app.pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            ?: return toast("${app.label} can't be opened.")
        runCatching { startActivity(i, from?.let { ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle() }) }
            .onFailure { toast("${app.label} can't be opened.") }
    }

    private fun open(i: Intent) {
        runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { toast("That isn't available on this TV.") }
    }

    // ---------- Arranging ----------

    private fun startMove(app: App) {
        closeAll()
        moving = app.pkg; moveBackup = home.toList(); lastFocusPkg = app.pkg
        buildHome()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val m = moving ?: return super.dispatchKeyEvent(e)
        val list = prefs[C.layout] == "list"
        val dir = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (list) -1 else 0
            KeyEvent.KEYCODE_DPAD_DOWN -> if (list) 1 else 0
            KeyEvent.KEYCODE_DPAD_LEFT -> if (list) 0 else -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (list) 0 else 1
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> 2
            else -> return super.dispatchKeyEvent(e)
        }
        if (e.action != KeyEvent.ACTION_DOWN || dir == 0) return true
        if (dir == 2) {
            if (e.keyCode == KeyEvent.KEYCODE_BACK || e.keyCode == KeyEvent.KEYCODE_BUTTON_B) home = moveBackup.toMutableList()
            else prefs.home = home
            moving = null
        } else {
            val vis = homeApps().map { it.pkg }
            val j = vis.indexOf(m) + dir
            if (j !in vis.indices) return true
            val a = home.indexOf(m); val b = home.indexOf(vis[j])
            home[a] = vis[j]; home[b] = m
        }
        buildHome()
        return true
    }

    private fun toggleHome(app: App) {
        if (!home.remove(app.pkg)) home.add(app.pkg)
        prefs.home = home
        reloadApps(app.pkg)
    }

    /** Re-reads apps after a rename, hide or membership change, and refreshes whatever is open. */
    private fun reloadApps(focusPkg: String?) = Ui.io.execute {
        val fresh = Apps.load(this, prefs) // queries every launcher activity: too slow for the main thread
        Ui.main.post {
            apps = fresh
            buildHome()
            val top = stack.lastOrNull()?.view ?: return@post
            if (top.tag == "apps") { pop(); allApps(focusPkg) } else refreshRows(top)
        }
    }

    private fun appOptions(app: App) {
        prefs.learnedOptions = true
        val onHome = app.pkg in home
        val a = ArrayList<Pair<String, () -> Unit>>()
        if (onHome && stack.isEmpty()) a += "Move" to { startMove(app) }
        a += (if (onHome) "Remove from Home" else "Add to Home") to { toggleHome(app) }
        a += "Rename" to {
            ask("Rename “${app.sysLabel}”", app.label, app.sysLabel) {
                prefs.setLabel(app.pkg, it.takeIf { s -> s.isNotBlank() && s != app.sysLabel }); reloadApps(app.pkg)
            }
        }
        a += "Hide" to { prefs.hidden = prefs.hidden + app.pkg; reloadApps(null) }
        a += "App Info" to { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}"))) }
        a += "Uninstall" to { open(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}"))) }
        sheet(app.label, null, a)
    }

    // ---------- Overlay stack ----------

    private fun push(v: View, focus: View? = null, onPop: (() -> Unit)? = null) {
        val restore = currentFocus
        (stack.lastOrNull()?.view as? ViewGroup ?: homeLayer).descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        overlayLayer.addView(v, MATCH, MATCH)
        stack += Layer(v, restore, onPop)
        updateShift()
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(Ui.T).setInterpolator(Ui.EASE)
        onLayout(v) { if (stack.lastOrNull()?.view === v) (focus ?: v).requestFocus() }
    }

    private fun pop(): Boolean {
        val l = stack.removeLastOrNull() ?: return false
        overlayLayer.removeView(l.view)
        sidebarPages.remove(l.view)
        updateShift()
        val below = stack.lastOrNull()?.view
        (below as? ViewGroup ?: homeLayer).descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        l.onPop?.invoke()
        val r = l.restore
        when {
            r != null && r.isAttachedToWindow && r.isShown -> r.requestFocus()
            below != null -> below.requestFocus()
            else -> focusHome()
        }
        below?.let { refreshRows(it) }
        return true
    }

    private fun closeAll() { while (pop()) Unit }

    /**
     * While a Settings page is open, slide what it would cover (the clock in List, the dock in Dock)
     * into the visible part of the screen, so every change previews live.
     */
    private fun updateShift(animate: Boolean = true) {
        val on = stack.any { it.view in sidebarPages }
        if (on == shifted) return
        shifted = on
        val panelLeft = 1920.uf - 880.uf - 40.uf // panel width + right margin, plus breathing room
        clockBox?.takeIf { prefs[C.layout] == "list" }?.let { c ->
            val dx = if (on) panelLeft - (1920.uf - 136.uf) else 0f
            if (animate) c.animate().translationX(dx).setDuration(Ui.T + 80).setInterpolator(Ui.EASE) else c.translationX = dx
        }
        dockViews.forEach { d ->
            val w = d.width.takeIf { it > 0 }?.toFloat() ?: (d.layoutParams.width.toFloat())
            val sc = if (on) min(1f, (panelLeft - 80.uf) / w) else 1f
            val dx = if (on) (80.uf + panelLeft) / 2f - 960.uf else 0f
            if (animate) d.animate().translationX(dx).scaleX(sc).scaleY(sc).setDuration(Ui.T + 80).setInterpolator(Ui.EASE)
                .setUpdateListener { d.invalidate() } // the mat re-reads its glass offset
            else { d.translationX = dx; d.scaleX = sc; d.scaleY = sc }
        }
    }

    private fun refreshRows(v: View) {
        (v.tag as? Runnable)?.run()
        if (v is ViewGroup) for (i in 0 until v.childCount) refreshRows(v.getChildAt(i))
    }

    // ---------- Building blocks ----------

    private fun text(s: String, size: Float, tf: Typeface, color: Int = ink, shadow: Boolean = true) = TextView(this).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_PX, size); typeface = tf; setTextColor(color)
        letterSpacing = if (size > 60.uf) -.02f else -.005f // SF-like tracking: tighter at display sizes
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
        if (shadow && Glass.bg == "photo") setShadowLayer(10.uf, 0f, 2.uf, 0x52000000)
    }

    private fun roundOutline(r: Float) = object : ViewOutlineProvider() {
        override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, r)
    }

    private fun thumb() = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; outlineProvider = roundOutline(10.uf)
        setBackgroundColor(0x22FFFFFF)
    }

    private fun scaledThumb(b: Bitmap?) = b?.let { Bitmap.createScaledBitmap(it, 256, 256 * it.height / it.width, true) }

    /** Right-hand sidebar, like tvOS Settings, so Home stays visible as a live preview. */
    private fun sidebar(title: String, note: String? = null, fill: (LinearLayout) -> Unit): FrameLayout {
        val page = FrameLayout(this).also { sidebarPages += it }
        val panel = GlassBox(this, 44.uf, Glass.Kind.PANEL).apply { setPadding(36.u, 56.u, 36.u, 0) }
        panel.addView(text(title, 48.uf, uiFont(700), shadow = false).apply { setPadding(32.u, 0, 32.u, 0) })
        note?.let {
            panel.addView(text(it, 27.uf, uiFont(400), dimInk, false).apply {
                isSingleLine = false; maxLines = 4; setLineSpacing(0f, 1.15f); setPadding(32.u, 14.u, 32.u, 0)
            })
        }
        val scroll = VScroll(this, .5f).apply { setFadingEdgeLength(72.u) }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
            setPadding(12.u, 20.u, 12.u, 64.u)
        }
        scroll.addView(list)
        panel.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = 12.u })
        fill(list)
        // Inside the TV safe area: keystone correction and overscan crop the outer edges.
        page.addView(panel, FrameLayout.LayoutParams(800.u, MATCH, Gravity.END).apply { setMargins(0, 60.u, 80.u, 60.u) })
        panel.translationX = 60.uf
        panel.animate().translationX(0f).setDuration(Ui.T + 80).setInterpolator(Ui.EASE)
        return page
    }

    private fun header(list: LinearLayout, s: String) = list.addView(
        text(s.uppercase(), 24.uf, uiFont(600), dimInk, false).apply { letterSpacing = .06f; setPadding(28.u, 30.u, 28.u, 8.u) },
    )

    /** A grouped-list row. Focus turns it into a white plate with dark text, as on tvOS. */
    private fun row(
        list: LinearLayout, label: String,
        value: (() -> String)? = null, checked: (() -> Boolean)? = null, chevron: Boolean = false,
        leading: View? = null, icon: Int = 0, center: Boolean = false, visible: (() -> Boolean)? = null,
        onFocus: ((Boolean) -> Unit)? = null, onClick: (View) -> Unit,
    ): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or (if (center) Gravity.CENTER_HORIZONTAL else 0)
            isFocusable = true; isClickable = true; setPadding(28.u, 0, 28.u, 0)
        }
        val bg = GradientDrawable().apply { cornerRadius = 18.uf }
        r.background = bg
        leading?.let { r.addView(it, LinearLayout.LayoutParams(112.u, 63.u).apply { marginEnd = 24.u }) }
        val ic = if (icon != 0) ImageView(this).apply { setImageResource(icon) } else null
        ic?.let { r.addView(it, LinearLayout.LayoutParams(34.u, 34.u).apply { marginEnd = 20.u }) }
        val t = text(label, 31.uf, uiFont(500), shadow = false)
        r.addView(t, if (center) LinearLayout.LayoutParams(WRAP, WRAP) else LinearLayout.LayoutParams(0, WRAP, 1f))
        val vt = value?.let { text("", 27.uf, uiFont(400), shadow = false).also { v -> r.addView(v, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = 16.u }) } }
        val ck = checked?.let { ImageView(this).apply { setImageResource(R.drawable.ic_check) }.also { v -> r.addView(v, LinearLayout.LayoutParams(32.u, 32.u).apply { marginStart = 16.u }) } }
        val ch = if (chevron) ImageView(this).apply { setImageResource(R.drawable.ic_chevron) }.also { v -> r.addView(v, LinearLayout.LayoutParams(26.u, 26.u).apply { marginStart = 10.u }) } else null
        fun paint(f: Boolean) {
            val fg = if (f) 0xFF1C1C1E.toInt() else ink
            val dim = (fg and 0xFFFFFF) or 0xB3000000.toInt()
            bg.setColor(if (f) Color.WHITE else Color.TRANSPARENT)
            t.setTextColor(fg); vt?.setTextColor(dim)
            ic?.imageTintList = ColorStateList.valueOf(fg); ck?.imageTintList = ColorStateList.valueOf(fg)
            ch?.imageTintList = ColorStateList.valueOf(dim)
            r.elevation = if (f) 10.uf else 0f
        }
        r.setOnFocusChangeListener { _, f ->
            paint(f)
            val s = if (f) 1.03f else 1f
            r.animate().scaleX(s).scaleY(s).setDuration(Ui.T).setInterpolator(Ui.EASE)
            onFocus?.invoke(f)
        }
        r.tag = Runnable {
            vt?.text = value?.invoke()
            ck?.visibility = if (checked?.invoke() == true) View.VISIBLE else View.INVISIBLE
            visible?.let { r.visibility = if (it()) View.VISIBLE else View.GONE }
        }
        (r.tag as Runnable).run(); paint(false)
        r.setOnClickListener { onClick(r) }
        list.addView(r, LinearLayout.LayoutParams(MATCH, if (leading != null) 92.u else 80.u).apply { topMargin = 6.u })
        return r
    }

    /** A centered action sheet, the tvOS alert. */
    private fun sheet(title: String, message: String?, actions: List<Pair<String, () -> Unit>>, cancel: String = "Cancel") {
        val page = FrameLayout(this).apply { setBackgroundColor(0x80000000.toInt()) }
        val card = GlassBox(this, 44.uf, Glass.Kind.PANEL).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(44.u, 48.u, 44.u, 36.u) }
        card.addView(text(title, 40.uf, uiFont(700), shadow = false).apply { gravity = Gravity.CENTER; isSingleLine = false; maxLines = 3 }, LinearLayout.LayoutParams(MATCH, WRAP))
        message?.let {
            card.addView(text(it, 27.uf, uiFont(400), dimInk, false).apply {
                gravity = Gravity.CENTER; isSingleLine = false; maxLines = 14; setLineSpacing(0f, 1.18f); setPadding(0, 18.u, 0, 0)
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(8.u, 24.u, 8.u, 0) }
        card.addView(list, LinearLayout.LayoutParams(MATCH, WRAP))
        actions.forEach { (label, act) -> row(list, label, center = true) { pop(); act() } }
        row(list, cancel, center = true) { pop() }
        page.addView(card, FrameLayout.LayoutParams(860.u, WRAP, Gravity.CENTER))
        card.scaleX = .94f; card.scaleY = .94f
        card.animate().scaleX(1f).scaleY(1f).setDuration(Ui.T).setInterpolator(Ui.EASE)
        push(page)
    }

    /** Text entry as a glass sheet, matching the rest of Still instead of the grey system dialog. */
    private fun ask(title: String, initial: String, hint: String, done: (String) -> Unit) {
        val imm = getSystemService(InputMethodManager::class.java)
        val page = FrameLayout(this).apply { setBackgroundColor(0x80000000.toInt()) }
        val card = GlassBox(this, 44.uf, Glass.Kind.PANEL).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(44.u, 48.u, 44.u, 36.u) }
        card.addView(text(title, 40.uf, uiFont(700), shadow = false).apply { gravity = Gravity.CENTER; isSingleLine = false; maxLines = 2 }, LinearLayout.LayoutParams(MATCH, WRAP))
        val e = EditText(this).apply {
            setText(initial); this.hint = hint; isSingleLine = true; setSelectAllOnFocus(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 31.uf); typeface = uiFont(500)
            setTextColor(0xFF1C1C1E.toInt()); setHintTextColor(0x801C1C1E.toInt())
            background = GradientDrawable().apply { cornerRadius = 18.uf; setColor(Color.WHITE) }
            setPadding(28.u, 0, 28.u, 0)
        }
        card.addView(e, LinearLayout.LayoutParams(MATCH, 84.u).apply { topMargin = 32.u; leftMargin = 8.u; rightMargin = 8.u })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(8.u, 20.u, 8.u, 0) }
        card.addView(list, LinearLayout.LayoutParams(MATCH, WRAP))
        fun submit() { imm?.hideSoftInputFromWindow(e.windowToken, 0); val v = e.text.toString(); pop(); done(v) }
        row(list, "Done", center = true) { submit() }
        row(list, "Cancel", center = true) { imm?.hideSoftInputFromWindow(e.windowToken, 0); pop() }
        e.setOnEditorActionListener { _, a, _ -> (a == EditorInfo.IME_ACTION_DONE).also { if (it) submit() } }
        // Upper third: the on-screen keyboard takes the bottom half of the screen.
        page.addView(card, FrameLayout.LayoutParams(860.u, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 96.u })
        push(page, e)
        e.postDelayed({ if (e.hasFocus()) imm?.showSoftInput(e, 0) }, 300)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------- All Apps ----------

    private fun allApps(focusPkg: String? = null) {
        val page = GlassBox(this, 0f, Glass.Kind.PANEL).apply { tag = "apps" }
        val top = FrameLayout(this)
        top.addView(text("All Apps", 56.uf, uiFont(700), shadow = false), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.START or Gravity.CENTER_VERTICAL))
        top.addView(text("Hold OK for options", 27.uf, uiFont(500), dimInk, false), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL))
        page.addView(top, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(114.u, 64.u, 114.u, 0) })
        val scroll = VScroll(this, .45f).apply { setFadingEdgeLength(120.u) }
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(114.u, 56.u, 114.u, 160.u) }
        scroll.addView(grid)
        page.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        val hidden = prefs.hidden
        val visible = apps.filter { it.pkg !in hidden }
        var focus: View? = null
        val cols = 5; val gap = 48.u; val tileW = (1692.u - gap * (cols - 1)) / cols; val tileH = tileW * 9 / 16
        val (tv, other) = visible.partition { it.tv }
        for ((title, group) in listOf(null to tv, "Other Apps" to other)) {
            if (group.isEmpty()) continue
            if (title != null && tv.isNotEmpty()) grid.addView(text(title.uppercase(), 24.uf, uiFont(600), dimInk, false).apply {
                letterSpacing = .06f; setPadding(0, 16.u, 0, 28.u)
            })
            group.chunked(cols).forEach { chunk ->
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
                chunk.forEachIndexed { i, app ->
                    val t = appTile(app, tileW, tileH)
                    r.addView(t, LinearLayout.LayoutParams(tileW, WRAP).apply { if (i > 0) marginStart = gap })
                    if (app.pkg == focusPkg) focus = t
                }
                grid.addView(r, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = 44.u })
            }
        }
        if (visible.isEmpty()) grid.addView(text("No apps found.", 34.uf, uiFont(500), dimInk, false))
        push(page, focus)
    }

    private fun appTile(app: App, w: Int, h: Int): View {
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true; isClickable = true; clipChildren = false; contentDescription = app.label
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; outlineProvider = roundOutline(22.uf)
            setBackgroundColor(0x33FFFFFF)
        }
        Icons.into(img, app.pkg, Icons.Kind.TILE, w, h)
        val label = text(app.label, 27.uf, uiFont(500), ink, false).apply { gravity = Gravity.CENTER; alpha = .6f }
        t.addView(img, LinearLayout.LayoutParams(w, h))
        t.addView(label, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 18.u })
        t.setOnFocusChangeListener { _, f ->
            val s = if (f) 1.1f else 1f
            img.animate().scaleX(s).scaleY(s).translationZ(if (f) 28.uf else 0f).setDuration(Ui.T).setInterpolator(Ui.EASE)
            label.animate().alpha(if (f) 1f else .6f).translationY(if (f) 10.uf else 0f).setDuration(Ui.T)
        }
        t.setOnClickListener { press(img) { launch(app, img) } }
        t.setOnLongClickListener { appOptions(app); true }
        t.setOnKeyListener(menuKey)
        return t
    }

    // ---------- Settings ----------

    private fun settings() = push(sidebar("Settings") { l ->
        header(l, "Appearance")
        C.appearance.forEach { c -> row(l, c.title, value = { c.label(prefs[c]) }, chevron = true, visible = { applies(c) }) { choicePage(c) } }
        header(l, "Wallpaper")
        row(l, "Wallpaper", value = { Wall.name(this, prefs.wallpaper) }, chevron = true) { wallpaperPage() }
        row(l, C.rotation.title, value = { C.rotation.label(prefs[C.rotation]) }, chevron = true) { choicePage(C.rotation) }
        header(l, "Apps")
        row(l, "Apps on Home", value = { "${homeApps().size}" }, chevron = true) { homeAppsPage() }
        row(l, "Hidden Apps", value = { "${prefs.hidden.count { h -> apps.any { it.pkg == h } }}" }, chevron = true) { hiddenPage() }
        header(l, "TV")
        if (inputList.isNotEmpty()) row(l, "Inputs", chevron = true) { inputsSheet() }
        row(l, "Default Home App", value = { homeState() }, chevron = true) { makeDefault() }
        stockLauncher()?.let { i -> row(l, "Open TV's Own Launcher", chevron = true) { open(i) } }
        row(l, "Android Settings", chevron = true) { open(Intent(Settings.ACTION_SETTINGS)) }
        row(l, "About Still", chevron = true) { about() }
    })

    /** Options with no visible effect in the current setup are hidden rather than left as dead ends. */
    private fun applies(c: Choice) = when (c) {
        C.material -> Glass.bg == "photo"
        C.icons -> prefs[C.show] != "name"
        else -> true
    }

    private fun choicePage(c: Choice) {
        val note = if (c.key.startsWith("pos:")) "Choose which part of the photo stays on screen. Square and portrait photos move up and down; wide ones move left and right." else when (c) {
            C.material -> "Material applies to the glass over photo backgrounds."
            C.focus -> "Glass places a plate behind the focused app. Lift enlarges it instead."
            C.icons -> "Monochrome uses each app's glyph where it has one."
            C.font -> "Applies to app names, the clock and Home controls."
            else -> null
        }
        var focus: View? = null
        push(sidebar(c.title, note) { l ->
            c.opts.forEach { (v, label) ->
                val r = row(l, label, checked = { prefs[c] == v }) {
                    if (prefs[c] != v) { prefs[c] = v; if (c == C.rotation) scheduleRotation() else applyPrefs() }
                    refreshRows(l)
                }
                if (prefs[c] == v) focus = r
            }
        }, focus)
    }

    private var previewId: String? = null
    private val preview = Runnable { previewId?.let { if (Glass.bg == "photo") showWall(it) } }

    private fun wallpaperPage() {
        var focus: View? = null
        val note = if (Glass.bg != "photo") "Wallpaper shows when Background is set to Photo." else null
        val page = sidebar("Wallpaper", note) { l ->
            // Leaving the photo rows drops any preview, so these rows always describe the wallpaper in use.
            val showCurrent: (Boolean) -> Unit = { f -> if (f) { Ui.main.removeCallbacks(preview); previewId = prefs.wallpaper; Ui.main.postDelayed(preview, 350) } }
            header(l, "Your Photos")
            row(l, "Photo Folder", value = { folderName() ?: "None" }, chevron = true, onFocus = showCurrent) { withPhotos { loadThen({ Wall.folders(this) }, ::folderPage) } }
            row(l, "Add a Photo from This TV", icon = R.drawable.ic_image, chevron = true, onFocus = showCurrent) { withPhotos { loadThen(::recentPhotos, ::galleryPage) } }
            row(l, "Position", value = { C.position(prefs.wallpaper).let { it.label(prefs[it]) } }, chevron = true, onFocus = showCurrent) { choicePage(C.position(prefs.wallpaper)) }
            header(l, "Photos")
            for ((id, name) in Wall.all(this)) {
                val th = thumb()
                Icons.async(th, "thumb:$id") { scaledThumb(Wall.decode({ Wall.open(this, id) }, 256, 144)) }
                val r = row(l, name, checked = { prefs.wallpaper == id }, leading = th, onFocus = { f ->
                    // Browsing previews the photo after focus settles; only OK keeps it.
                    Ui.main.removeCallbacks(preview)
                    if (f) { previewId = id; Ui.main.postDelayed(preview, 350) }
                }) {
                    prefs.wallpaper = id; prefs.folder = null // a single photo replaces the folder
                    if (Glass.bg == "photo") showWall(id)
                    refreshRows(l)
                }
                if (id.startsWith("file:")) {
                    r.setOnLongClickListener { deleteWall(id, name); true }
                    r.setOnKeyListener(menuKey)
                }
                if (prefs.wallpaper == id) focus = r
            }
        }
        page.tag = "wallpaper"
        push(page, focus) {
            Ui.main.removeCallbacks(preview)
            if (Glass.bg == "photo") showWall(prefs.wallpaper)
        }
    }

    private fun deleteWall(id: String, name: String) = sheet("Delete “$name”?", null, listOf("Delete" to {
        File(Wall.dir(this), id.removePrefix("file:")).delete()
        if (prefs.wallpaper == id) prefs.wallpaper = Wall.BUILTIN.first().first
        reopenWallpaper()
    }))

    private fun reopenWallpaper() {
        while (stack.isNotEmpty() && stack.last().view.tag != "wallpaper") pop()
        if (stack.isNotEmpty()) pop()
        wallpaperPage()
    }

    private var afterPhotos: (() -> Unit)? = null

    private fun withPhotos(then: () -> Unit) {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) then()
        else { afterPhotos = then; requestPermissions(arrayOf(perm), REQ_PERM) }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (code != REQ_PERM) return
        val then = afterPhotos.also { afterPhotos = null }
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) then?.invoke()
        else toast("Still needs access to photos to show the ones on this TV.")
    }

    private fun folderName() = prefs.folder?.let { prefs.folderName ?: "Photo Folder" }

    /** Scans the photo library on a worker thread, then opens the page with the result. */
    private fun <T> loadThen(load: () -> T, show: (T) -> Unit) = Ui.io.execute {
        val r = load()
        Ui.main.post { show(r) }
    }

    private fun folderPage(folders: List<Wall.Folder>) {
        var focus: View? = null
        val note = if (folders.isEmpty()) "No photos found. Copy images to this TV or connect a USB drive, then try again."
        else "Home shows the photos in this folder, newest first, and moves to the next one on the Rotate Wallpaper schedule."
        push(sidebar("Photo Folder", note) { l ->
            val none = row(l, "None", checked = { prefs.folder == null }) {
                if (prefs.folder != null) {
                    prefs.folder = null
                    if (prefs.wallpaper.startsWith("media:")) { prefs.wallpaper = Wall.BUILTIN.first().first; if (Glass.bg == "photo") showWall(prefs.wallpaper) }
                }
                refreshRows(l)
            }
            if (prefs.folder == null) focus = none
            folders.forEach { f ->
                val th = thumb()
                Icons.async(th, "thumb:${f.cover}") { scaledThumb(Wall.decode({ Wall.open(this, f.cover) }, 256, 144)) }
                val r = row(l, f.name, value = { "${f.count}" }, checked = { prefs.folder == f.id }, leading = th) {
                    prefs.folder = f.id; prefs.folderName = f.name; prefs.wallpaper = f.cover
                    // A folder is a slideshow; start one if rotation was off.
                    if (prefs[C.rotation] == "off") { prefs[C.rotation] = "15"; toast("Wallpaper will change every 15 minutes.") }
                    scheduleRotation()
                    if (Glass.bg == "photo") showWall(f.cover)
                    refreshRows(l)
                }
                if (prefs.folder == f.id) focus = r
            }
        }, focus)
    }

    private val docIntent get() = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")

    private fun recentPhotos() = ArrayList<Pair<Long, String>>().also { imgs ->
        runCatching {
            contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME), null, null,
                "${MediaStore.Images.Media.DATE_MODIFIED} DESC")?.use { c ->
                // ponytail: newest 60 only; add paging if people keep big libraries on a TV
                while (c.moveToNext() && imgs.size < 60) imgs += c.getLong(0) to (c.getString(1) ?: "Photo")
            }
        }
    }

    private fun galleryPage(imgs: List<Pair<Long, String>>) {
        val src = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val canBrowse = docIntent.resolveActivity(packageManager) != null
        val note = if (imgs.isEmpty()) "No photos found. Copy images to this TV or connect a USB drive, then try again." else null
        push(sidebar("Photos on This TV", note) { l ->
            if (canBrowse) row(l, "Browse Files", chevron = true) { runCatching { startActivityForResult(docIntent, REQ_DOC) } }
            imgs.forEach { (id, name) ->
                val uri = ContentUris.withAppendedId(src, id)
                val th = thumb()
                Icons.async(th, "media:$id") { scaledThumb(Wall.decode({ contentResolver.openInputStream(uri)!! }, 256, 144)) }
                row(l, name, leading = th) { importWall { contentResolver.openInputStream(uri)!! } }
            }
        })
    }

    @Deprecated("Fine on API 28–34 without AndroidX")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        val uri = data?.data
        if (code == REQ_DOC && result == RESULT_OK && uri != null) importWall { contentResolver.openInputStream(uri)!! }
    }

    /** Copies a photo into app storage at screen size, so it needs no permission or network later. */
    private fun importWall(open: () -> InputStream) {
        toast("Adding photo…")
        Ui.io.execute {
            val id = runCatching {
                val tmp = File(cacheDir, "import.tmp")
                open().use { i ->
                    tmp.outputStream().use { o ->
                        val buf = ByteArray(64 shl 10); var total = 0
                        while (true) {
                            val n = i.read(buf); if (n < 0) break
                            total += n; check(total < 40 shl 20) { "too large" }
                            o.write(buf, 0, n)
                        }
                    }
                }
                val b = Wall.decode({ tmp.inputStream() }, 1920, 1080) ?: error("not an image")
                val f = File(Wall.dir(this), "w${System.currentTimeMillis()}.jpg")
                f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                tmp.delete()
                "file:${f.name}"
            }.getOrNull()
            Ui.main.post {
                if (id == null) return@post toast("That file couldn't be used as a wallpaper.")
                prefs.wallpaper = id
                if (Glass.bg == "photo") showWall(id)
                toast("Wallpaper added.")
                if (stack.any { it.view.tag == "wallpaper" }) reopenWallpaper()
            }
        }
    }

    private fun homeAppsPage() = push(sidebar("Apps on Home", "Checked apps appear on Home. To reorder, hold OK on an app on Home and choose Move.") { l ->
        val hidden = prefs.hidden
        apps.filter { it.pkg !in hidden }.forEach { app ->
            val th = ImageView(this)
            Icons.into(th, app.pkg, Icons.Kind.TILE, 224, 126)
            th.clipToOutline = true; th.outlineProvider = roundOutline(10.uf)
            row(l, app.label, checked = { app.pkg in home }, leading = th) {
                if (!home.remove(app.pkg)) home.add(app.pkg)
                prefs.home = home; buildHome(); refreshRows(l)
            }
        }
    })

    private fun hiddenPage() {
        val hidden = prefs.hidden
        val list = apps.filter { it.pkg in hidden }
        push(sidebar("Hidden Apps", if (list.isEmpty()) "No hidden apps. Hold OK on any app and choose Hide." else "Checked apps are hidden from Home and All Apps.") { l ->
            list.forEach { app ->
                row(l, app.label, checked = { app.pkg in prefs.hidden }) {
                    prefs.hidden = prefs.hidden.let { if (app.pkg in it) it - app.pkg else it + app.pkg }
                    buildHome(); refreshRows(l)
                }
            }
        })
    }

    // ---------- TV ----------

    private fun inputs(): List<Pair<String, String>> = runCatching {
        val tim = getSystemService(TvInputManager::class.java) ?: return emptyList()
        tim.tvInputList.filter { it.isPassthroughInput && !it.isHidden(this) }
            .map { it.id to ((it.loadCustomLabel(this) ?: it.loadLabel(this))?.toString() ?: "Input") }
    }.getOrDefault(emptyList())

    private fun inputsSheet() = sheet("Inputs", null, inputList.map { (id, label) ->
        label to { open(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id))) }
    })

    private fun isDefaultHome() = packageManager.resolveActivity(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
    )?.activityInfo?.packageName == packageName

    private fun homeState() = when {
        isDefaultHome() -> "Still"
        HomeButtonService.enabled(this) -> "Still (Accessibility)"
        else -> "Not Set"
    }

    /** The launcher Still replaces, kept reachable as the way back. */
    private fun stockLauncher(): Intent? = packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0,
    ).map { it.activityInfo }.firstOrNull { it.packageName != packageName && !it.name.endsWith("FallbackHome") }
        ?.let { Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setClassName(it.packageName, it.name) }

    private fun makeDefault() {
        val a11y = "Accessibility Settings" to { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        if (isDefaultHome() || HomeButtonService.enabled(this)) return sheet(
            "Still Is Your Home Screen",
            if (isDefaultHome()) "The Home button opens Still."
            else "Still Home Button is on, so the Home button opens Still. Turn it off in Accessibility settings to go back to the TV's own launcher.",
            if (isDefaultHome()) emptyList() else listOf(a11y), cancel = "OK",
        )
        val picker = Intent(Settings.ACTION_HOME_SETTINGS)
        if (picker.resolveActivity(packageManager) != null) return open(picker)
        sheet(
            "Make Still Your Home Screen",
            "This TV only lets its own launcher be the home app. Turn on Still Home Button in Accessibility settings, and the Home button will open Still instead.",
            listOf(a11y), cancel = "Not Now",
        )
    }

    private fun about() {
        val v = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        sheet(
            "Still $v",
            "A quiet home screen for Android TV, adapted from CleanFrame by Nathan Clark (MIT).\n\n" +
                "Inter, Manrope, Open Sans and Be Vietnam Pro: SIL Open Font License. Icons: Lucide (ISC).\n" +
                "Photos on Unsplash by Mohammad Alizade, John Rodenn Castillo, Aniket Deole, Ganapathy Kumar, " +
                "Dejan Zakic, Rudra Gupta and Karthik Sreenivas.",
            emptyList(), cancel = "Done",
        )
    }
}
