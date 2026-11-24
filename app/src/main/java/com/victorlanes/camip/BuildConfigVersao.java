package com.victorlanes.camip;

import android.content.Context;

final class BuildConfigVersao {
    private BuildConfigVersao() {
    }

    static String nome(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0";
        }
    }
}
