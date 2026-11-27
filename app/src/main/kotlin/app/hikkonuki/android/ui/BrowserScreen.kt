package app.hikkonuki.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.hikkonuki.android.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt


@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(
    state: BrowserUiState,
    onRefresh: () -> Unit,
    onToggleLayout: () -> Unit,
    onItemClick: (MediaItem) -> Unit,
    onItemLongClick: (MediaItem) -> Unit,
    onToggleSelection: (MediaItem) -> Unit,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onExportSelected: () -> Unit,
    onOpenExports: () -> Unit,
    onPickFolder: () -> Unit,
) {
    // Back in selection mode only exits selection; it must not pop the browser destination.
    BackHandler(enabled = state.selectionMode) { onClearSelection() }

    // With many photos the list is the screen: thumbnails run edge to edge behind the
    // status and navigation bars, the app bar is a translucent layer over them, and it
    // scrolls away with them and returns on the first scroll up.
    val showingMedia = !state.loading && state.usbConnected && state.items.isNotEmpty()
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val barHeightPx = with(LocalDensity.current) { TopAppBarDefaults.TopAppBarExpandedHeight.toPx() }
    val bar = remember(barHeightPx) { ListDrivenBar(barHeightPx) }
    // Safety net for everything that moves the list without a scroll gesture (fewer items
    // after a rescan, switching layout, leaving selection mode): at the top of the list
    // the bar is shown, always. A hidden bar must never be out of reach.
    LaunchedEffect(state.layout, showingMedia) {
        snapshotFlow {
            !showingMedia || if (state.layout == BrowserLayout.GRID) !gridState.canScrollBackward else !listState.canScrollBackward
        }.collect { atTop -> if (atTop) bar.offset = 0f }
    }
    val barColor = MaterialTheme.colorScheme.surface.copy(alpha = if (showingMedia) 0.88f else 1f)
    val barColors = TopAppBarDefaults.topAppBarColors(containerColor = barColor, scrolledContainerColor = barColor)
    // Selection mode pins its bar: what is selected, and the way out, stay in view.
    val hidden = if (showingMedia && !state.selectionMode) -bar.offset else 0f

    Scaffold(
        modifier = Modifier.nestedScroll(bar.nestedScrollConnection),
        topBar = {
          Column {
            // The strip behind the status bar stays, so the clock and icons remain readable
            // over photos once the bar itself has scrolled away.
            Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(barColor))
            // The bar slides up out of this box by exactly the distance the list scrolled.
            Box(
                Modifier.clipToBounds().layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val visible = (placeable.height - hidden).roundToInt().coerceIn(0, placeable.height)
                    layout(placeable.width, visible) { placeable.place(0, visible - placeable.height) }
                },
            ) {
            if (state.selectionMode) {
                // Pinned: what is selected, and the way out, stay in view while scrolling.
                TopAppBar(
                    colors = barColors,
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    title = { Text(stringResource(R.string.selected_count, state.selectedIds.size)) },
                    navigationIcon = {
                        IconButton(onClick = onClearSelection, modifier = Modifier.testTag("clearSelection")) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear_selection))
                        }
                    },
                    actions = {
                        // Text-only action: export lives on the FAB, so the bar stays minimal.
                        TextButton(onClick = onSelectAll, modifier = Modifier.testTag("selectAll")) {
                            Text(stringResource(R.string.select_all))
                        }
                    },
                )
            } else {
                var menuExpanded by remember { mutableStateOf(false) }
                TopAppBar(
                    colors = barColors,
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    // The camera's own name while one is attached, the app's otherwise.
                    title = { Text(state.deviceName ?: stringResource(R.string.app_name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    actions = {
                        IconButton(onClick = onToggleLayout, modifier = Modifier.testTag("layoutToggle")) {
                            Icon(
                                imageVector = if (state.layout == BrowserLayout.GRID) Icons.AutoMirrored.Filled.List else Icons.Filled.GridView,
                                contentDescription = stringResource(if (state.layout == BrowserLayout.GRID) R.string.switch_to_list else R.string.switch_to_grid),
                            )
                        }
                        IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.reload)) }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu)) }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.export_status)) },
                                    onClick = { menuExpanded = false; onOpenExports() },
                                )
                                if (state.canPickFolder) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.open_usb_folder)) },
                                        onClick = { menuExpanded = false; onPickFolder() },
                                        leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                                    )
                                }
                            }
                        }
                    },
                )
            }
            }
          }
        },
        floatingActionButton = {
            if (state.selectionMode) {
                ExtendedFloatingActionButton(
                    onClick = onExportSelected,
                    modifier = Modifier.testTag("exportSelectedFab"),
                    icon = { Icon(Icons.Filled.Download, contentDescription = null) },
                    text = { Text(stringResource(R.string.export_count, state.selectedIds.size)) },
                )
            }
        },
    ) { padding ->
        // The media list fills the whole window and pads its content instead, so rows
        // scroll under the bars. The top padding is the bar's full height, not the
        // Scaffold's live value, which shrinks as the bar slides away and would make
        // the list jump.
        val listPadding = PaddingValues(
            start = 4.dp,
            end = 4.dp,
            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopAppBarDefaults.TopAppBarExpandedHeight + 4.dp,
            // Room for the export button so it never covers the last row.
            bottom = padding.calculateBottomPadding() + if (state.selectionMode) 88.dp else 4.dp,
        )
        Box(modifier = Modifier.fillMaxSize().then(if (showingMedia) Modifier else Modifier.padding(padding))) {
            when {
                state.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).testTag("loading"))
                !state.usbConnected -> UsbDisconnectedPlaceholder()
                state.items.isEmpty() -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(if (state.hasSavedFolder) R.string.no_media_in_saved_folder_title else R.string.no_media_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(if (state.hasSavedFolder) R.string.no_media_in_saved_folder_body else R.string.no_media_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (state.folderPermissionError) {
                        Text(
                            stringResource(R.string.folder_permission_error),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("folderPermissionError"),
                        )
                    }
                    androidx.compose.material3.Button(onClick = onPickFolder, modifier = Modifier.testTag("pickFolderButton")) {
                        Text(stringResource(if (state.hasSavedFolder) R.string.choose_another_usb_folder else R.string.open_usb_folder))
                    }
                }
                state.layout == BrowserLayout.GRID -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize().testTag("mediaGrid"),
                    state = gridState,
                    contentPadding = listPadding,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    gridItems(state.items, key = { it.id }) { item ->
                        val selected = item.id in state.selectedIds
                        // Google Photos-style affordance: selected thumbnails shrink slightly
                        // inside their cell, so the selection reads at a glance. The cell
                        // itself keeps its full size as the tap target.
                        val thumbnailScale by animateFloatAsState(
                            targetValue = if (selected) 0.86f else 1f,
                            animationSpec = tween(durationMillis = 180),
                            label = "selectionScale_${item.id}",
                        )
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .testTag("mediaItem_${item.displayName}")
                                .combinedClickable(
                                    onClick = { if (state.selectionMode) onToggleSelection(item) else onItemClick(item) },
                                    onLongClick = { onItemLongClick(item) },
                                ),
                        ) {
                            MediaThumbnail(
                                item.uri,
                                item.kind,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        scaleX = thumbnailScale
                                        scaleY = thumbnailScale
                                    }
                                    .testTag("mediaThumbnail_${item.displayName}"),
                            )
                            if (item.isVideo) {
                                Text(
                                    text = formatDuration(item.durationMillis),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                                )
                            }
                            if (selected) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = stringResource(R.string.item_selected),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp),
                                )
                            }
                        }
                    }
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag("mediaList"),
                    state = listState,
                    contentPadding = PaddingValues(top = listPadding.calculateTopPadding(), bottom = listPadding.calculateBottomPadding()),
                ) {
                    items(state.items, key = { it.id }) { item ->
                        val selected = item.id in state.selectedIds
                        ListItem(
                            headlineContent = { Text(item.displayName) },
                            supportingContent = { Text(stringResource(R.string.joined, formatSize(item.sizeBytes), formatDate(item.dateTakenMillis))) },
                            leadingContent = { MediaThumbnail(item.uri, item.kind, modifier = Modifier.size(56.dp)) },
                            trailingContent = {
                                if (selected) Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.item_selected), tint = MaterialTheme.colorScheme.primary)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("mediaItem_${item.displayName}")
                                .combinedClickable(
                                    onClick = { if (state.selectionMode) onToggleSelection(item) else onItemClick(item) },
                                    onLongClick = { onItemLongClick(item) },
                                ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * How far the app bar has scrolled away, driven by the distance the list itself scrolled.
 *
 * Material's enterAlways behaviour takes the drag before the list sees it, so the bar gets
 * a head start (it moves during the touch slop, and even when the list has nothing to
 * scroll), and it moves its contents at half the speed of its edge. Here the bar is moved
 * by scroll the list actually consumed, pixel for pixel, so it looks attached to the
 * content. Two guarantees follow: a list that fits on screen never loses its bar, and a
 * hidden bar always comes back when scrolling up.
 */
internal class ListDrivenBar(private val heightPx: Float) {
    /** 0 when fully shown, -height when fully scrolled away. */
    var offset by mutableFloatStateOf(0f)

    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            offset = (offset + consumed.y).coerceIn(-heightPx, 0f)
            return Offset.Zero
        }
    }
}

@Composable
fun UsbDisconnectedPlaceholder() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp).testTag("usbDisconnected"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Usb, contentDescription = null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Text(
            text = stringResource(R.string.connect_camera),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}
