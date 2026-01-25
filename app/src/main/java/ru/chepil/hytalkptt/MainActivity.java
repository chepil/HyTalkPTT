package ru.chepil.hytalkptt;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String[] POSSIBLE_PACKAGE_NAMES = {
            "com.hytera.ocean"
    };
    
    // Static flag to communicate with accessibility service
    public static volatile boolean isPTTButtonPressed = false;
    
    // Track if HyTalk has been launched for the current PTT press
    private boolean hyTalkLaunched = false;
    
    // Track previous state of isPTTButtonPressed to detect new presses
    private boolean wasPTTButtonPressed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        try {
            // Check if launched from launcher or from PTT button
            Intent intent = getIntent();
            boolean isLauncherLaunch = Intent.ACTION_MAIN.equals(intent.getAction()) && 
                                       intent.hasCategory(Intent.CATEGORY_LAUNCHER);
            
            // Set content view first (needed for checking accessibility service)
            setContentView(R.layout.activity_main);
            
            // Ensure default PTT keycode (228) in sandbox if not set
            PttPreferences.ensureDefault(this);
            
            // Check if accessibility service is enabled
            boolean isAccessibilityServiceEnabled = isAccessibilityServiceEnabled();
            
            setupSettingsButtons();

            if (isLauncherLaunch && !isPTTButtonPressed) {
                if (!isAccessibilityServiceEnabled) {
                    Log.d(TAG, "Launched from launcher - accessibility not enabled, showing setup");
                    showSetupInstructions();
                    return;
                }
                Log.d(TAG, "Launched from launcher - accessibility enabled, showing MainActivity");
                TextView statusText = (TextView) findViewById(R.id.tv_status);
                if (statusText != null) {
                    statusText.setText("Waiting for PTT press...");
                }
                return;
            }

            // Launched via PTT or other intent: launch HyTalk and move to background
            Button btnProgrammableKeys = (Button) findViewById(R.id.btn_programmable_keys);
            Button btnAccessibility = (Button) findViewById(R.id.btn_accessibility);
            Button btnPttKey = (Button) findViewById(R.id.btn_ptt_key);
            if (btnProgrammableKeys != null) btnProgrammableKeys.setVisibility(View.VISIBLE);
            if (btnAccessibility != null) btnAccessibility.setVisibility(View.VISIBLE);
            if (btnPttKey != null) btnPttKey.setVisibility(View.VISIBLE);

            isPTTButtonPressed = true;
            wasPTTButtonPressed = false;
            launchHyTalkIfNeeded();
            moveTaskToBack(true);
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate", e);
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            isPTTButtonPressed = false; // Reset flag on error
        }
    }
    
    /**
     * Checks if the Accessibility Service is enabled.
     * 
     * @return true if the accessibility service is enabled, false otherwise
     */
    private boolean isAccessibilityServiceEnabled() {
        try {
            AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am == null) {
                Log.w(TAG, "AccessibilityManager is null");
                return false;
            }
            
            // Get the component name for our accessibility service
            ComponentName serviceComponent = new ComponentName(this, PTTAccessibilityService.class);
            String serviceName = serviceComponent.flattenToString();
            
            // Get list of enabled accessibility services
            List<AccessibilityServiceInfo> enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            
            if (enabledServices != null) {
                for (AccessibilityServiceInfo serviceInfo : enabledServices) {
                    String enabledServiceId = serviceInfo.getId();
                    if (serviceName.equals(enabledServiceId)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error checking accessibility service status", e);
            return false;
        }
    }

    /**
     * Shows setup instructions on the screen.
     * Displays instructions to configure Programmable Keys and Accessibility.
     */
    private void setupSettingsButtons() {
        Button btnProgrammableKeys = (Button) findViewById(R.id.btn_programmable_keys);
        Button btnAccessibility = (Button) findViewById(R.id.btn_accessibility);
        Button btnPttKey = (Button) findViewById(R.id.btn_ptt_key);
        
        // Show and configure buttons
        if (btnProgrammableKeys != null) {
            btnProgrammableKeys.setVisibility(View.VISIBLE);
            btnProgrammableKeys.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openProgrammableKeysSettings();
                }
            });
        }
        
        if (btnAccessibility != null) {
            btnAccessibility.setVisibility(View.VISIBLE);
            btnAccessibility.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openAccessibilitySettings();
                }
            });
        }

        if (btnPttKey != null) {
            btnPttKey.setVisibility(View.VISIBLE);
            btnPttKey.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openPttKeyButton();
                }
            });
        }

        // Try to enable accessibility service (may not work, but we try)
        enableAccessibilityService();
    }

    private void showSetupInstructions() {
        TextView statusText = (TextView) findViewById(R.id.tv_status);
        if (statusText != null) {
            statusText.setText("Scroll screen...\n\nTo use this app, you need:\n\n1) Set PTT Key code: Tap on 'Configure PTT Key'\n2) Programmable Keys (Motorola) Settings → Programmable Keys → Select PTT Key app → HyTalkPTT\n3) Accessibility Settings → Accessibility → HyTalkPTT → enable the toggle");
            statusText.setVisibility(View.VISIBLE);
        }
    }
    
    /**
     * Opens the Programmable Keys settings screen.
     */
    private void openProgrammableKeysSettings() {
        try {
            // Open general Settings (user will navigate to Programmable Keys)
            Intent settingsIntent = new Intent(Settings.ACTION_SETTINGS);
            startActivity(settingsIntent);
            Log.d(TAG, "Opened Settings - user should navigate to Programmable Keys");
        } catch (Exception e) {
            Log.e(TAG, "Error opening Programmable Keys settings", e);
            Toast.makeText(this, "Failed to open settings", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * Opens the Accessibility settings screen.
     */
    private void openAccessibilitySettings() {
        try {
            Intent accessibilityIntent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(accessibilityIntent);
            Log.d(TAG, "Opened Accessibility Settings");
        } catch (Exception e) {
            Log.e(TAG, "Error opening Accessibility settings", e);
            Toast.makeText(this, "Failed to open Accessibility settings", Toast.LENGTH_SHORT).show();
        }
    }

    private void openPttKeyButton() {
        try {
            Intent intent = new Intent(this, PttKeySetupActivity.class);
            startActivity(intent);
            Log.d(TAG, "Opened PTT Key setup");
        } catch (Exception e) {
            Log.e(TAG, "Error opening PTT key settings", e);
            Toast.makeText(this, "Failed to open PTT key settings", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * Enables the Accessibility Service programmatically.
     * Requires WRITE_SECURE_SETTINGS permission (must be granted via ADB).
     * Note: On some devices/Android versions, this may not work even with permission.
     */
    private void enableAccessibilityService() {
        try {
            ContentResolver resolver = getContentResolver();
            String enabledServices = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            
            // Get the component name for our accessibility service
            ComponentName serviceComponent = new ComponentName(this, PTTAccessibilityService.class);
            String serviceName = serviceComponent.flattenToString();
            
            Log.d(TAG, "Current enabled accessibility services: " + enabledServices);
            Log.d(TAG, "Service component name: " + serviceName);
            
            // Check if service is already enabled
            if (enabledServices != null && enabledServices.contains(serviceName)) {
                Log.d(TAG, "Accessibility service is already enabled");
                return;
            }
            
            // Add our service to the list of enabled services
            String newEnabledServices;
            if (enabledServices == null || enabledServices.isEmpty()) {
                newEnabledServices = serviceName;
            } else {
                newEnabledServices = enabledServices + ":" + serviceName;
            }
            
            // Try to set the new list of enabled services
            // This requires WRITE_SECURE_SETTINGS permission
            try {
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newEnabledServices);
                
                // Also enable accessibility (if not already enabled)
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                
                Log.d(TAG, "Accessibility service enabled successfully");
                Log.d(TAG, "New enabled accessibility services: " + newEnabledServices);
            } catch (SecurityException e) {
                // Permission denied - this can happen even with WRITE_SECURE_SETTINGS on some devices
                Log.w(TAG, "Cannot enable accessibility service programmatically. SecurityException: " + e.getMessage());
                Log.w(TAG, "This may be a device/OS limitation. User must enable manually via Settings -> Accessibility");
                // Don't rethrow - just log the error and continue
            }
        } catch (Exception e) {
            Log.e(TAG, "Error enabling accessibility service", e);
        }
    }
    
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        
        // Check if this is a new PTT press (flag changed from false to true)
        if (isPTTButtonPressed && !wasPTTButtonPressed) {
            // New PTT press detected - reset launch flag
            hyTalkLaunched = false;
        }
        wasPTTButtonPressed = isPTTButtonPressed;
        
        if (hasFocus && isPTTButtonPressed && !hyTalkLaunched) {
            // Activity returned to foreground while PTT is pressed
            // Launch/bring HyTalk to foreground (will bring to foreground if already running)
            launchHyTalkIfNeeded();
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Reset flag when activity is destroyed
        isPTTButtonPressed = false;
        hyTalkLaunched = false;
        Log.d(TAG, "MainActivity destroyed - PTT flag reset");
    }

    private void launchHyTalkIfNeeded() {
        // If PTT flag is false, reset launch flag (accessibility service may have reset it)
        if (!isPTTButtonPressed) {
            hyTalkLaunched = false;
            wasPTTButtonPressed = false; // Reset to detect next new press
            return;
        }
        
        // Always try to launch/bring HyTalk to foreground when PTT is pressed
        // Intent flags will bring it to foreground if already running, or launch if not

        // Try to find HyTalk app
        Intent launchIntent = findHyTalkApp();
        if (launchIntent != null) {
            // Add flags to bring app to foreground
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            
            try {
                startActivity(launchIntent);
                hyTalkLaunched = true; // Mark as launched

                // Move MainActivity to background so HyTalk stays in foreground
                moveTaskToBack(true);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start HyTalk app", e);
                Toast.makeText(this, "Failed to launch: " + e.getMessage(), Toast.LENGTH_LONG).show();
                TextView statusText = (TextView) findViewById(R.id.tv_status);
                if (statusText != null) {
                    statusText.setText("Failed to launch!\n" + e.getMessage());
                }
                isPTTButtonPressed = false; // Reset flag on error
                hyTalkLaunched = false;
            }
        } else {
            Log.w(TAG, "HyTalk app not found - staying on MainActivity");
            isPTTButtonPressed = false;
            hyTalkLaunched = false;
            // No toast, no search; just keep MainActivity visible
        }
    }

    private Intent findHyTalkApp() {
        PackageManager pm = getPackageManager();
        
        // First, try the known package names
        for (String packageName : POSSIBLE_PACKAGE_NAMES) {
            Intent launchIntent = pm.getLaunchIntentForPackage(packageName);
            if (launchIntent != null) {
                return launchIntent;
            }
        }
        
        // If not found, search through all installed packages
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(mainIntent, 0);
        
        String selfPackage = getPackageName().toLowerCase();
        for (ResolveInfo info : apps) {
            String packageName = info.activityInfo.packageName.toLowerCase();
            if (packageName.equals(selfPackage)) {
                continue; // Exclude ourselves (ru.chepil.hytalkptt contains "hytalk")
            }
            if (packageName.contains("hytera") || packageName.contains("hytalk")) {
                Log.d(TAG, "Found potential HyTalk app: " + packageName);
                Intent launchIntent = pm.getLaunchIntentForPackage(info.activityInfo.packageName);
                if (launchIntent != null) {
                    Log.d(TAG, "Launching with package: " + info.activityInfo.packageName);
                    return launchIntent;
                }
            }
        }
        
        return null;
    }

    private void searchForHyTalkPackages() {
        Log.d(TAG, "=== Searching for HyTalk packages ===");
        PackageManager pm = getPackageManager();
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(mainIntent, 0);
        
        Log.d(TAG, "Total installed apps: " + apps.size());
        int foundCount = 0;
        
        String selfPackage = getPackageName().toLowerCase();
        for (ResolveInfo info : apps) {
            String packageName = info.activityInfo.packageName.toLowerCase();
            if (packageName.equals(selfPackage)) {
                continue;
            }
            String appName = info.loadLabel(pm).toString();
            if (packageName.contains("hytera") || packageName.contains("hytalk") || 
                appName.toLowerCase().contains("hytalk") || appName.toLowerCase().contains("hytera")) {
                foundCount++;
                Log.d(TAG, "Found: " + packageName + " (" + appName + ")");
            }
        }
        
        Log.d(TAG, "Found " + foundCount + " potential HyTalk apps");
        Log.d(TAG, "=== End search ===");
    }
}
