/*
 * Copyright (C) 2026 IchthysOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.screenshot.ichthys

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.compose.theme.PlatformTheme
import com.android.systemui.res.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * IchthysOS: lightweight in-SystemUI screenshot editor (draw / crop / rotate).
 *
 * Launched with ACTION_EDIT + a MediaStore content:// URI by the screenshot shelf and by
 * LongScreenshotActivity (see config_screenshotEditor). Edits are kept as a replayable op list:
 * the preview works on a screen-sized sample, Save replays the ops on the full-resolution image.
 */
class ShotEditorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val uri = intent?.data
        if (uri == null) {
            finish()
            return
        }
        setContent { PlatformTheme { ShotEditorScreen(uri = uri, onDone = { finish() }) } }
    }

    companion object {
        const val TAG = "ShotEditor"
    }
}

// region State

private enum class Tool {
    DRAW,
    CROP,
    ROTATE,
}

/** Normalized rect (0..1 of the current image). */
private data class NRect(val l: Float, val t: Float, val r: Float, val b: Float) {
    val isFull: Boolean
        get() = l <= 0.001f && t <= 0.001f && r >= 0.999f && b >= 0.999f

    companion object {
        val FULL = NRect(0f, 0f, 1f, 1f)
    }
}

private val WIDTH_FRACS = floatArrayOf(0.005f, 0.01f, 0.02f)
private val CROP_RATIOS = floatArrayOf(0f, 1f, 9f / 16f, 16f / 9f)
private const val MIN_CROP = 0.05f

private class EditorState {
    var source by mutableStateOf<Bitmap?>(null)
    /** inSampleSize the preview [source] was decoded with. */
    var sample = 1

    var ops by mutableStateOf<List<EditOp>>(emptyList())
    var redoStack by mutableStateOf<List<EditOp>>(emptyList())

    /** [source] with [bakedOps] applied. */
    var baked by mutableStateOf<Bitmap?>(null)
    var bakedOps by mutableStateOf<List<EditOp>>(emptyList())

    var tool by mutableStateOf(Tool.DRAW)
    var colorIndex by mutableIntStateOf(0)
    var widthIndex by mutableIntStateOf(1)
    var highlighter by mutableStateOf(false)
    /** Pen palette as ARGB, refreshed from composition (theme-dependent). */
    var palette: List<Int> = emptyList()

    /** Stroke being drawn, normalized x,y pairs. */
    val live = mutableStateListOf<Float>()

    var crop by mutableStateOf(NRect.FULL)
    var cropRatioIndex by mutableIntStateOf(0)

    var saving by mutableStateOf(false)

    fun currentSize(): Pair<Int, Int> {
        val src = source ?: return 0 to 0
        return ShotRenderer.sizeAfter(src.width, src.height, ops)
    }

    fun push(op: EditOp) {
        ops = ops + op
        redoStack = emptyList()
    }

    fun undo() {
        if (ops.isEmpty()) return
        redoStack = redoStack + ops.last()
        ops = ops.dropLast(1)
        resetCrop()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        ops = ops + redoStack.last()
        redoStack = redoStack.dropLast(1)
        resetCrop()
    }

    fun resetCrop() {
        val (w, h) = currentSize()
        crop = presetRect(CROP_RATIOS[cropRatioIndex], w, h)
    }

    fun commitCrop() {
        val c = crop
        if (!c.isFull) push(CropOp(c.l, c.t, c.r, c.b))
        resetCrop()
    }

    fun selectTool(t: Tool) {
        if (tool == t) return
        if (tool == Tool.CROP) commitCrop()
        tool = t
        if (t == Tool.CROP) resetCrop()
    }

    /** Strokes appended after [bakedOps] that are not baked yet (drawn as an overlay). */
    fun pendingStrokes(): List<StrokeOp> {
        val done = bakedOps
        val all = ops
        if (done.size > all.size) return emptyList()
        for (i in done.indices) if (all[i] !== done[i]) return emptyList()
        val rest = all.subList(done.size, all.size)
        return if (rest.all { it is StrokeOp }) rest.map { it as StrokeOp } else emptyList()
    }
}

