package com.nuvio.tv.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.core.party.*
import com.nuvio.tv.ui.screens.party.displayTitle
import com.nuvio.tv.ui.screens.party.messageRes
import com.nuvio.tv.ui.screens.party.statusRes
import kotlinx.coroutines.launch

@Composable
internal fun MobileWatchParty(repository: PartyRepository, current: PartyMedia?, open: (PartyMedia) -> Unit,
    canShare: Boolean = false, switchMedia: (() -> Unit)? = null, catchUp: (() -> Unit)? = null, back: () -> Unit) {
    val state by repository.state.collectAsStateWithLifecycle()
    val available by repository.available.collectAsStateWithLifecycle()
    val canUse by repository.canUse.collectAsStateWithLifecycle()
    val party = state.party
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var code by rememberSaveable { mutableStateOf("") }
    var preview by remember(code) { mutableStateOf<PartyPreview?>(null) }
    var error by remember(code) { mutableStateOf<PartyError?>(null) }
    var confirm by remember(party?.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { repository.refreshAvailability() }
    LaunchedEffect(code, canUse, party?.id) {
        if (canUse && party == null && isValidPartyCode(code)) {
            val result = repository.preview(code)
            preview = result
            error = partyErrorOf(result.error) ?: PartyError.Full.takeIf { result.isFull }
        }
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BrowseHeading("Watch Party", back)
        when {
            !available -> Label(stringResource(PartyError.Unavailable.messageRes))
            !canUse -> Label(stringResource(PartyError.SignInRequired.messageRes))
            party == null -> {
                if (current != null) Action("Start a party for " + current.displayTitle()) {
                    if (!state.busy) scope.launch { error = repository.create(current) }
                } else Label("Start a party from a video player, or join with a code.")
                MobileTextInput("Party code", code) { code = normalizePartyCode(it) }
                preview?.media?.let { Label(it.displayTitle(), strong = true) }
                preview?.host?.let { Label("Hosted by " + it.displayName) }
                if (isValidPartyCode(code) && preview?.isFull != true) Action("Join party") {
                    if (!state.busy) scope.launch { error = repository.join(code) }
                }
            }
            else -> {
                Label(party.media.displayTitle(), strong = true)
                Label(if (party.connected) "Connected" else "Reconnecting…")
                Action("Copy invite code · " + formatPartyCode(party.code)) { clipboard.setText(AnnotatedString(party.code)) }
                if (current?.key != party.media.key) Action("Play party title") { open(party.media) }
                if (current?.key == party.media.key && !party.isHost && catchUp != null) Action("Catch up with party", onClick = catchUp)
                if (party.isHost) {
                    if (current != null && current.key != party.media.key && switchMedia != null) Action("Switch party to " + current.displayTitle(), onClick = switchMedia)
                    Action("Host-only playback controls", selected = party.hostOnly) { repository.setHostOnly(!party.hostOnly) }
                    if (party.sharing) Action("Stop sharing playback link") { repository.setSharing(false) }
                    else if (canShare && current?.key == party.media.key) Action(if (confirm == "share") "Confirm sharing link with party members" else "Share playback link") {
                        if (confirm == "share") { repository.setSharing(true); confirm = null } else confirm = "share"
                    }
                }
                SectionTitle("Members")
                party.members.forEach { member ->
                    Label(member.displayName + " · " + stringResource(party.statusRes(member)))
                    if (party.isHost && member.id != party.memberId) Action(if (confirm == member.id) "Confirm removal" else "Remove " + member.displayName) {
                        if (confirm == member.id) { repository.removeMember(member.id); confirm = null } else confirm = member.id
                    }
                }
                Action("Leave party") { repository.leave() }
                if (party.isHost) Action(if (confirm == "end") "Confirm end for everyone" else "End party") {
                    if (confirm == "end") repository.end() else confirm = "end"
                }
            }
        }
        if (state.busy) Label("Connecting…")
        error?.let { Label(stringResource(it.messageRes)) }
    }
}
