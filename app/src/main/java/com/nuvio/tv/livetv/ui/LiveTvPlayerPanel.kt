package com.nuvio.tv.livetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionDisabled
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.ui.theme.NuvioTheme

/** What the bottom panel is showing. */
internal enum class PanelMode { LIVE, CATCHUP, SHIFT }

/** The panel's buttons and quick options, in the order the highlight moves through them. */
internal enum class PanelControl { TIMELINE, BACK, PLAY, FORWARD, LIVE, CC, AUDIO, SIZE, SLEEP, MORE }

/** Timeline: everything in wall-clock milliseconds. */
internal data class PanelTimeline(
    val startMs: Long,
    val endMs: Long,
    val positionMs: Long,
    /** Where "live" is on the line (pause and rewind), or null. */
    val liveMs: Long? = null,
    /** The recorded stretch ends here (pause and rewind), or null. */
    val recordedUntilMs: Long? = null,
    val leftLabel: String,
    val rightLabel: String,
    val status: String,
    val statusLive: Boolean = false
)

/** Which controls the panel offers in each mode. */
internal fun panelControls(mode: PanelMode, paused: Boolean, canSkip: Boolean): List<PanelControl> = buildList {
    val transport = mode != PanelMode.LIVE || paused
    // The timeline itself can be grabbed (Up from the buttons) and moved with ◀ / ▶.
    if (transport && canSkip) add(PanelControl.TIMELINE)
    if (transport && canSkip) add(PanelControl.BACK)
    add(PanelControl.PLAY)
    if (transport && canSkip) add(PanelControl.FORWARD)
    if (mode != PanelMode.LIVE) add(PanelControl.LIVE)
    add(PanelControl.CC); add(PanelControl.AUDIO); add(PanelControl.SIZE); add(PanelControl.SLEEP); add(PanelControl.MORE)
}

/**
 * The full-screen player's bottom panel, in two sizes:
 *  - compact ([full] = false): channel, show and progress, shown while changing channels;
 *  - full (OK): also the description, the timeline (live, catch-up, or a pause-and-rewind
 *    recording), the playback buttons and quick options.
 * [focused] is the highlighted control in the full panel, else null. Colors follow Nuvio's theme.
 */
