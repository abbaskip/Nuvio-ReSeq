package com.nuvio.tv.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.ui.screens.library.*

@Composable
internal fun MobileLibraryLists(state: LibraryUiState, vm: LibraryViewModel) {
    val capabilities = state.listManagement ?: return
    val personal = state.listTabs.filter { it.type == LibraryListTab.Type.PERSONAL }
    val editor = state.listEditorState
    var removing by remember(state.manageSelectedListKey) { mutableStateOf(false) }
    val back = if (editor == null) vm::onCloseManageLists else vm::onCancelEditor
    TouchSheet(back) {
        LazyColumn(Modifier.fillMaxWidth().imePadding(), contentPadding = PaddingValues(12.dp)) {
            item { BrowseHeading(if (editor != null) "Edit list" else "Manage lists", back) }
            if (editor == null) {
                item { Action("New list", onClick = vm::onStartCreateList) }
                itemsIndexed(personal, key = { _, list -> list.key }) { index, list ->
                    Action(list.title, Modifier.fillMaxWidth(), list.key == state.manageSelectedListKey) { vm.onSelectManageList(list.key) }
                    if (list.key == state.manageSelectedListKey && !state.pendingOperation) {
                        Row {
                            Action("Edit", onClick = vm::onStartEditList)
                            if (capabilities.supportsReordering && index > 0) Action("Earlier", onClick = vm::onMoveSelectedListUp)
                            if (capabilities.supportsReordering && index < personal.lastIndex) Action("Later", onClick = vm::onMoveSelectedListDown)
                            Action("Delete") { removing = true }
                        }
                        if (removing) ConfirmAction("Delete " + list.title + "?", "Delete", { removing = false }) { removing = false; vm.onDeleteSelectedList() }
                    }
                }
            } else item {
                MobileTextInput("List name", editor.name, vm::onUpdateEditorName)
                if (capabilities.supportsDescription) MobileTextInput("Description", editor.description, vm::onUpdateEditorDescription)
                if (capabilities.privacyOptions.isNotEmpty()) {
                    Label("Visibility")
                    LazyRow { items(capabilities.privacyOptions) { mode -> Action(mode.apiValue, selected = mode == editor.privacy) { vm.onUpdateEditorPrivacy(mode) } } }
                }
                if (!state.pendingOperation) Action("Save list", onClick = vm::onSubmitEditor)
            }
            if (state.pendingOperation) item { Label("Saving list…") }
            state.errorMessage?.let { item { Label(it) } }
            state.transientMessage?.let { item { Label(it) } }
        }
    }
}
