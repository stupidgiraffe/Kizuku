#!/usr/bin/env python3
"""Metadata-only upstream radar; no third-party code or APK is downloaded."""
import base64
import datetime as dt
import json
import os
from pathlib import Path
import re
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / "tools/upstream-sources.json"
STATE = ROOT / "tools/upstream-state.json"
REPORT = ROOT / "docs/UPSTREAM_RADAR.md"
API = "https://api.github.com"
REPO_PATTERN = re.compile(r"^[\w.-]+/[\w.-]+$")
SIGNALS = re.compile(r"android\s*1[67]|security|vulnerab|permission|authoriz|signature|certificate|dhizuku|device.owner|bridge|shizuku|axeron|axsh|plugin|webui|module|user.?service|pair|adb|startup|boot|watchdog|reconnect|root|compatib|binder|api\b|ui\b|fix", re.I)
FOCUS = re.compile(r"fork|replac|implement|manager|server|runtime|privileg|adb|shizuku|axeron|module", re.I)
NAME = re.compile(r"shizuku|axeron|axmanager|shevery|nightzuku|porter|shizako|stellar|xhizuku", re.I)

def request(path, *, optional=False):
    url = path if path.startswith("https://") else API + path
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "Kizuku-Upstream-Radar/1.0", "X-GitHub-Api-Version": "2022-11-28"}
    if os.environ.get("GH_TOKEN"):
        headers["Authorization"] = "Bearer " + os.environ["GH_TOKEN"]
    for attempt in range(3):
        try:
            with urlopen(Request(url, headers=headers), timeout=18) as resp:
                return json.load(resp)
        except HTTPError as ex:
            if optional and ex.code == 404:
                return None
            if ex.code in (429, 502, 503, 504) and attempt < 2:
                time.sleep(2 ** attempt)
                continue
            raise RuntimeError(f"GitHub request failed ({ex.code}): {url}") from ex
        except (TimeoutError, URLError) as ex:
            if attempt < 2:
                time.sleep(2 ** attempt)
                continue
            raise RuntimeError(f"GitHub request failed: {url}: {ex}") from ex

def clean(s, n=180):
    s = re.sub(r"[\x00-\x1f\x7f]", " ", str(s or ""))
    s = re.sub(r"[<>|]", " ", s)
    return re.sub(r"\s+", " ", s).strip()[:n]

def notes(text):
    lines = []
    for line in (text or "").splitlines():
        line = line.strip().lstrip("-#*• ")
        if SIGNALS.search(line) and len(line) > 12:
            value = clean(line, 200)
            if value and value not in lines:
                lines.append(value)
        if len(lines) == 3:
            break
    return lines

def discover(config, warnings):
    discovered = {}
    try:
        blob = request("/repos/" + config["catalog"] + "/contents/README.md")
        markdown = base64.b64decode(blob["content"]).decode("utf-8")
        section = re.search(r"^### Shizuku implementations\b(.*?)(?=^### |\Z)", markdown, re.M | re.S | re.I)
        if section:
            for repo in re.findall(r"https://github\.com/([\w.-]+/[\w.-]+)", section.group(1), re.I):
                discovered[repo] = "awesome-shizuku catalogue"
        else:
            warnings.append("Community catalog section not found")
    except (RuntimeError, ValueError, KeyError, UnicodeError) as ex:
        warnings.append(clean(ex))
    for query in config["queries"]:
        try:
            results = request("/search/repositories?" + urlencode({"q": query, "sort": "updated", "order": "desc", "per_page": 30}))
            for repo in results.get("items", []):
                slug = repo.get("full_name", "")
                description = repo.get("description") or ""
                if (REPO_PATTERN.fullmatch(slug) and not repo.get("archived")
                    and NAME.search(slug + " " + description)
                    and FOCUS.search(description + " " + slug)
                    and not slug.lower().startswith("stupidgiraffe/")):
                    discovered.setdefault(slug, "GitHub search")
        except (RuntimeError, ValueError) as ex:
            warnings.append(clean(ex))
    return discovered

