"""Prepare reproducible Android release versions; ordinary builds never increment them."""
from pathlib import Path
import argparse
import json
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
CORE = r"(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)"
CHANNEL = r"[a-z][a-z0-9]*(?:-[a-z0-9]+)*"
KNOWN_BRANCHES = {"main": "main", "master": "main", "酸性设计": "acid"}
RELEASE_START = "<!-- release-version:start -->"
RELEASE_END = "<!-- release-version:end -->"
REPO = "https://github.com/Pitohui-Dichrous/WebSurf"


def parse_properties(text):
    values = {}
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith(("#", "!")):
            continue
        key, separator, value = line.partition("=")
        key, value = key.strip(), value.strip()
        if not separator or key in values:
            raise ValueError("Invalid or duplicate version property: " + key)
        values[key] = value
    if set(values) != {"base", "code", "channel", "sequence", "branch"}:
        raise ValueError("version.properties requires base, code, channel, sequence, branch")
    if not re.fullmatch(CORE, values["base"]) or not re.fullmatch(CHANNEL, values["channel"]):
        raise ValueError("Invalid base version or channel")
    if not values["code"].isdigit() or not values["sequence"].isdigit():
        raise ValueError("code and sequence must be integers")
    values["code"], values["sequence"] = int(values["code"]), int(values["sequence"])
    if not 1 <= values["code"] <= 2100000000:
        raise ValueError("Android versionCode is outside its supported range")
    if not values["branch"] or "`" in values["branch"]:
        raise ValueError("branch must be a nonempty Git branch name without backticks")
    main = values["channel"] == "main"
    if (main and values["sequence"] != 0) or (not main and values["sequence"] < 1):
        raise ValueError("main requires sequence=0; preview channels require sequence>=1")
    values["name"] = values["base"] + "-" + values["channel"]
    if not main:
        values["name"] += "." + str(values["sequence"])
    return values


def load_version(root=ROOT):
    return parse_properties((Path(root) / "version.properties").read_text(encoding="utf-8-sig"))


def git(root, *args, required=True):
    result = subprocess.run(["git", "-C", str(root), *args], capture_output=True,
                            text=True, encoding="utf-8")
    if result.returncode and required:
        raise ValueError("Git failed: " + result.stderr.strip())
    return result.stdout.strip() if result.returncode == 0 else None


def current_branch(root):
    if not (Path(root) / ".git").exists():
        return None  # A source archive must not inherit an enclosing repository's branch.
    return git(root, "symbolic-ref", "--quiet", "--short", "HEAD", required=False)


def validate_branch(info, root=ROOT):
    branch = current_branch(root)
    if branch is not None and (branch != info["branch"] or
                              KNOWN_BRANCHES.get(branch, info["channel"]) != info["channel"]):
        raise ValueError("Version metadata belongs to another branch; run scripts/version.py prepare first")
    return branch


def tags(root):
    if not (Path(root) / ".git").exists():
        return []
    return git(root, "tag", "--list").splitlines()


def next_code(root, previous, release_tags):
    maximum = previous
    for tag in release_tags:
        if not re.fullmatch("v" + CORE + r"(?:-" + CHANNEL + r"(?:\.\d+)?)?", tag):
            continue
        text = git(root, "show", tag + ":version.properties", required=False)
        if text is not None:
            maximum = max(maximum, parse_properties(text)["code"])
            continue
        text = git(root, "show", tag + ":app/build.gradle", required=False) or ""
        match = re.search(r"(?m)^\s*versionCode\s+(\d+)\s*$", text)
        if match:
            maximum = max(maximum, int(match[1]))
    return maximum + 1


def update_docs(root, info, release_tags):
    stable_tags = [tag for tag in release_tags if re.fullmatch("v" + CORE + r"(?:-main)?", tag)]
    stable = max(stable_tags, key=lambda tag: tuple(map(int, tag[1:].split("-")[0].split("."))),
                 default=None)
    name, branch, channel = info["name"], info["branch"], info["channel"]
    main = channel == "main"
    label = "WebSurf" if main else "酸性设计" if channel == "acid" else channel
    public_name = info["base"] if main else name
    title = f"**{label} · {public_name}" + ("**" if main else " 视觉预览版**")
    download = "下载 APK" if main else "下载预览版"
    link = f"**[{download}]({REPO}/releases/download/v{name}/WebSurf-{name}.apk)**"
    if not main and stable:
        link += f" · [稳定版]({REPO}/releases/tag/{stable})"
    block = (RELEASE_START + "\n" + title + "\n\n"
             + "黑白与荧光绿、硬边色块、粗窄字体。" + ("" if main else f"此版本来自 `{branch}` 分支。") + "\n\n"
             + link + "\n" + RELEASE_END)
    readme_path, usage_path = Path(root) / "README.md", Path(root) / "docs/USAGE.md"
    readme = readme_path.read_text(encoding="utf-8")
    if RELEASE_START in readme:
        pattern = re.escape(RELEASE_START) + r".*?" + re.escape(RELEASE_END)
    else:
        pattern = r"^\*\*酸性设计 · .*?(?=\n\n## 基本功能)"
    readme, changed = re.subn(pattern, lambda _: block, readme, flags=re.S | re.M)
    if changed != 1:
        raise ValueError("README release block is missing or duplicated; no files changed")
    usage = usage_path.read_text(encoding="utf-8")
    heading = f"版本：**{public_name}**，" + ("正式版。" if main else f"`{branch}` 分支的公开预览版。")
    if not main and stable:
        heading += f"稳定版仍为 {stable[1:]}。"
    usage, changed = re.subn(r"^版本：[^\n]*$", lambda _: heading, usage, flags=re.M)
    if changed != 1:
        raise ValueError("USAGE version heading is missing or duplicated; no files changed")
    usage, changed = re.subn(r"安装 `WebSurf-[^`]+\.apk`", f"安装 `WebSurf-{name}.apk`", usage, count=1)
    if changed != 1:
        raise ValueError("USAGE install filename is missing; no files changed")
    return {readme_path: readme, usage_path: usage}


