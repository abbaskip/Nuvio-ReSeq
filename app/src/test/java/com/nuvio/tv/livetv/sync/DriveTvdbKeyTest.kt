package com.nuvio.tv.livetv.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class DriveTvdbKeyTest {
    @Test fun divergingRotationsRequireResolution() {
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            chooseDriveTvdbKey("local", "remote", true, true, true, false, remoteChangedSinceSync = true)
        }
    }

    @Test fun malformedProfileIsRejectedBeforeApplyingAnyKeys() {
        val entries = org.json.JSONObject().put("1", org.json.JSONObject().put("name", "One").put("key", "fixture"))
        val envelope = org.json.JSONObject().put("format", 1).put("profiles", entries)
        assertEquals(entries, tvdbBackupProfiles(envelope))
        entries.put("2", org.json.JSONObject().put("name", "Two").put("key", 12))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { tvdbBackupProfiles(envelope) }
    }

    @Test fun freshDeviceAdoptsRemoteKeyAndOldBackupPreservesLocalKey() {
        assertEquals(DriveKeyAction.RESTORE, chooseDriveTvdbKey(null, "remote", true, false, true, false))
        assertEquals(DriveKeyAction.KEEP, chooseDriveTvdbKey("local", null, false, true, false, true))
    }

    @Test fun pendingRotationOrRemovalSurvivesAutomaticPullAndUploads() {
        for (local in listOf("new", null)) {
            assertEquals(DriveKeyAction.KEEP, chooseDriveTvdbKey(local, "old", true, true, false, false))
            assertEquals(DriveKeyAction.UPLOAD, chooseDriveTvdbKey(local, "old", true, true, true, false))
        }
    }

    @Test fun explicitRestoreCanApplyRemoteRotationOrRemoval() {
        for (remote in listOf("new", null)) {
            assertEquals(DriveKeyAction.RESTORE, chooseDriveTvdbKey("old", remote, true, true, false, true))
        }
    }
}
