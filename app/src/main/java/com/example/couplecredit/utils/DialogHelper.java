package com.example.couplecredit.utils;

import android.app.AlertDialog;
import android.content.Context;
import android.view.ViewGroup;

public class DialogHelper {

    public static void showWide(AlertDialog dialog, Context context) {
        dialog.show();
        if (dialog.getWindow() != null) {
            int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
            dialog.getWindow().setLayout((int) (screenWidth * 0.85), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }
}