def inspect(slug, origin, previous=None):
    if not REPO_PATTERN.fullmatch(slug):
        raise ValueError("Unexpected repository name: " + slug)
    repo = request("/repos/" + slug, optional=True)
    if repo is None:
        return {"name": slug, "origin": origin, "missing": True}
    actual = repo["full_name"]
    commits = request("/repos/" + actual + "/commits?per_page=1", optional=True) or []
    releases = request("/repos/" + actual + "/releases?per_page=1", optional=True) or []
    commit = commits[0] if commits else {}
    release = releases[0] if releases else {}
    previous = previous or {}
    diff_subjects = []
    if previous.get("head") and commit.get("sha") and previous["head"] != commit["sha"]:
        try:
            url = "/repos/" + actual + "/compare/" + previous["head"] + "..." + commit["sha"] + "?per_page=40"
            compared = request(url, optional=True) or {}
            for change in compared.get("commits", [])[-30:]:
                subject = (change.get("commit") or {}).get("message", "").split("\n")[0]
                if SIGNALS.search(subject):
                    diff_subjects.append(clean(subject, 160))
        except RuntimeError:
            # A force-push can make a comparison unavailable; still report the new HEAD.
            pass
    return {
        "name": actual, "origin": origin, "url": repo["html_url"],
        "description": clean(repo.get("description")),
        "head": commit.get("sha", ""),
        "head_changes": diff_subjects[-10:],
        "commit_subject": clean((commit.get("commit") or {}).get("message", "").split("\n")[0]),
        "release": release.get("tag_name", ""),
        "release_url": release.get("html_url", ""),
        "release_published": release.get("published_at", ""),
        "release_notes": notes(release.get("body", "")),
        "pushed_at": repo.get("pushed_at", ""),
        "license": (repo.get("license") or {}).get("spdx_id", "UNKNOWN"),
        "stars": repo.get("stargazers_count", 0),
        "archived": repo.get("archived", False),
        "fork": repo.get("fork", False),
    }

def eligible(item):
    """Only surface implementations, not apps that merely use Shizuku."""
    desc = (item.get("description") or "").lower()
    if any(word in desc for word in ("mirror of", "private mirror", "compatible with shizuku forks", "fork of shizucallrecorder")):
        return False
    expressions = (
        r"\bshizuku(?:[- ]next|plus|x)?(?:\s+[\w.-]+){0,4}\s+(?:fork|implementation|replacement|manager|server)\b",
        r"\b(?:fork|variant|implementation|replacement) of (?:the\s+)?(?:official\s+)?(?:rikkaapps/|thedjchi/)?shizuku\b",
        r"\b(?:fork|version) (?:of |based on )?(?:the official )?shizuku(?:-next)?\b",
        r"\b(?:axeron|axmanager)\b.{0,45}\b(?:fork|server|runtime|manager|implementation)\b",
        r"\b(?:fork|implementation)\b.{0,45}\b(?:axeron|axmanager)\b",
    )
    return any(re.search(pattern, desc) for pattern in expressions)

def rank(item):
    score = 0
    label = (item.get("name", "") + " " + item.get("description", "")).lower()
    if re.search(r"fork|shizuku implementation|shizuku alternative|shizuku replacement|privileged server|axeron manager", label):
        score += 4
    if re.search(r"shizuku|axeron", label):
        score += 2
    pushed = item.get("pushed_at", "")[:10]
    if pushed:
        try:
            age = (dt.date.today() - dt.date.fromisoformat(pushed)).days
            if 0 <= age <= 90:
                score += 3
            elif age <= 180:
                score += 1
        except ValueError:
            pass
    if item.get("release"):
        score += 2
    if item.get("stars", 0) >= 20:
        score += 1
    if item.get("archived") or item.get("missing"):
        score -= 10
    return score

def changes_for(current, old):
    events = []
    for slug, item in current.items():
        prior = old.get(slug)
        if prior is None:
            events.append(("new", slug, item, "New tracked source"))
        elif item.get("release") != prior.get("release"):
            events.append(("release", slug, item, f"Release {clean(prior.get('release') or 'none')} → {clean(item.get('release') or 'none')}"))
        elif item.get("head") != prior.get("head"):
            events.append(("commit", slug, item, "New HEAD " + clean(item.get("commit_subject"), 100)))
        elif item.get("missing") != prior.get("missing"):
            events.append(("availability", slug, item, "Source availability changed"))
    for slug, prior in old.items():
        if slug not in current:
            events.append(("untracked", slug, prior, "No longer in the active discovery shortlist"))
    return events

def review_area(item):
    words = " ".join([item.get("commit_subject", ""), *item.get("head_changes", []), *item.get("release_notes", [])]).lower()
    topics = (
        (r"dhizuku|device.owner|dpm", "Device Owner / Dhizuku interoperability"),
        (r"permission|authoriz|security|certificate|signature|grant", "Capability security and grants"),
        (r"axeron|axsh|pty|plugin|webui|module", "Axeron runtime, modules and WebUI"),
        (r"android\s*1[67]|binder|sdk|user.?service|compatib", "Android 16/17 and stock Shizuku compatibility"),
        (r"adb|pair|wireless|tcp|boot|reconnect|watchdog|startup", "ADB activation and service recovery"),
        (r"app manager|bulk|all on|all off|accessibility|settings", "App controls and UX"),
    )
    return next((label for pattern, label in topics if re.search(pattern, words)), "Review change against Kizuku feature gaps")

