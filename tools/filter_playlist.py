#!/usr/bin/env python3
"""Filter M3U/IPTV playlist to only channels that are actually reachable.

Usage:
    python3 tools/filter_playlist.py <playlist_url_or_path> -o out.m3u
    python3 tools/filter_playlist.py <playlist_url_or_path> -o out.m3u --workers 30 --timeout 8
    python3 tools/filter_playlist.py <playlist_url_or_path> -o out.m3u --group Japan --group Indonesia
    python3 tools/filter_playlist.py <playlist_url_or_path> -o out.m3u --playback-check
"""

import argparse
import re
import shutil
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from urllib.parse import urlparse, parse_qsl, urljoin

import requests

DEFAULT_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)

ATTR_RE = re.compile(r'([\w-]+)="([^"]*)"')
HTML_SNIFF_RE = re.compile(rb"^\s*(<!doctype\s+html|<html[\s>])", re.IGNORECASE)
CLEARKEY_RE = re.compile(r"license_key\s*=\s*([0-9a-fA-F]{8,}):([0-9a-fA-F]{8,})")


@dataclass
class Channel:
    extinf: str            # full raw "#EXTINF:..." line
    attrs: dict = field(default_factory=dict)
    name: str = ""
    url: str = ""           # clean stream URL, without any "|key=value" suffix
    pipe_headers: dict = field(default_factory=dict)  # headers from "url|Referer=...&User-Agent=..." syntax
    pre_lines: list = field(default_factory=list)   # e.g. #KODIPROP lines before EXTINF (clearkey license info)
    extra_lines: list = field(default_factory=list)  # e.g. #KODIPROP/#EXTVLCOPT lines between EXTINF and URL
    kind: str = ""          # sniffed stream kind ("hls"/"dash"/"ts"/"fmp4"/"unknown"), set by check_channel


def split_pipe_url(raw_url: str):
    """Split a 'url|Header=value&Header2=value2' style line (common in Kodi/TiviMate
    playlists, incl. DASH clearkey ones) into (clean_url, headers_dict)."""
    if "|" not in raw_url:
        return raw_url, {}
    url, _, tail = raw_url.partition("|")
    headers = {}
    for key, value in parse_qsl(tail, keep_blank_values=True):
        headers[key] = value
    return url, headers


def load_playlist_text(source: str) -> str:
    parsed = urlparse(source)
    if parsed.scheme in ("http", "https"):
        resp = requests.get(source, timeout=20, headers={"User-Agent": DEFAULT_UA})
        resp.raise_for_status()
        return resp.text
    with open(source, "r", encoding="utf-8", errors="replace") as f:
        return f.read()


def parse_playlist(text: str) -> list:
    lines = [l.rstrip("\n").rstrip("\r") for l in text.splitlines()]
    channels = []
    current = None
    pending_pre_lines = []  # directive lines (e.g. #KODIPROP clearkey) seen before the next EXTINF
    for line in lines:
        if not line.strip():
            continue
        if line.startswith("#EXTM3U"):
            continue
        if line.startswith("#EXTINF"):
            current = Channel(extinf=line, pre_lines=pending_pre_lines)
            pending_pre_lines = []
            current.attrs = dict(ATTR_RE.findall(line))
            # channel name is whatever follows the last comma
            current.name = line.rsplit(",", 1)[-1].strip()
            continue
        if line.startswith("#"):
            if current is not None:
                current.extra_lines.append(line)
            else:
                # directive that precedes the EXTINF it applies to (e.g. some
                # #KODIPROP:inputstream.adaptive.license_key=... clearkey blocks)
                pending_pre_lines.append(line)
            continue
        # a plain line after an #EXTINF is the stream URL
        if current is not None:
            url, pipe_headers = split_pipe_url(line.strip())
            current.url = url
            current.pipe_headers = pipe_headers
            channels.append(current)
            current = None
    return channels


