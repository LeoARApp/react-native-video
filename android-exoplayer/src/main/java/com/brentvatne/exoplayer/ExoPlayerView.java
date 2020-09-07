package com.brentvatne.exoplayer;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.core.content.ContextCompat;

import com.daasuu.epf.CustomEPlayerView;
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
    private boolean filterEnabled = false;
    private int angle = 0;

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

    public void setRotationAngle(final int angle) {
        int calculatedAngle = Utils.calculateAngle(angle);
        if (this.angle != calculatedAngle) {
            this.angle = calculatedAngle;
            bind();
        }
    }

    /**
     * @param filterEnabled attach OpenGL or TextureView
     */
    public void enableFilter(boolean filterEnabled) {
        if (this.filterEnabled != filterEnabled) {
            this.filterEnabled = filterEnabled;
            bind();
        }
    }

    private void setVideoView() {
        if (surfaceView instanceof TextureView) {
            if (angle == 90 || angle == -90) {
                (surfaceView).post(rotate);
                layout.setAspectRatio((float) 16/9);
            }
            player.setVideoTextureView((TextureView) surfaceView);
        }else if (surfaceView instanceof CustomEPlayerView) {
            if (angle == 90 || angle == -90) {
                layout.setAspectRatio(1f);
            }
            ((CustomEPlayerView) surfaceView).setup(this.angle);
            ((CustomEPlayerView) surfaceView).setSimpleExoPlayer(player);
        }
    }

    private void bind() {
        layout.removeViewAt(0);
        surfaceView = null;
        surfaceView = filterEnabled ? new CustomEPlayerView(context) : new TextureView(context);
        surfaceView.setLayoutParams(layoutParams);
        layout.addView(surfaceView, 0, layoutParams);
        setVideoView();
    }

    /**
     * generate a bitmap for apply filter to CustomEPlayerView
     * @param name is rawSrc of lookup filter
     */
    public void setFilterRawResourceName(final String name) {
        int resourceId = context.getResources().getIdentifier(name, "raw", context.getPackageName());
        Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), resourceId);
        GlFilter filter;

        if (bitmap != null) {
            filter = new GlLut512Filter(bitmap);
        } else {
            filter = new GlFilter();
        }

        if (!(surfaceView instanceof CustomEPlayerView)) {
            return;
        }

        ((CustomEPlayerView) surfaceView).setGlFilter(new GlFilter()); // reset filter before attach to new
        ((CustomEPlayerView) surfaceView).setGlFilter(filter);
    }


    /**
     * Sets the resize mode which can be of value {@link ResizeMode.Mode}
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

    private final Runnable measureAndLayout = () -> {
        measure(MeasureSpec.makeMeasureSpec(getWidth(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(getHeight(), MeasureSpec.EXACTLY));
        layout(getLeft(), getTop(), getRight(), getBottom());
    };

    private final Runnable rotate = () -> {
        int width = surfaceView.getWidth();
        int height = surfaceView.getHeight();
        float pivotX = (float) width / 2;
        float pivotY = (float) height / 2;
        Matrix matrix = new Matrix();
        matrix.postRotate(angle, pivotX, pivotY);

        RectF originalTextureRect = new RectF(0, 0, width, height);
        RectF rotatedTextureRect = new RectF();
        matrix.mapRect(rotatedTextureRect, originalTextureRect);
        matrix.postScale(width / rotatedTextureRect.width(), height / rotatedTextureRect.height(), pivotX, pivotY);

        ((TextureView) surfaceView).setTransform(matrix);
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

    private final class ComponentListener implements
            SimpleExoPlayer.VideoListener,
            TextRenderer.Output,
            ExoPlayer.EventListener {

        // TextRenderer.Output implementation

        @Override
        public void onCues(List<Cue> cues) {
            subtitleLayout.onCues(cues);
        }

        // SimpleExoPlayer.VideoListener implementation

        @Override
        public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
            if (angle == 0) {
                boolean isInitialRatio = layout.getAspectRatio() == 0;
                layout.setAspectRatio(height == 0 ? 1 : (width * pixelWidthHeightRatio) / height);
                // React native workaround for measuring and layout on initial load.
                if (isInitialRatio) {
                    post(measureAndLayout);
                }
            }
        }

        @Override
        public void onRenderedFirstFrame() {
            shutterView.setVisibility(INVISIBLE);
        }

        // ExoPlayer.EventListener implementation

        @Override
        public void onLoadingChanged(boolean isLoading) { }

        @Override
        public void onPlayerStateChanged(boolean playWhenReady, int playbackState) { }

        @Override
        public void onPlayerError(ExoPlaybackException e) { }

        @Override
        public void onPositionDiscontinuity(int reason) { }

        @Override
        public void onTimelineChanged(Timeline timeline, Object manifest, int reason) { }

        @Override
        public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) { updateForCurrentTrackSelections(); }

        @Override
        public void onPlaybackParametersChanged(PlaybackParameters params) { }

        @Override
        public void onSeekProcessed() { }

        @Override
        public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) { }

        @Override
        public void onRepeatModeChanged(int repeatMode) { }
    }
}