private fun presetRect(ratio: Float, iw: Int, ih: Int): NRect {
    if (ratio <= 0f || iw <= 0 || ih <= 0) return NRect.FULL
    val imgR = iw.toFloat() / ih
    return if (imgR > ratio) {
        val wn = ratio / imgR
        NRect((1f - wn) / 2f, 0f, (1f + wn) / 2f, 1f)
    } else {
        val hn = imgR / ratio
        NRect(0f, (1f - hn) / 2f, 1f, (1f + hn) / 2f)
    }
}

private fun fit(bw: Int, bh: Int, cw: Float, ch: Float): Rect {
    if (bw <= 0 || bh <= 0 || cw <= 0f || ch <= 0f) return Rect.Zero
    val s = min(cw / bw, ch / bh)
    val w = bw * s
    val h = bh * s
    val l = (cw - w) / 2f
    val t = (ch - h) / 2f
    return Rect(l, t, l + w, t + h)
}

/** Crop rect for a drag of (dx, dy) (normalized) from [s] with the given grabbed edges. */
private fun dragCrop(
    s: NRect,
    dx: Float,
    dy: Float,
    mL: Boolean,
    mT: Boolean,
    mR: Boolean,
    mB: Boolean,
    ratio: Float,
    iw: Int,
    ih: Int,
): NRect {
    if (!mL && !mT && !mR && !mB) {
        val w = s.r - s.l
        val h = s.b - s.t
        val l = (s.l + dx).coerceIn(0f, 1f - w)
        val t = (s.t + dy).coerceIn(0f, 1f - h)
        return NRect(l, t, l + w, t + h)
    }
    if (ratio <= 0f) {
        return NRect(
            if (mL) (s.l + dx).coerceIn(0f, s.r - MIN_CROP) else s.l,
            if (mT) (s.t + dy).coerceIn(0f, s.b - MIN_CROP) else s.t,
            if (mR) (s.r + dx).coerceIn(s.l + MIN_CROP, 1f) else s.r,
            if (mB) (s.b + dy).coerceIn(s.t + MIN_CROP, 1f) else s.b,
        )
    }
    // Locked aspect: only corners resize, anchored at the opposite corner.
    if (!((mL || mR) && (mT || mB))) return s
    val ax = if (mL) s.r else s.l
    val ay = if (mT) s.b else s.t
    val mx = ((if (mL) s.l else s.r) + dx).coerceIn(0f, 1f)
    val my = ((if (mT) s.t else s.b) + dy).coerceIn(0f, 1f)
    var wpx = max(abs(mx - ax) * iw, abs(my - ay) * ih * ratio)
    val availX = (if (mL) ax else 1f - ax) * iw
    val availY = (if (mT) ay else 1f - ay) * ih
    val maxW = min(availX, availY * ratio)
    wpx = max(wpx, MIN_CROP * min(iw.toFloat(), ih * ratio)).coerceAtMost(maxW)
    val wn = wpx / iw
    val hn = wpx / ratio / ih
    return NRect(
        if (mL) ax - wn else ax,
        if (mT) ay - hn else ay,
        if (mL) ax else ax + wn,
        if (mT) ay else ay + hn,
    )
}

private suspend fun AwaitPointerEventScope.trackPointer(id: PointerId, onMove: (Offset) -> Unit) {
    while (true) {
        val ev = awaitPointerEvent()
        val c = ev.changes.firstOrNull { it.id == id } ?: return
        if (!c.pressed) return
        if (c.positionChanged()) {
            onMove(c.position)
            c.consume()
        }
    }
}

// endregion

// region IO

private fun decodePreview(resolver: ContentResolver, uri: Uri, maxDim: Int): Pair<Bitmap, Int>? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val w = bounds.outWidth
    val h = bounds.outHeight
    if (w <= 0 || h <= 0) return null
    var sample = 1
    while (w / sample > maxDim || (w / sample).toLong() * (h / sample) > MAX_PREVIEW_PIXELS) {
        sample *= 2
    }
    val opts =
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    val bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    return bmp?.let { it to sample }
}

