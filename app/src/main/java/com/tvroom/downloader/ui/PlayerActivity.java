package com.tvroom.downloader.ui;

import android.app.PictureInPictureParams;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
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

    private ExoPlayer player;
    private PlayerView playerView;
    private LinearLayout actions;
    private boolean immersive;
    private boolean inPictureInPicture;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_player);
        playerView = findViewById(R.id.player_view);
        actions = findViewById(R.id.player_actions);
        findViewById(R.id.close).setOnClickListener(v -> finish());
        findViewById(R.id.pip).setOnClickListener(v -> enterPip());

        String path = getIntent().getStringExtra(EXTRA_PATH);
        if (path == null || !new File(path).isFile()) {
            Toast.makeText(this, "영상 파일을 찾을 수 없습니다.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        setTitle(getIntent().getStringExtra(EXTRA_TITLE));
        player = new ExoPlayer.Builder(this).build();
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException error) {
                String detail = error.getMessage();
                Toast.makeText(PlayerActivity.this,
                        "영상 재생 오류" + (detail == null ? "" : " · " + detail),
                        Toast.LENGTH_LONG).show();
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
        player.prepare();
        player.play();
    }

    private void setActionIconsVisible(boolean visible) {
        if (inPictureInPicture) visible = false;
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

    private void setFullscreen(boolean enabled) {
        immersive = enabled;
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
        if (Build.VERSION.SDK_INT < 26 || isInPictureInPictureMode()) return;
        PictureInPictureParams params = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(16, 9))
                .build();
        enterPictureInPictureMode(params);
    }

    @Override public void onPictureInPictureModeChanged(boolean inPip,
                                                         @NonNull Configuration newConfig) {
        super.onPictureInPictureModeChanged(inPip, newConfig);
        inPictureInPicture = inPip;
        actions.setVisibility(inPip ? View.GONE : View.VISIBLE);
        actions.setAlpha(1f);
        playerView.setUseController(!inPip);
        if (!inPip) playerView.showController();
    }

    @Override public void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (player != null && player.isPlaying() && Build.VERSION.SDK_INT >= 26 &&
                !isInPictureInPictureMode()) {
            enterPip();
        }
    }

    @Override protected void onDestroy() {
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
