#!/usr/bin/env python3
"""Create a compact immutable APK distribution tag without changing a branch/index.

The source vVERSION tag and the released APK remain unchanged. Artifact blobs are
reused from that tag, in an orphan commit containing four assets and provenance.
No push is performed. Use --dry-run to validate everything without writing objects
or creating a tag. jsDelivr's GitHub package limit applies to the complete tree:
https://github.com/jsdelivr/data.jsdelivr.com#restrictions
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

PACKAGE_LIMIT = 50_000_000  # Conservative decimal MB; the whole tree must be smaller.
FILE_LIMIT = 20_000_000
VERSION = re.compile(r"\d+\.\d+\.\d+(?:beta)?-build(\d+)\Z")


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def git(repo, *args, data=None, env=None):
    result = subprocess.run(["git", "-C", str(repo), *args], input=data, capture_output=True, env=env)
    require(result.returncode == 0, f"git {' '.join(args)} failed: {result.stderr.decode('utf-8', 'replace').strip()}")
    return result.stdout


def tree_entries(repo, ref):
    entries = {}
    for row in git(repo, "ls-tree", "-r", "-l", "-z", ref).split(b"\0"):
        if not row:
            continue
        fields, path = row.split(b"\t", 1)
        mode, kind, oid, size = fields.split()
        require(kind == b"blob" and size.isdigit(), "Distribution trees cannot contain submodules")
        entries[path.decode("utf-8")] = (mode.decode(), oid.decode(), int(size))
    return entries


def make_tree(repo, entries):
    rows = b"".join(f"{mode} {kind} {oid}\t{name}".encode("utf-8") + b"\0"
                    for name, mode, kind, oid in sorted(entries))
    return git(repo, "mktree", "-z", data=rows).decode().strip()


def create(repo, version, dry_run=False):
    match = VERSION.fullmatch(version)
    require(match, "Expected a product version such as 2.6.4-build123")
    source_ref, distribution_ref = f"v{version}", f"apk-{version}"
    source = git(repo, "rev-parse", "--verify", f"refs/tags/{source_ref}^{{commit}}").decode().strip()
    prefix = f"releases/{version}/"
    names = [f"YMPlayer-{version}.apk", "build.json", "SHA256.txt", "signature.txt"]
    entries = tree_entries(repo, source)
    expected = {}
    payloads = {}
    for name in names:
        path = prefix + name
        require(path in entries, f"Source tag {source_ref} does not contain {path}")
        mode, oid, size = entries[path]
        require(mode == "100644", f"Release asset must be an ordinary file: {path}")
        data = git(repo, "cat-file", "blob", oid)
        local = repo / path
        require(local.is_file(), f"Local release asset is missing: {path}")
        if name.endswith(".apk"):
            require(local.read_bytes() == data, f"Local APK bytes differ from immutable source tag: {path}")
        else:
            # Git may normalize committed text to LF while the published attachment uses CRLF.
            # Reuse the source blob; accept only BOM/newline normalization, never content changes.
            source_text = data.decode("utf-8-sig").replace("\r\n", "\n")
            local_text = local.read_text(encoding="utf-8-sig").replace("\r\n", "\n")
            require(local_text == source_text, f"Local text asset differs from immutable source tag: {path}")
        require(size == len(data), f"Git asset size mismatch: {path}")
        expected[path] = (mode, oid, size)
        payloads[name] = data
    metadata = json.loads(payloads["build.json"].decode("utf-8-sig"))
    apk_name = names[0]
    apk_sha = hashlib.sha256(payloads[apk_name]).hexdigest()
    require(metadata.get("versionName") == version and metadata.get("versionCode") == int(match.group(1)), "Metadata version mismatch")
    require(metadata.get("applicationId") == "dev.petrov.ymplayer2" and isinstance(metadata.get("minSdk"), int)
            and metadata["minSdk"] > 0, "Metadata is not the released 2.x product")
    require(metadata.get("sha256") == apk_sha, "APK SHA256 differs from build.json")
    checksum = payloads["SHA256.txt"].decode("utf-8-sig").strip().split()
    require(checksum == [apk_sha, apk_name], "SHA256.txt does not match the released APK")
    require(len(payloads[apk_name]) < FILE_LIMIT, "APK exceeds the conservative jsDelivr individual-file limit")
    readme = f"""# YMPlayer2 {version} — APK distribution

This immutable snapshot contains only the original release assets and this provenance.
It exists to keep the complete GitHub package below jsDelivr's 50 MB limit.
The APK was not rebuilt, re-signed or modified.

- Source release: https://github.com/reziarlleh/YMPlayer2/releases/tag/{source_ref}
- Source Git tag: `{source_ref}`
- Source commit: `{source}`
- Verified runtime source: `{metadata.get('verifiedSourceCommit', 'see source release verification')}`
- APK: `{prefix}{apk_name}`
- APK SHA256: `{apk_sha}`

The source tag, main branch and development history remain in the original repository.
""".encode("utf-8")
    # Compute the Git blob id without writing anything; this also makes --dry-run read-only.
    readme_oid = git(repo, "hash-object", "--stdin", data=readme).decode().strip()
    expected["README.md"] = ("100644", readme_oid, len(readme))
    total = sum(item[2] for item in expected.values())
    require(total < PACKAGE_LIMIT, f"Compact distribution tree is too large: {total} bytes")
    existing = subprocess.run(["git", "-C", str(repo), "show-ref", "--verify", "--quiet", f"refs/tags/{distribution_ref}"])
    require(existing.returncode in (0, 1), "Cannot inspect existing distribution tag")
    if existing.returncode == 0:
        commit = git(repo, "rev-parse", "--verify", f"refs/tags/{distribution_ref}^{{commit}}").decode().strip()
        require(tree_entries(repo, commit) == expected, "Existing distribution tag differs; refusing to overwrite it")
        header = git(repo, "cat-file", "commit", commit).split(b"\n\n", 1)[0]
        require(not any(line.startswith(b"parent ") for line in header.splitlines()), "Existing distribution tag is not an orphan snapshot")
        return {"tag": distribution_ref, "sourceTag": source_ref, "sourceCommit": source,
                "commit": commit, "treeBytes": total, "apkSha256": apk_sha, "reused": True, "pushed": False}
    plan = {"tag": distribution_ref, "sourceTag": source_ref, "sourceCommit": source,
            "treeBytes": total, "apkSha256": apk_sha, "reused": False, "pushed": False}
    if dry_run:
        return {**plan, "dryRun": True, "files": sorted(expected)}
    written = git(repo, "hash-object", "-w", "--stdin", data=readme).decode().strip()
    require(written == readme_oid, "Provenance blob changed unexpectedly")
    leaves = make_tree(repo, [(name, expected[prefix + name][0], "blob", expected[prefix + name][1]) for name in names])
    releases = make_tree(repo, [(version, "040000", "tree", leaves)])
    tree = make_tree(repo, [("README.md", "100644", "blob", readme_oid), ("releases", "040000", "tree", releases)])
    require(tree_entries(repo, tree) == expected, "Generated distribution tree differs from the release")
    message = f"APK distribution for YMPlayer2 {version}\n\nSource: {source_ref}@{source}\nAPK SHA256: {apk_sha}\n"
    commit = git(repo, "commit-tree", tree, data=message.encode("utf-8")).decode().strip()
    # Create-only atomic ref update. No branch/index manipulation, force flag or push.
    git(repo, "update-ref", f"refs/tags/{distribution_ref}", commit, "0" * len(commit))
    return {**plan, "commit": commit, "tree": tree}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    print(json.dumps(create(args.repo.resolve(), args.version, args.dry_run), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