@Composable
internal fun LiveTvPlayerPanel(
    channel: LiveChannel,
    mode: PanelMode,
    full: Boolean,
    title: String,
    meta: String,
    description: String?,
    poster: String?,
    showPosters: Boolean,
    timeline: PanelTimeline?,
    paused: Boolean,
    controls: List<PanelControl>,
    focused: PanelControl?,
    captionsOn: Boolean,
    audioLabel: String,
    sizeLabel: String,
    sleepLabel: String?,
    clock: String,
    resolution: String?,
    showNumber: Boolean,
    /** What's on next ("World Tonight") and when ("7:00 – 8:00 PM"), shown on the right. */
    nextTitle: String? = null,
    nextTime: String? = null,
    /** The channel offers catch-up (a tag beside the channel). */
    catchupAvailable: Boolean = false,
    /** The full panel's Recent row (channels watched before), or null for none. */
    recent: List<PanelRecent>? = null,
    /** The highlighted tile in the Recent row: 0 TV Guide, 1… the channels, then Clear. */
    recentFocus: Int? = null,
    /** The row's heading: "RECENT" or "FAVORITES". */
    recentTitle: String = "RECENT",
    /** A Clear tile at the end of the row (Recent only). */
    recentClear: Boolean = true,
    /** Channel surfing: this is a channel you're looking at, not the one playing. */
    browsing: Boolean = false,
    modifier: Modifier = Modifier
) {
    // With the Recent row, the rest is a little smaller so the panel stays about as tall.
    val withRecent = full && recent != null
    val colors = NuvioTheme.colors
    val accent = colors.Secondary
    val posterW = if (withRecent) 88.dp else if (full) 116.dp else 110.dp
    val posterH = if (withRecent) 132.dp else if (full) 174.dp else 165.dp
    // Fades up from the bottom over the picture, in the theme's background color.
    val scrim = androidx.compose.ui.graphics.Brush.verticalGradient(
        0f to Color.Transparent,
        0.38f to colors.Background.copy(alpha = 0.86f),
        1f to colors.Background.copy(alpha = 0.97f)
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(scrim)
            .padding(start = 48.dp, end = 48.dp, top = if (withRecent) 48.dp else 64.dp, bottom = if (withRecent) 14.dp else if (full) 18.dp else 24.dp)
    ) {
    Row(verticalAlignment = Alignment.Bottom) {
        // Poster (or the channel logo when posters are off or there isn't one).
        val posterShape = RoundedCornerShape(liveCorner())
        Box(
            modifier = Modifier
                .width(if (showPosters) posterW else 72.dp)
                .height(if (showPosters) posterH else 72.dp)
                .clip(posterShape)
                .background(colors.BackgroundCard.copy(alpha = 0.85f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), posterShape),
            contentAlignment = Alignment.Center
        ) {
            if (showPosters && !poster.isNullOrBlank()) {
                coil3.compose.AsyncImage(
                    model = poster,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                ChannelLogo(channel.logo, if (showPosters) 56.dp else 48.dp)
            }
        }
        Spacer(Modifier.width(24.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (browsing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveTag("BROWSING", accent, colors.OnSecondary)
                    Spacer(Modifier.width(10.dp))
                    LiveText(
                        "OK to watch  ·  ▲ ▼ more channels (hold to go faster)  ·  Back to stay",
                        size = 12.sp, color = colors.TextSecondary
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            // Channel line: logo, number and name, tags, and the clock on the right.
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!channel.logo.isNullOrBlank()) {
                    ChannelLogo(channel.logo, 24.dp)
                    Spacer(Modifier.width(10.dp))
                }
                LiveText(
                    (if (showNumber) "${channel.number} · " else "") + channel.name,
                    size = 14.sp, weight = FontWeight.SemiBold, color = colors.TextSecondary
                )
                when (mode) {
                    PanelMode.LIVE -> { Spacer(Modifier.width(10.dp)); LiveTag("LIVE", LIVE_RED, Color.White) }
                    PanelMode.CATCHUP -> { Spacer(Modifier.width(10.dp)); LiveTag("ARCHIVE", accent, colors.OnSecondary) }
                    PanelMode.SHIFT -> { Spacer(Modifier.width(10.dp)); LiveTag("PAUSED LIVE", accent, colors.OnSecondary) }
                }
                resolution?.let {
                    Spacer(Modifier.width(6.dp))
                    LiveTag(it.uppercase(), Color.Transparent, colors.TextSecondary,
                        Modifier.border(1.dp, colors.Border, RoundedCornerShape(5.dp)))
                }
                if (catchupAvailable && mode == PanelMode.LIVE) {
                    Spacer(Modifier.width(6.dp))
                    LiveTag("CATCH-UP", Color.Transparent, colors.TextSecondary,
                        Modifier.border(1.dp, colors.Border, RoundedCornerShape(5.dp)))
                }
                Spacer(Modifier.weight(1f))
                if (full) timeline?.let {
                    LiveText(it.status, size = 13.sp, color = if (it.statusLive) colors.Error else accent, modifier = Modifier.padding(end = 14.dp))
                }
                LiveText(clock, size = 18.sp, weight = FontWeight.Bold, color = colors.TextPrimary)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    LiveText(title, size = if (withRecent) 26.sp else 30.sp, weight = FontWeight.ExtraBold, maxLines = 1, marquee = true, color = colors.TextPrimary)
                    if (meta.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        LiveText(meta, size = 14.sp, color = colors.TextSecondary, maxLines = 1)
                    }
                    description?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(4.dp))
                        LiveText(it, size = 14.sp, color = colors.TextSecondary, maxLines = if (full && !withRecent) 2 else 1)
                    }
                }
                // What's on next, on the right.
                if (nextTitle != null) {
                    Spacer(Modifier.width(24.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        LiveText("NEXT", size = 11.sp, weight = FontWeight.Bold, color = colors.TextTertiary)
                        LiveText(nextTitle, size = 15.sp, weight = FontWeight.Bold, color = colors.TextPrimary, modifier = Modifier.widthIn(max = 260.dp))
                        nextTime?.let { LiveText(it, size = 13.sp, color = colors.TextSecondary) }
                    }
                }
            }
            timeline?.let { t ->
                Spacer(Modifier.height(if (full) 10.dp else 10.dp))
                PanelTimelineBar(t, grabbed = focused == PanelControl.TIMELINE, compact = !full)
            }
            if (full) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    controls.filter { it != PanelControl.TIMELINE }.forEach { c ->
                        if (c == PanelControl.CC) Spacer(Modifier.weight(1f))
                        PanelButton(c, focused == c && recentFocus == null, paused, captionsOn, audioLabel, sizeLabel, sleepLabel, mode)
                    }
                }
            }
        }
    }
    if (withRecent) {
        Spacer(Modifier.height(12.dp))
        RecentRow(recent.orEmpty(), recentFocus, recentTitle, recentClear)
    }
    }
}

/** A channel in the info panel's Recent row: what's on now and how far into it. */
internal data class PanelRecent(
    val channel: LiveChannel,
    val title: String?,
    val progress: Float?
)

/** Tiles in the Recent row: TV Guide, the channels, then Clear. */
internal fun recentTileCount(recent: List<PanelRecent>): Int = recent.size + 2

