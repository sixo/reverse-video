package eu.sisik.reversevideo

import android.media.*
import android.util.Size
import android.view.Surface
import java.io.FileDescriptor
import java.security.InvalidParameterException
import java.util.*

/**
 * Copyright (c) 2019 by Roman Sisik. All rights reserved.
 */
class VideoToReversedConverter {

    // Format for the greyscale video output file
    private val outMime = "video/avc"

    // Main classes from Android's API responsible
    // for processing of the video
    private var extractor: MediaExtractor? = null
    private var muxer: MediaMuxer? = null
    private var decoder: MediaCodec? = null
    private var encoder: MediaCodec? = null

    private val mediaCodedTimeoutUs = 10000L
    private val bufferInfo = MediaCodec.BufferInfo()
    private var trackIndex = -1

    private val syncSampleTimes = Stack<Long>()
    private var endPresentationTimeUs = -1L

    // These control the state of video processing
    private var allInputExtracted = false
    private var allInputDecoded = false
    private var allOutputEncoded = false

    // Handle to raw video data used by MediaCodec encoder & decoder
    private var surface: Surface? = null

    private var width = -1
    private var height = -1


    /**
     * Reverts video so it can be played backwards
     *
     * @outPath path to output video file
     * @inputVidFd fd to input video file. I decided to use FileDescriptor
     *             simply because it is one of data sources accepted by MediaExtractor
     *             and it can be obtained from Uri (which I get from system file picker).
     *             Feel free to adjust to your preferences.
     */
    fun convert(outPath: String, inputVidFd: FileDescriptor) {
        try {
            init(outPath, inputVidFd)
            convert()
        } finally {
            releaseConverter()
        }
    }

    private fun init(outPath: String, inputVidFd: FileDescriptor) {
        // Init extractor
        extractor = MediaExtractor()
        extractor!!.setDataSource(inputVidFd)
        val inFormat = selectVideoTrack(extractor!!)

        // Create H.264 encoder
        encoder = MediaCodec.createEncoderByType(outMime)

        // Prepare output format for the encoder
        val outFormat = getOutputFormat(inFormat)
        width = outFormat.getInteger(MediaFormat.KEY_WIDTH)
        height = outFormat.getInteger(MediaFormat.KEY_HEIGHT)

        // Configure the encoder
        encoder!!.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = encoder!!.createInputSurface()

        // Init decoder
        decoder = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME))
        decoder!!.configure(inFormat, surface, null, 0)

        // Init muxer
        muxer = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        encoder!!.start()
        decoder!!.start()
    }

    private fun selectVideoTrack(extractor: MediaExtractor): MediaFormat {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            if (format.getString(MediaFormat.KEY_MIME).startsWith("video/")) {
                extractor.selectTrack(i)
                return format
            }
        }

        throw InvalidParameterException("File contains no video track")
    }

    private fun getOutputFormat(inputFormat: MediaFormat): MediaFormat {
        // Preferably the output vid should have same resolution as input vid
        val inputSize = Size(inputFormat.getInteger(MediaFormat.KEY_WIDTH), inputFormat.getInteger(MediaFormat.KEY_HEIGHT))
        val outputSize = getSupportedVideoSize(encoder!!, outMime, inputSize)

        return MediaFormat.createVideoFormat(outMime, outputSize.width, outputSize.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 20000000)
            setInteger(MediaFormat.KEY_FRAME_RATE, inputFormat.getInteger(MediaFormat.KEY_FRAME_RATE)            )
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 15)
            setString(MediaFormat.KEY_MIME, outMime)
        }
    }

    private fun convert() {
        while(true) {
            if (extractor!!.sampleFlags == MediaExtractor.SAMPLE_FLAG_SYNC)
                syncSampleTimes.push(extractor!!.sampleTime)

            if (!extractor!!.advance())
                break
        }

        endPresentationTimeUs = syncSampleTimes.lastElement()

        allInputExtracted = false
        allInputDecoded = false
        allOutputEncoded = false

        // Extract, decode, edit, encode, and mux
        while (!allOutputEncoded) {
            // Feed input to decoder
            if (!allInputExtracted)
                feedInputToDecoder()

            var encoderOutputAvailable = true
            var decoderOutputAvailable = !allInputDecoded

            while (encoderOutputAvailable || decoderOutputAvailable) {
                // Drain Encoder & mux to output file first
                val outBufferId = encoder!!.dequeueOutputBuffer(bufferInfo, mediaCodedTimeoutUs)
                if (outBufferId >= 0) {

                    val encodedBuffer = encoder!!.getOutputBuffer(outBufferId)

                    muxer!!.writeSampleData(trackIndex, encodedBuffer, bufferInfo)

                    encoder!!.releaseOutputBuffer(outBufferId, false)

                    // Are we finished here?
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        allOutputEncoded = true
                        break
                    }
                } else if (outBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    encoderOutputAvailable = false
                } else if (outBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    trackIndex = muxer!!.addTrack(encoder!!.outputFormat)
                    muxer!!.start()
                }

                if (outBufferId != MediaCodec.INFO_TRY_AGAIN_LATER)
                    continue

                // Get output from decoder and feed it to encoder
                if (!allInputDecoded) {
                    val outBufferId = decoder!!.dequeueOutputBuffer(bufferInfo, mediaCodedTimeoutUs)
                    if (outBufferId >= 0) {
                        val render = bufferInfo.size > 0

                        // Get the decoded frame
                        decoder!!.releaseOutputBuffer(outBufferId, render)

                        // Did we get all output from decoder?
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            allInputDecoded = true
                            encoder!!.signalEndOfInputStream()
                        }
                    } else if (outBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        decoderOutputAvailable = false
                    }
                }
            }
        }
    }

    private fun feedInputToDecoder() {
        val inBufferId = decoder!!.dequeueInputBuffer(mediaCodedTimeoutUs)
        if (inBufferId >= 0) {
            if (syncSampleTimes.isNotEmpty() && syncSampleTimes.peek() > 0) { // If we're not yet at the beginning
                val buffer = decoder!!.getInputBuffer(inBufferId)
                val sampleSize = extractor!!.readSampleData(buffer, 0)
                if (sampleSize > 0) {
                    decoder!!.queueInputBuffer(
                        inBufferId, 0, sampleSize,
                        endPresentationTimeUs - extractor!!.sampleTime, extractor!!.sampleFlags
                    )
                }

                val next = syncSampleTimes.pop()

                extractor!!.seekTo(next, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            } else {
                decoder!!.queueInputBuffer(inBufferId, 0, 0,
                    0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                allInputExtracted = true
            }
        }
    }

    private fun releaseConverter() {
        extractor!!.release()

        decoder?.stop()
        decoder?.release()
        decoder = null

        encoder?.stop()
        encoder?.release()
        encoder = null

        muxer?.stop()
        muxer?.release()
        muxer = null

        surface?.release()
        surface = null

        width = -1
        height = -1
        trackIndex = -1
    }
}