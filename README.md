# Nudge — Mindful Screen Time & Digital Wellbeing 🌿

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android_8.0+_(API_26+)-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android Platform" />
  <img src="https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Jetpack_Compose-Material_3-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" />
  <img src="https://img.shields.io/badge/Privacy-100%25_On--Device-00796B?style=for-the-badge" alt="100% On-Device" />
  <img src="https://img.shields.io/badge/License-MIT-teal?style=for-the-badge" alt="MIT License" />
</p>

<p align="center">
  <b>Break the loop of mindless doomscrolling with gentle, soft-nudge floating reminders.</b><br>
  A modern, private, and battery-efficient digital wellbeing application for Android.
</p>

---

## 📖 About Nudge

**Nudge** is designed to help you build a healthier relationship with your smartphone without aggressive blockers or stressful lockouts. Instead of abruptly locking you out of apps, Nudge displays a **calming floating soft-nudge card** directly over your screen when you reach your session limit, giving you conscious control to take a breather, snooze, or wrap up what you are doing.

---

## 🤖 Built with AI & Author's Learning Journey

> [!NOTE]
> **Disclaimer**: This project was **100% built using AI** (pair-programmed with advanced AI coding agents). 
> 
> It was created as a hands-on learning exploration for the author to understand and master how modern, production-ready Android applications (using Kotlin, Jetpack Compose, Material 3, and system-level Android APIs) can be designed, architected, debugged, and refined end-to-end through AI-driven software engineering.

---

## ✨ Key Features

### 🌿 Soft-Nudge Floating Overlays
- When your continuous session time limit is reached, a calming, unobtrusive floating card pops up directly over the active app (`SYSTEM_ALERT_WINDOW`).
- **"Take a Break 🙌"**: Instantly minimizes the active app and returns you to the home screen.
- **"+5m Snooze"**: Extends your session by 5 minutes before the next gentle reminder.
- **"Dismiss"**: Closes the reminder card so you can finish your task.

### 📱 Dynamic App Monitoring & Searchable Picker
- Monitor **any installed app or game** on your device (Instagram, YouTube, TikTok, Reddit, games, shopping, etc.).
- Includes a smooth **Material 3 Searchable Bottom Sheet** to find and add apps from your phone in seconds.
- Individual toggle switches and delete actions for every monitored app.

### 📊 Real-Time Screen Time Dashboard
- **Custom Canvas Progress Ring**: Visualizes your daily monitored usage and progress towards your limit.
- **Per-App Usage Breakdown**: Canvas-drawn horizontal bar charts comparing screen time across apps.
- **Per-App Usage Cards**: Detailed metrics with app icons and proportional progress indicators.

### 🔄 Fluid Pull-to-Refresh & Silent On-Resume Sync
- Swipe down anywhere on the Dashboard to trigger an **animated spinning teal pull-to-refresh** gesture.
- Automatically and silently synchronizes stats whenever you return to the app from other apps or Settings.

### 🔋 Continuous Background Reliability
- **Auto-Start on Boot**: Uses `RECEIVE_BOOT_COMPLETED` to resume tracking automatically when your phone reboots.
- **Survives Task Kills**: Automatically re-spawns the monitoring service if swiped away from Recent Apps.
- **Battery Optimization Whitelist**: One-tap setting to prevent aggressive OEM battery savers (Samsung, Xiaomi, Oppo, etc.) from killing background tracking.
- **Screen-State Aware**: Pauses event polling when the screen turns off to preserve battery life.

### 🔒 100% Private & Offline
- **Zero Cloud Servers**: All usage queries use local Android APIs.
- **Zero Telemetry**: No analytics, no ads, and no data tracking. Your usage data never leaves your device.

---

## 🛠️ Architecture & Tech Stack

Nudge is built following modern Android development best practices and Clean Architecture principles:

```
com.nudge.app/
├── data/               # Data layer & repositories
│   ├── AppInfo.kt              # App info & usage data models
│   ├── PreferencesManager.kt   # SharedPreferences persistence
│   ├── SocialMediaApp.kt       # Presets & package queries
│   └── UsageRepository.kt      # UsageStatsManager & PackageManager queries
├── service/            # Background monitoring & overlays
│   ├── BootReceiver.kt         # Auto-start on device reboot
│   ├── NotificationHelper.kt   # Notification channels & alert builders
│   ├── OverlayManager.kt       # Floating WindowManager overlay card
│   └── ScreenTimeTrackerService.kt # Foreground service tracking loop
├── ui/                 # Jetpack Compose UI layer
│   ├── components/     # Reusable Canvas charts, cards, & bottom sheets
│   │   ├── AppPickerBottomSheet.kt
│   │   ├── CircularProgress.kt
│   │   ├── UsageBarChart.kt
│   │   └── UsageCard.kt
│   ├── navigation/     # Jetpack Navigation Compose routes & graph
│   ├── screens/        # Dashboard, Settings, & Onboarding screens
│   │   ├── DashboardScreen.kt
│   │   ├── DashboardViewModel.kt
│   │   ├── PermissionScreen.kt
│   │   └── SettingsScreen.kt
│   └── theme/          # Calming teal/sage Material 3 color system
│       ├── Color.kt
│       ├── Theme.kt
│       └── Type.kt
├── MainActivity.kt     # Single Activity host
└── NudgeApplication.kt    # Application entrypoint & notification channels
```

* **UI Toolkit**: [Jetpack Compose](https://developer.android.com/jetpack/compose) & [Material 3](https://m3.material.io/)
* **State Management**: `ViewModel`, `StateFlow`, `LifecycleEventEffect`
* **Concurrency**: Kotlin Coroutines & SupervisorScope
* **System Integration**: `UsageStatsManager`, `WindowManager`, `ForegroundService` (Android 14+ `specialUse`), `AlarmManager`

---

## 🔑 Permissions Explained

| Permission | Purpose |
| :--- | :--- |
| `PACKAGE_USAGE_STATS` | Required by Android's `UsageStatsManager` to aggregate daily screen time and detect active foreground app sessions. |
| `SYSTEM_ALERT_WINDOW` | Required to display the floating soft-nudge reminder card over other applications. |
| `POST_NOTIFICATIONS` | (Android 13+) Required to post high-priority limit alerts and ongoing foreground service status. |
| `FOREGROUND_SERVICE` | Keeps the lightweight monitoring service active while social media apps are open. |
| `RECEIVE_BOOT_COMPLETED` | Automatically restarts the monitoring service when the device turns on. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Allows whitelisting from aggressive device power savers so tracking is never paused. |

---

## 🚀 Getting Started

### Prerequisites
* **Android Studio**: Ladybug / Meerkat or newer
* **JDK**: 17 or 21
* **Android Device / Emulator**: Running Android 8.0 (API 26) or higher

### Build & Run
1. Clone this repository:
   ```bash
   git clone https://github.com/your-username/nudge-app.git
   cd nudge-app
   ```
2. Open the project in **Android Studio**.
3. Build the debug APK via Gradle:
   ```bash
   ./gradlew assembleDebug
   ```
4. Connect your Android device via USB debugging and press **Run (▶️)** in Android Studio.

---

## 🤝 Contributing

Contributions, feature suggestions, and bug reports are warmly welcome!

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a **Pull Request**

---

## 📄 License

This project is licensed under the **MIT License** — see the [LICENSE](LICENSE) file for details.