/** "RECENT", then TV Guide · the channels · Clear, scrolling to keep the highlight on screen. */
@Composable
private fun RecentRow(recent: List<PanelRecent>, focus: Int?, title: String, showClear: Boolean) {
    val colors = NuvioTheme.colors
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(focus) {
        val f = focus ?: return@LaunchedEffect
        val info = state.layoutInfo
        val fully = info.visibleItemsInfo.filter { it.offset >= info.viewportStartOffset && it.offset + it.size <= info.viewportEndOffset }
        val first = fully.firstOrNull()?.index ?: 0
        val last = fully.lastOrNull()?.index ?: 0
        when {
            f < first -> state.animateScrollToItem(f)
            f > last -> state.animateScrollToItem((f - (fully.size - 1).coerceAtLeast(0)).coerceAtLeast(0))
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        LiveText(title, size = 10.sp, weight = FontWeight.Bold, color = colors.TextTertiary)
        if (focus != null) {
            Spacer(Modifier.width(10.dp))
            LiveText("OK to switch · ◀ ▶ for more · ▲ back to the buttons", size = 10.sp, color = colors.TextTertiary)
        } else {
            Spacer(Modifier.width(10.dp))
            LiveText("▼ from the buttons", size = 10.sp, color = colors.TextTertiary)
        }
    }
    Spacer(Modifier.height(6.dp))
    androidx.compose.foundation.lazy.LazyRow(
        state = state,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        userScrollEnabled = false
    ) {
        item(key = "guide") {
            RecentTile(focused = focus == 0, width = 80.dp) {
                Icon(Icons.Default.GridView, contentDescription = null, tint = colors.TextPrimary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.height(4.dp))
                LiveText("TV Guide", size = 11.sp, weight = FontWeight.SemiBold, color = colors.TextPrimary)
            }
        }
        recent.forEachIndexed { i, r ->
            item(key = "ch:" + r.channel.key) {
                RecentTile(focused = focus == i + 1, width = 112.dp, progress = r.progress) {
                    if (!r.channel.logo.isNullOrBlank()) ChannelLogo(r.channel.logo, 22.dp)
                    else LiveText(r.channel.name, size = 13.sp, weight = FontWeight.Bold, color = colors.TextPrimary, maxLines = 1)
                    Spacer(Modifier.height(4.dp))
                    LiveText(r.title ?: r.channel.name, size = 10.sp, color = colors.TextSecondary, maxLines = 1)
                }
            }
        }
        if (showClear) item(key = "clear") {
            RecentTile(focused = focus == recent.size + 1, width = 80.dp) {
                Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = colors.TextSecondary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.height(4.dp))
                LiveText("Clear", size = 11.sp, weight = FontWeight.SemiBold, color = colors.TextSecondary)
            }
        }
    }
}

@Composable
private fun RecentTile(
    focused: Boolean,
    width: androidx.compose.ui.unit.Dp,
    progress: Float? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .graphicsLayer { val s = if (focused) 1.06f else 1f; scaleX = s; scaleY = s }
            .width(width)
            .height(62.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = if (focused) 0.16f else 0.08f))
            .border(if (focused) 2.dp else 1.dp, if (focused) colors.FocusRing else Color.White.copy(alpha = 0.08f), shape)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            content = content
        )
        progress?.let { p ->
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth(p.coerceIn(0f, 1f)).height(3.dp)
                    .background(colors.Secondary)
            )
        }
    }
}

