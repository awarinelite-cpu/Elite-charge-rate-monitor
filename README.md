# Elite Charge Rate Monitor

Android app that always shows your real charging/discharging rate.

- Live current in mA (via `BatteryManager.BATTERY_PROPERTY_CURRENT_NOW`), watts, % per hour, time to full / time left
- Foreground service keeps checking with the screen off; the live rate sits in a notification
- Rolling log with a graph, lifetime peak charge/discharge, optional slow-charge alert
- Restarts after reboot

## Install

Every push to `main` builds the APK with GitHub Actions.
Download `Elite-Charge-Rate-Monitor.apk` from the **Releases > Latest build** page (or the Actions run artifact) and install it.

First launch: allow notifications, then tap **Keep running in background** so Android doesn't kill the monitor.
