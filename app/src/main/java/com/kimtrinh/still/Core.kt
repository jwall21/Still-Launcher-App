package com.kimtrinh.still

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.*
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/** Layout is designed on CleanFrame's 1920×1080 stage and scaled to the real screen. */
object Ui {
    var s = 1f
    val EASE = PathInterpolator(.2f, .7f, .2f, 1f)
    const val T = 220L
    val main = Handler(Looper.getMainLooper())
    val io = Executors.newFixedThreadPool(2)!!

    fun init(ctx: Context) {
        val dm = ctx.resources.displayMetrics
        s = max(dm.widthPixels, dm.heightPixels) / 1920f
    }
}

val Int.u get() = (this * Ui.s).roundToInt()
val Int.uf get() = this * Ui.s

object Fonts {
    private val cache = HashMap<String, Typeface>()
    fun get(ctx: Context, family: String, weight: Int): Typeface = cache.getOrPut("$family/$weight") {
        if (family == "system") Typeface.create(Typeface.DEFAULT, weight, false)
        else if ('-' in family) Typeface.createFromAsset(ctx.assets, "fonts/$family.ttf") // static cut: the file is the weight
        else Typeface.Builder(ctx.assets, "fonts/$family.ttf")
            // Inter's display optical size reads like SF Pro Display at TV distances.
            .setFontVariationSettings(if (family == "Inter") "'wght' $weight, 'opsz' 32" else "'wght' $weight")
            .setWeight(weight).build() ?: Typeface.DEFAULT
    }
}

class Choice(val key: String, val title: String, val def: String, vararg val opts: Pair<String, String>) {
    fun label(v: String) = opts.firstOrNull { it.first == v }?.second ?: v
}

object C {
    val background = Choice("bg", "Background", "photo", "photo" to "Photo", "dark" to "Dark", "light" to "Light")
    val layout = Choice("layout", "Layout", "list", "list" to "List", "dock" to "Dock")
    val show = Choice("show", "App Style", "both", "name" to "Names", "icon" to "Icons", "both" to "Icons and Names")
    val icons = Choice("icons", "Icon Color", "mono", "mono" to "Monochrome", "color" to "Full Color")
    val controls = Choice("controls", "Control Style", "both", "name" to "Names", "icon" to "Icons", "both" to "Icons and Names")
    val focus = Choice("focus", "Focus Style", "glass", "glass" to "Glass", "lift" to "Lift")
    val material = Choice("material", "Material", "clear", "clear" to "Clear", "frost" to "Frosted", "tint" to "Tinted")
    val size = Choice("size", "Text Size", "m", "s" to "Small", "m" to "Medium", "l" to "Large")
    val font = Choice(
        "font", "Typeface", "Inter",
        "Inter" to "Inter", "Manrope" to "Manrope", "OpenSans" to "Open Sans",
        "BeVietnamPro-Light" to "Be Vietnam Pro Light", "BeVietnamPro-Regular" to "Be Vietnam Pro", "system" to "System",
    )
    val clock = Choice("clock", "Clock", "12", "12" to "12-Hour", "24" to "24-Hour", "off" to "Off")
    val rotation = Choice(
        "rotation", "Rotate Wallpaper", "off",
        "off" to "Never", "open" to "Each Time Home Opens", "5" to "Every 5 Minutes",
        "15" to "Every 15 Minutes", "60" to "Every Hour",
    )
    val appearance = listOf(background, layout, show, icons, controls, focus, material, size, font, clock)
}

