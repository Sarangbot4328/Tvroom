package com.tvroom.downloader.ui;

import android.app.PictureInPictureParams;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.ui.PlayerView;

import com.tvroom.downloader.R;

import java.io.File;

@UnstableApi
public final class PlayerActivity extends AppCompatActivity {
    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_TITLE = "title";
    private static final String POSITION_PREFS = "video_playback_positions";
    private static final long FINISHED_MARGIN_MS = 10_000L;

    private ExoPlayer player;
    private PlayerView playerView;
    private LinearLayout actions;
    private ImageButton pipButton, closeButton, lockButton;
    private View lockTouchGuard;
    private String mediaPath;
    private boolean immersive;
    private boolean inPictureInPicture;
    private boolean controlsLocked;
    private final Runnable hideLockedActions = () -> {
        if (controlsLocked && actions != null && actions.getVisibility() == View.VISIBLE) {
            actions.animate().cancel();
            actions.animate().alpha(0f).setDuration(160)
                    .withEndAction(() -> {
                        if (controlsLocked) actions.setVisibility(View.GONE);
                    }).start();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_player);
        playerView = findViewById(R.id.player_view);
        actions = findViewById(R.id.player_actions);
        pipButton = findViewById(R.id.pip);
        closeButton = findViewById(R.id.close);
        lockButton = findViewById(R.id.screen_lock);
        lockTouchGuard = findViewById(R.id.lock_touch_guard);
        closeButton.setOnClickListener(v -> finish());
        pipButton.setOnClickListener(v -> enterPip());
        lockTouchGuard.setOnClickListener(v -> showLockedActions());
        lockButton.setOnClickListener(v -> {
            String message = !immersive
                    ? "화면 잠금은 전체 화면에서만 사용할 수 있습니다."
                    : controlsLocked
                    ? "잠금을 해제하려면 잠금 아이콘을 길게 누르세요."
                    : "화면을 잠그려면 잠금 아이콘을 길게 누르세요.";
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        });
        lockButton.setOnTouchListener((v, event) -> {
            if (controlsLocked) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    actions.removeCallbacks(hideLockedActions);
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                        || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    actions.postDelayed(hideLockedActions, 3000L);
                }
            }
            return false;
        });
        lockButton.setOnLongClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (!immersive) {
                Toast.makeText(this, "전체 화면으로 전환한 뒤 길게 눌러 주세요.",
                        Toast.LENGTH_SHORT).show();
                return true;
            }
            setControlsLocked(!controlsLocked);
            return true;
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (controlsLocked) {
                    showLockedActions();
                    return;
                }
                finish();
            }
        });
        updateLockAvailability();

        String path = getIntent().getStringExtra(EXTRA_PATH);
        if (path == null || !new File(path).isFile()) {
            Toast.makeText(this, "영상 파일을 찾을 수 없습니다.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        mediaPath = new File(path).getAbsolutePath();

        setTitle(getIntent().getStringExtra(EXTRA_TITLE));
        player = new ExoPlayer.Builder(this).build();
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException error) {
                setKeepScreenOn(false);
                String detail = error.getMessage();
                Toast.makeText(PlayerActivity.this,
                        "영상 재생 오류" + (detail == null ? "" : " · " + detail),
                        Toast.LENGTH_LONG).show();
            }

            @Override public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_ENDED) clearPlaybackPosition();
                updateKeepScreenOn();
            }

            @Override public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                updateKeepScreenOn();
            }
        });
        playerView.setPlayer(player);
        playerView.setControllerShowTimeoutMs(3000);
        playerView.setControllerHideOnTouch(true);
        playerView.setFullscreenButtonClickListener(this::setFullscreen);
        playerView.setControllerVisibilityListener(
                (PlayerView.ControllerVisibilityListener) visibility ->
                        setActionIconsVisible(visibility == View.VISIBLE));
        boolean offlineHls = path.toLowerCase(java.util.Locale.US).endsWith(".m3u8");
        MediaItem.Builder item = new MediaItem.Builder().setUri(Uri.fromFile(new File(path)));
        if (offlineHls) item.setMimeType(MimeTypes.APPLICATION_M3U8);
        MediaItem mediaItem = item.build();
        if (offlineHls) {
            int tsFlags = DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
                    | DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES;
            DefaultHlsExtractorFactory extractorFactory =
                    new DefaultHlsExtractorFactory(tsFlags, true);
            player.setMediaSource(new HlsMediaSource.Factory(new DefaultDataSource.Factory(this))
                    .setExtractorFactory(extractorFactory).createMediaSource(mediaItem));
        } else {
            player.setMediaItem(mediaItem);
        }
        long savedPosition = getSharedPreferences(POSITION_PREFS, MODE_PRIVATE)
                .getLong(mediaPath, 0L);
        if (savedPosition > 0L) player.seekTo(savedPosition);
        player.prepare();
        player.play();
    }

    private void updateKeepScreenOn() {
        if (player == null) {
            setKeepScreenOn(false);
            return;
        }
        int state = player.getPlaybackState();
        setKeepScreenOn(player.getPlayWhenReady()
                && state != Player.STATE_IDLE && state != Player.STATE_ENDED);
    }

    private void setKeepScreenOn(boolean keepScreenOn) {
        if (keepScreenOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void savePlaybackPosition() {
        if (player == null || mediaPath == null) return;
        long position = Math.max(0L, player.getCurrentPosition());
        long duration = player.getDuration();
        boolean finished = player.getPlaybackState() == Player.STATE_ENDED
                || (duration != C.TIME_UNSET && duration > 0L
                && position >= Math.max(0L, duration - FINISHED_MARGIN_MS));
        if (finished || position < 1_000L) {
            clearPlaybackPosition();
        } else {
            getSharedPreferences(POSITION_PREFS, MODE_PRIVATE).edit()
                    .putLong(mediaPath, position).apply();
        }
    }

    private void clearPlaybackPosition() {
        if (mediaPath == null) return;
        clearSavedPosition(this, mediaPath);
    }

    public static void clearSavedPosition(android.content.Context context, String path) {
        if (path == null || path.isEmpty()) return;
        String absolutePath = new File(path).getAbsolutePath();
        context.getSharedPreferences(POSITION_PREFS, MODE_PRIVATE).edit()
                .remove(absolutePath).apply();
    }

    private void setActionIconsVisible(boolean visible) {
        if (controlsLocked) return;
        if (inPictureInPicture) visible = false;
        setActionsVisible(visible);
    }

    private void setActionsVisible(boolean visible) {
        actions.animate().cancel();
        if (visible) {
            actions.setAlpha(0f);
            actions.setVisibility(View.VISIBLE);
            actions.animate().alpha(1f).setDuration(120).start();
        } else if (actions.getVisibility() == View.VISIBLE) {
            actions.animate().alpha(0f).setDuration(120)
                    .withEndAction(() -> actions.setVisibility(View.GONE)).start();
        }
    }

    private void updateLockAvailability() {
        if (lockButton == null) return;
        lockButton.setAlpha(immersive || controlsLocked ? 1f : 0.45f);
    }

    private void setControlsLocked(boolean locked) {
        if (locked && !immersive) return;
        if (controlsLocked == locked) return;
        controlsLocked = locked;
        actions.removeCallbacks(hideLockedActions);
        lockTouchGuard.setVisibility(locked ? View.VISIBLE : View.GONE);
        pipButton.setVisibility(locked ? View.GONE : View.VISIBLE);
        closeButton.setVisibility(View.VISIBLE);
        lockButton.setImageResource(locked
                ? R.drawable.ic_player_lock_closed : R.drawable.ic_player_lock_open);
        lockButton.setContentDescription(locked ? "화면 잠금 해제" : "화면 잠금");
        updateLockAvailability();
        if (locked) {
            playerView.hideController();
            playerView.setUseController(false);
            showLockedActions();
            Toast.makeText(this,
                    "화면이 잠겼습니다. 잠금 아이콘을 길게 누르면 해제됩니다.",
                    Toast.LENGTH_SHORT).show();
        } else {
            playerView.setUseController(!inPictureInPicture);
            if (inPictureInPicture) {
                setActionsVisible(false);
            } else {
                playerView.showController();
                setActionsVisible(true);
            }
            Toast.makeText(this, "화면 잠금을 해제했습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    private void showLockedActions() {
        if (!controlsLocked) return;
        actions.removeCallbacks(hideLockedActions);
        actions.animate().cancel();
        actions.setAlpha(0f);
        actions.setVisibility(View.VISIBLE);
        actions.animate().alpha(1f).setDuration(120).start();
        actions.postDelayed(hideLockedActions, 3000L);
    }

    private void setFullscreen(boolean enabled) {
        if (!enabled && controlsLocked) setControlsLocked(false);
        immersive = enabled;
        updateLockAvailability();
        setRequestedOrientation(enabled
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                if (enabled) controller.hide(WindowInsets.Type.systemBars());
                else controller.show(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(enabled
                    ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    : View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    private void enterPip() {
        if (controlsLocked || Build.VERSION.SDK_INT < 26 || isInPictureInPictureMode()) return;
        PictureInPictureParams params = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(16, 9))
                .build();
        enterPictureInPictureMode(params);
    }

    @Override public void onPictureInPictureModeChanged(boolean inPip,
                                                         @NonNull Configuration newConfig) {
        super.onPictureInPictureModeChanged(inPip, newConfig);
        inPictureInPicture = inPip;
        if (inPip && controlsLocked) setControlsLocked(false);
        actions.setVisibility(inPip ? View.GONE : View.VISIBLE);
        actions.setAlpha(1f);
        playerView.setUseController(!inPip);
        if (!inPip) playerView.showController();
    }

    @Override public void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (!controlsLocked && player != null && player.isPlaying() && Build.VERSION.SDK_INT >= 26 &&
                !isInPictureInPictureMode()) {
            enterPip();
        }
    }

    @Override protected void onPause() {
        savePlaybackPosition();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (actions != null) actions.removeCallbacks(hideLockedActions);
        setKeepScreenOn(false);
        if (player != null) {
            savePlaybackPosition();
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
