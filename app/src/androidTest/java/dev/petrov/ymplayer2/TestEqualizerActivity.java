package dev.petrov.ymplayer2;

import android.app.Activity;
import android.content.Intent;
import android.media.audiofx.AudioEffect;
import android.os.Bundle;
import android.widget.TextView;

/** Separate test APK activity: proves launch and session extras, never changes audio effects. */
public class TestEqualizerActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = getIntent();
        getSharedPreferences("equalizer-probe", MODE_PRIVATE).edit()
            .putString("action", intent.getAction())
            .putInt("session", intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1))
            .putString("package", intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME))
            .putInt("content", intent.getIntExtra(AudioEffect.EXTRA_CONTENT_TYPE, -1)).apply();
        TextView view = new TextView(this); view.setText("YMPlayer EQ test panel"); view.setTextSize(24); setContentView(view);
    }
}
