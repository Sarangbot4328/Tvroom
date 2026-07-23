package com.tvroom.downloader.activation;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.tvroom.downloader.MainActivity;
import com.tvroom.downloader.R;
import com.tvroom.downloader.ui.SystemBarInsets;

public final class ActivationActivity extends AppCompatActivity {
    private View nameSection;
    private View activationSection;
    private View codeSection;
    private EditText userNameInput;
    private EditText activationCode;
    private TextView registeredName;
    private TextView statusText;
    private TextView guideText;
    private TelegramActivationClient telegramClient;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (ActivationStore.isActivated(this)) {
            openMain();
            return;
        }
        setContentView(R.layout.activity_activation);
        SystemBarInsets.apply(this, findViewById(R.id.activation_root), true);

        nameSection = findViewById(R.id.name_section);
        activationSection = findViewById(R.id.activation_section);
        codeSection = findViewById(R.id.code_section);
        userNameInput = findViewById(R.id.user_name_input);
        activationCode = findViewById(R.id.activation_code);
        registeredName = findViewById(R.id.registered_name);
        statusText = findViewById(R.id.activation_status);
        guideText = findViewById(R.id.activation_guide);

        findViewById(R.id.save_user_name).setOnClickListener(v -> saveUserName());
        findViewById(R.id.verify_activation).setOnClickListener(v -> verifyCode());
        render();
    }

    private void saveUserName() {
        String name = ActivationStore.normalizeName(userNameInput.getText().toString());
        if (name.isEmpty()) {
            userNameInput.setError("사용자명을 입력하세요.");
            return;
        }
        if (!ActivationStore.saveInitialUserName(this, name)) {
            userNameInput.setError("사용자명을 저장할 수 없습니다.");
            return;
        }
        render();
    }

    private void render() {
        String userName = ActivationStore.getUserName(this);
        boolean needsName = userName.isEmpty();
        nameSection.setVisibility(needsName ? View.VISIBLE : View.GONE);
        activationSection.setVisibility(needsName ? View.GONE : View.VISIBLE);
        if (needsName) return;

        registeredName.setText("등록 사용자: " + userName);
        guideText.setText("관리자가 텔레그램 관리자 채팅에 아래 명령을 보내야 합니다.\n\n"
                + "/티비룸 " + userName + " (비번)\n\n"
                + "관리자 승인이 확인되면 활성화 암호 입력칸이 나타납니다.");
        boolean pending = ActivationStore.hasPendingCode(this);
        codeSection.setVisibility(pending ? View.VISIBLE : View.GONE);
        statusText.setText(pending
                ? "관리자 승인을 확인했습니다. 괄호 안에 보낸 암호를 입력하세요."
                : "관리자 승인을 준비하고 있습니다…");

        if (!TelegramActivationClient.isConfigured()) {
            statusText.setText("이 APK에는 텔레그램 인증 정보가 설정되지 않았습니다.");
            return;
        }
        startTelegramPolling(userName);
    }

    private void startTelegramPolling(String userName) {
        if (telegramClient != null) return;
        telegramClient = new TelegramActivationClient(this, userName,
                new TelegramActivationClient.Listener() {
                    @Override public void onReady() {
                        runOnUiThread(() -> {
                            if (!ActivationStore.hasPendingCode(ActivationActivity.this)) {
                                statusText.setText("관리자 승인을 기다리는 중입니다.");
                            }
                        });
                    }

                    @Override public void onPendingCode() {
                        runOnUiThread(() -> {
                            codeSection.setVisibility(View.VISIBLE);
                            statusText.setText(
                                    "관리자 승인을 확인했습니다. 괄호 안에 보낸 암호를 입력하세요.");
                            activationCode.requestFocus();
                        });
                    }

                    @Override public void onConnectionMessage(String message) {
                        runOnUiThread(() -> statusText.setText(message));
                    }
                });
        telegramClient.start();
    }

    private void verifyCode() {
        String code = activationCode.getText().toString().trim();
        long messageId = ActivationStore.getPendingMessageId(this);
        if (!ActivationStore.verifyAndActivate(this, code)) {
            activationCode.setError("활성화 암호가 올바르지 않습니다.");
            return;
        }
        if (telegramClient != null) telegramClient.notifyActivated(messageId);
        Toast.makeText(this, "영구 활성화되었습니다.", Toast.LENGTH_SHORT).show();
        openMain();
    }

    private void openMain() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    @Override protected void onDestroy() {
        if (telegramClient != null) telegramClient.stop();
        super.onDestroy();
    }
}
