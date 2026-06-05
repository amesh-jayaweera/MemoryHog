# MemoryHog

An Android app that deliberately consumes RAM, on demand, so you can watch how a real device reacts to memory pressure: low-memory warnings, the kernel's OOM killer, app-process eviction, jank, swap, and the rest.

It exists as a debugging / experimentation tool. Use it to stress-test devices, reproduce low-memory bugs, profile other apps under realistic pressure, or just learn how Android's memory management actually behaves when something refuses to let go.

> **Not for production.** This app will gladly push a phone to the edge of the OOM killer. Don't ship it, and don't run it on a device you care about while doing anything important.

## How it works

Java/Kotlin allocations live on the managed heap, which Android caps per-process (typically 256-512 MB). To allocate beyond that and to be sure pages actually land in physical RAM, MemoryHog uses native code:

1. `mmap(MAP_PRIVATE | MAP_ANONYMOUS)` reserves a region of virtual address space — no physical RAM yet, Linux is lazy.
2. `memset(ptr, 0xAB, bytes)` writes to every page, forcing the kernel to commit real physical pages.
3. The pointers are kept in a `std::vector` so they can be `munmap`'d on demand.

This sidesteps the Dalvik/ART heap limit entirely. The only ceiling is the device's free RAM (plus whatever zRAM/swap the OEM has configured).

To stop Android from killing the process the moment you switch apps, the allocations are owned by a **foreground service** (`MemoryHogService`) with a persistent notification. Foreground services are near the top of the OOM-killer's "leave alone" list, so the held pages survive backgrounding.

## Project layout

```
app/src/main/
├── cpp/
│   ├── memory_hog.cpp        # mmap/munmap allocator, JNI entrypoints
│   └── CMakeLists.txt
├── java/com/test/memoryhog/
│   ├── MainActivity.kt       # UI; binds to the service
│   ├── MemoryHogService.kt   # Foreground service that owns the allocations
│   └── NativeMemory.kt       # JNI bindings (loads libmemoryhog.so)
├── res/layout/activity_main.xml
└── AndroidManifest.xml
```

## Requirements

- Android Studio Ladybug or newer
- Android Gradle Plugin 8.13.2
- Kotlin 2.4.0
- NDK + CMake 3.22.1 (installed via SDK Manager)
- A device or emulator running Android 6.0 (API 23) or higher
- ABIs built: `arm64-v8a`, `x86_64`

## Build & run

```bash
git clone <repo-url> MemoryHog
cd MemoryHog
./gradlew :app:installDebug
```

Or open the project in Android Studio and hit Run. First build pulls the NDK toolchain and compiles `libmemoryhog.so`, so expect it to take a minute.

On Android 13+ the app will ask for notification permission on first launch — grant it, otherwise the foreground service notification (which is what keeps the process alive) won't be visible.

## Using the app

The UI has two rows of buttons:

- **Allocate** — `+256 MB` through `+550 MB`. Each press appends another `mmap` region.
- **Release** — `-256 MB` through `-510 MB` pop allocations off the back of the list. `FREE ALL` `munmap`s everything.

Live stats update every second:

- Total RAM
- Available RAM (from `ActivityManager.MemoryInfo`)
- Used RAM
- This app's mmap allocations
- Low-memory flag (set by the kernel when free RAM drops below the threshold)
- Low-memory threshold

You can also tap **Release all** in the notification to drop everything and stop the service.

## What to look for

Background the app after allocating ~half of the device's free RAM, then open a few other apps. Behaviors you may observe:

- The persistent notification stays. The foreground service keeps the process from being killed for memory.
- The `lowMemory` flag flips to `YES` once free RAM falls below the threshold.
- Other background apps get evicted before MemoryHog does. Reopening them shows cold-start times instead of warm resume.
- On lower-tier devices, the launcher itself can be killed, the system server can drop frames, and zRAM compression pressure goes up.

`adb shell dumpsys meminfo com.test.memoryhog` is useful while experimenting — the `Private Other` / `Native` bucket is where the mmap'd pages show up.

## Permissions

| Permission | Why |
|------|------|
| `FOREGROUND_SERVICE` | Required to run a foreground service. |
| `FOREGROUND_SERVICE_DATA_SYNC` | Android 14 (API 34) requires a type-specific permission matching `foregroundServiceType`. |
| `POST_NOTIFICATIONS` | Android 13+ runtime permission for the persistent notification. |

## Caveats

- **Foreground service runtime cap.** Android 14+ caps `dataSync` foreground services at ~6 hours of cumulative runtime per day. After that the system stops the service and the held RAM is freed. If you need indefinite retention, switch the manifest's `foregroundServiceType` to `specialUse` and declare a property explaining why.
- **mmap can still fail.** On a phone with very little free RAM, `mmap` returns `MAP_FAILED` (and the app shows `mmap FAILED for ...MB`). Allocate in smaller chunks.
- **zRAM / swap can hide pressure.** Many OEMs configure aggressive zRAM. The app may appear to "fit" hundreds of MB more than the device has, because most of it is compressed in swap. Watch CPU usage — that's the cost.
- **The OOM killer is still a thing.** A foreground service is not invulnerable. Under extreme pressure, or if the device is rebooted, allocations are gone. `START_STICKY` will restart the service but it starts empty.
- **Don't run this on a daily-driver phone.** Especially not while you're on call.

## License

MIT. Do whatever you want, just don't blame me when your phone reboots.