class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("still", Context.MODE_PRIVATE)
    operator fun get(c: Choice): String = sp.getString(c.key, c.def) ?: c.def
    operator fun set(c: Choice, v: String) = sp.edit().putString(c.key, v).apply()

    var wallpaper: String
        get() = sp.getString("wall", "night")!!
        set(v) = sp.edit().putString("wall", v).apply()
    var home: List<String>?
        get() = sp.getString("home", null)?.split('\n')?.filter { it.isNotEmpty() }
        set(v) = sp.edit().putString("home", v.orEmpty().joinToString("\n")).apply()
    /** MediaStore bucket id of the photo folder Home shows, or null for the built-in set. */
    var folder: String?
        get() = sp.getString("folder", null)
        set(v) = sp.edit().apply { if (v == null) remove("folder") else putString("folder", v) }.apply()
    /** Kept beside [folder] so Settings can show it without scanning the photo library. */
    var folderName: String?
        get() = sp.getString("folderName", null)
        set(v) = sp.edit().putString("folderName", v).apply()
    var hidden: Set<String>
        get() = sp.getStringSet("hidden", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("hidden", v).apply()

    fun label(pkg: String): String? = sp.getString("label:$pkg", null)
    fun setLabel(pkg: String, v: String?) = sp.edit().apply {
        if (v.isNullOrBlank()) remove("label:$pkg") else putString("label:$pkg", v.trim())
    }.apply()
}

class App(val pkg: String, val sysLabel: String, val tv: Boolean) {
    var label = sysLabel
    override fun equals(other: Any?) = other is App && other.pkg == pkg && other.label == label
    override fun hashCode() = pkg.hashCode()
}

object Apps {
    private val DEFAULTS = listOf(
        "com.netflix.ninja", "com.google.android.youtube.tv", "org.smarttube.stable", "com.stremio.one",
        "com.amazon.amazonvideo.livingroom", "com.apple.atve.androidtv.appletv", "com.disney.disneyplus",
        "com.spotify.tv.android", "com.plexapp.android", "org.xbmc.kodi", "org.videolan.vlc",
    )

    /** One entry per package; TV (leanback) activities win over phone launcher activities. */
    fun load(ctx: Context, prefs: Prefs): List<App> {
        val pm = ctx.packageManager
        val out = LinkedHashMap<String, App>()
        for ((cat, tv) in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER to true, Intent.CATEGORY_LAUNCHER to false))
            for (ri in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                val p = ri.activityInfo.packageName
                if (p == ctx.packageName || p in out) continue
                out[p] = App(p, ri.loadLabel(pm).toString(), tv).also { a -> prefs.label(p)?.let { a.label = it } }
            }
        return out.values.sortedBy { it.label.lowercase() }
    }

    fun defaults(all: List<App>): List<String> =
        DEFAULTS.filter { p -> all.any { it.pkg == p } }.ifEmpty { all.filter { it.tv }.take(6).map { it.pkg } }

    fun launchIntent(ctx: Context, pkg: String): Intent? =
        ctx.packageManager.run { getLeanbackLaunchIntentForPackage(pkg) ?: getLaunchIntentForPackage(pkg) }
}