private const val MAX_PREVIEW_PIXELS = 16_000_000L

private fun decodeFull(resolver: ContentResolver, uri: Uri): Bitmap? {
    for (sample in intArrayOf(1, 2)) {
        try {
            val opts =
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            val bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (bmp != null) return bmp
        } catch (e: OutOfMemoryError) {
            Log.w(ShotEditorActivity.TAG, "Full-res decode OOM at sample $sample", e)
        }
    }
    return null
}

/** Writes the edited image. Returns true on success. Runs on a background thread. */
private fun saveImage(
    resolver: ContentResolver,
    uri: Uri,
    preview: Bitmap,
    previewSample: Int,
    ops: List<EditOp>,
    asCopy: Boolean,
): Boolean {
    val full = if (previewSample == 1) preview else decodeFull(resolver, uri) ?: preview
    val out = ShotRenderer.render(full, ops)
    try {
        if (!asCopy) {
            // Keep the original container format so the MediaStore mime type stays truthful.
            val format =
                when (resolver.getType(uri)) {
                    "image/jpeg" -> Bitmap.CompressFormat.JPEG
                    "image/webp" -> Bitmap.CompressFormat.WEBP_LOSSLESS
                    else -> Bitmap.CompressFormat.PNG
                }
            val os = resolver.openOutputStream(uri, "wt") ?: return false
            val quality = if (format == Bitmap.CompressFormat.JPEG) 95 else 100
            os.use { return out.compress(format, quality, it) }
        }
        val name =
            "Screenshot_" +
                SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) +
                "_edited.png"
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/" + Environment.DIRECTORY_SCREENSHOTS,
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val newUri = resolver.insert(collection, values) ?: return false
        try {
            val ok =
                resolver.openOutputStream(newUri, "w")?.use {
                    out.compress(Bitmap.CompressFormat.PNG, 100, it)
                } ?: false
            if (!ok) {
                resolver.delete(newUri, null, null)
                return false
            }
            resolver.update(
                newUri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            return true
        } catch (e: Exception) {
            resolver.delete(newUri, null, null)
            throw e
        }
    } finally {
        out.recycle()
        if (full !== preview) full.recycle()
    }
}

// endregion

// region UI

private class ShotTokens(
    val ink: Color,
    val card: Color,
    val hairline: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val accentFill: Color,
    val onAccentFill: Color,
    val hairlineWidth: Dp,
    val font: FontFamily,
)

@Composable
private fun rememberShotTokens(): ShotTokens {
    val context = LocalContext.current
    val font =
        remember(context) {
            val name = context.resources.getString(com.android.internal.R.string.ichthys_font_text)
            if (name.isEmpty()) FontFamily.Default
            else FontFamily(Typeface.create(name, Typeface.NORMAL))
        }
    return ShotTokens(
        ink = colorResource(com.android.internal.R.color.ichthys_ink),
        card = colorResource(com.android.internal.R.color.ichthys_card),
        hairline = colorResource(com.android.internal.R.color.ichthys_hairline),
        text = colorResource(com.android.internal.R.color.ichthys_text),
        muted = colorResource(com.android.internal.R.color.ichthys_muted),
        accent = colorResource(com.android.internal.R.color.ichthys_accent),
        onAccent = colorResource(com.android.internal.R.color.ichthys_on_accent),
        accentFill = colorResource(com.android.internal.R.color.ichthys_accent_fill),
        onAccentFill = MaterialTheme.colorScheme.onPrimaryContainer,
        hairlineWidth = dimensionResource(com.android.internal.R.dimen.ichthys_hairline_width),
        font = font,
    )
}