def build_headers(channel: Channel) -> dict:
    headers = {
        "User-Agent": channel.attrs.get("http-user-agent", DEFAULT_UA),
    }
    referer = channel.attrs.get("http-referrer") or channel.attrs.get("http-referer")
    if referer:
        headers["Referer"] = referer
    # "url|Header=value&Header2=value2" style headers (Kodi/TiviMate/clearkey playlists)
    # take precedence since they're attached to this specific URL.
    for key, value in channel.pipe_headers.items():
        if key.lower() in ("user-agent", "referer", "origin", "cookie"):
            headers[key if key[:1].isupper() else key.capitalize()] = value
    return headers


def sniff_kind(chunk: bytes) -> str:
    """Guess what a response body actually is by its bytes, not the URL's
    file extension - lots of real stream URLs have no extension at all, and
    lots of dead channels return an HTML/JSON placeholder with HTTP 200."""
    stripped = chunk.lstrip(b"\xef\xbb\xbf").lstrip()
    if stripped.startswith(b"#EXTM3U"):
        return "hls"
    lowered = stripped[:300].lower()
    if lowered.startswith(b"<?xml") or b"<mpd" in lowered:
        return "dash"
    if HTML_SNIFF_RE.match(stripped):
        return "html"
    if stripped[:1] == b"\x47":  # MPEG-TS sync byte
        return "ts"
    if len(stripped) >= 8 and stripped[4:8] == b"ftyp":  # fMP4 'ftyp' box
        return "fmp4"
    return "unknown"


def fetch(url: str, headers: dict, timeout: float, max_bytes: int = 65536):
    """GET a URL and read up to max_bytes of the body. Returns
    (status_code, response_headers, body_bytes, final_url_after_redirects)."""
    resp = requests.get(url, headers=headers, timeout=timeout, stream=True, allow_redirects=True)
    body = b""
    try:
        if resp.status_code < 400:
            for piece in resp.iter_content(chunk_size=4096):
                body += piece
                if len(body) >= max_bytes:
                    break
        return resp.status_code, resp.headers, body, resp.url
    finally:
        resp.close()


def _first_uri_after(lines: list, marker_prefix: str, from_index: int = 0):
    for i in range(from_index, len(lines)):
        if lines[i].strip().upper().startswith(marker_prefix):
            for j in range(i + 1, len(lines)):
                candidate = lines[j].strip()
                if candidate and not candidate.startswith("#"):
                    return candidate
    return None


def verify_hls(url: str, headers: dict, timeout: float, depth: int = 0) -> bool:
    """Confirm an HLS URL is a real, playable manifest: resolve a master
    playlist down to a variant, then down to an actual media segment, and
    make sure that segment is fetchable - the same hops a player makes."""
    status, _resp_headers, body, final_url = fetch(url, headers, timeout, max_bytes=131072)
    if status >= 400:
        return False
    text = body.decode("utf-8", errors="replace")
    if "#EXTM3U" not in text:
        return False
    lines = text.splitlines()

    if depth == 0 and "#EXT-X-STREAM-INF" in text.upper():
        variant = _first_uri_after(lines, "#EXT-X-STREAM-INF")
        if not variant:
            return False
        return verify_hls(urljoin(final_url, variant), headers, timeout, depth=1)

    seg = _first_uri_after(lines, "#EXTINF")
    if not seg:
        return False
    seg_url = urljoin(final_url, seg)
    seg_headers = dict(headers)
    seg_headers["Range"] = "bytes=0-2047"
    try:
        seg_resp = requests.get(seg_url, headers=seg_headers, timeout=timeout, stream=True, allow_redirects=True)
        ok = seg_resp.status_code < 400
        seg_resp.close()
        return ok
    except requests.RequestException:
        return False


