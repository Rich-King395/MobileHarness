# MobileHarness

English · [简体中文](README.md)

Let a GUI Agent perform tasks on your Android phone, with control you can take over and return when needed.

Current version: `0.9.0-dev`. The first public APK has not been released. Once available, download it from [Releases](https://github.com/Rich-King395/MobileHarness/releases). License: [Apache-2.0](LICENSE).

## ✨ Features

- **Runs on your phone**: after installation and setup, everyday use requires no computer. Models run through remote services.
- **Open-ended tasks**: the Agent chooses and opens Apps within your authorized scope, without requiring an initial App for every task.
- **Manual takeover**: use the edge floating control to take over, return control, or stop a task. A notification provides a backup entry.
- **Optional Harness**: after you return control, a Planner helps identify remaining work. With Harness off, the Agent continues with the original task and its own history.
- **Multiple model profiles**: save named profiles and select the GUI Agent and Planner services and models separately.
- **Local experiment records**: review task text, steps used, Planner calls, and user ratings; filter, summarize, and export analysis CSV files.
- **Chinese and English interface**.

## 🚀 Get started

1. **Install and grant permissions**: once an APK is available in Releases, install it, manually enable the MobileHarness accessibility service as prompted, and allow notifications for the backup entry.
2. **Configure models**: on the Models page, add a name, HTTPS endpoint, model name, and your own API Key. Select a profile for the GUI Agent and for the Planner.
3. **Choose authorized Apps**: select which Apps the Agent may operate on the task page. This scope is reused for later tasks and can be adjusted when needed.
4. **Give a task**: enter your goal, choose Harness on or off and a step budget, then acknowledge that necessary screenshots will be sent to the configured remote services before starting. The default GUI budget is 30 steps.
5. **Take over and return**: expand the floating control and request takeover. Wait for “Pausing” to change to “Manual” before operating the phone, then return control when ready. Use Stop to end the task.

The current version supports **one effective takeover and return per task**. Manual actions do not consume or reset the GUI budget; Planner calls are recorded separately.

## 📱 Devices and models

| Item | Current scope |
| --- | --- |
| Android | Android 11 minimum; primarily tested on Google Pixel 4 / Android 13. Other devices await validation. |
| Inference | Uses your configured remote services; the APK includes neither a model service nor an API Key. |
| Model compatibility | Primarily validated with Qwen 3.8 MAX. Other models must meet the existing API and action protocol requirements. |

Naming a profile GPT or Claude does not add support for its native API. The GUI Agent and Planner may use the same profile or different profiles.

## 📊 Records and results

- On Records, review task text, GUI usage, Planner calls, end reasons, and user completion ratings.
- Add or update ratings for previous tasks, filter by Harness mode and rating, and view descriptive summaries.
- Analysis CSV exports include usage, ratings, durations, and call statistics. They exclude task text, API Keys, screenshots, and manual action trajectories.

A model declaring an end is recorded separately from task completion. User ratings are not independent success verification, and summaries across different tasks do not establish the effect of Harness.

## 🔒 Data boundaries

- API Keys are encrypted locally. They are not bundled with the APK or included in ordinary logs or exports.
- Original tasks are encrypted locally for later review. During execution, task content and necessary screenshots are sent to your configured model services.
- Screenshots are held temporarily in memory only as needed by the GUI Agent or Planner. No screenshot history or long-term screenshot storage is kept.
- No screen images or action trajectories are collected during manual control. A fresh observation is taken after control is returned.
- Remote data retention and use follow each provider’s policies. Operate only devices, Apps, and data you are authorized to use.
