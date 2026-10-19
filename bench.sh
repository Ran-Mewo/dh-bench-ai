#!/usr/bin/env bash
# Dev-only DH benchmark: boots the dev client into a fresh copy of a pre-generated world,
# measures FPS/frame times (DH on vs off) and memory, then quits and prints the results.
#
# Usage (from a Distant Horizons checkout): path/to/bench.sh [label] [extra gradle args, e.g. -PmcVer=26.1.2]
# See README.md for the environment variables.

set -euo pipefail

BENCH_DIR="$(cd "$(dirname "$0")" && pwd)"
DH_DIR="$(cd "${DH_DIR:-$PWD}" && pwd)"
GAME_DIR="$BENCH_DIR/game"
LOADER="${LOADER:-fabric}"
WIDTH="${WIDTH:-1600}"
HEIGHT="${HEIGHT:-900}"
LABEL="${1:-run}"
shift || true

SOURCE_WORLD="${SOURCE_WORLD:-$DH_DIR/run/client/saves/New World}"
RESULT="$BENCH_DIR/results/$(date +%Y%m%d-%H%M%S)-$LABEL.json"

if [ ! -f "$DH_DIR/gradlew" ]; then
	echo "Run this from a Distant Horizons checkout or set DH_DIR" >&2
	exit 1
fi

if [ ! -d "$BENCH_DIR/template" ]; then
	echo "Snapshotting [$SOURCE_WORLD] as the benchmark world..."
	cp -r --reflink=auto "$SOURCE_WORLD" "$BENCH_DIR/template"
	rm -f "$BENCH_DIR/template/session.lock"
fi

# every run starts from the same world, options and DH config
mkdir -p "$GAME_DIR/saves" "$GAME_DIR/config" "$BENCH_DIR/results"
rm -rf "$GAME_DIR/saves/benchmark"
cp -r --reflink=auto "$BENCH_DIR/template" "$GAME_DIR/saves/benchmark"
cp "$BENCH_DIR/options.txt" "$GAME_DIR/options.txt"

# DH throws away config files with a different version
CONFIG_VERSION="$(grep -ho 'CONFIG_FILE_VERSION = [0-9]*' -r "$DH_DIR/coreSubProjects/api/src" | grep -o '[0-9]*$')"
# shellcheck disable=SC2086 # DH_CONFIG is a whitespace separated list of section.key=value
python3 -I "$BENCH_DIR/config.py" "$BENCH_DIR/DistantHorizons.toml" "$CONFIG_VERSION" ${DH_CONFIG:-} > "$GAME_DIR/config/DistantHorizons.toml"

# tiling window managers would otherwise size the window to fit whatever else is open,
# pinning keeps it visible (and rendering normally) on every workspace
float_game_window()
{
	command -v hyprctl > /dev/null || return 0
	for _ in $(seq 900); do
		address="$(hyprctl clients -j | python3 -I -c 'import json, sys; print(next((c["address"] for c in json.load(sys.stdin) if c["class"] == "com.mojang.minecraft" or c["title"].startswith("Minecraft")), ""))')"
		if [ -n "$address" ]; then
			hyprctl --batch "dispatch setfloating address:$address; dispatch resizewindowpixel exact $WIDTH $HEIGHT,address:$address; dispatch movewindowpixel exact 0 0,address:$address; dispatch pin address:$address" > /dev/null
			return 0
		fi
		sleep 1
	done
}
float_game_window &

# SHOW_ANGLES="yaw:pitch,..." screenshots those views instead of measuring, to look at rendering issues
screenshot_shown_angles()
{
	tail -n 0 -F "$GAME_DIR/logs/latest.log" 2> /dev/null | grep --line-buffered -o "showing yaw .* pitch [^ ]*" | while read -r _ _ yaw _ pitch; do
		grim -g "0,0 ${WIDTH}x${HEIGHT}" "${RESULT%.json}-yaw$yaw-pitch$pitch.png"
		echo "screenshot: ${RESULT%.json}-yaw$yaw-pitch$pitch.png"
	done
}

GRADLE_ARGS=("-Pdhbench.dir=$BENCH_DIR" "-Pdhbench.output=$RESULT" "-Pdhbench.loader=$LOADER" "-Pdhbench.width=$WIDTH" "-Pdhbench.height=$HEIGHT")
if [ -n "${SHOW_ANGLES:-}" ]; then
	GRADLE_ARGS+=("-Pdhbench.showAngles=$SHOW_ANGLES")
	screenshot_shown_angles &
fi
if [ "${JFR:-0}" = "1" ]; then
	GRADLE_ARGS+=("-Pdhbench.jfr=${RESULT%.json}.jfr")
fi

cd "$DH_DIR"
./gradlew --init-script "$BENCH_DIR/benchmark.init.gradle" "${GRADLE_ARGS[@]}" "$@" ":$LOADER:runClient"
kill $(jobs -p) 2> /dev/null

python3 -I "$BENCH_DIR/summary.py" "$RESULT"
