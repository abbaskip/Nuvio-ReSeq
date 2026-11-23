// Collection, folder and source editing pages adapted from NuvioPlusIPTV-Mobile (GPLv3).
package com.nuvio.tv.mobile

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.ui.screens.collection.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun MobileCollections(back: () -> Unit, edit: (String?) -> Unit, folder: (String, String) -> Unit,
    vm: CollectionManagementViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var remove by remember { mutableStateOf<com.nuvio.tv.domain.model.Collection?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val load = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } } }
                .onSuccess(vm::handleFileContent).onFailure { message = "Could not open the selected file." }
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val payload = vm.getExportJson()
            scope.launch { message = runCatching { withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(payload) }
            }; "Collections exported." }.getOrDefault("Could not write the file.") }
        }
    }
    LazyColumn(contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { BrowseHeading("Collections", back)
            LazyRow { item { Action("New collection") { edit(null) } }; item { Action("Import", onClick = vm::showImportDialog) }
                item { Action("Export") { save.launch("nuvio-collections.json") } } }
        }
        (state.error ?: message)?.let { item { Label(it) } }
        if (state.isLoading) item { LinearProgressIndicator() }
        if (!state.isLoading && state.collections.isEmpty()) item { Label("Create a collection to group catalogues in folders on Home.") }
        itemsIndexed(state.collections, key = { _, c -> c.id }) { index, collection ->
            SectionTitle(collection.title)
            LazyRow { items(collection.folders) { f -> Action(f.title) { folder(collection.id, f.id) } } }
            LazyRow {
                item { Action("Edit") { edit(collection.id) } }
                if (index > 0) item { Action("Move up") { vm.enterCollectionMoveMode(collection.id); vm.moveCollectionDuringDrag(collection.id, -1); vm.confirmCollectionMove() } }
                if (index < state.collections.lastIndex) item { Action("Move down") { vm.enterCollectionMoveMode(collection.id); vm.moveCollectionDuringDrag(collection.id, 1); vm.confirmCollectionMove() } }
                item { Action("Delete") { remove = collection } }
            }
        }
    }
    remove?.let { collection -> ConfirmAction("Delete " + collection.title + "?", "Delete", { remove = null }) {
        vm.deleteCollection(collection.id); remove = null
    } }
    if (state.showImportDialog) TouchSheet(vm::hideImportDialog) {
        LazyColumn(contentPadding = PaddingValues(12.dp)) {
            item { SectionTitle("Import collections")
                Row { ImportMode.entries.forEach { mode -> Action(mode.name.lowercase(), selected = mode == state.importMode) { vm.setImportMode(mode) } } }
                when (state.importMode) {
                    ImportMode.FILE -> Action("Choose file") { load.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
                    ImportMode.URL -> { MobileTextInput("Collection URL", state.importUrl, vm::updateImportUrl); Action("Preview", onClick = vm::fetchUrl) }
                    ImportMode.PASTE -> { OutlinedTextField(state.importText, vm::updateImportText, Modifier.fillMaxWidth().heightIn(max = 180.dp),
                        label = { Text("Collection JSON") }, shape = RectangleShape); Action("Preview", onClick = vm::validateCurrentText) }
                }
                if (state.isLoadingImport) LinearProgressIndicator()
                state.validationResult?.let { result ->
                    if (result.valid) { Label("${result.collectionCount} collections · ${result.folderCount} folders"); Action("Import collections", onClick = vm::confirmImport) }
                    else result.error?.let { Label(it) }
                }
                state.importError?.let { Label(it) }
                Action("Cancel", onClick = vm::hideImportDialog)
            }
        }
    }
}