/** Icons are rendered off the main thread at their final pixel size and kept in one LRU. */
object Icons {
    enum class Kind { MONO, COLOR, TILE }

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 10).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun trim() = cache.evictAll()

    fun into(v: ImageView, pkg: String, kind: Kind, w: Int, h: Int = w, ink: Int = Color.WHITE) =
        async(v, "$pkg|$kind|$w|$ink") { render(v.context, pkg, kind, w, h, ink) }

    fun async(v: ImageView, key: String, make: () -> Bitmap?) {
        v.tag = key
        cache.get(key)?.let { v.setImageBitmap(it); return }
        Ui.io.execute {
            val b = runCatching(make).getOrNull()
            if (b != null) cache.put(key, b)
            Ui.main.post { if (v.tag == key && b != null) v.setImageBitmap(b) }
        }
    }

    private fun render(ctx: Context, pkg: String, kind: Kind, w: Int, h: Int, ink: Int): Bitmap {
        val pm = ctx.packageManager
        val launch = Apps.launchIntent(ctx, pkg)
        val icon = runCatching { launch?.let { pm.getActivityIcon(it) } ?: pm.getApplicationIcon(pkg) }
            .getOrElse { pm.defaultActivityIcon }
        return when (kind) {
            Kind.MONO -> mono(icon, w, ink) ?: rounded(icon, w)
            Kind.COLOR -> rounded(icon, w)
            Kind.TILE -> {
                val banner = runCatching {
                    pm.getLeanbackLaunchIntentForPackage(pkg)?.let { pm.getActivityBanner(it) } ?: pm.getApplicationBanner(pkg)
                }.getOrNull()
                if (banner != null) draw(banner, w, h) else poster(icon, w, h)
            }
        }
    }

    private fun draw(d: Drawable, w: Int, h: Int, inset: Int = 0) =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            d.setBounds(-inset, -inset, w + inset, h + inset); d.draw(Canvas(it))
        }

    /** Adaptive icons get Apple's continuous-corner proportion (≈22.5%) instead of the OEM mask. */
    private fun rounded(d: Drawable, s: Int): Bitmap {
        if (d !is AdaptiveIconDrawable) return draw(d, s, s)
        val e = s / 4 // the 72dp visible area inside a 108dp layer
        val art = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        Canvas(art).let { c -> listOfNotNull(d.background, d.foreground).forEach { it.setBounds(-e, -e, s + e, s + e); it.draw(c) } }
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawRoundRect(0f, 0f, s.toFloat(), s.toFloat(), s * .225f, s * .225f, p)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        c.drawBitmap(art, 0f, 0f, p)
        return out
    }

    /** A white glyph, like the marks in the reference. Null when the art is a full-bleed shape. */
    private fun mono(d: Drawable, s: Int, ink: Int): Bitmap? {
        val src = when {
            Build.VERSION.SDK_INT >= 33 && d is AdaptiveIconDrawable && d.monochrome != null -> d.monochrome!!
            d is AdaptiveIconDrawable -> d.foreground ?: return null
            else -> d
        }
        val b = draw(src, s, s, if (d is AdaptiveIconDrawable) s / 4 else 0)
        val px = IntArray(s * s)
        b.getPixels(px, 0, s, 0, 0, s, s)
        val solid = px.count { (it ushr 24) > 160 }
        // ponytail: coverage heuristic; a round or square badge silhouettes into a blob, so keep colour for those
        if (solid == 0 || solid > px.size * .62) return null
        for (i in px.indices) px[i] = (px[i] and 0xFF000000.toInt()) or (ink and 0xFFFFFF)
        b.setPixels(px, 0, s, 0, 0, s, s)
        return b
    }

    /** Apps without a TV banner: their icon on a field of its own average colour. */
    private fun poster(d: Drawable, w: Int, h: Int): Bitmap {
        val ic = rounded(d, h * 52 / 100)
        val px = IntArray(ic.width * ic.height).also { ic.getPixels(it, 0, ic.width, 0, 0, ic.width, ic.height) }
        var r = 0L; var g = 0L; var b = 0L; var a = 0L
        for (p in px) { val al = (p ushr 24).toLong(); a += al; r += (p shr 16 and 255) * al; g += (p shr 8 and 255) * al; b += (p and 255) * al }
        a = max(a, 1)
        val bg = Color.rgb((r / a * .55).toInt(), (g / a * .55).toInt(), (b / a * .55).toInt())
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).apply { drawColor(bg); drawBitmap(ic, (w - ic.width) / 2f, (h - ic.height) / 2f, null) }
        return out
    }
}

object Wall {
    val BUILTIN = listOf(
        "night" to "Desert Stars", "peaks" to "Blue Peaks", "valley" to "Mountain Valley", "misty" to "Misty Coast",
        "forest" to "Forest Mist", "summit" to "Sunlit Summits", "golden" to "Golden Hour",
    )

    class Loaded(val photo: Bitmap, val blur: Bitmap, val light: Boolean)

    fun dir(ctx: Context) = File(ctx.filesDir, "walls").apply { mkdirs() }

