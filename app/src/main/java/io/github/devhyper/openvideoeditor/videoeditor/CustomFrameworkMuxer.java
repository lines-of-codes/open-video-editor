/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.devhyper.openvideoeditor.videoeditor;

import static androidx.media3.common.util.Util.castNonNull;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.annotation.SuppressLint;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.system.ErrnoException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.MediaFormatUtil;
import androidx.media3.common.util.Util;
import androidx.media3.container.Mp4LocationData;
import androidx.media3.muxer.BufferInfo;
import androidx.media3.muxer.Muxer;
import androidx.media3.muxer.MuxerException;
import androidx.media3.transformer.TransformerUtil;
import androidx.media3.muxer.MediaMuxerCompat;

import com.google.common.collect.ImmutableList;

import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link Muxer} implementation that uses a {@link MediaMuxerCompat}.
 */
public final class CustomFrameworkMuxer implements Muxer {
    public static final String MUXER_STOPPING_FAILED_ERROR_MESSAGE = "Failed to stop the MediaMuxer";

    // MediaMuxer supported sample formats are documented in MediaMuxer.addTrack(MediaFormat).
    private static final ImmutableList<String> SUPPORTED_VIDEO_SAMPLE_MIME_TYPES =
            getSupportedVideoSampleMimeTypes();
    private static final ImmutableList<String> SUPPORTED_AUDIO_SAMPLE_MIME_TYPES =
            ImmutableList.of(MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_AMR_NB, MimeTypes.AUDIO_AMR_WB);

    /**
     * {@link Muxer.Factory} for {@link CustomFrameworkMuxer}.
     */
    public static final class Factory implements Muxer.Factory {
        private final long videoDurationMs;
        FileDescriptor fd;

        public Factory(long videoDurationMs) {
            this.videoDurationMs = videoDurationMs;
        }

        public Factory(FileDescriptor fd, long videoDurationMs) {
            this.videoDurationMs = videoDurationMs;
            this.fd = fd;
        }

        @NonNull
        @Override
        public CustomFrameworkMuxer create(String path) throws MuxerException {
            MediaMuxerCompat mediaMuxer;
            try {
                if (path.isEmpty()) {
                    mediaMuxer = new MediaMuxerCompat(fd, MediaMuxerCompat.OUTPUT_FORMAT_MP4);
                    android.system.Os.close(fd);
                } else {
                    mediaMuxer = new MediaMuxerCompat(path, MediaMuxerCompat.OUTPUT_FORMAT_MP4);
                }
            } catch (IOException e) {
                throw new MuxerException("Error creating muxer", e);
            } catch (ErrnoException e) {
                throw new RuntimeException(e);
            }
            return new CustomFrameworkMuxer(mediaMuxer, videoDurationMs);
        }

        @NonNull
        @Override
        public ImmutableList<String> getSupportedSampleMimeTypes(@C.TrackType int trackType) {
            if (trackType == C.TRACK_TYPE_VIDEO) {
                return SUPPORTED_VIDEO_SAMPLE_MIME_TYPES;
            } else if (trackType == C.TRACK_TYPE_AUDIO) {
                return SUPPORTED_AUDIO_SAMPLE_MIME_TYPES;
            }
            return ImmutableList.of();
        }
    }

    private final MediaMuxerCompat mediaMuxer;
    private final long videoDurationUs;
    private final Map<Integer, Long> trackTokenToLastPresentationTimeUs;
    private final Map<Integer, Long> trackTokenToPresentationTimeOffsetUs;

    @Nullable
    private Integer videoTrackToken;

    private boolean isStarted;
    private boolean isReleased;

    private CustomFrameworkMuxer(MediaMuxerCompat mediaMuxer, long videoDurationMs) {
        this.mediaMuxer = mediaMuxer;
        this.videoDurationUs = Util.msToUs(videoDurationMs);
        trackTokenToLastPresentationTimeUs = new HashMap<>();
        trackTokenToPresentationTimeOffsetUs = new HashMap<>();
    }

    @Override
    public int addTrack(Format format) throws MuxerException {
        String sampleMimeType = checkNotNull(format.sampleMimeType);
        MediaFormat mediaFormat;
        boolean isVideo = MimeTypes.isVideo(sampleMimeType);
        if (isVideo) {
            mediaFormat = MediaFormat.createVideoFormat(sampleMimeType, format.width, format.height);
            MediaFormatUtil.maybeSetColorInfo(mediaFormat, format.colorInfo);
            try {
                mediaMuxer.setOrientationHint(format.rotationDegrees);
            } catch (RuntimeException e) {
                throw new MuxerException(
                        "Failed to set orientation hint with rotationDegrees=" + format.rotationDegrees, e);
            }
        } else {
            mediaFormat =
                    MediaFormat.createAudioFormat(sampleMimeType, format.sampleRate, format.channelCount);
            MediaFormatUtil.maybeSetString(mediaFormat, MediaFormat.KEY_LANGUAGE, format.language);
        }
        MediaFormatUtil.setCsdBuffers(mediaFormat, format.initializationData);
        int trackIndex;
        try {
            trackIndex = mediaMuxer.addTrack(mediaFormat);
        } catch (RuntimeException e) {
            throw new MuxerException("Failed to add track with format=" + format, e);
        }

        if (isVideo) {
            videoTrackToken = trackIndex;
        }

        return trackIndex;
    }

