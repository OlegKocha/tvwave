package tv.tvwave.app

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory

/** IPTV H.264 streams can use non-IDR I-frames as their only entry points. */
class PlaybackSources(dataSource: DataSource.Factory) : MediaSource.Factory {
    private val standard = DefaultMediaSourceFactory(dataSource)
    private val hls = HlsMediaSource.Factory(dataSource).setExtractorFactory(
        DefaultHlsExtractorFactory(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES, true)
            .experimentalSetCodecsToParseWithinGopSampleDependencies(
                C.VIDEO_CODEC_FLAG_H264 or C.VIDEO_CODEC_FLAG_H265
            )
    )

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val local = mediaItem.localConfiguration
        val isHls = local != null &&
            Util.inferContentTypeForUriAndMimeType(local.uri, local.mimeType) == C.CONTENT_TYPE_HLS
        return if (isHls) hls.createMediaSource(mediaItem) else standard.createMediaSource(mediaItem)
    }

    override fun getSupportedTypes(): IntArray = standard.supportedTypes

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): PlaybackSources {
        standard.setDrmSessionManagerProvider(provider)
        hls.setDrmSessionManagerProvider(provider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): PlaybackSources {
        standard.setLoadErrorHandlingPolicy(policy)
        hls.setLoadErrorHandlingPolicy(policy)
        return this
    }
}
