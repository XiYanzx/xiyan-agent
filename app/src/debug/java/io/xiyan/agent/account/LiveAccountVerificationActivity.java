// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.account;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** Debug-only, DUMP-protected entry point that uses the already-running application process. */
public final class LiveAccountVerificationActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        String mode = getIntent().getStringExtra("mode");
        boolean inspect = mode == null || "inspect".equals(mode);
        if (inspect) setTheme(android.R.style.Theme_NoDisplay);
        super.onCreate(savedInstanceState);
        if (!inspect) {
            TextView status = new TextView(this);
            status.setText("正在记录本地验证结果，完成后将返回原页面。");
            status.setPadding(32, 48, 32, 32);
            status.setTextSize(17);
            setContentView(status);
        }
        LiveAccountVerifier.start(getApplicationContext(), getIntent().getExtras(), () -> runOnUiThread(this::finish));
    }
}
