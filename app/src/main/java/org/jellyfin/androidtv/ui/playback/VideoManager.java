package org.jellyfin.androidtv.ui.playback;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Handler;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.TracksInfo;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.extractor.ts.TsExtractor;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.trackselection.TrackSelectionOverrides;
import com.google.android.exoplayer2.trackselection.TrackSelectionParameters;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.common.collect.ImmutableSet;

import org.jellyfin.androidtv.R;
import org.jellyfin.androidtv.preference.UserPreferences;
import org.jellyfin.androidtv.util.Utils;
import org.jellyfin.apiclient.model.dto.MediaSourceInfo;
import org.jellyfin.apiclient.model.entities.MediaStream;
import org.jellyfin.apiclient.model.entities.MediaStreamType;
import org.koin.java.KoinJavaComponent;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.interfaces.IVLCVout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import timber.log.Timber;

public class VideoManager implements IVLCVout.OnNewVideoLayoutListener {
    public final static int ZOOM_FIT = 0;
    public final static int ZOOM_AUTO_CROP = 1;
    public final static int ZOOM_STRETCH = 2;

    private int mZoomMode = ZOOM_FIT;

    private PlaybackOverlayActivity mActivity;
    private PlaybackControllerNotifiable mPlaybackControllerNotifiable;
    private SurfaceHolder mSurfaceHolder;
    private SurfaceView mSurfaceView;
    private SurfaceView mSubtitlesSurface;
    private FrameLayout mSurfaceFrame;
    private ExoPlayer mExoPlayer;
    private PlayerView mExoPlayerView;
    private LibVLC mLibVLC;
    private org.videolan.libvlc.MediaPlayer mVlcPlayer;
    private Media mCurrentMedia;
    private VlcEventHandler mVlcHandler = new VlcEventHandler();
    private Handler mHandler = new Handler();
    private int mVideoHeight;
    private int mVideoWidth;
    private int mVideoVisibleHeight;
    private int mVideoVisibleWidth;
    private int mSarNum;
    private int mSarDen;
    private Integer exoplayerAudioIndex = null;

    private long mForcedTime = -1;
    private long mLastTime = -1;
    private long mMetaDuration = -1;
    private long mMetaVLCStreamStartPosition = -1;
    private long lastExoPlayerPosition = -1;

    private boolean nativeMode = false;
    private boolean mSurfaceReady = false;
    public boolean isContracted = false;