@Composable
private fun PanelTimelineBar(t: PanelTimeline, grabbed: Boolean, compact: Boolean) {
    val colors = NuvioTheme.colors
    val accent = colors.Secondary
    val span = (t.endMs - t.startMs).coerceAtLeast(1L).toFloat()
    fun frac(ms: Long) = ((ms - t.startMs) / span).coerceIn(0f, 1f)
    val track = if (compact) 4.dp else 4.dp
    if (compact) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveText(t.leftLabel, size = 13.sp, color = colors.TextSecondary)
            Spacer(Modifier.width(12.dp))
            BoxWithConstraints(modifier = Modifier.weight(1f).height(8.dp)) {
                val w = maxWidth
                Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(track).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.18f)))
                t.recordedUntilMs?.let { r ->
                    Box(Modifier.align(Alignment.CenterStart).width(w * frac(r)).height(track).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.35f)))
                }
                Box(Modifier.align(Alignment.CenterStart).width(w * frac(t.positionMs)).height(track).clip(RoundedCornerShape(2.dp)).background(accent))
                t.liveMs?.let { l ->
                    Box(Modifier.align(Alignment.CenterStart).offset(x = (w * frac(l)) - 1.dp).width(2.dp).fillMaxHeight().background(colors.Error))
                }
            }
            Spacer(Modifier.width(12.dp))
            LiveText(t.rightLabel, size = 13.sp, color = if (t.liveMs != null) colors.Error else colors.TextSecondary)
            Spacer(Modifier.width(14.dp))
            LiveText(t.status, size = 13.sp, color = if (t.statusLive) colors.Error else colors.TextSecondary)
        }
        return
    }
    Column {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(if (compact) 8.dp else 16.dp)) {
            val w = maxWidth
            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(track).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.18f)))
            t.recordedUntilMs?.let { r ->
                Box(Modifier.align(Alignment.CenterStart).width(w * frac(r)).height(track).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.35f)))
            }
            Box(Modifier.align(Alignment.CenterStart).width(w * frac(t.positionMs)).height(track).clip(RoundedCornerShape(2.dp)).background(accent))
            t.liveMs?.let { l ->
                Box(Modifier.align(Alignment.CenterStart).offset(x = (w * frac(l)) - 1.dp).width(2.dp).fillMaxHeight().background(colors.Error))
            }
            if (!compact) {
                // The handle: bigger, with a ring, while the timeline is grabbed.
                val knob = if (grabbed) 18.dp else 12.dp
                Box(
                    Modifier.align(Alignment.CenterStart).offset(x = (w * frac(t.positionMs)) - knob / 2).size(knob)
                        .clip(CircleShape).background(Color.White)
                        .border(if (grabbed) 3.dp else 2.dp, if (grabbed) colors.FocusRing else accent, CircleShape)
                )
            }
        }
        if (!compact) Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            LiveText(t.leftLabel, size = 11.sp, color = colors.TextSecondary)
            Spacer(Modifier.weight(1f))
            if (grabbed) LiveText("◀ ▶ to move · OK to play/pause", size = 11.sp, color = colors.TextSecondary)
            Spacer(Modifier.weight(1f))
            LiveText(t.rightLabel, size = 11.sp, color = if (t.liveMs != null) colors.Error else colors.TextSecondary)
        }
    }
}

@Composable
private fun PanelButton(
    c: PanelControl,
    focused: Boolean,
    paused: Boolean,
    captionsOn: Boolean,
    audioLabel: String,
    sizeLabel: String,
    sleepLabel: String?,
    mode: PanelMode
) {
    val colors = NuvioTheme.colors
    val idle = Color.White.copy(alpha = 0.12f)
    when (c) {
        PanelControl.TIMELINE -> Unit
        PanelControl.BACK, PanelControl.FORWARD, PanelControl.PLAY -> {
            val big = c == PanelControl.PLAY
            val icon: ImageVector = when (c) {
                PanelControl.BACK -> Icons.Default.Replay30
                PanelControl.FORWARD -> Icons.Default.Forward30
                else -> if (paused) Icons.Default.PlayArrow else Icons.Default.Pause
            }
            Box(
                modifier = Modifier
                    .size(if (big) 40.dp else 34.dp)
                    .clip(CircleShape)
                    .background(if (focused) colors.FocusRing else if (big) Color.White.copy(alpha = 0.22f) else idle),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = if (focused) colors.Background else colors.TextPrimary, modifier = Modifier.size(if (big) 22.dp else 18.dp))
            }
        }
        PanelControl.LIVE -> Pill(
            text = if (mode == PanelMode.CATCHUP) "Back to live" else "Go live",
            icon = null, focused = focused, dot = true
        )
        PanelControl.CC -> Pill(
            text = if (captionsOn) "CC on" else "CC off",
            icon = if (captionsOn) Icons.Default.ClosedCaption else Icons.Default.ClosedCaptionDisabled,
            focused = focused, active = captionsOn
        )
        PanelControl.AUDIO -> Pill(audioLabel, Icons.Default.VolumeUp, focused)
        PanelControl.SIZE -> Pill(sizeLabel, Icons.Default.AspectRatio, focused)
        PanelControl.SLEEP -> Pill(sleepLabel ?: "Sleep", Icons.Default.Bedtime, focused)
        PanelControl.MORE -> Pill("", Icons.Default.MoreHoriz, focused)
    }
}

@Composable
private fun Pill(
    text: String,
    icon: ImageVector?,
    focused: Boolean,
    active: Boolean = false,
    dot: Boolean = false
) {
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .height(32.dp)
            .clip(shape)
            .background(
                when {
                    focused -> colors.FocusRing
                    active -> colors.Secondary.copy(alpha = 0.30f)
                    else -> Color.White.copy(alpha = 0.12f)
                }
            )
            .padding(horizontal = if (text.isEmpty()) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val color = if (focused) colors.Background else colors.TextPrimary
        if (dot) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(colors.Error))
            Spacer(Modifier.width(6.dp))
        }
        icon?.let {
            Icon(it, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            if (text.isNotEmpty()) Spacer(Modifier.width(6.dp))
        }
        if (text.isNotEmpty()) LiveText(text, size = 12.sp, color = color, weight = FontWeight.Medium)
    }
}
