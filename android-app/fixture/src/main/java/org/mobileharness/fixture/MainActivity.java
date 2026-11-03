package org.mobileharness.fixture;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Insets;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Synthetic, memory-only target. This module is never required by product sessions. */
public final class MainActivity extends Activity {
    public static final String ACTION_RESET = "org.mobileharness.fixture.action.RESET";
    private EditText input;
    private TextView clickCount;
    private TextView longPressCount;
    private TextView saveState;
    private TextView savedValue;
    private ScrollView scroll;
    private int clicks;
    private int longPresses;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setDecorFitsSystemWindows(false);
        setContentView(R.layout.activity_fixture);
        View root = findViewById(R.id.fixture_root);
        final int padding = dp(16);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            view.setPadding(padding + bars.left, padding + bars.top,
                    padding + bars.right, padding + bars.bottom);
            return insets;
        });
        root.requestApplyInsets();
        input = findViewById(R.id.fixture_input);
        input.setShowSoftInputOnFocus(false);
        clickCount = findViewById(R.id.fixture_click_count);
        longPressCount = findViewById(R.id.fixture_long_press_count);
        saveState = findViewById(R.id.fixture_save_state);
        savedValue = findViewById(R.id.fixture_saved_value);
        scroll = findViewById(R.id.fixture_scroll);
        addStaticRows();
        findViewById(R.id.fixture_click).setOnClickListener(view -> {
            clicks++;
            clickCount.setText(getString(R.string.click_count, clicks));
        });
        findViewById(R.id.fixture_long_press).setOnLongClickListener(view -> {
            longPresses++;
            longPressCount.setText(getString(R.string.long_press_count, longPresses));
            return true;
        });
        findViewById(R.id.fixture_save).setOnClickListener(view -> {
            saveState.setText(R.string.saved);
            savedValue.setText(getString(R.string.saved_value, input.getText().toString()));
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                saveState.setText(R.string.not_saved);
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        resetScreen();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && ACTION_RESET.equals(intent.getAction())) resetScreen();
    }

    private void resetScreen() {
        clicks = 0;
        longPresses = 0;
        input.setText("initial");
        input.clearFocus();
        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
        clickCount.setText(getString(R.string.click_count, clicks));
        longPressCount.setText(getString(R.string.long_press_count, longPresses));
        saveState.setText(R.string.not_saved);
        savedValue.setText(R.string.saved_value_empty);
        scroll.scrollTo(0, 0);
    }

    private void addStaticRows() {
        int[] ids = {R.id.fixture_row_01, R.id.fixture_row_02, R.id.fixture_row_03, R.id.fixture_row_04,
                R.id.fixture_row_05, R.id.fixture_row_06, R.id.fixture_row_07, R.id.fixture_row_08,
                R.id.fixture_row_09, R.id.fixture_row_10, R.id.fixture_row_11, R.id.fixture_row_12,
                R.id.fixture_row_13, R.id.fixture_row_14, R.id.fixture_row_15, R.id.fixture_row_16};
        LinearLayout rows = findViewById(R.id.fixture_rows);
        for (int i = 0; i < ids.length; i++) {
            TextView row = new TextView(this);
            row.setId(ids[i]);
            row.setSaveEnabled(false);
            row.setText("静态测试行 " + (i + 1));
            row.setTextSize(18);
            row.setTextColor(0xff111111);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), 0, dp(12), 0);
            rows.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60)));
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
