package com.pilot.voiceassistant.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.pilot.voiceassistant.R;

/**
 * The first screen: chooses between the two assistants. Tradition runs every engine on the
 * device; Modern talks through Gemini Live.
 */
public final class ModeActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mode);
        findViewById(R.id.tradition_button).setOnClickListener(
                v -> startActivity(new Intent(this, MainActivity.class)));
        findViewById(R.id.modern_button).setOnClickListener(
                v -> startActivity(new Intent(this, LiveActivity.class)));
    }
}
