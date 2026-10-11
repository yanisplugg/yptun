package org.olcbox.app.update

/** Which step of the update is running; the UI turns it into "Step N of M: ...". */
enum class UpdateStageKind { DownloadPatch, DownloadInstaller, ApplyPatch, Install, Restart }

/** [step] of [total]; progress within the step goes through the separate progress callback. */
data class UpdateStage(val kind: UpdateStageKind, val step: Int, val total: Int)
