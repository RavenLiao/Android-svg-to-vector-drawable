#!/usr/bin/env python3
"""Resolve and verify an explicit svg2vd GitHub Release without shelling out."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Optional

DEFAULT_REPOSITORY = "RavenLiao/Android-svg-to-vector-drawable"
TAG_RE = re.compile(r"^v([0-9]+\.[0-9]+\.[0-9]+)$")
JAR_RE = re.compile(r"^svg2vd-([0-9]+\.[0-9]+\.[0-9]+)-studio-([0-9]+\.[0-9]+\.[0-9]+(?:-patch0*[1-9][0-9]*)?)-all\.jar$")


class ReleaseError(RuntimeError):
    pass


def cache_root(override: Optional[str]) -> Path:
    if override:
        return Path(override).expanduser()
    env_root = os.environ.get("SVG2VD_CACHE_DIR")
    if env_root:
        return Path(env_root).expanduser()
    if platform.system() == "Windows":
        local = os.environ.get("LOCALAPPDATA")
        if local:
            return Path(local) / "svg2vd" / "cache"
    return Path(os.environ.get("XDG_CACHE_HOME", "~/.cache")).expanduser() / "svg2vd"


def request_json(url: str) -> Dict[str, Any]:
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "svg2vd-cli-skill"}
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        if error.code == 403 and shutil.which("gh"):
            endpoint = url[len("https://api.github.com/"):]
            fallback = subprocess.run(["gh", "api", endpoint], text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
            if fallback.returncode == 0:
                try:
                    payload = json.loads(fallback.stdout)
                except json.JSONDecodeError as parse_error:
                    raise ReleaseError(f"gh api returned invalid JSON: {parse_error}") from parse_error
            else:
                raise ReleaseError("GitHub API rate limit exceeded; set GH_TOKEN or run gh auth login") from error
        else:
            raise ReleaseError(f"GitHub Release lookup failed: HTTP {error.code}") from error
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
        raise ReleaseError(f"GitHub Release lookup failed: {error}") from error
    if not isinstance(payload, dict):
        raise ReleaseError("GitHub Release response is not an object")
    return payload


def download(url: str, target: Path) -> None:
    parsed_url = urllib.parse.urlparse(url)
    if parsed_url.scheme != "https" or parsed_url.hostname not in {"github.com", "objects.githubusercontent.com"}:
        raise ReleaseError(f"refusing non-HTTPS asset URL: {url}")
    request = urllib.request.Request(url, headers={"User-Agent": "svg2vd-cli-skill"})
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary: Optional[Path] = None
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            final_host = urllib.parse.urlparse(response.geturl()).hostname
            if final_host not in {"github.com", "objects.githubusercontent.com"}:
                raise ReleaseError(f"refusing redirected asset URL host: {final_host}")
            with tempfile.NamedTemporaryFile(dir=target.parent, prefix=".download-", delete=False) as output:
                temporary = Path(output.name)
                while True:
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    output.write(chunk)
        os.replace(temporary, target)
        temporary = None
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError) as error:
        raise ReleaseError(f"asset download failed: {error}") from error
    finally:
        if temporary is not None:
            try:
                temporary.unlink()
            except FileNotFoundError:
                pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def checksum_for(checksums: Path, asset_name: str) -> str:
    for line in checksums.read_text(encoding="utf-8").splitlines():
        fields = line.strip().split()
        if len(fields) >= 2 and fields[-1] == asset_name and re.fullmatch(r"[0-9a-fA-F]{64}", fields[0]):
            return fields[0].lower()
    raise ReleaseError(f"SHA256SUMS has no entry for {asset_name}")


def release_from_api(repository: str, version: str) -> Dict[str, Any]:
    base = f"https://api.github.com/repos/{repository}/releases"
    payload = request_json(f"{base}/latest" if version == "latest" else f"{base}/tags/{version}")
    if payload.get("draft") or payload.get("prerelease"):
        raise ReleaseError(f"release {payload.get('tag_name', version)} is not a stable published release")
    tag = payload.get("tag_name")
    if not isinstance(tag, str) or not TAG_RE.fullmatch(tag):
        raise ReleaseError(f"release has an invalid tool tag: {tag!r}")
    return payload


def resolve(repository: str, version: str, cache: Path, refresh: bool) -> Dict[str, Any]:
    if version != "latest" and not TAG_RE.fullmatch(version):
        raise ReleaseError("version must be latest or an exact vX.Y.Z tag")
    cache.mkdir(parents=True, exist_ok=True)
    if version != "latest":
        cached = cache / version
        marker = cached / "release.json"
        if not refresh and marker.is_file():
            try:
                result = json.loads(marker.read_text(encoding="utf-8"))
                if not isinstance(result, dict) or result.get("repository") != repository or result.get("tag") != version:
                    raise ReleaseError("cached release marker identity does not match the requested release")
                jar_value = result.get("jar")
                jar_sha_value = result.get("jar_sha256")
                if not isinstance(jar_value, str) or not isinstance(jar_sha_value, str):
                    raise ReleaseError("cached release marker is missing JAR metadata")
                jar_name = Path(jar_value).name
                match = JAR_RE.fullmatch(jar_name)
                tag_version = TAG_RE.fullmatch(version).group(1)
                if match is None or match.group(1) != tag_version:
                    raise ReleaseError("cached release JAR name is invalid")
                cached_root = cached.resolve()
                jar = (cached / jar_name).resolve()
                if jar.parent != cached_root or Path(jar_value).expanduser().resolve() != jar or not jar.is_file():
                    raise ReleaseError("cached release JAR path is outside its release directory")
                checksums = cached / "SHA256SUMS"
                if not checksums.is_file() or sha256(jar) != jar_sha_value.lower() or sha256(jar) != checksum_for(checksums, jar_name):
                    raise ReleaseError("cached release JAR integrity check failed")
                result["cached"] = True
                return result
            except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError, ReleaseError):
                pass
    release = release_from_api(repository, version)
    tag = release["tag_name"]
    assets = release.get("assets")
    if not isinstance(assets, list):
        raise ReleaseError("release has no asset list")
    by_name = {item.get("name"): item for item in assets if isinstance(item, dict)}
    jars = [name for name in by_name if isinstance(name, str) and JAR_RE.fullmatch(name)]
    if len(jars) != 1:
        raise ReleaseError(f"expected exactly one svg2vd release JAR, found {jars}")
    jar_name = jars[0]
    for required in ("SHA256SUMS", "provenance.json"):
        if required not in by_name:
            raise ReleaseError(f"release is missing {required}")
    destination = cache / tag
    destination.mkdir(parents=True, exist_ok=True)
    for name in (jar_name, "SHA256SUMS", "provenance.json"):
        url = by_name[name].get("browser_download_url")
        if not isinstance(url, str):
            raise ReleaseError(f"asset {name} has no download URL")
        download(url, destination / name)
    jar = destination / jar_name
    checksums = destination / "SHA256SUMS"
    jar_sha = sha256(jar)
    expected_sha = checksum_for(checksums, jar_name)
    if jar_sha != expected_sha:
        raise ReleaseError(f"JAR SHA-256 mismatch: expected {expected_sha}, got {jar_sha}")
    match = JAR_RE.fullmatch(jar_name)
    assert match is not None
    tag_version = TAG_RE.fullmatch(tag).group(1)
    if match.group(1) != tag_version:
        raise ReleaseError(f"JAR tool version {match.group(1)} does not match release tag {tag}")
    warnings: List[str] = []
    provenance_path = destination / "provenance.json"
    provenance: Dict[str, Any] = {}
    try:
        parsed = json.loads(provenance_path.read_text(encoding="utf-8"))
        if isinstance(parsed, dict):
            provenance = parsed
        else:
            warnings.append("provenance.json is not an object")
    except (OSError, json.JSONDecodeError) as error:
        warnings.append(f"provenance.json could not be parsed: {error}")
    if provenance.get("tool_version") not in (None, match.group(1)):
        warnings.append("provenance tool_version does not match the JAR name")
    result = {
        "repository": repository,
        "tag": tag,
        "tool_version": match.group(1),
        "upstream_tag": provenance.get("upstream_tag"),
        "jar": str(jar.resolve()),
        "jar_sha256": jar_sha,
        "cache_dir": str(destination.resolve()),
        "cached": False,
        "warnings": warnings,
    }
    (destination / "release.json").write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description="Resolve and verify an svg2vd GitHub Release.")
    parser.add_argument("--repo", default=DEFAULT_REPOSITORY)
    parser.add_argument("--version", default="latest", help="latest or an exact vX.Y.Z tag")
    parser.add_argument("--cache-dir")
    parser.add_argument("--refresh", action="store_true")
    args = parser.parse_args()
    try:
        result = resolve(args.repo, args.version, cache_root(args.cache_dir), args.refresh)
    except ReleaseError as error:
        print(f"svg2vd release resolution failed: {error}", file=sys.stderr)
        return 4
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
