package eu.sisik.reversevideo

import android.app.IntentService
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * Copyright (c) 2019 by Roman Sisik. All rights reserved.
 */
class VideoProcessingService: IntentService("ConversionService") {
    override fun onHandleIntent(intent: Intent?) {
        intent?.let {
            val outPath = it.getStringExtra(KEY_OUT_PATH)
            var inputVidUri = it.getParcelableExtra<Uri>(KEY_INPUT_VID_URI)
            val allKeyFrames = it.getBooleanExtra(KEY_ALL_IFRAMES, false)

            val startTime = System.currentTimeMillis()


            // Convert all frames to keyframes?
            if (allKeyFrames) {
                val tmpVidPath = cacheDir.absolutePath + "/out.vid"
                contentResolver.openFileDescriptor(inputVidUri, "r").use {
                    AllToKeyFrameConverter().convert(tmpVidPath, it.fileDescriptor)
                }
                inputVidUri = Uri.fromFile(File(tmpVidPath))
            }

            // Reverse video here
            contentResolver.openFileDescriptor(inputVidUri, "r").use {
                VideoToReversedConverter().convert(outPath!!, it.fileDescriptor)
            }
            Log.d(TAG, "Total processing duration=" + (System.currentTimeMillis() - startTime)/1000 +  " seconds")

            val pi = intent.getParcelableExtra<PendingIntent>(KEY_RESULT_INTENT)
            pi.send()
        }
    }

    companion object {
        const val TAG = "VidProcService"
        const val KEY_OUT_PATH = "eu.sisik.videotogreyscale.key.OUT_PATH"
        const val KEY_INPUT_VID_URI = "eu.sisik.videotogreyscale.key.INPUT_VID_URI"
        const val KEY_RESULT_INTENT = "eu.sisik.videotogreyscale.key.RESULT_INTENT"
        const val KEY_ALL_IFRAMES = "eu.sisik.videotogreyscale.key.ALL_IFRAMES"
    }
}