@Composable
private fun ShotEditorScreen(uri: Uri, onDone: () -> Unit) {
    val context = LocalContext.current
    val t = rememberShotTokens()
    val state = remember { EditorState() }
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme

    val paletteColors = listOf(t.accent, Color.White, Color.Black, scheme.error, scheme.tertiary)
    state.palette = paletteColors.map { it.toArgb() }

    // Load a screen-sized preview.
    LaunchedEffect(uri) {
        val dm = context.resources.displayMetrics
        val maxDim = max(dm.widthPixels, dm.heightPixels)
        val loaded =
            withContext(Dispatchers.IO) {
                try {
                    decodePreview(context.contentResolver, uri, maxDim)
                } catch (e: Exception) {
                    Log.e(ShotEditorActivity.TAG, "Failed to load $uri", e)
                    null
                } catch (e: OutOfMemoryError) {
                    Log.e(ShotEditorActivity.TAG, "OOM loading $uri", e)
                    null
                }
            }
        if (loaded == null) {
            Toast.makeText(context, R.string.ichthys_shot_editor_load_failed, Toast.LENGTH_SHORT)
                .show()
            onDone()
        } else {
            state.sample = loaded.second
            state.source = loaded.first
        }
    }

    // Bake committed ops into the displayed bitmap off the main thread.
    LaunchedEffect(state.source, state.ops) {
        val src = state.source ?: return@LaunchedEffect
        val target = state.ops
        val prev = state.baked
        val prevOps = state.bakedOps
        val incremental =
            prev != null &&
                prevOps.size == target.size - 1 &&
                prevOps.indices.all { prevOps[it] === target[it] }
        val result =
            withContext(Dispatchers.Default) {
                if (incremental) {
                    ShotRenderer.applyOne(
                        prev!!.copy(Bitmap.Config.ARGB_8888, /* isMutable= */ true),
                        target.last(),
                    )
                } else {
                    ShotRenderer.render(src, target)
                }
            }
        state.baked = result
        state.bakedOps = target
    }

    fun save(asCopy: Boolean) {
        val src = state.source ?: return
        if (state.saving) return
        if (state.tool == Tool.CROP) state.commitCrop()
        val ops = state.ops
        val sample = state.sample
        state.saving = true
        scope.launch {
            val ok =
                withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        saveImage(context.contentResolver, uri, src, sample, ops, asCopy)
                    } catch (e: Exception) {
                        Log.e(ShotEditorActivity.TAG, "Save failed", e)
                        false
                    } catch (e: OutOfMemoryError) {
                        Log.e(ShotEditorActivity.TAG, "Save OOM", e)
                        false
                    }
                }
            val msg =
                when {
                    !ok -> R.string.ichthys_shot_editor_failed
                    asCopy -> R.string.ichthys_shot_editor_copy_saved
                    else -> R.string.ichthys_shot_editor_saved
                }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            if (ok) onDone() else state.saving = false
        }
    }

    val loaded = state.source != null
    val canEdit = loaded && !state.saving

    Column(
        Modifier.fillMaxSize().background(t.ink).windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        // Top bar
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Pill(
                t,
                label = null,
                icon = Icons.Filled.Close,
                cd = stringResource(R.string.ichthys_shot_editor_close),
                onClick = onDone,
            )
            Pill(
                t,
                label = null,
                icon = Icons.AutoMirrored.Filled.Undo,
                cd = stringResource(R.string.ichthys_shot_editor_undo),
                enabled = canEdit && state.ops.isNotEmpty(),
                onClick = { state.undo() },
            )
            Pill(
                t,
                label = null,
                icon = Icons.AutoMirrored.Filled.Redo,
                cd = stringResource(R.string.ichthys_shot_editor_redo),
                enabled = canEdit && state.redoStack.isNotEmpty(),
                onClick = { state.redo() },
            )
            Spacer(Modifier.weight(1f))
            Pill(
                t,
                label = stringResource(R.string.ichthys_shot_editor_save_copy),
                enabled = canEdit,
                onClick = { save(asCopy = true) },
            )
            Pill(
                t,
                label = stringResource(R.string.ichthys_shot_editor_save),
                icon = Icons.Filled.Check,
                filled = true,
                enabled = canEdit,
                onClick = { save(asCopy = false) },
            )
        }

        // Canvas
        Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
            EditorCanvas(state, t, scheme.scrim)
            if (!loaded || state.saving) {
                CircularProgressIndicator(
                    color = t.accent,
                    modifier = Modifier.align(Alignment.Center).size(40.dp),
                )
            }
        }

        // Bottom bar: per-tool options + tool selector
        Column(
            Modifier.fillMaxWidth()
                .background(t.card)
                .padding(vertical = 10.dp)
                .alpha(if (canEdit) 1f else 0.5f)
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (state.tool) {
                    Tool.DRAW -> DrawOptions(state, t, paletteColors)
                    Tool.CROP -> CropOptions(state, t)
                    Tool.ROTATE -> RotateOptions(state, t)
                }
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(t.hairlineWidth).background(t.hairline))
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                Pill(
                    t,
                    label = stringResource(R.string.ichthys_shot_editor_tool_draw),
                    icon = Icons.Filled.Brush,
                    selected = state.tool == Tool.DRAW,
                    enabled = canEdit,
                    onClick = { state.selectTool(Tool.DRAW) },
                )
                Pill(
                    t,
                    label = stringResource(R.string.ichthys_shot_editor_tool_crop),
                    icon = Icons.Filled.Crop,
                    selected = state.tool == Tool.CROP,
                    enabled = canEdit,
                    onClick = { state.selectTool(Tool.CROP) },
                )
                Pill(
                    t,
                    label = stringResource(R.string.ichthys_shot_editor_tool_rotate),
                    icon = Icons.Filled.Rotate90DegreesCw,
                    selected = state.tool == Tool.ROTATE,
                    enabled = canEdit,
                    onClick = { state.selectTool(Tool.ROTATE) },
                )
            }
        }
    }
}

