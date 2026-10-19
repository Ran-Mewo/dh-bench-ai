"""Prints a benchmark result, or compares two: summary.py <result.json> [baseline.json]"""
import json
import sys


def load(path):
    with open(path) as file:
        return json.load(file)


def row(name, stats):
    return (f"{name:<10} {stats['avgFps']:8.1f} fps  1%low {stats['onePercentLowFps']:7.1f}  "
            f"avg {stats['avgFrameMs']:6.2f} ms  p99 {stats['p99FrameMs']:6.2f} ms  "
            f"DH render thread {stats['dhRenderThreadMsPerFrame']:5.2f} ms")


def print_result(path):
    result = load(path)
    print(f"\n== {path}")
    if "error" in result:
        print(f"ERROR: {result['error']}")
    for key in ("renderingApi", "lodsFullyLoaded", "lodLoadSeconds"):
        if key in result:
            print(f"{key}: {result[key]}")
    for name in ("dh", "vanilla"):
        if name in result:
            print(row(name, result[name]))
            gpu = result[name].get("gpuMsPerFrame", {})
            if gpu:
                print(f"{'':<10} GPU ms/frame {sum(gpu.values()):.2f}: " + ", ".join(f"{step} {ms:.2f}" for step, ms in sorted(gpu.items(), key=lambda item: -item[1])))
    if "dh" in result and "vanilla" in result:
        print(f"DH frame cost: {result['dh']['avgFrameMs'] - result['vanilla']['avgFrameMs']:.2f} ms")
    for view in result.get("views", []):
        vertices = view.get("opaqueVertices", {}).get("total", 0)
        print("   " + row(f"{view['renderer']}@{view['yaw']}", view) + f"  {view['vboRenderCount']}  opaque vertices {vertices / 1e6:.2f}M")
    memory = result.get("memory", {})
    print("memory: " + ", ".join(f"{key} {value:.0f}" if isinstance(value, float) else f"{key} {value}" for key, value in memory.items()))
    return result


def compare(new, old):
    print("\n== change vs baseline")
    for name in ("dh", "vanilla"):
        if name in new and name in old:
            fps_change = (new[name]["avgFps"] / old[name]["avgFps"] - 1) * 100
            ms_change = new[name]["avgFrameMs"] - old[name]["avgFrameMs"]
            print(f"{name:<10} avg fps {fps_change:+6.1f}%   frame time {ms_change:+6.2f} ms   "
                  f"1%low {(new[name]['onePercentLowFps'] / old[name]['onePercentLowFps'] - 1) * 100:+6.1f}%")
    if "memory" in new and "memory" in old:
        print(f"heap used {new['memory']['heapUsedMb'] - old['memory']['heapUsedMb']:+.0f} MB")


new_result = print_result(sys.argv[1])
if len(sys.argv) > 2:
    compare(new_result, print_result(sys.argv[2]))
