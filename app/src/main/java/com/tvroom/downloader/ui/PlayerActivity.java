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
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.tvroom.downloader.R;

import java.io.File;

public final class PlayerActivity extends AppCompatActivity {
    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_TITLE = "title";
    private ExoPlayer player;
    private PlayerView playerView;
    private LinearLayout actions;
    private Button fullscreen;
    private boolean immersive;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); setContentView(R.layout.activity_player);
        playerView = findViewById(R.id.player_view); actions = findViewById(R.id.player_actions);
        fullscreen = findViewById(R.id.fullscreen);
        findViewById(R.id.close).setOnClickListener(v -> finish());
        findViewById(R.id.pip).setOnClickListener(v -> enterPip());
        fullscreen.setOnClickListener(v -> setFullscreen(!immersive));
        String path = getIntent().getStringExtra(EXTRA_PATH);
        if (path == null || !new File(path).isFile()) {
            Toast.makeText(this, "영상 파일을 찾을 수 없습니다.", Toast.LENGTH_LONG).show(); finish(); return;
        }
        setTitle(getIntent().getStringExtra(EXTRA_TITLE));
        player = new ExoPlayer.Builder(this).build(); playerView.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(new File(path))));
        player.prepare(); player.play();
    }

    private void setFullscreen(boolean enabled) {
        immersive = enabled; fullscreen.setText(enabled ? "원래대로" : "전체화면");
        setRequestedOrientation(enabled ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE :
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                if (enabled) controller.hide(WindowInsets.Type.systemBars());
                else controller.show(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(enabled ?
                    View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY : View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    private void enterPip() {
        if (Build.VERSION.SDK_INT < 26) return;
        PictureInPictureParams params = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(16, 9)).build();
        enterPictureInPictureMode(params);
    }

    @Override public void onPictureInPictureModeChanged(boolean inPictureInPictureMode,
                                                         @NonNull Configuration newConfig) {
        super.onPictureInPictureModeChanged(inPictureInPictureMode, newConfig);
        actions.setVisibility(inPictureInPictureMode ? View.GONE : View.VISIBLE);
        playerView.setUseController(!inPictureInPictureMode);
    }

    @Override public void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (player != null && player.isPlaying() && Build.VERSION.SDK_INT >= 26 && !isInPictureInPictureMode()) enterPip();
    }

    @Override protected void onDestroy() {
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }
}
