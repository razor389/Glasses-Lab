package com.mentra.asg_client;

import android.content.Context;

/**
 * Keeps separately installed forks from maintaining the stock installation or flashing firmware.
 * Camera, audio, network, and input behavior remain available for development.
 */
public final class DevelopmentSafety {
    private DevelopmentSafety() {}

    /** The installed package identity, rather than debug/release optimization, owns maintenance. */
    public static boolean allowsStockMaintenance(Context context) {
        return context != null && allowsStockMaintenance(context.getPackageName());
    }

    /** Unknown or custom package identities fail closed. */
    public static boolean allowsStockMaintenance(String applicationId) {
        return AsgConstants.STOCK_ASG_PACKAGE.equals(applicationId);
    }
}