    public VideoManager(PlaybackOverlayActivity activity, View view) {
        mActivity = activity;
        mSurfaceView = view.findViewById(R.id.player_surface);
        mSurfaceHolder = mSurfaceView.getHolder();
        mSurfaceHolder.addCallback(mSurfaceCallback);
        mSurfaceFrame = view.findViewById(R.id.player_surface_frame);
        mSubtitlesSurface = view.findViewById(R.id.subtitles_surface);
        mSubtitlesSurface.setZOrderMediaOverlay(true);
        mSubtitlesSurface.getHolder().setFormat(PixelFormat.TRANSLUCENT);

        mExoPlayer = configureExoplayerBuilder(activity).build();
        mExoPlayer.setTrackSelectionParameters(mExoPlayer.getTrackSelectionParameters()
                                                            .buildUpon()
                                                            .setDisabledTrackTypes(ImmutableSet.of(C.TRACK_TYPE_TEXT))
                                                            .build());

        mExoPlayerView = view.findViewById(R.id.exoPlayerView);
        mExoPlayerView.setPlayer(mExoPlayer);
        mExoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Timber.e("***** Got error from player");
                if (mPlaybackControllerNotifiable != null) mPlaybackControllerNotifiable.onError();
                stopProgressLoop();
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) {
                    if (mPlaybackControllerNotifiable != null) mPlaybackControllerNotifiable.onPrepared();
                    startProgressLoop();
                } else {
                    stopProgressLoop();
                }
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_BUFFERING) {
                    Timber.d("Player is buffering");
                }

                if (playbackState == Player.STATE_ENDED) {
                    if (mPlaybackControllerNotifiable != null) mPlaybackControllerNotifiable.onCompletion();
                    stopProgressLoop();
                }
            }

            @Override
            public void onPlaybackParametersChanged(@NonNull PlaybackParameters playbackParameters) {
                if (mPlaybackControllerNotifiable != null){
                    mPlaybackControllerNotifiable.onPlaybackSpeedChange(playbackParameters.speed);
                }
            }

            @Override
            public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition, @NonNull Player.PositionInfo newPosition, int reason) {
                // discontinuity for reason internal usually indicates an error, and that the player will reset to its default timestamp
                if (reason == Player.DISCONTINUITY_REASON_INTERNAL) {
                    Timber.d("Caught player discontinuity (reason internal) - oldPos: %s newPos: %s", oldPosition.positionMs, newPosition.positionMs);
                }
            }

            @Override
            public void onTimelineChanged(@NonNull Timeline timeline, int reason) {
                Timber.d("Caught player timeline change - reason: %s", reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED ? "PLAYLIST_CHANGED" : "SOURCE_UPDATE");
            }

            @Override
            public void onTracksInfoChanged(TracksInfo tracksInfo) {
                Timber.d("Tracks info changed");
                exoplayerAudioIndex = null;
            }
        });
    }

    public void subscribe(@NonNull PlaybackControllerNotifiable notifier){
        mPlaybackControllerNotifiable = notifier;
        setupVLCListeners();
    }

    /**
     * Configures Exoplayer for video playback. Initially we try with core decoders, but allow
     * ExoPlayer to silently fallback to software renderers.
     *
     * @param context The associated context
     * @return A configured builder for Exoplayer
     */
    private ExoPlayer.Builder configureExoplayerBuilder(Context context) {
        ExoPlayer.Builder exoPlayerBuilder = new ExoPlayer.Builder(context);
        DefaultRenderersFactory defaultRendererFactory = new DefaultRenderersFactory(context);
        defaultRendererFactory.setEnableDecoderFallback(true);
        defaultRendererFactory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
        exoPlayerBuilder.setRenderersFactory(defaultRendererFactory);

        DefaultExtractorsFactory defaultExtractorsFactory = new DefaultExtractorsFactory().setTsExtractorTimestampSearchBytes(TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES * 3);
        exoPlayerBuilder.setMediaSourceFactory(new DefaultMediaSourceFactory(context, defaultExtractorsFactory));

        return exoPlayerBuilder;
    }

    public boolean isInitialized() {
        return mSurfaceReady && (isNativeMode() ? mExoPlayer != null : mVlcPlayer != null);
    }

    public void init(int buffer, boolean isInterlaced) {
        createPlayer(buffer, isInterlaced);
    }

    public void setNativeMode(boolean value) {
        nativeMode = value;
        if (nativeMode) {
            mExoPlayerView.setVisibility(View.VISIBLE);
        } else {
            mExoPlayerView.setVisibility(View.GONE);
        }
    }

    public boolean isNativeMode() {
        return nativeMode;
    }

    public int getZoomMode() {
        return mZoomMode;
    }

    public void setZoom(int mode) {
        mZoomMode = mode;
        switch (mode) {
            case ZOOM_FIT:
                mExoPlayerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
                break;
            case ZOOM_AUTO_CROP:
                mExoPlayerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
                break;
            case ZOOM_STRETCH:
                mExoPlayerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
                break;
        }
    }

    // set by playbackController when a new vlc transcode stream starts, and before seeking
    public void setMetaVLCStreamStartPosition(long value) {
        mMetaVLCStreamStartPosition = value;
    }

    public long getMetaVLCStreamStartPosition() {
        return mMetaVLCStreamStartPosition;
    }

    public void setMetaDuration(long duration) {
        mMetaDuration = duration;
    }

    public long getDuration() {
        if (nativeMode) {
            return isInitialized() && mExoPlayer.getDuration() > 0 ? mExoPlayer.getDuration() : mMetaDuration;
        } else {
            return isInitialized() && mVlcPlayer.getLength() > 0 ? mVlcPlayer.getLength() : mMetaDuration;
        }
    }

    public long getBufferedPosition() {
        if (!isInitialized())
            return -1;

        // only exoplayer supports reporting buffered position
        if (!isNativeMode())
            return -1;

        long bufferedPosition = mExoPlayer.getBufferedPosition();

        if (bufferedPosition > -1 && bufferedPosition < getDuration()) {
            return bufferedPosition;
        }
        return -1;
    }

    public long getCurrentPosition() {
        if (nativeMode) {
            if (mExoPlayer == null || !isPlaying()) {
                return lastExoPlayerPosition == -1 ? 0 : lastExoPlayerPosition;
            } else {
                long mExoPlayerCurrentPosition = mExoPlayer.getCurrentPosition();
                lastExoPlayerPosition = mExoPlayerCurrentPosition;
                return mExoPlayerCurrentPosition;
            }
        }

        if (mVlcPlayer == null) return 0;

        long time = mVlcPlayer.getTime();

        // vlc returns ms from stream start. metaStartPosition + time = actual position
        time = mMetaVLCStreamStartPosition != -1 ? time + getMetaVLCStreamStartPosition() : time;
        if (mForcedTime != -1 && mLastTime != -1) {
            /* XXX: After a seek, mLibVLC.getTime can return the position before or after
             * the seek position. Therefore we return mForcedTime in order to avoid the seekBar
             * to move between seek position and the actual position.
             * We have to wait for a valid position (that is after the seek position).
             * to re-init mLastTime and mForcedTime to -1 and return the actual position.
             */
            if (mLastTime > mForcedTime) {
                if (time <= mLastTime && time > mForcedTime)
                    mLastTime = mForcedTime = -1;
            } else {
                if (time > mForcedTime)
                    mLastTime = mForcedTime = -1;
            }
        }
        return mForcedTime == -1 ? time : mForcedTime;
    }

    public boolean isPlaying() {
        return nativeMode ? mExoPlayer.isPlaying() : mVlcPlayer != null && mVlcPlayer.isPlaying();
    }

    public void start() {
        if (nativeMode) {
            if (mExoPlayer == null) {
                Timber.e("mExoPlayer should not be null!!");
                mActivity.finish();
                return;
            }
            exoplayerAudioIndex = null;
            mExoPlayer.setPlayWhenReady(true);
            normalWidth = mExoPlayerView.getLayoutParams().width;
            normalHeight = mExoPlayerView.getLayoutParams().height;
        } else {
            if (!mSurfaceReady) {
                Timber.e("Attempt to play before surface ready");
                return;
            }
            if (!mVlcPlayer.isPlaying()) {
                mVlcPlayer.play();
            }
        }
    }

    public void play() {
        if (nativeMode) {
            mExoPlayer.setPlayWhenReady(true);
        } else {
            mVlcPlayer.play();
        }
    }

    public void pause() {
        if (nativeMode) {
            mExoPlayer.setPlayWhenReady(false);
        } else {
            mVlcPlayer.pause();
        }
    }

    public void stopPlayback() {
        if (nativeMode && mExoPlayer != null) {
            mExoPlayer.stop();
            disableSubs();

            TrackSelectionOverrides overrides = mExoPlayer.getTrackSelectionParameters().trackSelectionOverrides.buildUpon()
                                                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                                                    .build();
            mExoPlayer.setTrackSelectionParameters(mExoPlayer.getTrackSelectionParameters()
                                                    .buildUpon()
                                                    .setTrackSelectionOverrides(overrides).build());
            exoplayerAudioIndex = null;
        } else if (mVlcPlayer != null) {
            mVlcPlayer.stop();
        }
        stopProgressLoop();
    }

    public boolean isSeekable() {
        if (!isInitialized())
            return false;
        boolean canSeek = false;
        if (isNativeMode())
            canSeek = mExoPlayer.isCurrentMediaItemSeekable();
        else {
            canSeek = mVlcPlayer.isSeekable() && mVlcPlayer.getLength() > 0;
        }
        Timber.d("current media item is%s seekable", canSeek ? "" : " not");
        return canSeek;
    }

    public long seekTo(long pos) {
        if (!isInitialized())
            return -1;
        if (nativeMode) {
            Timber.i("Exo length in seek is: %d", getDuration());
            mExoPlayer.seekTo(pos);
            return pos;
        } else {
            if (mVlcPlayer == null || !mVlcPlayer.isSeekable()) return -1;
            mForcedTime = pos;
            mLastTime = mVlcPlayer.getTime();
            Timber.i("VLC length in seek is: %d", getDuration());
            try {
                if (getDuration() > 0) mVlcPlayer.setPosition((float) pos / getDuration());
                else mVlcPlayer.setTime(pos);

                return pos;

            } catch (Exception e) {
                Timber.e(e, "Error seeking in VLC");
                Utils.showToast(mActivity, mActivity.getString(R.string.seek_error));
                return -1;
            }
        }
    }

    public void setVideoPath(@Nullable String path) {
        if (path == null) {
            Timber.w("Video path is null cannot continue");
            return;
        }
        Timber.i("Video path set to: %s", path);

        if (nativeMode) {
            try {
                mExoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(path)));
                mExoPlayer.prepare();
            } catch (IllegalStateException e) {
                Timber.e(e, "Unable to set video path.  Probably backing out.");
            }
        } else {
            mCurrentMedia = new Media(mLibVLC, Uri.parse(path));
            mCurrentMedia.parse();
            mVlcPlayer.setMedia(mCurrentMedia);

            mCurrentMedia.release();
        }
    }

    public void disableSubs() {
        if (!isInitialized())
            return;
        if (isNativeMode()) {
            mExoPlayer.setTrackSelectionParameters(mExoPlayer.getTrackSelectionParameters()
                                                    .buildUpon()
                                                    .setDisabledTrackTypes(ImmutableSet.of(C.TRACK_TYPE_TEXT))
                                                    .build());
        } else {
            mVlcPlayer.setSpuTrack(-1);
        }
    }

    public boolean setSubtitleTrack(int index, @Nullable List<MediaStream> allStreams) {
        if (!nativeMode && allStreams != null) {
            //find the relative order of our sub index within the sub tracks in VLC
            int vlcIndex = 1; // start at 1 to account for "disabled"
            for (MediaStream stream : allStreams) {
                if (stream.getType() == MediaStreamType.Subtitle && !stream.getIsExternal()) {
                    if (stream.getIndex() == index) {
                        break;
                    }
                    vlcIndex++;
                }
            }

            org.videolan.libvlc.MediaPlayer.TrackDescription vlcSub;
            try {
                vlcSub = getSubtitleTracks()[vlcIndex];

            } catch (IndexOutOfBoundsException e) {
                Timber.e("Could not locate subtitle with index %s in vlc track info", index);
                return false;
            } catch (NullPointerException e) {
                Timber.e("No subtitle tracks found in player trying to set subtitle with index %s in vlc track info", index);
                return false;
            }

            Timber.i("Setting Vlc sub to %s", vlcSub.name);
            return mVlcPlayer.setSpuTrack(vlcSub.id);

        }

        return false;
    }

    public int getExoPlayerTrack(@Nullable MediaStreamType streamType, @Nullable List<MediaStream> allStreams) {
        if (!nativeMode || !isInitialized() || streamType == null || allStreams == null)
            return -1;
        if (streamType != MediaStreamType.Subtitle && streamType != MediaStreamType.Audio)
            return -1;

        if (exoplayerAudioIndex != null && streamType == MediaStreamType.Audio)
            return exoplayerAudioIndex;

        int chosenTrackType = streamType == MediaStreamType.Subtitle ? C.TRACK_TYPE_TEXT : C.TRACK_TYPE_AUDIO;

        TracksInfo exoTracks = mExoPlayer.getCurrentTracksInfo();
        for (TracksInfo.TrackGroupInfo groupInfo : exoTracks.getTrackGroupInfos()) {
            // Group level information.
            @C.TrackType int trackType = groupInfo.getTrackType();
            TrackGroup group = groupInfo.getTrackGroup();
            for (int i = 0; i < group.length; i++) {
                // Individual track information.
                Format trackFormat = group.getFormat(i);
                if (trackType == chosenTrackType) {
                    if (groupInfo.isTrackSelected(i)) {
                        if (trackFormat.id != null) {
                            int id;
                            try {
                                id = Integer.parseInt(trackFormat.id) - 1;
                            } catch (NumberFormatException e) {
                                Timber.d("failed to parse track ID [%s]", trackFormat.id);
                                return -1;
                            }
                            if (id >= 0 && id < allStreams.size()) {
                                Timber.d("re-retrieved exoplayer track index %s", id);
                                if (streamType == MediaStreamType.Audio)
                                    exoplayerAudioIndex = id;
                                return id;
                            }
                        }
                        return -1;
                    }
                }
            }
        }
        return -1;
    }

    public boolean setExoPlayerTrack(int index, @Nullable MediaStreamType streamType, @Nullable List<MediaStream> allStreams) {
        if (!nativeMode || !isInitialized() || streamType == null || allStreams == null || index < 0 || index >= allStreams.size())
            return false;
        if (streamType != MediaStreamType.Subtitle && streamType != MediaStreamType.Audio)
            return false;

        int chosenTrackType = streamType == MediaStreamType.Subtitle ? C.TRACK_TYPE_TEXT : C.TRACK_TYPE_AUDIO;

        TracksInfo exoTracks = mExoPlayer.getCurrentTracksInfo();

        List<String> internallyRenderedSubFormats = Arrays.asList("ass", "ssa");

        // make sure the chosen track exists, is the correct type, and isn't external
        boolean isValidMediaStream = false;
        for (MediaStream stream : allStreams) {
            Timber.d("MediaStream track %s type %s label %s codec %s isExternal %s", stream.getIndex(), stream.getType(), stream.getTitle(), stream.getCodec(), stream.getIsExternal());
            if (stream.getType() == streamType) {
                if (stream.getIndex() == index) {
                    if (stream.getIsExternal())
                        continue;
                    if (streamType == MediaStreamType.Subtitle && !internallyRenderedSubFormats.contains(stream.getCodec())) {
                        continue;
                    }
                    isValidMediaStream = true;
                }
            }
        }

        if (!isValidMediaStream)
            return false;

        // MediaStream IDs start at 0 and exoplayer tracks start at 1
        // force selection of the chosen track by setting its TrackGroup as the only allowed group for its type

        // design choices for exoplayer track selection overrides:
        // * build upon the prior parameters so we can mix overrides of different track types without erasing priors
        //
        // * for subtitles (not currently used) - use setDisabledTrackTypes to disable or enable exoplayer handing subtitles
        //   if we want most formats to be handled by the external subtitle handler (which has adjustable size, background), we leave sub track selection disabled
        //   if we decide to use exoplayer to render a specific subtitle format, allow subtitle track selection and restrict selection to the chosen group

        int exoTrackID = index + 1;

        TrackGroup matchedGroup = null;
        for (TracksInfo.TrackGroupInfo groupInfo : exoTracks.getTrackGroupInfos()) {
            // Group level information.
            @C.TrackType int trackType = groupInfo.getTrackType();
            TrackGroup group = groupInfo.getTrackGroup();
            for (int i = 0; i < group.length; i++) {
                // Individual track information.
                boolean isSupported = groupInfo.isTrackSupported(i);
                boolean isSelected = groupInfo.isTrackSelected(i);
                Format trackFormat = group.getFormat(i);

                Timber.d("track %s group %s/%s trackType %s label %s mime %s isSelected %s isSupported %s",
                        trackFormat.id, i+1, group.length, trackType, trackFormat.label, trackFormat.sampleMimeType, isSelected, isSupported);

                if (trackType != chosenTrackType || trackFormat.id == null)
                    continue;

                int id;
                try {
                    id = Integer.parseInt(trackFormat.id);
                    if (id != exoTrackID)
                        continue;
                } catch (NumberFormatException e) {
                    Timber.d("failed to parse track ID [%s]", trackFormat.id);
                    continue;
                }

                if (groupInfo.isTrackSelected(i) || !groupInfo.isTrackSupported(i)) {
                    Timber.d("track is %s", groupInfo.isTrackSelected(i) ? "already selected" : "not compatible");
                    return false;
                }

                Timber.d("matched exoplayer track %s to mediaStream track %s", trackFormat.id, index);
                matchedGroup = group;
            }
        }

        if (matchedGroup == null)
            return false;

        try {
            TrackSelectionOverrides overrides = mExoPlayer.getTrackSelectionParameters().trackSelectionOverrides.buildUpon()
                    .setOverrideForType(new TrackSelectionOverrides.TrackSelectionOverride(matchedGroup))
                    .build();
            TrackSelectionParameters.Builder mExoPlayerSelectionParams = mExoPlayer.getTrackSelectionParameters().buildUpon();
            if (streamType == MediaStreamType.Subtitle)
                mExoPlayerSelectionParams.setDisabledTrackTypes(ImmutableSet.of(C.TRACK_TYPE_NONE));
            mExoPlayerSelectionParams.setTrackSelectionOverrides(overrides);
            mExoPlayer.setTrackSelectionParameters(mExoPlayerSelectionParams.build());
        } catch (Exception e) {
            Timber.d("Error setting track selection");
            return false;
        }
        return true;
    }

    public int getVLCAudioTrack() {
        if (!isInitialized() || nativeMode)
            return -1;
        return mVlcPlayer.getAudioTrack();
    }

    public int setVLCAudioTrack(int ndx) {
        if (!isInitialized() || ndx < 0)
            return -1;

        if (isNativeMode()) {
            Timber.e("Cannot set audio track in native mode");
            return -1;
        }

        //debug
        Timber.d("Setting VLC audio track index to: %d", ndx);
        for (org.videolan.libvlc.MediaPlayer.TrackDescription track : mVlcPlayer.getAudioTracks()) {
            Timber.d("VLC Audio Track: %s / %d", track.name, track.id);
        }
        //

        boolean matched = false;
        for (org.videolan.libvlc.MediaPlayer.TrackDescription track : mVlcPlayer.getAudioTracks()) {
            if (track.id == ndx) {
                matched = true;
                break;
            }
        }

        org.videolan.libvlc.MediaPlayer.TrackDescription vlcTrack = null;

        if (matched) {
            try {
                vlcTrack = mVlcPlayer.getAudioTracks()[ndx];
            } catch (IndexOutOfBoundsException e) {
                Timber.e("Could not locate audio with index %s in vlc track info", ndx);
                return -1;
            } catch (NullPointerException e) {
                Timber.e("Could not locate audio track with index %s in vlc, null exception", ndx);
                return -1;
            }
        }

        if (!matched || vlcTrack == null) {
            Timber.e("Could not locate audio track with index %s in vlc", ndx);
            return -1;
        } else if (mVlcPlayer.getAudioTrack() == ndx) {
            Timber.d("provided index points to the audio track already in use, aborting");
            return -1;
        }

        if (mVlcPlayer.setAudioTrack(vlcTrack.id)) {
            Timber.i("Setting by ID was successful");
        } else {
            Timber.i("Setting by ID not successful, trying index");
            mVlcPlayer.setAudioTrack(ndx);
        }
        return ndx;
    }

    public float getPlaybackSpeed(){
        if (!isInitialized()) {
            return 1.0f;
        } else if (isNativeMode()){
            return mExoPlayer.getPlaybackParameters().speed;
        } else {
            return mVlcPlayer.getRate();
        }
    }

    public void setPlaybackSpeed(float speed) {
        if (speed < 0.25) {
            Timber.w("Invalid playback speed requested: %f", speed);
            return;
        }
        Timber.d("Setting playback speed: %f", speed);

        if (nativeMode) {
            mExoPlayer.setPlaybackParameters(new PlaybackParameters(speed));
        } else {
            mVlcPlayer.setRate(speed);
            // VLC will always change rate, so we can post this immediately
            if (mPlaybackControllerNotifiable != null){
                mPlaybackControllerNotifiable.onPlaybackSpeedChange(speed);
            }
        }
    }

    public void setAudioDelay(long value) {
        if (!nativeMode && mVlcPlayer != null) {
            if (!mVlcPlayer.setAudioDelay(value * 1000)) {
                Timber.e("Error setting audio delay");
            } else {
                Timber.i("Audio delay set to %d", value);
            }
        }
    }

    public long getAudioDelay() {
        return mVlcPlayer != null ? mVlcPlayer.getAudioDelay() / 1000 : 0;
    }

    public void setCompatibleAudio() {
        if (!nativeMode) {
            mVlcPlayer.setAudioOutput("opensles_android");
            mVlcPlayer.setAudioOutputDevice("hdmi");
        }
    }

    public void setAudioMode() {
        if (!nativeMode) {
            setVlcAudioOptions();
        }
    }

    private void setVlcAudioOptions() {
        if (!Utils.downMixAudio(mActivity)) {
            mVlcPlayer.setAudioDigitalOutputEnabled(true);
        } else {
            setCompatibleAudio();
        }
    }

    public void setVideoTrack(MediaSourceInfo mediaSource) {
        if (!nativeMode && mediaSource != null && mediaSource.getMediaStreams() != null) {
            for (MediaStream stream : mediaSource.getMediaStreams()) {
                if (stream.getType() == MediaStreamType.Video && stream.getIndex() >= 0) {
                    Timber.d("Setting video index to: %d", stream.getIndex());
                    mVlcPlayer.setVideoTrack(stream.getIndex());
                    return;
                }
            }
        }
    }

    public org.videolan.libvlc.MediaPlayer.TrackDescription[] getSubtitleTracks() {
        return nativeMode ? null : mVlcPlayer.getSpuTracks();
    }

    public void destroy() {
        mPlaybackControllerNotifiable = null;
        stopPlayback();
        releasePlayer();
    }

    private void createPlayer(int buffer, boolean isInterlaced) {
        if (isInitialized())
            return;

        try {
            // Create a new media player
            ArrayList<String> options = new ArrayList<>(20);
            options.add("--network-caching=" + buffer);
            options.add("--audio-time-stretch");
            options.add("--avcodec-skiploopfilter");
            options.add("1");
            options.add("--avcodec-skip-frame");
            options.add("0");
            options.add("--avcodec-skip-idct");
            options.add("0");
            options.add("--android-display-chroma");
            options.add("RV32");
            options.add("--audio-resampler");
            options.add("soxr");
            options.add("--stats");
            if (isInterlaced) {
                options.add("--video-filter=deinterlace");
                options.add("--deinterlace-mode=Bob");
            }
//            options.add("--subsdec-encoding");
//            options.add("Universal (UTF-8)");
            options.add("--audio-desync");
            options.add(String.valueOf(KoinJavaComponent.<UserPreferences>get(UserPreferences.class).get(UserPreferences.Companion.getLibVLCAudioDelay())));
            options.add("-v");
            options.add("--vout=android-opaque,android-display");

            mLibVLC = new LibVLC(mActivity, options);
            Timber.i("Network buffer set to %d", buffer);

            mVlcPlayer = new org.videolan.libvlc.MediaPlayer(mLibVLC);
            setVlcAudioOptions();

            mSurfaceHolder.addCallback(mSurfaceCallback);
            mVlcPlayer.setEventListener(mVlcHandler);

            //setup surface
            mVlcPlayer.getVLCVout().detachViews();
            mVlcPlayer.getVLCVout().setVideoView(mSurfaceView);
            mVlcPlayer.getVLCVout().setSubtitlesView(mSubtitlesSurface);
            mVlcPlayer.getVLCVout().attachViews(this);
            Timber.d("Surface attached");
            mSurfaceReady = true;
        } catch (Exception e) {
            Timber.e(e, "Error creating VLC player");
            Utils.showToast(mActivity, mActivity.getString(R.string.msg_video_playback_error));
        }
    }

    private void releasePlayer() {
        if (mVlcPlayer != null) {
            mVlcPlayer.setEventListener(null);
            mVlcPlayer.stop();
            mVlcPlayer.getVLCVout().detachViews();
            mVlcPlayer.release();
            mLibVLC.release();
            mLibVLC = null;
            mVlcPlayer = null;
        }

        if (mExoPlayer != null) {
            mExoPlayerView.setPlayer(null);
            mExoPlayer.release();
            mExoPlayer = null;
        }
    }

    int normalWidth;
    int normalHeight;

    public void contractVideo(int height) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) (nativeMode ? mExoPlayerView.getLayoutParams() : mSurfaceView.getLayoutParams());
        if (isContracted) return;

        int sw = mActivity.getWindow().getDecorView().getWidth();
        int sh = mActivity.getWindow().getDecorView().getHeight();
        float ar = (float) sw / sh;
        lp.height = height;
        lp.width = (int) Math.ceil(height * ar);
        lp.rightMargin = ((lp.width - normalWidth) / 2) - 110;
        lp.bottomMargin = ((lp.height - normalHeight) / 2) - 50;

        if (nativeMode) {
            mExoPlayerView.setLayoutParams(lp);
            mExoPlayerView.invalidate();
        } else mSurfaceView.setLayoutParams(lp);

        isContracted = true;

    }

    public void setVideoFullSize(boolean force) {
        if (normalHeight == 0) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) (nativeMode ? mExoPlayerView.getLayoutParams() : mSurfaceView.getLayoutParams());
        if (force) {
            lp.height = -1;
            lp.width = -1;
        } else {
            lp.height = normalHeight;
            lp.width = normalWidth;
        }
        if (nativeMode) {
            lp.rightMargin = 0;
            lp.bottomMargin = 0;
            mExoPlayerView.setLayoutParams(lp);
            mExoPlayerView.invalidate();
        } else mSurfaceView.setLayoutParams(lp);

        isContracted = false;

    }

    private void changeSurfaceLayout(int videoWidth, int videoHeight, int videoVisibleWidth, int videoVisibleHeight, int sarNum, int sarDen) {
        int sw;
        int sh;

        // get screen size
        if (mActivity == null) return; //called during destroy
        sw = mActivity.getWindow().getDecorView().getWidth();
        sh = mActivity.getWindow().getDecorView().getHeight();

        double dw = sw, dh = sh;
        boolean isPortrait;

        isPortrait = mActivity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;

        if (sw > sh && isPortrait || sw < sh && !isPortrait) {
            dw = sh;
            dh = sw;
        }

        // sanity check
        if (dw * dh == 0 || videoWidth * videoHeight == 0) {
            Timber.e("Invalid surface size");
            return;
        }

        // compute the aspect ratio
        double ar;
        if (sarDen == sarNum) {
            /* No indication about the density, assuming 1:1 */
            ar = (double) videoVisibleWidth / (double) videoVisibleHeight;
        } else {
            /* Use the specified aspect ratio */
            double vw = videoVisibleWidth * (double) sarNum / sarDen;
            ar = vw / videoVisibleHeight;
        }

        // compute the display aspect ratio
        double dar = dw / dh;

        if (dar < ar)
            dh = dw / ar;
        else
            dw = dh * ar;

        // set display size
        ViewGroup.LayoutParams lp = mSurfaceView.getLayoutParams();
        lp.width = (int) Math.ceil(dw * videoWidth / videoVisibleWidth);
        lp.height = (int) Math.ceil(dh * videoHeight / videoVisibleHeight);
        normalWidth = lp.width;
        normalHeight = lp.height;
        mSurfaceView.setLayoutParams(lp);
        mSubtitlesSurface.setLayoutParams(lp);

        // set frame size (crop if necessary)
        if (mSurfaceFrame != null) {
            lp = mSurfaceFrame.getLayoutParams();
            lp.width = (int) Math.floor(dw);
            lp.height = (int) Math.floor(dh);
            mSurfaceFrame.setLayoutParams(lp);

        }

        Timber.d("Surface sized %d x %d ", lp.width, lp.height);
        mSurfaceView.invalidate();
        mSubtitlesSurface.invalidate();
    }

    private void setupVLCListeners() {
        mVlcHandler.setOnCompletionListener(() -> {
            if (mPlaybackControllerNotifiable != null) {
                mPlaybackControllerNotifiable.onCompletion();
            }
        });
        mVlcHandler.setOnErrorListener(() -> {
            if (mPlaybackControllerNotifiable != null) {
                mPlaybackControllerNotifiable.onError();
            }
        });
        mVlcHandler.setOnPreparedListener(() -> {
            if (mPlaybackControllerNotifiable != null) {
                mPlaybackControllerNotifiable.onPrepared();
            }
        });
        mVlcHandler.setOnProgressListener(() -> {
            if (mPlaybackControllerNotifiable != null) {
                mPlaybackControllerNotifiable.onProgress();
            }
        });
    }

    private Runnable progressLoop;
    private void startProgressLoop() {
        stopProgressLoop();
        progressLoop = new Runnable() {
            @Override
            public void run() {
                if (mPlaybackControllerNotifiable != null) mPlaybackControllerNotifiable.onProgress();
                mHandler.postDelayed(this, 500);
            }
        };
        mHandler.post(progressLoop);
    }

    private void stopProgressLoop() {
        if (progressLoop != null) {
            mHandler.removeCallbacks(progressLoop);
        }
    }

    private SurfaceHolder.Callback mSurfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {

        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            if (mVlcPlayer != null) mVlcPlayer.getVLCVout().detachViews();
            mSurfaceReady = false;

        }
    };

    @Override
    public void onNewVideoLayout(IVLCVout vout, int width, int height, int visibleWidth, int visibleHeight, int sarNum, int sarDen) {
        if (width * height == 0 || isContracted)
            return;

        // store video size
        mVideoHeight = height;
        mVideoWidth = width;
        mVideoVisibleHeight = visibleHeight;
        mVideoVisibleWidth = visibleWidth;
        mSarNum = sarNum;
        mSarDen = sarDen;

        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                changeSurfaceLayout(mVideoWidth, mVideoHeight, mVideoVisibleWidth, mVideoVisibleHeight, mSarNum, mSarDen);
            }
        });
    }
}