@Composable
private fun EditorCanvas(state: EditorState, t: ShotTokens, scrim: Color) {
    val baked = state.baked
    val image = remember(baked) { baked?.asImageBitmap() }
    Canvas(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (state.source == null || state.saving) return@awaitEachGesture
                val (iw, ih) = state.currentSize()
                val dst = fit(iw, ih, size.width.toFloat(), size.height.toFloat())
                if (dst.width <= 0f) return@awaitEachGesture
                when (state.tool) {
                    Tool.DRAW -> {
                        fun addPoint(p: Offset) {
                            state.live.add((p.x - dst.left) / dst.width)
                            state.live.add((p.y - dst.top) / dst.height)
                        }
                        state.live.clear()
                        addPoint(down.position)
                        down.consume()
                        trackPointer(down.id) { addPoint(it) }
                        val pts = state.live.toFloatArray()
                        state.live.clear()
                        val color = state.palette.getOrElse(state.colorIndex) { 0 }
                        state.push(
                            StrokeOp(pts, color, WIDTH_FRACS[state.widthIndex], state.highlighter)
                        )
                    }
                    Tool.CROP -> {
                        val s = state.crop
                        val slop = 28.dp.toPx()
                        val cl = dst.left + s.l * dst.width
                        val ct = dst.top + s.t * dst.height
                        val cr = dst.left + s.r * dst.width
                        val cb = dst.top + s.b * dst.height
                        val p = down.position
                        val inY = p.y in (ct - slop)..(cb + slop)
                        val inX = p.x in (cl - slop)..(cr + slop)
                        val mL = inY && abs(p.x - cl) < slop
                        val mR = inY && !mL && abs(p.x - cr) < slop
                        val mT = inX && abs(p.y - ct) < slop
                        val mB = inX && !mT && abs(p.y - cb) < slop
                        val inside = p.x in cl..cr && p.y in ct..cb
                        if (!mL && !mR && !mT && !mB && !inside) return@awaitEachGesture
                        down.consume()
                        val ratio = CROP_RATIOS[state.cropRatioIndex]
                        trackPointer(down.id) { pos ->
                            state.crop =
                                dragCrop(
                                    s,
                                    (pos.x - p.x) / dst.width,
                                    (pos.y - p.y) / dst.height,
                                    mL,
                                    mT,
                                    mR,
                                    mB,
                                    ratio,
                                    iw,
                                    ih,
                                )
                        }
                    }
                    Tool.ROTATE -> Unit
                }
            }
        }
    ) {
        val bmp = baked ?: return@Canvas
        val img = image ?: return@Canvas
        val dst = fit(bmp.width, bmp.height, size.width, size.height)
        if (dst.width <= 0f) return@Canvas
        drawImage(
            img,
            dstOffset = IntOffset(dst.left.roundToInt(), dst.top.roundToInt()),
            dstSize = IntSize(dst.width.roundToInt(), dst.height.roundToInt()),
            filterQuality = FilterQuality.Low,
        )
        drawRect(
            t.hairline,
            topLeft = dst.topLeft,
            size = dst.size,
            style = Stroke(width = t.hairlineWidth.toPx()),
        )

        // Unbaked strokes + the stroke in progress, drawn in image space.
        val pending = state.pendingStrokes()
        val livePts = state.live
        if (pending.isNotEmpty() || livePts.size >= 2) {
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                nc.save()
                nc.clipRect(dst.left, dst.top, dst.right, dst.bottom)
                nc.translate(dst.left, dst.top)
                nc.scale(dst.width / bmp.width, dst.height / bmp.height)
                for (op in pending) ShotRenderer.drawStroke(nc, op, bmp.width, bmp.height)
                if (livePts.size >= 2) {
                    val liveOp =
                        StrokeOp(
                            livePts.toFloatArray(),
                            state.palette.getOrElse(state.colorIndex) { 0 },
                            WIDTH_FRACS[state.widthIndex],
                            state.highlighter,
                        )
                    ShotRenderer.drawStroke(nc, liveOp, bmp.width, bmp.height)
                }
                nc.restore()
            }
        }

        if (state.tool == Tool.CROP) {
            val cRect = state.crop
            val cr =
                Rect(
                    dst.left + cRect.l * dst.width,
                    dst.top + cRect.t * dst.height,
                    dst.left + cRect.r * dst.width,
                    dst.top + cRect.b * dst.height,
                )
            val dim = scrim.copy(alpha = 0.6f)
            drawRect(dim, dst.topLeft, Size(dst.width, cr.top - dst.top))
            drawRect(dim, Offset(dst.left, cr.bottom), Size(dst.width, dst.bottom - cr.bottom))
            drawRect(dim, Offset(dst.left, cr.top), Size(cr.left - dst.left, cr.height))
            drawRect(dim, Offset(cr.right, cr.top), Size(dst.right - cr.right, cr.height))
            val guide = t.text.copy(alpha = 0.35f)
            val hw = t.hairlineWidth.toPx()
            for (i in 1..2) {
                val x = cr.left + cr.width * i / 3f
                val y = cr.top + cr.height * i / 3f
                drawLine(guide, Offset(x, cr.top), Offset(x, cr.bottom), hw)
                drawLine(guide, Offset(cr.left, y), Offset(cr.right, y), hw)
            }
            drawRect(t.accent, cr.topLeft, cr.size, style = Stroke(width = 1.5.dp.toPx()))
            val len = 18.dp.toPx()
            val th = 3.dp.toPx()
            for ((corner, sx, sy) in
                listOf(
                    Triple(cr.topLeft, 1f, 1f),
                    Triple(cr.topRight, -1f, 1f),
                    Triple(cr.bottomLeft, 1f, -1f),
                    Triple(cr.bottomRight, -1f, -1f),
                )) {
                drawLine(t.accent, corner, Offset(corner.x + sx * len, corner.y), th)
                drawLine(t.accent, corner, Offset(corner.x, corner.y + sy * len), th)
            }
        }
    }
}

