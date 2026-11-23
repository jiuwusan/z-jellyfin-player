package cn.jiuwusan.jellyfin.player;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Applies bounded retry only to the selected standalone subtitle; video retains Media3 policy. */
final class SubtitleLoadPolicy extends DefaultLoadErrorHandlingPolicy {
    interface FailureListener { void failed(IOException error); }
    private final Uri subtitle;
    private final FailureListener listener;
    private final AtomicBoolean notified = new AtomicBoolean();
    SubtitleLoadPolicy(Uri subtitle, FailureListener listener) { this.subtitle = subtitle; this.listener = listener; }
    @Override public long getRetryDelayMsFor(LoadErrorHandlingPolicy.LoadErrorInfo info) {
        if (subtitle == null || !subtitle.equals(info.loadEventInfo.uri)) return super.getRetryDelayMsFor(info);
        // Selected files have already exhausted their one download retry before extraction.
        if (notified.compareAndSet(false, true)) listener.failed(info.exception);
        return C.TIME_UNSET;
    }
}