def prepare(root, *, branch_update=False, patch=False, minor=False, version=None, channel=None):
    root = Path(root)
    old = load_version(root)
    branch = current_branch(root)
    if branch is None:
        raise ValueError("Preparing a release requires a named Git branch")
    inferred = KNOWN_BRANCHES.get(branch)
    if channel and inferred and channel != inferred:
        raise ValueError("Explicit channel conflicts with the known branch channel")
    channel = inferred or channel or (old["channel"] if old["branch"] == branch else None)
    if channel is None or not re.fullmatch(CHANNEL, channel):
        raise ValueError("Unknown branch: provide --channel with its stable lowercase slug")
    main = channel == "main"
    if main and branch not in {"main", "master"}:
        raise ValueError("The main channel is reserved for the main/master branch")
    if not main and (patch or minor or (version is not None and version != old["base"])):
        raise ValueError("Preview branches keep the base version; use prepare --branch. Increase the base only when promoting to main")
    if main and (branch_update or not (patch or minor or version is not None)):
        raise ValueError("Promoting to main requires --patch, --minor or --version X.Y.Z")
    if git(root, "remote", "get-url", "origin", required=False) is not None:
        git(root, "fetch", "--tags", "origin")
    release_tags = tags(root)
    if "v" + old["name"] not in release_tags and old["branch"] == branch:
        raise ValueError("This branch already has an unreleased version; use check before preparing another release")
    if not main:
        version = old["base"]
    elif version is None:
        major, minor_number, patch_number = map(int, old["base"].split("."))
        version = f"{major}.{minor_number + 1}.0" if minor else f"{major}.{minor_number}.{patch_number + 1}"
    if not re.fullmatch(CORE, version):
        raise ValueError("--version requires a semantic base such as 0.6.1")
    if main and tuple(map(int, version.split("."))) <= tuple(map(int, old["base"].split("."))):
        raise ValueError("The new base version must be greater than the stored base")
    ordinals = [int(match[1]) for tag in release_tags
                if (match := re.fullmatch("v" + CORE + "-" + re.escape(channel) + r"\.(\d+)", tag))]
    sequence = 0 if channel == "main" else max(ordinals, default=0) + 1
    text = (f"base={version}\ncode={next_code(root, old['code'], release_tags)}\n"
            f"channel={channel}\nsequence={sequence}\nbranch={branch}\n")
    info = parse_properties(text)
    if "v" + info["name"] in release_tags:
        raise ValueError("The generated release tag already exists")
    writes = update_docs(root, info, release_tags)
    writes[root / "version.properties"] = text
    for path, content in writes.items():
        path.write_text(content, encoding="utf-8")
    return info


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    check = commands.add_parser("check", help="Validate the persisted version and branch")
    check.add_argument("--unreleased", action="store_true", help="Also reject an existing release tag")
    update = commands.add_parser("prepare", help="Advance version metadata and release docs once")
    choice = update.add_mutually_exclusive_group()
    choice.add_argument("--branch", dest="branch_update", action="store_true",
                        help="Keep the base version and advance the preview release ordinal")
    choice.add_argument("--patch", action="store_true")
    choice.add_argument("--minor", action="store_true")
    choice.add_argument("--version")
    update.add_argument("--channel")
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            info = prepare(ROOT, branch_update=args.branch_update, patch=args.patch,
                           minor=args.minor, version=args.version, channel=args.channel)
        else:
            info = load_version()
            branch = validate_branch(info)
            if args.unreleased:
                if branch is None:
                    raise ValueError("--unreleased requires a named Git branch and local release tags")
                if "v" + info["name"] in tags(ROOT):
                    raise ValueError("Version is already tagged; run prepare before a new release")
        print(json.dumps(info, ensure_ascii=False, indent=2))
    except (ValueError, OSError) as error:
        parser.exit(2, str(error) + "\n")


if __name__ == "__main__":
    main()
