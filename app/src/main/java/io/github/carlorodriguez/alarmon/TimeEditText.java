package io.github.carlorodriguez.alarmon;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

public class TimeEditText extends EditText {

    public TimeEditText(Context context) {
        super(context);
    }

    public TimeEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public TimeEditText(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK &&
                event.getAction() == KeyEvent.ACTION_UP) {
            onEditorAction(EditorInfo.IME_ACTION_DONE);
        }

        return super.dispatchKeyEvent(event);
    }

}