@Composable
private fun DrawOptions(state: EditorState, t: ShotTokens, colors: List<Color>) {
    colors.forEachIndexed { i, c ->
        val selected = state.colorIndex == i
        Box(
            Modifier.size(32.dp)
                .clip(CircleShape)
                .border(
                    if (selected) 2.dp else t.hairlineWidth,
                    if (selected) t.accent else t.hairline,
                    CircleShape,
                )
                .clickable { state.colorIndex = i }
                .padding(5.dp)
                .clip(CircleShape)
                .background(c)
                .border(t.hairlineWidth, t.hairline, CircleShape)
        )
    }
    Box(Modifier.width(t.hairlineWidth).height(24.dp).background(t.hairline))
    WIDTH_FRACS.indices.forEach { i ->
        val selected = state.widthIndex == i
        Box(
            Modifier.size(32.dp)
                .clip(CircleShape)
                .background(if (selected) t.accentFill else Color.Transparent)
                .clickable { state.widthIndex = i },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size((5 + i * 5).dp)
                    .clip(CircleShape)
                    .background(if (selected) t.onAccentFill else t.text)
            )
        }
    }
    Box(Modifier.width(t.hairlineWidth).height(24.dp).background(t.hairline))
    Pill(
        t,
        label = stringResource(R.string.ichthys_shot_editor_pen),
        icon = Icons.Filled.Brush,
        selected = !state.highlighter,
        onClick = { state.highlighter = false },
    )
    Pill(
        t,
        label = stringResource(R.string.ichthys_shot_editor_highlighter),
        icon = Icons.Filled.BorderColor,
        selected = state.highlighter,
        onClick = { state.highlighter = true },
    )
}

