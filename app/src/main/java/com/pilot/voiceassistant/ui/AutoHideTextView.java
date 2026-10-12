package com.pilot.voiceassistant.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.TextView;

/** A text with a background that takes no room while it is empty: a bubble or a message. */
public final class AutoHideTextView extends TextView {

    public AutoHideTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setVisibility(length() == 0 ? GONE : VISIBLE);
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int lengthBefore,
            int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
        setVisibility(text == null || text.length() == 0 ? GONE : VISIBLE);
    }
}
