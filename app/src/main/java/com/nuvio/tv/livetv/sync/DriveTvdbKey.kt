package com.nuvio.tv.livetv.sync

internal enum class DriveKeyAction { KEEP, UPLOAD, RESTORE }

internal fun chooseDriveTvdbKey(
    local: String?, remote: String?, hasRemoteEntry: Boolean, localChanged: Boolean,
    upload: Boolean, restore: Boolean, remoteChangedSinceSync: Boolean = false
): DriveKeyAction = when {
    local == remote -> DriveKeyAction.KEEP
    restore && hasRemoteEntry -> DriveKeyAction.RESTORE
    localChanged && upload && remoteChangedSinceSync ->
        error("This TV and another TV changed the TVDB key. Restore the Drive copy to resolve the conflict.")
    localChanged && upload -> DriveKeyAction.UPLOAD
    localChanged -> DriveKeyAction.KEEP
    hasRemoteEntry -> DriveKeyAction.RESTORE
    upload && local != null -> DriveKeyAction.UPLOAD
    else -> DriveKeyAction.KEEP
}


internal fun tvdbBackupProfiles(document: org.json.JSONObject): org.json.JSONObject {
    require(document.optInt("format") == 1) { "Unsupported TVDB credential backup." }
    val entries = requireNotNull(document.optJSONObject("profiles")) { "Invalid TVDB credential backup." }
    for (id in entries.keys()) {
        val entry = entries.optJSONObject(id)
        require(id.toIntOrNull() in 1..com.nuvio.tv.core.profile.ProfileManager.MAX_PROFILES &&
            entry?.opt("name") is String && entry.opt("key") is String) { "Invalid TVDB profile entry." }
    }
    return entries
}