    fun all(ctx: Context): List<Pair<String, String>> = BUILTIN +
        dir(ctx).listFiles().orEmpty().sortedBy { it.name }.mapIndexed { i, f -> "file:${f.name}" to "My Photo ${i + 1}" }

    fun name(ctx: Context, id: String) =
        if (id.startsWith("media:")) "From Your Folder" else all(ctx).firstOrNull { it.first == id }?.second ?: "Photo"

    private val MEDIA = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    fun open(ctx: Context, id: String): InputStream = when {
        id.startsWith("file:") -> File(dir(ctx), id.removePrefix("file:")).inputStream()
        id.startsWith("media:") -> ctx.contentResolver.openInputStream(
            android.content.ContentUris.withAppendedId(MEDIA, id.removePrefix("media:").toLong()),
        ) ?: error("photo gone")
        else -> ctx.assets.open("wallpapers/$id.jpg")
    }

    class Folder(val id: String, val name: String, val count: Int, val cover: String)

    /** Image folders the media scanner knows about, USB drives included, newest first. */
    fun folders(ctx: Context): List<Folder> {
        val out = LinkedHashMap<String, Folder>()
        query(ctx, null, null) { c ->
            val b = c.getString(1) ?: return@query
            val f = out[b]
            out[b] = if (f == null) Folder(b, c.getString(2) ?: "Photos", 1, "media:${c.getLong(0)}") else Folder(b, f.name, f.count + 1, f.cover)
        }
        return out.values.toList()
    }

    fun folderPhotos(ctx: Context, bucket: String): List<String> = ArrayList<String>().also { l ->
        query(ctx, "${android.provider.MediaStore.Images.Media.BUCKET_ID} = ?", arrayOf(bucket)) { l += "media:${it.getLong(0)}" }
    }

    private fun query(ctx: Context, where: String?, args: Array<String>?, each: (android.database.Cursor) -> Unit) = runCatching {
        ctx.contentResolver.query(
            MEDIA, arrayOf("_id", "bucket_id", "bucket_display_name"), where, args,
            "${android.provider.MediaStore.Images.Media.DATE_MODIFIED} DESC",
        )?.use { c -> while (c.moveToNext()) each(c) }
    }

