package com.nuvio.tv.livetv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** A draft survives every move; only Save writes it. Back and Cancel discard it. */
@Composable
internal fun LiveTvOrderDialog(request: OrderRequest, onDismiss: () -> Unit) {
    var order by remember(request) { mutableStateOf(request.entries.map { it.id }) }
    val titles = remember(request) { request.entries.associate { it.id to it.title } }
    var selected by remember(request) { mutableStateOf(emptySet<String>()) }
    var selecting by remember(request) { mutableStateOf(false) }
    var moving by remember(request) { mutableStateOf(false) }
    val focus = remember(request) { request.entries.associate { it.id to FocusRequester() } }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    fun move(direction: OrderMove) {
        order = moveOrderedItems(order, selected, direction)
        if (moving) {
            val id = order.firstOrNull { it in selected } ?: return
            scope.launch {
                val index = order.indexOf(id)
                if (list.layoutInfo.visibleItemsInfo.none { it.index == index }) list.scrollToItem(index)
                focus[id]?.requestFocus()
            }
        }
    }
    fun toggle(id: String) { selected = if (id in selected) selected - id else selected + id }
    LiveDialog(onDismiss = onDismiss, width = 760.dp) {
        Column {
            LiveText(request.title, size = 20.sp, weight = FontWeight.Bold)
            LiveText(
                when {
                    moving -> "Up/Down: move • OK: put down • Left/Right: move actions"
                    selecting -> "OK: toggle selection • Right: move actions • ${selected.size} selected"
                    else -> "OK: pick up • Hold OK: select multiple • Right: move actions"
                }, size = 14.sp, modifier = Modifier.padding(vertical = 10.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LazyColumn(
                    state = list,
                    modifier = Modifier.weight(1f).height(330.dp).onPreviewKeyEvent { event ->
                        if (!moving) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionUp, Key.DirectionDown -> {
                                if (event.type == KeyEventType.KeyDown) move(if (event.key == Key.DirectionUp) OrderMove.UP else OrderMove.DOWN)
                                true
                            }
                            Key.DirectionLeft, Key.DirectionRight -> { moving = false; false }
                            else -> false
                        }
                    },
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(order, key = { _, id -> id }) { index, id ->
                        LiveFocusRow(
                            modifier = Modifier.fillMaxWidth().focusRequester(focus.getValue(id)),
                            onClick = {
                                if (moving) { moving = false; selected = emptySet() }
                                else if (selecting) toggle(id)
                                else { selected = setOf(id); moving = true }
                            },
                            onLongClick = {
                                moving = false
                                if (selecting) toggle(id) else selected = selected + id
                                selecting = true
                            }
                        ) { focused ->
                            LiveText("${if (id in selected) "✓" else "${index + 1}."} ${titles[id]}", color = focusedTextColor(focused), size = 16.sp)
                        }
                    }
                }
                Column(Modifier.width(190.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    MenuItem("Move up") { move(OrderMove.UP) }
                    MenuItem("Move down") { move(OrderMove.DOWN) }
                    MenuItem("Move to top") { move(OrderMove.TOP) }
                    MenuItem("Move to bottom") { move(OrderMove.BOTTOM) }
                    MenuItem(if (selecting) "Clear selection" else "Select multiple") {
                        selecting = !selecting; moving = false; selected = emptySet()
                    }
                    MenuItem("Save") { request.save(order); onDismiss() }
                    MenuItem("Cancel", onClick = onDismiss)
                }
            }
        }
    }
    LaunchedEffect(request) {
        val id = request.initialId.takeIf { it in order } ?: order.firstOrNull() ?: return@LaunchedEffect
        list.scrollToItem(order.indexOf(id))
        focus[id]?.requestFocus()
    }
}

@Composable
internal fun OrderSortWarningDialog(warning: OrderSortWarning, onDismiss: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    LiveDialog(onDismiss = onDismiss, width = 560.dp) {
        LiveText("Replace custom order?", size = 20.sp, weight = FontWeight.Bold)
        LiveText(
            "This list is sorted alphabetically. Continuing will replace your saved custom order for ${warning.scope} with the alphabetical order and switch to custom sorting. You can then rearrange it.",
            maxLines = 6, modifier = Modifier.padding(vertical = 16.dp)
        )
        MenuItem("Cancel", Modifier.focusRequester(cancelFocus), onClick = onDismiss)
        MenuItem("Continue") { onDismiss(); warning.proceed() }
    }
    LaunchedEffect(Unit) { cancelFocus.requestFocus() }
}