@Composable
private fun CropOptions(state: EditorState, t: ShotTokens) {
    val labels =
        listOf(stringResource(R.string.ichthys_shot_editor_crop_free), "1:1", "9:16", "16:9")
    labels.forEachIndexed { i, label ->
        Pill(
            t,
            label = label,
            selected = state.cropRatioIndex == i,
            onClick = {
                state.cropRatioIndex = i
                state.resetCrop()
            },
        )
    }
    Box(Modifier.width(t.hairlineWidth).height(24.dp).background(t.hairline))
    Pill(
        t,
        label = stringResource(R.string.ichthys_shot_editor_crop_reset),
        onClick = {
            state.cropRatioIndex = 0
            state.resetCrop()
        },
    )
    Pill(
        t,
        label = stringResource(R.string.ichthys_shot_editor_crop_apply),
        icon = Icons.Filled.Check,
        filled = true,
        enabled = !state.crop.isFull,
        onClick = { state.commitCrop() },
    )
}

@Composable
private fun RotateOptions(state: EditorState, t: ShotTokens) {
    Pill(
        t,
        label = stringResource(R.string.ichthys_shot_editor_rotate_left),
        icon = Icons.Filled.Rotate90DegreesCcw,
        onClick = { state.push(RotateOp(clockwise = false)) },
    )
    Pill(
        t,
        label = stringResource(R.string.ichthys_shot_editor_rotate_right),
        icon = Icons.Filled.Rotate90DegreesCw,
        onClick = { state.push(RotateOp(clockwise = true)) },
    )
    Pill(
        t,
        label = stringResource(R.string.ichthys_shot_editor_flip),
        icon = Icons.Filled.Flip,
        onClick = { state.push(FlipOp) },
    )
}

@Composable
private fun Pill(
    t: ShotTokens,
    label: String?,
    icon: ImageVector? = null,
    cd: String? = null,
    selected: Boolean = false,
    filled: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    val bg =
        when {
            filled -> t.accent
            selected -> t.accentFill
            else -> Color.Transparent
        }
    val fg =
        when {
            filled -> t.onAccent
            selected -> t.onAccentFill
            else -> t.text
        }
    Row(
        Modifier.height(40.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .clip(shape)
            .background(bg)
            .then(
                if (!filled && !selected) Modifier.border(t.hairlineWidth, t.hairline, shape)
                else Modifier
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (label == null) 10.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = cd ?: label, tint = fg, modifier = Modifier.size(20.dp))
        }
        if (label != null) {
            if (icon != null) Spacer(Modifier.width(6.dp))
            Text(label, color = fg, fontFamily = t.font, fontSize = 14.sp, maxLines = 1)
        }
    }
}

// endregion
