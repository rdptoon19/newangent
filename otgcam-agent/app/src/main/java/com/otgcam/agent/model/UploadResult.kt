package com.otgcam.agent.model

import java.io.File

/**
 * Sealed class representing the result of a Telegram file upload attempt.
 */
sealed class UploadResult {
    /** Upload succeeded; Telegram assigned a [fileId] to the remote file. */
    data class Success(val fileId: String, val fileName: String) : UploadResult()

    /** Upload failed with [reason]; the original [file] is preserved for retry. */
    data class Failure(val reason: String, val file: File) : UploadResult()
}