    /** Decodes at the smallest power-of-two size that still covers w×h. */
    fun decode(open: () -> InputStream, w: Int, h: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0) return null
        var sample = 1
        // Compare long side to long side so a portrait phone photo isn't decoded at full 12 MP.
        val long = max(o.outWidth, o.outHeight); val short = kotlin.math.min(o.outWidth, o.outHeight)
        while (long / (sample * 2) >= max(w, h) && short / (sample * 2) >= kotlin.math.min(w, h)) sample *= 2
        val b = open().use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = config })
        } ?: return null
        // Phone photos are stored sideways with an EXIF hint; honour it.
        val deg = runCatching {
            open().use {
                when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
                }
            }
        }.getOrDefault(0f)
        return if (deg == 0f) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { setRotate(deg) }, true)
    }

    fun load(ctx: Context, id: String, w: Int, h: Int): Loaded? {
        // Low-RAM TVs get a half-resolution 16-bit photo: 1 MB instead of 8 MB.
        val low = ctx.getSystemService(ActivityManager::class.java).isLowRamDevice
        val tw = if (low) w / 2 else w; val th = if (low) h / 2 else h
        val config = if (low) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        val photo = runCatching { decode({ open(ctx, id) }, tw, th, config)?.let { cover(it, tw, th, config) } }.getOrNull() ?: return null
        val blur = blur(photo)
        var lum = 0L
        val px = IntArray(blur.width * blur.height).also { blur.getPixels(it, 0, blur.width, 0, 0, blur.width, blur.height) }
        for (p in px) lum += (p shr 16 and 255) * 3 + (p shr 8 and 255) * 6 + (p and 255)
        return Loaded(photo, blur, lum / px.size / 10 > 150)
    }

    /** Crops to exactly w×h, so a square or 12 MP photo costs no more than one screen of pixels. */
    private fun cover(src: Bitmap, w: Int, h: Int, config: Bitmap.Config): Bitmap {
        if (src.width == w && src.height == h) return src
        val sc = max(w / src.width.toFloat(), h / src.height.toFloat())
        val m = Matrix().apply { setScale(sc, sc); postTranslate((w - src.width * sc) / 2f, (h - src.height * sc) / 2f) }
        val out = Bitmap.createBitmap(w, h, config)
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle() // never shown, so safe to free right away
        return out
    }

    /** Glass material, rendered once per photo: halve to ~240px, then three box passes ≈ gaussian. */
    private fun blur(src: Bitmap): Bitmap {
        var b = src
        while (b.width > 320) b = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
        val w = b.width; val h = b.height
        val px = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        repeat(3) { box(px, w, h, 4, true); box(px, w, h, 4, false) }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun box(px: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val n = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val tmp = IntArray(n)
        val d = 2 * r + 1
        for (l in 0 until lines) {
            fun at(i: Int) = px[if (horizontal) l * w + i.coerceIn(0, n - 1) else i.coerceIn(0, n - 1) * w + l]
            for (i in 0 until n) {
                var rs = 0; var gs = 0; var bs = 0
                for (k in -r..r) { val p = at(i + k); rs += p shr 16 and 255; gs += p shr 8 and 255; bs += p and 255 }
                tmp[i] = (0xFF shl 24) or (rs / d shl 16) or (gs / d shl 8) or (bs / d)
            }
            for (i in 0 until n) px[if (horizontal) l * w + i else i * w + l] = tmp[i]
        }
    }
}

/** Every translucent surface shares one pre-blurred copy of the wallpaper; no live blur. */
object Glass {
    enum class Kind { MAT, PLATE, LIFT, PANEL }

    var wall: Wall.Loaded? = null
    var bg = "photo"
    var material = "clear"
    var sw = 1920; var sh = 1080

    val light get() = bg == "light"
    val ink get() = if (light) 0xFF1C1C1E.toInt() else 0xFFF5F5F7.toInt()
    val flat get() = if (light) 0xFFEDEDF0.toInt() else 0xFF131416.toInt()

    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val veil = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val m = Matrix()
    private var shaderFor: Bitmap? = null

    fun centerCrop(m: Matrix, bw: Int, bh: Int) {
        val sc = max(sw / bw.toFloat(), sh / bh.toFloat())
        m.setScale(sc, sc)
        m.postTranslate((sw - bw * sc) / 2f, (sh - bh * sc) / 2f)
    }

