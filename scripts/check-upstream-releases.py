#!/usr/bin/env python3
"""Report relevant upstream release changes without importing donor code."""

import argparse
import json
import os
import re
import subprocess
from pathlib import Path
from urllib.parse import quote

ISSUE_TITLE = "Review upstream releases for Kizuku"


def api(path, body=None, method=None):
    command = ["gh", "api", path]
    if method:
        command += ["--method", method]
    if body is not None:
        command += ["--input", "-"]
    return json.loads(subprocess.check_output(
        command, input=None if body is None else json.dumps(body), text=True,
    ))


def inspect(repo, baseline):
    releases = api(f"repos/{repo}/releases?per_page=30")
    latest = max(
        (release for release in releases if not release["draft"] and release["published_at"]),
        key=lambda release: (release["published_at"], release["id"]),
    )
    # Resolve the tag as a commit, peeling annotated tags rather than trusting
    # target_commitish, which frequently contains the current branch name.
    tag = quote(latest["tag_name"], safe="")
    commit = api(f"repos/{repo}/commits/{tag}")["sha"]
    record = {
        "repo": repo, "tag": latest["tag_name"], "commit": commit,
        "published_at": latest["published_at"], "prerelease": latest["prerelease"],
        "url": latest["html_url"], "relevant": [],
    }
    if commit == baseline["commit"] or latest["published_at"] < baseline["published_at"]:
        return record, False
    compare = f"{baseline['commit']}...{commit}"
    record["compare"] = f"https://github.com/{repo}/compare/{compare}"
    record["previous_tag"] = baseline["tag"]
    try:
        comparison = api(f"repos/{repo}/compare/{compare}?per_page=1")
        files = comparison.get("files", [])
        relevant = re.compile(baseline["paths"], re.IGNORECASE)
        record["relevant"] = [
            row["filename"] for row in files
            if relevant.search(row["filename"]) or relevant.search(row.get("previous_filename", ""))
        ]
        # GitHub caps the changed-file list at 300.
        if len(files) >= 300 or comparison["status"] in {"behind", "diverged"}:
            record["relevant"].append("Review required because compare history is incomplete.")
    except subprocess.CalledProcessError:
        record["relevant"] = ["Review required because the upstream comparison failed."]
    return record, any(asset["name"].endswith(".apk") for asset in latest["assets"])


def update_issue(repo, changes):
    lines = [
        "Review release diffs affecting Kizuku's compatibility surfaces.",
        "Reproduce the applicable changes on Android 16 and 17 and update the pins after triage.",
        "",
    ]
    for change in changes:
        stage = " prerelease" if change["prerelease"] else ""
        lines.extend([
            f"- [ ] **{change['repo']}** {change['previous_tag']} → "
            f"[{change['tag']}]({change['url']}){stage} at {change['commit']}",
            f"  [Compare changes]({change['compare']})",
            *[f"  - {path}" for path in change["relevant"][:8]],
        ])
    body = "\n".join(lines) + "\n"
    # The title and author identify the single issue owned by the workflow.
    issues = subprocess.check_output([
        "gh", "api", "--paginate", "--slurp", f"repos/{repo}/issues?state=open&per_page=100",
    ], text=True)
    issue = next((item for page in json.loads(issues) for item in page
        if item["title"] == ISSUE_TITLE and item["user"]["login"] == "github-actions[bot]"
        and "pull_request" not in item), None)
    if issue is None:
        api(f"repos/{repo}/issues", {"title": ISSUE_TITLE, "body": body}, "POST")
    elif issue["body"] != body:
        api(f"repos/{repo}/issues/{issue['number']}", {"body": body}, "PATCH")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--download-dir", type=Path)
    parser.add_argument("--update-issue", action="store_true")
    args = parser.parse_args()
    baseline = json.loads(Path("upstream-releases.json").read_text())
    report = []
    for repo, pin in baseline.items():
        record, has_apk = inspect(repo, pin)
        report.append(record)
        if args.download_dir and has_apk and record["relevant"]:
            target = args.download_dir / repo.replace("/", "-")
            target.mkdir(parents=True, exist_ok=True)
            subprocess.run(
                ["gh", "release", "download", record["tag"], "--repo", repo,
                 "--dir", str(target), "--pattern", "*.apk"], check=True,
            )
    args.report.write_text(json.dumps(report, indent=2) + "\n")
    changes = [release for release in report if release["relevant"]]
    if changes and args.update_issue:
        update_issue(os.environ["GITHUB_REPOSITORY"], changes)


if __name__ == "__main__":
    main()
