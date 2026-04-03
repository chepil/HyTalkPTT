package ru.chepil.hytalkptt;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import java.util.List;

/** Shared check: is {@link PTTAccessibilityService} enabled in system accessibility settings. */
final class PttAccessibilityHelper {

    private PttAccessibilityHelper() {}

    /**
     * Huawei / EMUI often reports {@link AccessibilityServiceInfo#getId()} as
     * {@code package/fully.qualified.ClassName}, while {@link ComponentName#flattenToString()} is
     * {@code package/.ShortClass} — they must both be accepted.
     */
    static boolean isHyTalkPttServiceEnabled(Context context) {
        if (matchesEnabledList(context)) {
            return true;
        }
        return matchesSecureSettingsList(context);
    }

    private static boolean matchesEnabledList(Context context) {
        try {
            AccessibilityManager am = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am == null) {
                return false;
            }
            List<AccessibilityServiceInfo> enabledServices =
                    am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            if (enabledServices == null) {
                return false;
            }
            for (AccessibilityServiceInfo serviceInfo : enabledServices) {
                if (serviceIdIsOurs(context, serviceInfo.getId())) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean matchesSecureSettingsList(Context context) {
        try {
            String enabled = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null || enabled.isEmpty()) {
                return false;
            }
            for (String token : enabled.split(":")) {
                if (token.isEmpty()) {
                    continue;
                }
                if (serviceIdIsOurs(context, token)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean serviceIdIsOurs(Context context, String id) {
        if (id == null) {
            return false;
        }
        ComponentName cn = new ComponentName(context, PTTAccessibilityService.class);
        String flat = cn.flattenToString();
        if (id.equals(flat)) {
            return true;
        }
        String pkg = context.getPackageName();
        String fullClass = PTTAccessibilityService.class.getName();
        if (id.equals(pkg + "/" + fullClass)) {
            return true;
        }
        if (!id.startsWith(pkg + "/")) {
            return false;
        }
        String rest = id.substring(pkg.length() + 1);
        if (rest.equals(fullClass)) {
            return true;
        }
        if (rest.equals(PTTAccessibilityService.class.getSimpleName())) {
            return true;
        }
        if (rest.startsWith(".") && rest.substring(1).equals(PTTAccessibilityService.class.getSimpleName())) {
            return true;
        }
        return rest.endsWith("." + PTTAccessibilityService.class.getSimpleName());
    }
}