def render(current, events, warnings, config, baseline):
    today = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    result = [
        "# Kizuku upstream radar", "",
        f"Last changed: {today}. Automated metadata check; **not** a source-code, security, or compatibility audit.",
        "", "Source discovery is best-effort and not exhaustive. No upstream code, APKs, or dependencies are copied or upgraded.",
        "", "## Recent signals", ""
    ]
    if baseline:
        result.append("Initial baseline captured. Later runs will identify changes against these commit and release markers.")
    elif not events:
        result.append("No new tracked releases, commits or sources.")
    else:
        priority = {"release": 0, "new": 1, "commit": 2, "availability": 3, "untracked": 4}
        for kind, slug, item, reason in sorted(events, key=lambda ev: (priority.get(ev[0], 5), -rank(ev[2]), ev[1].lower()))[:30]:
            link = item.get("release_url") if kind == "release" else item.get("url", "https://github.com/" + slug)
            result.append(f"- **[{clean(slug)}]({link})** — {clean(reason)}")
            if kind in ("release", "commit"):
                lines = item.get("release_notes", []) if kind == "release" else item.get("head_changes", [])
                for note in lines[:3]:
                    result.append("  - " + clean(note, 180))
    if not baseline:
        result.extend(["", "## Feature opportunities to evaluate", ""])
        meaningful = [e for e in events if e[0] in ("release", "commit") and not e[2].get("missing")]
        if meaningful:
            for kind, slug, item, _ in meaningful[:12]:
                url = item.get("release_url") if kind == "release" else item.get("url", "https://github.com/" + slug)
                result.append(f"- **{review_area(item)}** — [{clean(slug)}]({url}): {clean(((item.get('release_notes') if kind == 'release' else item.get('head_changes')) or [item.get('commit_subject') or 'Inspect upstream diff'])[0], 140)}")
        else:
            result.append("No new release/commit candidates since the previous scan.")
    result.extend(["", "## Tracked projects", "", "| Project | Role | Release | Last push | License |", "|---|---|---|---|---|"])
    for slug, item in sorted(current.items(), key=lambda x: (x[1]["origin"] != "curated", x[0].lower())):
        role = config["curated"].get(slug, "discovered — review before adoption")
        tag = clean(item.get("release") or "—", 45)
        date = clean((item.get("pushed_at") or "")[:10] or "—")
        license_ = clean(item.get("license") or "UNKNOWN")
        result.append(f"| [{clean(slug)}](https://github.com/{slug}) | {clean(role, 100)} | {tag} | {date} | {license_} |")
    result.extend(["", "## Other reference sources", ""])
    for url in config["external_references"]:
        result.append(f"- {url}")
    if warnings:
        result.extend(["", "## Discovery warnings", ""])
        result.extend("- " + clean(w) for w in warnings)
    result.extend([
        "", "## Decision rule", "",
        "Treat a release note as a lead, not proof. Review actual diffs and licenses, assess Kizuku's gaps, then implement behind its existing single-host, caller-identity, capability-grant and compatibility contracts. Prioritize security/Android regressions, Shizuku compatibility, Axeron parity, and user-visible reliability before cosmetic changes.", ""
    ])
    return "\n".join(result)

def main():
    config = json.loads(CONFIG.read_text())
    prior = json.loads(STATE.read_text()) if STATE.exists() else {"repositories": {}}
    old = prior.get("repositories", {})
    warnings = []
    new_candidates = discover(config, warnings)
    for slug, value in old.items():
        if value.get("origin") != "curated":
            new_candidates.setdefault(slug, value.get("origin", "previous discovery"))
    pinned = config["curated"]
    current = {slug: inspect(slug, "curated", old.get(slug)) for slug in pinned}
    pool = {}
    pinned_lower = {k.lower() for k in pinned}
    for slug, origin in new_candidates.items():
        if slug.lower() not in pinned_lower and REPO_PATTERN.fullmatch(slug):
            pool[slug] = origin
    for slug, origin in list(pool.items())[:45]:
        try:
            item = inspect(slug, origin, old.get(slug))
            if not item.get("missing") and eligible(item) and rank(item) >= 5:
                current[slug] = item
        except RuntimeError as ex:
            warnings.append(clean(ex))
    discovered = sorted((i for i in current.values() if i["origin"] != "curated"), key=lambda i: (-rank(i), i["name"].lower()))[:20]
    current = {slug: item for slug, item in current.items() if item["origin"] == "curated"}
    current.update({item["name"]: item for item in discovered})
    baseline = not bool(old)
    events = changes_for(current, old)
    if warnings and not current:
        raise RuntimeError("Upstream scan failed; refusing to overwrite previous reports")
    if baseline or events or not REPORT.exists():
        STATE.write_text(json.dumps({"repositories": current}, indent=2, ensure_ascii=False) + "\n")
        REPORT.write_text(render(current, events, warnings, config, baseline), encoding="utf-8")
    else:
        print("No upstream HEAD/release/discovery changes; retaining the last report")
    print(f"Tracked {len(current)} repositories; {len(events)} changes; {len(warnings)} discovery warnings")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as f:
            f.write(f"## Kizuku upstream watch\n\nTracked **{len(current)}** repositories. New/changed **{0 if baseline else len(events)}**. Discovery warnings **{len(warnings)}**.\n\n[Report](../../blob/main/docs/UPSTREAM_RADAR.md)\n")

if __name__ == "__main__":
    main()
