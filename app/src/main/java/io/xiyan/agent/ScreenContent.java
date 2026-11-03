// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent;

import android.view.View;
import io.xiyan.agent.account.SiwcClient;

interface ScreenContent {
    View view();
    void update(SiwcClient.Snapshot snapshot);
    default void leave() { }
}