def verify_dash(url: str, headers: dict, timeout: float) -> bool:
    """Confirm a DASH manifest is real and actually describes media, not just
    an empty/broken <MPD> shell. Note: does not resolve an actual media
    segment (DASH segment URLs are template-driven, e.g. $Number$/$Time$),
    so this is manifest-level confidence, not a guarantee segments exist."""
    status, _resp_headers, body, _final_url = fetch(url, headers, timeout, max_bytes=131072)
    if status >= 400:
        return False
    text = body.decode("utf-8", errors="replace").lower()
    if "<mpd" not in text:
        return False
    return any(tag in text for tag in ("<representation", "<segmenttemplate", "<segmentbase", "<baseurl"))


def check_channel(channel: Channel, timeout: float) -> bool:
    if not channel.url:
        return False
    headers = build_headers(channel)

    try:
        status, resp_headers, chunk, final_url = fetch(channel.url, headers, timeout, max_bytes=4096)
    except requests.RequestException:
        return False
    if status >= 400:
        return False

    kind = sniff_kind(chunk)
    channel.kind = kind
    if kind == "html":
        return False  # placeholder / "channel not found" page disguised as HTTP 200
    if kind == "hls":
        try:
            return verify_hls(final_url, headers, timeout)
        except requests.RequestException:
            return False
    if kind == "dash":
        try:
            return verify_dash(final_url, headers, timeout)
        except requests.RequestException:
            return False
    if kind in ("ts", "fmp4"):
        return True  # already looking at real media bytes

    # Unknown binary content (e.g. a proprietary segment format): fall back
    # to the response's declared Content-Type as a last sanity check.
    content_type = resp_headers.get("Content-Type", "").lower()
    if "html" in content_type or "json" in content_type:
        return False
    return bool(chunk)


def extract_clearkey(channel: Channel):
    """Pull a KEYID:KEY pair out of a #KODIPROP inputstream.adaptive.license_key
    directive, if the playlist ships one. Returns the KEY (hex) or None."""
    for line in (*channel.pre_lines, *channel.extra_lines):
        m = CLEARKEY_RE.search(line)
        if m:
            return m.group(2)
    return None


def verify_playback(channel: Channel, headers: dict, timeout: float, seconds: float):
    """Actually decode a few seconds of the stream with ffmpeg - the closest a
    CLI tool can get to 'does this really play', short of running the app's
    own player. Returns (ok, reason)."""
    cmd = ["ffmpeg", "-nostdin", "-v", "error", "-user_agent", headers.get("User-Agent", DEFAULT_UA)]

    header_lines = "".join(f"{k}: {v}\r\n" for k, v in headers.items() if k.lower() != "user-agent")
    if header_lines:
        cmd += ["-headers", header_lines]

    key = extract_clearkey(channel)
    if key:
        # Best-effort: only covers plain MPEG-CENC clearkey with a single key.
        # Streams using other/multiple keys will still fail to decode here.
        cmd += ["-decryption_key", key]

    if channel.kind == "hls":
        # some CDNs serve HLS segments with unusual extensions (e.g. .json);
        # ffmpeg's HLS demuxer whitelists extensions by default and separately
        # sanity-checks each segment's detected format against its extension -
        # both would otherwise misreport a perfectly live stream as broken.
        # These are HLS-demuxer-only options - ffmpeg hard-errors if passed
        # while probing a different format (e.g. DASH), so gate on kind.
        cmd += ["-allowed_extensions", "ALL", "-extension_picky", "0"]

    cmd += [
        "-timeout", str(int(timeout * 1_000_000)),  # microseconds, HTTP I/O timeout
        "-i", channel.url,
        "-t", str(seconds),
        "-f", "null", "-",
    ]

    try:
        proc = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=timeout + seconds + 15,
        )
    except subprocess.TimeoutExpired:
        return False, "timeout saat decode"

    if proc.returncode != 0:
        reason = next((l.strip() for l in proc.stderr.splitlines() if l.strip()), "gagal decode")
        return False, reason
    return True, ""