    fun draw(c: Canvas, r: RectF, radius: Float, ox: Float, oy: Float, kind: Kind) {
        val w = wall
        val photo = bg == "photo" && w != null
        if (photo) {
            if (shaderFor !== w!!.blur) {
                shaderPaint.shader = BitmapShader(w.blur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                shaderFor = w.blur
            }
            centerCrop(m, w.blur.width, w.blur.height)
            m.postTranslate(-ox, -oy)
            shaderPaint.shader.setLocalMatrix(m)
            c.drawRoundRect(r, radius, radius, shaderPaint)
        }
        val lightPhoto = photo && w!!.light
        veil.color = when {
            photo -> when (kind) {
                Kind.PANEL -> if (lightPhoto) 0x80000000.toInt() else 0x66000000
                Kind.MAT -> when (material) { "frost" -> 0x29FFFFFF; "tint" -> 0x38000000; else -> 0x0FFFFFFF }
                Kind.PLATE -> when (material) { "frost" -> 0x47FFFFFF; "tint" -> 0x24FFFFFF; else -> 0x2EFFFFFF }
                Kind.LIFT -> 0x5CFFFFFF
            }
            light -> when (kind) {
                Kind.PANEL -> 0xF7FFFFFF.toInt(); Kind.MAT -> 0x0D000000; Kind.PLATE -> 0xE6FFFFFF.toInt(); Kind.LIFT -> 0xFFFFFFFF.toInt()
            }
            else -> when (kind) {
                Kind.PANEL -> 0xF72A2A2D.toInt(); Kind.MAT -> 0x14FFFFFF; Kind.PLATE -> 0x24FFFFFF; Kind.LIFT -> 0x3DFFFFFF
            }
        }
        c.drawRoundRect(r, radius, radius, veil)
        if (!light && kind != Kind.PANEL) {
            // Specular top edge, fading out by the end of the corner radius.
            edge.strokeWidth = max(1f, 1.5f * Ui.s)
            edge.shader = LinearGradient(0f, r.top, 0f, r.top + radius, 0x47FFFFFF, 0, Shader.TileMode.CLAMP)
            val i = edge.strokeWidth / 2
            c.drawRoundRect(r.left + i, r.top + i, r.right - i, r.bottom - i, radius, radius, edge)
        }
    }
}

/** A LinearLayout that paints itself as glass. */
class GlassBox(ctx: Context, private val radius: Float, private val kind: Glass.Kind) : LinearLayout(ctx) {
    private val loc = IntArray(2)
    private val r = RectF()

    init { setWillNotDraw(false); orientation = VERTICAL }

    override fun onDraw(c: Canvas) {
        getLocationInWindow(loc)
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        Glass.draw(c, r, radius, loc[0].toFloat(), loc[1].toFloat(), kind)
    }
}

/** Full-screen wallpaper with the scrims that keep type legible, cross-faded on change. */
class WallView(ctx: Context) : View(ctx) {
    private var cur: Bitmap? = null
    private var prev: Bitmap? = null
    private var fade = 1f
    private val m = Matrix()
    private val p = Paint(Paint.FILTER_BITMAP_FLAG)
    private val scrim = Paint()
    var listLayout = true

