package com.brentvatne.exoplayer;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.core.content.ContextCompat;

import com.daasuu.epf.EPlayerView;
import com.daasuu.epf.filter.GlFilter;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.text.TextRenderer;
import com.google.android.exoplayer2.text.TextOutput;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.ui.SubtitleView;

import java.util.List;

@TargetApi(16)
public final class ExoPlayerView extends FrameLayout {

    private Context context;
    private ViewGroup.LayoutParams layoutParams;
    private View surfaceView;
    private final View shutterView;
    private final SubtitleView subtitleLayout;
    private final AspectRatioFrameLayout layout;
    private final ComponentListener componentListener;
    private SimpleExoPlayer player;
    private GlFilter filter;

    public ExoPlayerView(Context context) {
        this(context, null);
    }

    public ExoPlayerView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ExoPlayerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        this.context = context;
        layoutParams = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        componentListener = new ComponentListener();

        FrameLayout.LayoutParams aspectRatioParams = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        aspectRatioParams.gravity = Gravity.CENTER;
        layout = new AspectRatioFrameLayout(context);
        layout.setLayoutParams(aspectRatioParams);

        shutterView = new View(getContext());
        shutterView.setLayoutParams(layoutParams);
        shutterView.setBackgroundColor(ContextCompat.getColor(context, android.R.color.black));

        subtitleLayout = new SubtitleView(context);
        subtitleLayout.setLayoutParams(layoutParams);
        subtitleLayout.setUserDefaultStyle();
        subtitleLayout.setUserDefaultTextSize();

        surfaceView = new TextureView(context);
        surfaceView.setLayoutParams(layoutParams);

        layout.addView(surfaceView, 0, layoutParams);
        layout.addView(shutterView, 1, layoutParams);
        layout.addView(subtitleLayout, 2, layoutParams);

        addViewInLayout(layout, 0, aspectRatioParams);
    }

    /**
     * Set the {@link SimpleExoPlayer} to use. The {@link SimpleExoPlayer#setTextOutput} and
     * {@link SimpleExoPlayer#setVideoListener} method of the player will be called and previous
     * assignments are overridden.
     *
     * @param player The {@link SimpleExoPlayer} to use.
     */
    public void setPlayer(SimpleExoPlayer player) {
        if (this.player == player) {
            return;
        }
        if (this.player != null) {
            this.player.setTextOutput(null);
            this.player.setVideoListener(null);
            this.player.removeListener(componentListener);
            this.player.setVideoSurface(null);
        }
        this.player = player;
        shutterView.setVisibility(VISIBLE);
        if (player != null) {
            setVideoView();
            player.setVideoListener(componentListener);
            player.addListener(componentListener);
            player.setTextOutput(componentListener);
        }
    }

    private void setVideoView() {
        if (surfaceView instanceof TextureView) {
            player.setVideoTextureView((TextureView) surfaceView);
        }else if (surfaceView instanceof EPlayerView) {
            ((EPlayerView) surfaceView).setSimpleExoPlayer(player);
        }
    }

    public void enableFilter(boolean filterEnabled) {
        if (filterEnabled && player != null) {
            layout.removeViewAt(0);
            surfaceView = null;
            surfaceView = new EPlayerView(context);
            ((EPlayerView)surfaceView).setSimpleExoPlayer(player);
            surfaceView.setLayoutParams(layoutParams);
            layout.addView(surfaceView, 0, layoutParams);
            ((EPlayerView) surfaceView).onResume();
            applyFilter();
        }
    }

    /*
    * generate a bitmap for apply filter to EPlayerView
    * @param path of lookup filter
    * */
    public void setFilterRawResourceName(final String name) {
        int resourceId = context.getResources().getIdentifier(name, "raw", context.getPackageName());
        Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), resourceId);
        GlFilter filter;

        if (bitmap != null) {
            filter = new GlLut512Filter(bitmap);
        } else {
            filter = new GlFilter();
        }

        this.filter = filter;
        applyFilter();
    }

    private void applyFilter() {
        if (surfaceView instanceof EPlayerView && this.filter != null) {
            ((EPlayerView) surfaceView).setGlFilter(this.filter);
        }
    }

    /**
     * Sets the resize mode which can be of value {@link ResizeMode.Mode}
     *
     * @param resizeMode The resize mode.
     */
    public void setResizeMode(@ResizeMode.Mode int resizeMode) {
        if (layout.getResizeMode() != resizeMode) {
            layout.setResizeMode(resizeMode);
            post(measureAndLayout);
        }

    }

    public void setHideShutterView(boolean hideShutterView) {
        shutterView.setVisibility(hideShutterView ? View.INVISIBLE : View.VISIBLE);
    }

    private final Runnable measureAndLayout = new Runnable() {
        @Override
        public void run() {
            measure(
                    MeasureSpec.makeMeasureSpec(getWidth(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(getHeight(), MeasureSpec.EXACTLY));
            layout(getLeft(), getTop(), getRight(), getBottom());
        }
    };

    private void updateForCurrentTrackSelections() {
        if (player == null) {
            return;
        }
        TrackSelectionArray selections = player.getCurrentTrackSelections();
        for (int i = 0; i < selections.length; i++) {
            if (player.getRendererType(i) == C.TRACK_TYPE_VIDEO && selections.get(i) != null) {
                // Video enabled so artwork must be hidden. If the shutter is closed, it will be opened in
                // onRenderedFirstFrame().
                return;
            }
        }
        // Video disabled so the shutter must be closed.
        shutterView.setVisibility(VISIBLE);
    }

    public void invalidateAspectRatio() {
        // Resetting aspect ratio will force layout refresh on next video size changed
        layout.invalidateAspectRatio();
    }

    private final class ComponentListener implements SimpleExoPlayer.VideoListener,
            TextOutput, ExoPlayer.EventListener {

        // TextRenderer.Output implementation

        @Override
        public void onCues(List<Cue> cues) {
            subtitleLayout.onCues(cues);
        }

        // SimpleExoPlayer.VideoListener implementation

        @Override
        public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
            boolean isInitialRatio = layout.getAspectRatio() == 0;
            layout.setAspectRatio(height == 0 ? 1 : (width * pixelWidthHeightRatio) / height);

            // React native workaround for measuring and layout on initial load.
            if (isInitialRatio) {
                post(measureAndLayout);
            }
        }

        @Override
        public void onRenderedFirstFrame() {
            shutterView.setVisibility(INVISIBLE);
        }

        // ExoPlayer.EventListener implementation

        @Override
        public void onLoadingChanged(boolean isLoading) {
            // Do nothing.
        }

        @Override
        public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
            // Do nothing.
        }

        @Override
        public void onPlayerError(ExoPlaybackException e) {
            // Do nothing.
        }

        @Override
        public void onPositionDiscontinuity(int reason) {
            // Do nothing.
        }

        @Override
        public void onTimelineChanged(Timeline timeline, Object manifest, int reason) {
            // Do nothing.
        }

        @Override
        public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
            updateForCurrentTrackSelections();
        }

        @Override
        public void onPlaybackParametersChanged(PlaybackParameters params) {
            // Do nothing
        }

        @Override
        public void onSeekProcessed() {
            // Do nothing.
        }

        @Override
        public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
            // Do nothing.
        }

        @Override
        public void onRepeatModeChanged(int repeatMode) {
            // Do nothing.
        }
    }
}