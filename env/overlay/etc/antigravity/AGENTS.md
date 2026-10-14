# Antigravity Android Agent Rules & Environment Guidelines

> [!NOTE]
> This rule is automatically loaded into every conversation in Antigravity for Android (AGY v2).
> It defines the operating environment, execution rules, package management, and elevated permissions.

---

## 1. Platform & Environment Identity

- **Platform:** Native Android (Linux kernel 6.1+, ARM64 / `aarch64` architecture, ARMv8.1-A+ with LSE).
- **Application Context:** Runs under the installed AGY application id (`$AGY_APPLICATION_ID`) and the current Android user/profile.
- **Key Filesystem Paths:**
  - Application Root (`$HOME`): the app-private `files/` directory.
  - Linux Userspace Prefix (`$PREFIX`): `$HOME/usr`.
  - User Binary Path: `$PREFIX/bin` (prefer the existing `$PATH`; do not hard-code `/data/user/0` or an application id).
  - Runtime Internals: `$HOME/runtime/bin`.
  - External / Shared Storage: `/storage/emulated/0` (Projects typically reside in `/storage/emulated/0/Documents/`).
  - AI State & Config: `$HOME/.gemini/config/`.
  - Conversation Brain & Artifacts: `$HOME/.gemini/antigravity-app/brain/<conversation-id>/`.

---

## 2. Terminal Execution Rules

- **No Bubblewrap / Desktop Sandboxing:**
  Standard desktop Linux bubblewrap (`bwrap`) is **not supported** by the Android kernel and SELinux. Never attempt to invoke `bwrap` or run commands wrapped in desktop sandboxes. All shell commands execute directly within the user shell.
- **Path Quoting:**
  Android storage paths frequently contain spaces (e.g. `/storage/emulated/0/Documents/antigravity v2/`). Always quote file paths in commands.
- **Environment Variables for Shell Commands:**
  When executing commands, ensure the environment is configured:
  ```bash
  # HOME is resolved by the AGY profile; derive PREFIX without a fixed app id.
  : "${HOME:?open an AGY shell with the env profile loaded}"
  export PREFIX="${PREFIX:-$HOME/usr}"
  export HOME PREFIX
  export PATH="$PREFIX/bin:$PATH"
  ```
- **Git on Android Storage:**
  Git installed via Termux repos may check `/data/data/com.termux/files/usr/etc/` which doesn't exist. Always use:
  ```bash
  export GIT_CONFIG_NOSYSTEM=1
  git config --global --add safe.directory "<path>"
  ```

---

## 3. Package Management (`pkg` / `tlx`)

AGY includes a small Termux-repository package client (`pkg`) written in Bash & AWK. Python is part of the default seed package set and is installed on first networked startup; first launch can remain offline, in which case Python stays pending until a later retry.

- **Correct Syntax:**
  ```bash
  # Update package indexes:
  pkg update

  # Install packages (do NOT pass -y flag; list package names directly):
  pkg install git python nodejs bash

  # Search and query:
  pkg search <pattern>
  pkg list
  pkg installed
  pkg info <package>

  # Remove package:
  pkg remove <package>

  # System health self-test:
  pkg selftest
  ```
- **Post-Install Path Fixes:**
  After installing global Python/Node scripts (`pip`, `npm`), fix hardcoded shebangs using:
  ```bash
  agy-fix-paths
  ```

---

## 4. Elevated Operations via Shizuku (`rish`)

The agent has native access to Shizuku's elevated ADB shell (`uid=2000(shell)`):
- **Executable Location:** `$PREFIX/bin/rish`. It discovers the Shizuku dex under `$PREFIX/../runtime/bin` and receives the current package id from the app bridge.
- **How to Execute:**
  ```bash
  rish -c "<command>"
  ```
- **When to Use `rish`:**
  1. **Filesystem Access:** Read, write, or copy files in protected Android directories or shared storage blocked by app sandbox permissions:
     ```bash
     rish -c "ls -la /storage/emulated/0/Android/data"
     rish -c "cp <src> <dest>"
     ```
  2. **APK & Package Management (`pm`):**
     ```bash
     rish -c "pm install -r /storage/emulated/0/.../app.apk"
     rish -c "pm list packages -3"
     rish -c "pm uninstall com.example.app"
     ```
  3. **Activity Management & Diagnostics (`am`, `logcat`, `dumpsys`):**
     ```bash
     rish -c "am start -n com.example.app/.MainActivity"
     rish -c "logcat -d -t 150"
     rish -c "dumpsys package com.example.app"
     ```
  4. **System Properties & Settings:**
     ```bash
     rish -c "getprop ro.build.version.release"
     rish -c "settings get global airplane_mode_on"
     ```

---

## 5. Development & Server Guidelines

- **Port Binding:** Local development servers (Vite, Next.js, Express, FastAPI) MUST bind to `localhost` or `127.0.0.1` on ports such as `3000`, `5173`, `8000`, `8080`. Avoid ports `45157` and `45158` (reserved for Antigravity core).
- **Compilation:** When compiling native C/C++/Rust code, target `-march=armv8.1-a` (or higher) to leverage native LSE atomics.

---

## 6. Background Daemon & AI Task Manager (`agy-daemon`)

Antigravity includes an ultra-lightweight background scheduler, watcher, and AI task manager:
- **Footprint:** <10MB RAM, 0.0% CPU idle.
- **Persistence:** Automatically started alongside `CoreServerService` under Android Foreground Service Partial WakeLock (keeps executing even when screen is locked or turned off).

### Service Control
```bash
# Check daemon service status, PID, and recent task execution logs:
agy-daemon status

# Manage daemon service lifecycle manually:
agy-daemon start
agy-daemon stop
agy-daemon restart
```

### AI Task Manager (`agy-daemon task`)
The agent and user can manage autonomous background jobs using structured commands (supports `--json` for machine parsing):

```bash
# 1. List scheduled tasks:
agy-daemon task list
agy-daemon task list --json
agy-daemon task list --active-only

# 2. Add delayed or recurring tasks:
# Delay (e.g. 10s, 30m, 4h):
agy-daemon task add --name "Review Code" --delay 30m --prompt "Check test results and summarize changes"

# Interval (e.g. 5m, 1h):
agy-daemon task add --name "Disk Health" --interval 1h --command "df -h > ~/.gemini/disk.log"

# Standard 5-field Cron ("min hour dom month dow"):
agy-daemon task add --name "Nightly Clean" --cron "0 3 * * *" --command "rm -rf ~/.gemini/tmp/*"

# Event & Watcher Triggers:
agy-daemon task add --name "Watch PRs" --github-pr "owner/repo" --interval 5m --command "agy-notify post -t 'New PR' -m 'PR detected'"
agy-daemon task add --name "File Watcher" --watch-file "/path/to/project/build.gradle" --command "pkg selftest"
agy-daemon task add --name "Battery Alert" --battery-below 15 --command "agy-notify post -t 'Low Battery' -m 'Save your work'"

# 3. Task Management & Debugging:
# Pause / Resume:
agy-daemon task pause <task_id_or_name>
agy-daemon task resume <task_id_or_name>

# Trigger immediate test run:
agy-daemon task run-now <task_id_or_name>

# Inspect task execution logs and output:
agy-daemon task logs <task_id_or_name>

# Remove task or clear all:
agy-daemon task remove <task_id_or_name>
agy-daemon clear
```

### Native Android Notifications (`agy-notify`)
Post, list, or clear Android status notifications from the terminal:
```bash
# Send an alert to user notification tray:
agy-notify post -t "Build Finished" -m "Tests passed without errors"

# Inspect active notifications:
agy-notify list
```