def format_channel(channel: Channel) -> str:
    url = channel.url
    if channel.pipe_headers:
        tail = "&".join(f"{k}={v}" for k, v in channel.pipe_headers.items())
        url = f"{url}|{tail}"
    lines = [*channel.pre_lines, channel.extinf, *channel.extra_lines, url]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", help="Playlist URL or local .m3u path")
    parser.add_argument("-o", "--output", required=True, help="Output .m3u file path")
    parser.add_argument("--workers", type=int, default=20, help="Parallel checks (default: 20)")
    parser.add_argument("--timeout", type=float, default=10, help="Per-channel timeout in seconds (default: 10)")
    parser.add_argument("--group", action="append", default=None,
                         help="Only keep channels whose group-title matches (repeatable). Case-insensitive substring.")
    parser.add_argument("--playback-check", action="store_true",
                         help="After the network check passes, actually decode a few seconds via ffmpeg "
                              "to confirm real playback (needs ffmpeg on PATH; much slower, uses real bandwidth).")
    parser.add_argument("--playback-seconds", type=float, default=5,
                         help="How many seconds to decode per channel for --playback-check (default: 5)")
    parser.add_argument("--playback-workers", type=int, default=5,
                         help="Parallel ffmpeg processes for --playback-check (default: 5, keep low - CPU/bandwidth heavy)")
    args = parser.parse_args()

    if args.playback_check and shutil.which("ffmpeg") is None:
        print("ffmpeg tidak ditemukan di PATH. Install dulu (mis. 'brew install ffmpeg') "
              "atau jalankan tanpa --playback-check.", file=sys.stderr)
        sys.exit(1)

    print(f"Mengambil playlist dari: {args.source}", file=sys.stderr)
    text = load_playlist_text(args.source)
    channels = parse_playlist(text)
    print(f"Ditemukan {len(channels)} channel.", file=sys.stderr)

    if args.group:
        wanted = [g.lower() for g in args.group]
        channels = [
            c for c in channels
            if any(w in c.attrs.get("group-title", "").lower() for w in wanted)
        ]
        print(f"Setelah filter group {args.group}: {len(channels)} channel.", file=sys.stderr)

    if not channels:
        print("Tidak ada channel untuk dicek.", file=sys.stderr)
        sys.exit(1)

    working = []
    total = len(channels)
    done = 0
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        future_to_channel = {pool.submit(check_channel, c, args.timeout): c for c in channels}
        for future in as_completed(future_to_channel):
            channel = future_to_channel[future]
            done += 1
            ok = False
            try:
                ok = future.result()
            except Exception:
                ok = False
            status = "OK  " if ok else "FAIL"
            print(f"[{done}/{total}] {status} {channel.name}", file=sys.stderr)
            if ok:
                working.append(channel)

    print(f"\n{len(working)}/{total} channel dapat diakses.", file=sys.stderr)

    if args.playback_check and working:
        print(f"\nValidasi playback sungguhan (decode {args.playback_seconds}s/channel via ffmpeg) "
              f"untuk {len(working)} channel...", file=sys.stderr)
        playable = []
        total2 = len(working)
        done2 = 0
        with ThreadPoolExecutor(max_workers=args.playback_workers) as pool:
            future_to_channel = {
                pool.submit(verify_playback, c, build_headers(c), args.timeout, args.playback_seconds): c
                for c in working
            }
            for future in as_completed(future_to_channel):
                channel = future_to_channel[future]
                done2 += 1
                try:
                    ok, reason = future.result()
                except Exception as exc:
                    ok, reason = False, str(exc)
                status = "OK  " if ok else "FAIL"
                suffix = f" ({reason})" if not ok and reason else ""
                print(f"[{done2}/{total2}] {status} {channel.name}{suffix}", file=sys.stderr)
                if ok:
                    playable.append(channel)
        print(f"\n{len(playable)}/{total2} channel benar-benar bisa di-decode (playback nyata).", file=sys.stderr)
        working = playable

    with open(args.output, "w", encoding="utf-8") as f:
        f.write("#EXTM3U\n")
        for c in working:
            f.write(format_channel(c) + "\n")

    print(f"Playlist hasil filter disimpan ke: {args.output}", file=sys.stderr)


if __name__ == "__main__":
    main()
