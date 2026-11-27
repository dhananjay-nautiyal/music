package com.music.music.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.music.data.lyrics.LyricLine
import com.music.music.sharedui.resources.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.stringResource

/**
 * iMessage-style lyrics: one bubble per line. The line being sung rides the
 * right as a filled "sent" bubble whose words light up as they land; every
 * other line stacks left as a dim incoming bubble, the ones already sung
 * fading further than the ones still to come.
 *
 * The contract matches [LyricsPanel] where it overlaps — same playhead, same
 * seek-on-tap, same empty/looking states — so the player can swap between the
 * two styles on the same inputs. Share-picking and the translation particle
 * motion deliberately stay with [LyricsPanel]: the caller keeps those on the
 * classic style instead of reimplementing them here.
 */
@Composable
internal fun IMessageLyricsView(
    lines: List<LyricLine>,
    /** Romanized or translated copies of [lines], drawn small under each bubble. */
    subLines: List<LyricLine>? = null,
    trackKey: String,
    playhead: LyricPlayhead,
    /** Whether a lookup for this track is still in flight. */
    looking: Boolean,
    isPlaying: Boolean,
    /** False while the panel is retained offscreen solely as a warm layout. */
    active: Boolean = true,
    onSeekToLine: (Long) -> Unit,
    /** Portrait-only: whether the transport controls are currently hidden. */
    controlsOpen: Boolean = true,
    onRevealControls: () -> Unit = {},
    /** Reports whether the lyric list is mid-scroll, as [LyricsPanel] does. */
    onScrollingChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val clock = rememberLyricClock(trackKey, playhead, isPlaying && active)
    val listState = rememberLazyListState()

    val isSynced = remember(lines) { lines.any { it.timeMs > 0L } }

    // The last line that has started, skipping instrumental gaps, so the
    // bubble stays on the last thing actually sung through a break.
    val activeIndex by remember(lines, isSynced) {
        derivedStateOf {
            if (!isSynced) -1
            else activeLyricRows(lines, clock.longValue).firstOrNull()?.let { focus ->
                var i = focus
                while (i >= 0 && lines[i].isGap) i--
                i.takeIf { it >= 0 } ?: -1
            } ?: -1
        }
    }

    var placed by remember(trackKey) { mutableStateOf(false) }
    var browsing by remember(trackKey) { mutableStateOf(false) }

    // A user's drag suspends the follow so the reader can look ahead; three
    // seconds of stillness hands the list back to the song, the same bargain
    // [LyricsPanel] makes with its own timers.
    val browseWatcher = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) browsing = true
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(browsing) {
        if (browsing) {
            delay(3_000)
            browsing = false
        }
    }

    LaunchedEffect(activeIndex, browsing, active) {
        if (active && isSynced && !browsing && activeIndex >= 0) {
            snapshotFlow { listState.layoutInfo.viewportSize.height }.first { it > 0 }
            if (!placed) {
                listState.scrollToItem(activeIndex)
                placed = true
            } else {
                listState.animateScrollToItem(activeIndex)
            }
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect(onScrollingChange)
    }

    if (lines.isEmpty()) {
        val empty = modifier.revealLyricsControlsOnTap(!controlsOpen, onRevealControls)
        if (looking) {
            LyricsSkeleton(empty)
        } else {
            Box(empty, contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(Res.string.no_lyrics_for_track),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
        }
        return
    }

    var viewportPx by remember { mutableIntStateOf(0) }

    LazyColumn(
        state = listState,
        modifier = modifier
            .bleedHorizontally(PLAYER_GUTTER)
            .nestedScroll(browseWatcher)
            .revealLyricsControlsOnTap(!controlsOpen, onRevealControls)
            .fadingEdges()
            .onSizeChanged { viewportPx = it.height },
        contentPadding = PaddingValues(
            top = 40.dp,
            bottom = with(LocalDensity.current) { (viewportPx * 0.55f).toDp() },
            start = PLAYER_GUTTER,
            end = PLAYER_GUTTER,
        ),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        itemsIndexed(lines, key = { index, _ -> "$trackKey-$index" }) { index, line ->
            if (line.isGap) return@itemsIndexed
            val isCurrent = index == activeIndex
            val sung = isSynced && activeIndex >= 0 && index < activeIndex
            val alpha = when {
                isCurrent -> 1f
                sung -> 0.32f
                else -> 0.55f
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isCurrent) Arrangement.End else Arrangement.Start,
            ) {
                // The tail sits on the side the bubble is anchored to, the way
                // a sent message's does — a small thing, but it is what makes
                // the stack read as a conversation rather than as cards.
                val shape = if (isCurrent) {
                    RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
                } else {
                    RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp)
                }
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Box(
                        modifier = Modifier
                            .clip(shape)
                            .background(
                                if (isCurrent) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color.White.copy(alpha = 0.09f)
                                },
                            )
                            .clickable(enabled = isSynced && line.timeMs > 0L) {
                                onSeekToLine(line.timeMs)
                            }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                    ) {
                        Column {
                            if (isCurrent && line.isWordSynced) {
                                // Read here rather than in a draw block: the
                                // wipe is an AnnotatedString, which can only be
                                // rebuilt in composition, and only this one
                                // bubble pays for it.
                                Text(
                                    text = sungUpTo(line, clock.longValue),
                                    style = CurrentBubbleStyle,
                                    color = Color.White.copy(alpha = 0.45f),
                                )
                            } else {
                                Text(
                                    text = line.text,
                                    style = if (isCurrent) CurrentBubbleStyle else BubbleStyle,
                                    color = Color.White.copy(alpha = alpha),
                                )
                            }
                            subLines?.getOrNull(index)?.text
                                ?.takeIf { it.isNotBlank() && it != line.text }
                                ?.let { sub ->
                                    Text(
                                        text = sub,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = if (isCurrent) 0.75f else 0.4f),
                                        modifier = Modifier.padding(top = 3.dp),
                                    )
                                }
                        }
                    }
                }
            }
        }
    }
}

/** The playing line's text with every character already sung drawn bright. */
private fun sungUpTo(line: LyricLine, positionMs: Long): AnnotatedString = buildAnnotatedString {
    append(line.text)
    val spans = line.wordSpans
    line.words.forEachIndexed { index, word ->
        val span = spans.getOrNull(index) ?: return@forEachIndexed
        val sung = word.charsSungAt(positionMs).toInt().coerceIn(0, span.last - span.first + 1)
        if (sung > 0) addStyle(SpanStyle(color = Color.White), span.first, span.first + sung)
    }
}

private val CurrentBubbleStyle = TextStyle(
    fontSize = 17.sp,
    lineHeight = 22.sp,
    fontWeight = FontWeight.Medium,
)

private val BubbleStyle = TextStyle(
    fontSize = 15.sp,
    lineHeight = 20.sp,
)