@Composable
internal fun ConfirmAction(title: String, action: String, close: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = close, shape = RectangleShape, title = { Text(title) },
        confirmButton = { TextButton(onClick = confirm) { Text(action) } }, dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

@Composable
internal fun MobileCollectionEditor(back: () -> Unit, vm: CollectionEditorViewModel = hiltViewModel()) {
    val s by vm.uiState.collectAsStateWithLifecycle()
    var discard by remember { mutableStateOf<(() -> Unit)?>(null) }
    var delete by remember { mutableStateOf<CollectionFolder?>(null) }
    val leave: () -> Unit = {
        when {
            s.showCatalogPicker -> vm.hideCatalogPicker()
            s.showTmdbSourcePicker -> vm.hideTmdbSourcePicker()
            s.showTraktSourcePicker -> vm.hideTraktSourcePicker()
            s.showFolderEditor && s.hasUnsavedFolderChanges -> discard = vm::cancelFolderEdit
            s.showFolderEditor -> vm.cancelFolderEdit()
            s.hasUnsavedCollectionChanges -> discard = back
            else -> back()
        }
    }
    BackHandler(onBack = leave)
    val folder = s.editingFolder
    LazyColumn(contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { BrowseHeading(when {
            s.showCatalogPicker -> "Choose catalogues"
            s.showTmdbSourcePicker -> "TMDB sources"
            s.showTraktSourcePicker -> "Trakt lists"
            s.showFolderEditor -> "Edit folder"
            s.isNew -> "New collection"
            else -> "Edit collection"
        }, leave) }
        if (s.isLoading) item { LinearProgressIndicator() }
        else if (s.showCatalogPicker && folder != null) {
            items(s.availableCatalogs) { catalog ->
                val selected = folder.catalogSources.any { it.addonId == catalog.addonId && it.catalogId == catalog.catalogId && it.type == catalog.type }
                Action(catalog.catalogName + " · " + catalog.addonName, selected = selected) { vm.toggleCatalogSource(catalog) }
            }
            item { Action("Done", onClick = vm::hideCatalogPicker) }
        } else if (s.showTmdbSourcePicker) {
            item { MobileTextInput("TMDB link or ID", s.tmdbInput, vm::setTmdbInput)
                MobileTextInput("Display title", s.tmdbTitleInput, vm::setTmdbTitleInput)
                LazyRow { items(TmdbBuilderMode.entries.toList()) { mode -> Action(mode.name.lowercase(), selected = mode == s.tmdbBuilderMode) { vm.setTmdbBuilderMode(mode) } } }
                Row { TmdbCollectionMediaType.entries.forEach { media -> Action(media.value, selected = !s.tmdbMediaBoth && s.tmdbMediaType == media) { vm.setTmdbMediaBoth(false); vm.setTmdbMediaType(media) } }
                    Action("Both", selected = s.tmdbMediaBoth) { vm.setTmdbMediaBoth(true) } }
                LazyRow { items(TmdbCollectionSort.entries.toList()) { sort -> Action(sort.name.lowercase().replace('_', ' '), selected = s.tmdbSortBy == sort.value) { vm.setTmdbSortBy(sort.value) } } }
                s.tmdbSearchError?.let { Label(it) }
                Action("Add TMDB source", onClick = vm::addTmdbSourceFromInput)
                Action("Add Discover source", onClick = vm::addDiscoverSource)
                SectionTitle("Presets")
            }
            items(vm.tmdbPresets()) { preset -> Action(preset.title) { vm.addTmdbSource(preset.source) } }
        } else if (s.showTraktSourcePicker) {
            item { MobileTextInput("List name, URL or ID", s.traktInput, vm::setTraktInput)
                MobileTextInput("Display title", s.traktTitleInput, vm::setTraktTitleInput)
                Row { Action("Search", onClick = vm::searchTraktLists); Action("Add list", onClick = vm::addTraktSourceFromInput) }
                s.traktSearchError?.let { Label(it) }
            }
            items((s.traktSearchResults + s.traktTrendingResults + s.traktPopularResults).distinctBy { it.traktListId }) { result ->
                Action(result.title + " · " + result.subtitle) { vm.addTraktSourceFromResult(result) }
            }
        } else if (s.showFolderEditor && folder != null) {
            item {
                MobileTextInput("Folder title", folder.title, vm::updateFolderTitle)
                MobileTextInput("Cover image URL", folder.coverImageUrl.orEmpty(), vm::updateFolderCoverImage)
                MobileTextInput("Cover emoji", folder.coverEmoji.orEmpty(), vm::updateFolderCoverEmoji)
                MobileTextInput("Backdrop URL", folder.heroBackdropUrl.orEmpty(), vm::updateFolderHeroBackdropUrl)
                SectionTitle("Sources")
                LazyRow { item { Action("Catalogues", onClick = vm::showCatalogPicker) }; item { Action("TMDB", onClick = vm::showTmdbSourcePicker) }
                    item { Action("Trakt", onClick = vm::showTraktSourcePicker) } }
            }
            val keys = collectionSourceKeys(folder.sources)
            itemsIndexed(folder.sources) { i, src ->
                val name = when (src) { is AddonCatalogCollectionSource -> s.availableCatalogs.find { it.addonId == src.addonId && it.catalogId == src.catalogId && it.type == src.type }?.catalogName ?: src.catalogId
                    is TraktCollectionSource -> src.title; is TmdbCollectionSource -> src.title }
                Label(name, strong = true)
                LazyRow {
                    if (src is TraktCollectionSource) item { Action("Edit") { vm.editTraktSource(i) } }
                    if (src is TmdbCollectionSource) item { Action("Edit") { vm.editTmdbSource(i) } }
                    if (i > 0) item { Action("Move up") { vm.enterSourceMoveMode(keys[i]); vm.moveSourceDuringDrag(keys[i], -1); vm.confirmSourceMove() } }
                    if (i < folder.sources.lastIndex) item { Action("Move down") { vm.enterSourceMoveMode(keys[i]); vm.moveSourceDuringDrag(keys[i], 1); vm.confirmSourceMove() } }
                    item { Action("Remove") { vm.removeCatalogSource(i) } }
                }
                if (src is AddonCatalogCollectionSource) {
                    val genres = s.availableCatalogs.find { it.addonId == src.addonId && it.catalogId == src.catalogId && it.type == src.type }?.genreOptions.orEmpty()
                    if (genres.isNotEmpty()) LazyRow { items(listOf("") + genres) { genre -> Action(genre.ifBlank { "All genres" }, selected = genre == src.genre.orEmpty()) { vm.updateCatalogSourceGenre(i, genre.ifBlank { null }) } } }
                }
            }
            item { Button(onClick = vm::saveFolderEdit, enabled = s.canSaveFolder, shape = RectangleShape) { Text("Save folder") } }
        } else {
            item { MobileTextInput("Title", s.title, vm::setTitle)
                MobileTextInput("Backdrop URL", s.backdropImageUrl, vm::setBackdropImageUrl)
                Action("Pin to top of Home", selected = s.pinToTop) { vm.setPinToTop(!s.pinToTop) }
                LazyRow { items(FolderViewMode.entries.toList()) { mode -> Action(mode.name.lowercase().replace('_', ' '), selected = s.viewMode == mode) { vm.setViewMode(mode) } } }
                Action("Show All tab", selected = s.showAllTab) { vm.setShowAllTab(!s.showAllTab) }
                Action("Add folder", onClick = vm::addFolder)
            }
            itemsIndexed(s.folders, key = { _, f -> f.id }) { i, f ->
                Action(f.title) { vm.editFolder(f.id) }
                LazyRow { if (i > 0) item { Action("Move up") { vm.enterFolderMoveMode(f.id); vm.moveFolderDuringDrag(f.id, -1); vm.confirmFolderMove() } }
                    if (i < s.folders.lastIndex) item { Action("Move down") { vm.enterFolderMoveMode(f.id); vm.moveFolderDuringDrag(f.id, 1); vm.confirmFolderMove() } }
                    item { Action("Delete folder") { delete = f } } }
            }
            item { Button(onClick = { vm.save(back) }, enabled = s.canSaveCollection && !s.isSaving, shape = RectangleShape) { Text("Save collection") } }
        }
    }
    delete?.let { f -> ConfirmAction("Delete " + f.title + "?", "Delete", { delete = null }) { vm.removeFolder(f.id); delete = null } }
    discard?.let { next -> ConfirmAction("Discard unsaved changes?", "Discard", { discard = null }) { discard = null; next() } }
}