    @Override
    public void writeSampleData(int trackToken, @NonNull ByteBuffer data, BufferInfo bufferInfo)
            throws MuxerException {
        long presentationTimeUs = bufferInfo.presentationTimeUs;
        if (videoDurationUs != C.TIME_UNSET
                && trackToken == videoTrackToken
                && presentationTimeUs > videoDurationUs) {
            return;
        }
        if (!isStarted) {
            if (Build.VERSION.SDK_INT < 30 && presentationTimeUs < 0) {
                trackTokenToPresentationTimeOffsetUs.put(trackToken, -presentationTimeUs);
            }
            startMuxer();
        }

        long presentationTimeOffsetUs =
                trackTokenToPresentationTimeOffsetUs.containsKey(trackToken)
                        ? trackTokenToPresentationTimeOffsetUs.get(trackToken)
                        : 0;
        presentationTimeUs += presentationTimeOffsetUs;

        long lastSamplePresentationTimeUs =
                trackTokenToLastPresentationTimeUs.containsKey(trackToken)
                        ? trackTokenToLastPresentationTimeUs.get(trackToken)
                        : 0;
        // writeSampleData blocks on old API versions, so check here to avoid calling the method.
        checkState(
                Build.VERSION.SDK_INT > 24 || presentationTimeUs >= lastSamplePresentationTimeUs,
                "Samples not in presentation order ("
                        + presentationTimeUs
                        + " < "
                        + lastSamplePresentationTimeUs
                        + ") unsupported on this API version");
        trackTokenToLastPresentationTimeUs.put(trackToken, presentationTimeUs);

        checkState(
                presentationTimeOffsetUs == 0 || presentationTimeUs >= lastSamplePresentationTimeUs,
                "Samples not in presentation order ("
                        + presentationTimeUs
                        + " < "
                        + lastSamplePresentationTimeUs
                        + ") unsupported when using negative PTS workaround");
        var tempBuf = new MediaCodec.BufferInfo();
        tempBuf.set(tempBuf.offset, bufferInfo.size, presentationTimeUs, bufferInfo.flags);

        try {
            mediaMuxer.writeSampleData(trackToken, data, tempBuf);
        } catch (RuntimeException e) {
            throw new MuxerException(
                    "Failed to write sample for presentationTimeUs="
                            + presentationTimeUs
                            + ", size="
                            + bufferInfo.size,
                    e);
        }
    }

    @Override
    public void addMetadataEntry(@NonNull Metadata.Entry metadataEntry) {
        if (metadataEntry instanceof Mp4LocationData) {
            mediaMuxer.setLocation(
                    ((Mp4LocationData) metadataEntry).latitude, ((Mp4LocationData) metadataEntry).longitude);
        }
    }

    @Override
    public void close() throws MuxerException {
        if (isReleased) {
            return;
        }

        if (!isStarted) {
            // Start the muxer even if no samples have been written so that it throws instead of silently
            // writing nothing to the output file.
            startMuxer();
        }

        if (videoDurationUs != C.TIME_UNSET && videoTrackToken != null) {
            BufferInfo bufferInfo = new BufferInfo(
                    videoDurationUs,
                    0,
                    TransformerUtil.getMediaCodecFlags(C.BUFFER_FLAG_END_OF_STREAM));
            writeSampleData(checkNotNull(videoTrackToken), ByteBuffer.allocateDirect(0), bufferInfo);
        }

        isStarted = false;
        try {
            stopMuxer(mediaMuxer);
        } catch (RuntimeException e) {
            throw new MuxerException(MUXER_STOPPING_FAILED_ERROR_MESSAGE, e);
        } finally {
            mediaMuxer.release();
            isReleased = true;
        }
    }

    private void startMuxer() throws MuxerException {
        try {
            mediaMuxer.start();
        } catch (RuntimeException e) {
            throw new MuxerException("Failed to start the muxer", e);
        }
        isStarted = true;
    }

    // Accesses MediaMuxer state via reflection to ensure that muxer resources can be released even
    // if stopping fails.
    @SuppressLint("PrivateApi")
    private static void stopMuxer(MediaMuxerCompat mediaMuxer) {
        try {
            mediaMuxer.stop();
        } catch (RuntimeException e) {
            if (Build.VERSION.SDK_INT < 30) {
                // Set the muxer state to stopped even if mediaMuxer.stop() failed so that
                // mediaMuxer.release() doesn't attempt to stop the muxer and therefore doesn't throw the
                // same exception without releasing its resources. This is already implemented in MediaMuxer
                // from API level 30. See also b/80338884.
                try {
                    Field muxerStoppedStateField = MediaMuxer.class.getDeclaredField("MUXER_STATE_STOPPED");
                    muxerStoppedStateField.setAccessible(true);
                    int muxerStoppedState = castNonNull((Integer) muxerStoppedStateField.get(mediaMuxer));
                    Field muxerStateField = MediaMuxer.class.getDeclaredField("mState");
                    muxerStateField.setAccessible(true);
                    muxerStateField.set(mediaMuxer, muxerStoppedState);
                } catch (Exception reflectionException) {
                    // Do nothing.
                }
            }
            // Rethrow the original error.
            throw e;
        }
    }

    private static ImmutableList<String> getSupportedVideoSampleMimeTypes() {
        ImmutableList.Builder<String> supportedMimeTypes =
                new ImmutableList.Builder<String>()
                        .add(MimeTypes.VIDEO_H265, MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H263, MimeTypes.VIDEO_MP4V);
        if (Build.VERSION.SDK_INT >= 34) {
            supportedMimeTypes.add(MimeTypes.VIDEO_AV1);
        }
        return supportedMimeTypes.build();
    }
}