    fun set(b: Bitmap?) {
        if (b === cur) return
        prev = cur; cur = b; fade = 0f
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450
            addUpdateListener {
                fade = it.animatedValue as Float
                if (fade >= 1f) prev = null // let the old photo go once it's no longer visible
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { Glass.sw = w; Glass.sh = h }

    override fun onDraw(c: Canvas) {
        if (Glass.bg != "photo") { c.drawColor(Glass.flat); return }
        c.drawColor(Color.BLACK)
        if (fade < 1f) prev?.let { photo(c, it, 255) }
        cur?.let { photo(c, it, (fade * 255).toInt()) }
        val w = width.toFloat(); val h = height.toFloat()
        val k = if (Glass.wall?.light == true) 1.4f else 1f
        fun a(x: Float) = ((x * k).coerceAtMost(1f) * 255).toInt() shl 24
        // Top shade for the clock, bottom shade, and a left column behind the list.
        scrim.shader = LinearGradient(0f, 0f, 0f, h * .28f, a(.30f), 0, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h * .28f, scrim)
        scrim.shader = LinearGradient(0f, h * .55f, 0f, h, 0, a(.40f), Shader.TileMode.CLAMP)
        c.drawRect(0f, h * .55f, w, h, scrim)
        if (listLayout) {
            scrim.shader = LinearGradient(0f, 0f, w * .6f, 0f, a(.28f), 0, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w * .6f, h, scrim)
        }
    }

    private fun photo(c: Canvas, b: Bitmap, alpha: Int) {
        Glass.centerCrop(m, b.width, b.height)
        p.alpha = alpha
        c.drawBitmap(b, m, p)
    }
}

/**
 * The single focus plate on Home. It glides to the focused view, then re-reads that view's
 * on-screen bounds before every frame, so scrolling, relayout and press animations can't strand it.
 */
class PlateLayer(ctx: Context) : View(ctx), android.view.ViewTreeObserver.OnPreDrawListener {
    private val cur = RectF(); private val from = RectF(); private val live = RectF(); private val tmp = RectF()
    private var target: View? = null
    private var radius = 0f
    private var f = 1f
    var lifted = false
        set(v) { field = v; invalidate() }
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = Ui.T; interpolator = Ui.EASE
        addUpdateListener { f = it.animatedValue as Float; follow() }
    }
    private val loc = IntArray(2); private val vloc = IntArray(2)

    private fun lerp(a: Float, b: Float) = a + (b - a) * f

    private fun follow() {
        cur.set(lerp(from.left, live.left), lerp(from.top, live.top), lerp(from.right, live.right), lerp(from.bottom, live.bottom))
        invalidate()
    }

    private fun bounds(v: View, out: RectF) {
        getLocationInWindow(loc); v.getLocationInWindow(vloc)
        val l = (vloc[0] - loc[0]).toFloat(); val t = (vloc[1] - loc[1]).toFloat()
        // Scaled views report their unscaled size; the plate wraps the visual bounds.
        out.set(l, t, l + v.width * v.scaleX, t + v.height * v.scaleY)
    }

    fun moveTo(v: View, rad: Float) {
        val first = target == null
        target = v; radius = rad
        bounds(v, live)
        from.set(cur); f = 0f
        if (first || !ValueAnimator.areAnimatorsEnabled()) { anim.cancel(); f = 1f; follow() } else anim.start()
    }

    fun hide() { anim.cancel(); target = null; invalidate() }

    override fun onPreDraw(): Boolean {
        val v = target ?: return true
        if (!v.isAttachedToWindow) { hide(); return true }
        bounds(v, tmp)
        if (tmp != live) { live.set(tmp); if (!anim.isRunning) follow() }
        return true
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); viewTreeObserver.addOnPreDrawListener(this) }
    override fun onDetachedFromWindow() { viewTreeObserver.removeOnPreDrawListener(this); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        if (target != null) Glass.draw(c, cur, radius, 0f, 0f, if (lifted) Glass.Kind.LIFT else Glass.Kind.PLATE)
    }
}

/** Keeps the focused child near [anchor] of the viewport rather than pinned to an edge. */
class VScroll(ctx: Context, private val anchor: Float) : ScrollView(ctx) {
    init {
        isVerticalScrollBarEnabled = false; isVerticalFadingEdgeEnabled = true
        overScrollMode = OVER_SCROLL_NEVER; clipToPadding = false
        isFocusable = false // focus belongs to the rows, never the scroller
    }

    // Past the last item, stop instead of paging into empty padding.
    override fun arrowScroll(direction: Int) =
        android.view.FocusFinder.getInstance().findNextFocus(this, findFocus(), direction) != null && super.arrowScroll(direction)

    override fun computeScrollDeltaToGetChildRectOnScreen(r: android.graphics.Rect): Int {
        val child = getChildAt(0) ?: return 0
        val max = max(0, child.height - height)
        return (r.centerY() - (height * anchor).toInt()).coerceIn(0, max) - scrollY
    }
}

class HScroll(ctx: Context, private val anchor: Float) : HorizontalScrollView(ctx) {
    init {
        isHorizontalScrollBarEnabled = false; isHorizontalFadingEdgeEnabled = true
        overScrollMode = OVER_SCROLL_NEVER; isFocusable = false
    }

    override fun arrowScroll(direction: Int) =
        android.view.FocusFinder.getInstance().findNextFocus(this, findFocus(), direction) != null && super.arrowScroll(direction)

    override fun computeScrollDeltaToGetChildRectOnScreen(r: android.graphics.Rect): Int {
        val child = getChildAt(0) ?: return 0
        val max = max(0, child.width - width)
        return (r.centerX() - (width * anchor).toInt()).coerceIn(0, max) - scrollX
    }
}
