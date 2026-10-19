"""Writes the benchmark's DistantHorizons.toml: config.py <base.toml> <config version> [section.key=value ...]"""
import sys

base_path, config_version, overrides = sys.argv[1], sys.argv[2], sys.argv[3:]

sections = {}
section = ""
for line in open(base_path).read().splitlines():
    stripped = line.strip()
    if stripped.startswith("[") and stripped.endswith("]"):
        section = stripped[1:-1]
        sections.setdefault(section, {})
    elif "=" in stripped and not stripped.startswith("#"):
        key, value = (part.strip() for part in stripped.split("=", 1))
        sections.setdefault(section, {})[key] = value

for override in overrides:
    path, value = override.split("=", 1)
    section, key = path.rsplit(".", 1)
    sections.setdefault(section, {})[key] = value

print(f"_version = {config_version}")
for section, values in sections.items():
    print(f"\n[{section}]")
    for key, value in values.items():
        print(f"\t{key} = {value}")
