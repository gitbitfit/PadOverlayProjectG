package com.example.padoverlay;

import android.content.Context;
import android.content.ContextWrapper;

/** Pretends to be the shell app, so the system accepts display requests from us. */
public class ShellContext extends ContextWrapper {
    public ShellContext(Context base) {
        super(base);
    }

    @Override
    public String getPackageName() {
        return "com.android.shell";
    }

    // Hidden in some SDK versions, so no @Override here; it still overrides at runtime.
    public String getOpPackageName() {
        return "com.android.shell";
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }
}
