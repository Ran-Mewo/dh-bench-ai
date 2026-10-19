# dh-bench-ai

Dev-only benchmark harness for [Distant Horizons](https://gitlab.com/distant-horizons-team/distant-horizons).
It boots the DH dev client into a fresh copy of a pre-generated world, waits for the LODs to finish loading,
records frame times for 4 fixed camera directions with DH rendering on and then off, measures memory,
writes everything to a JSON file and quits the game.

Nothing here touches the DH codebase: the harness is a tiny Fabric mod that is compiled against the dev
client's own classpath and injected into `runClient` through a Gradle init script.

## Usage

Clone this repo to `run/benchmark` inside a DH checkout (`run/` is git-ignored there), then run from the DH root:

```bash
run/benchmark/bench.sh <label> [extra gradle args, e.g. -PmcVer=26.1.2]
run/benchmark/suite.sh base= no-ssao=client.advanced.graphics.enableSsao=false   # several runs back to back
python3 run/benchmark/summary.py results/new.json results/old.json             # print a result and compare it to a baseline
```

The first run snapshots `run/client/saves/New World` as the benchmark world. Every run starts from a reflink
copy of that snapshot with the same `options.txt` and `DistantHorizons.toml`, at noon with clear weather,
so runs are comparable. Results land in `results/<timestamp>-<label>.json`.

| Env | Default | |
|---|---|---|
| `DH_DIR` | current directory | Distant Horizons checkout to build and run |
| `SOURCE_WORLD` | `$DH_DIR/run/client/saves/New World` | world snapshotted on the first run |
| `DH_CONFIG` | | whitespace separated `section.key=value` DH config overrides, e.g. `client.advanced.debugging.lodOnlyMode=true` |
| `WIDTH` / `HEIGHT` | `1600` / `900` | window size |
| `LOADER` | `fabric` | loader sub-project to run |
| `JFR` | `0` | `1` records a Java Flight Recording next to the result |
| `SHOW_ANGLES` | | `yaw:pitch,...` holds each view and screenshots it (needs `grim`) instead of measuring |

## What gets measured

- `dh` / `vanilla`: average FPS, 1% low FPS, average/p50/p99/max frame time, the time DH spends on the
  render thread per frame (LOD passes, render thread tasks and vanilla fade passes) and the GPU time of each
  DH render step (GL timestamp queries, only on MC's OpenGL backend).
  `vanilla` is the same scene with DH's renderer disabled, so `dh.avgFrameMs - vanilla.avgFrameMs` is DH's frame cost.
- `views`: the same stats for each camera direction plus DH's `VBO Render Count` and the opaque vertices drawn per detail level.
- `memory`: heap used after a full GC, direct buffers, process RSS, the GPU name and the resolution.
- `lodLoadSeconds`: time from joining the world until DH's render loading queues stay empty for 5 seconds.

## Notes

- Fabric only, MC versions with Mojang mappings in dev (1.17+), `--quickPlaySingleplayer` needs 1.20+.
- Distant generation and the auto updater are disabled, so only the LODs already in the world are measured.
- DH dev builds turn on Blaze3D's per-draw validation (`IS_RUNNING_IN_IDE`), the harness turns it back off
  so numbers match release builds.
- On Hyprland the game window is floated, sized and pinned so tiling doesn't change the resolution.
- Don't touch the game window during a run, and don't edit `bench.sh` while it runs (bash reads scripts as it goes).
