"""
One-shot probe of the config endpoint.

Replays what the Kotlin+Rust gate does on a real device: POST a non-organic
install JSON to the backend with a Chrome-impersonated TLS fingerprint (same
BoringSSL+emulation path `wreq` drives in `libhaven.so`). Prints the raw
response so the result can be compared against the client's parsing logic in
`ConfigClient.parseConfigBody`.

Usage:
    python scripts/probe_config.py                 # non-organic install
    python scripts/probe_config.py --organic       # organic install
    python scripts/probe_config.py --raw '{...}'   # custom attribution JSON

The script does not read `gray.properties`; everything the backend keys on is
hard-coded here so this stays self-contained and runnable from any machine.
Requires `curl_cffi` (BoringSSL + Chrome JA3/JA4 fingerprint).
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import uuid

from curl_cffi import requests


ENDPOINT        = "https://stormreachhaven.store/config.php"
BUNDLE_ID       = "com.stormreachhaven.stormreachgame"
FIREBASE_PROJECT = "38488221496"
IMPERSONATE     = "chrome124"   # closest supported profile to wreq's Chrome134
LOCALE          = "en_US"


def non_organic_attribution() -> dict:
    """AppsFlyer-shaped conversion data for a paid install."""
    return {
        "af_status":    "Non-organic",
        "media_source": "googleadwords_int",
        "campaign":     "probe_cf_test",
        "campaign_id":  "1234567890",
        "adset":        "probe_adset",
        "adset_id":     "9876543210",
        "ad":           "probe_ad",
        "ad_id":        "5555555555",
        "af_sub1":      "cf_probe_sub1",
        "af_sub2":      "cf_probe_sub2",
        "af_sub3":      "",
        "af_sub4":      "",
        "af_sub5":      "",
        "install_time": time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime()),
        "click_time":   time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime()),
        "is_first_launch":  "true",
        "orig_cost":    "0.00",
        "cost_cents_USD": "0",
    }


def organic_attribution() -> dict:
    return {
        "af_status":        "Organic",
        "is_first_launch":  "true",
        "install_time":     time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime()),
    }


def build_body(attribution: dict) -> dict:
    """Mirrors ConfigClient.buildBody — attribution first, device fields win."""
    out = dict(attribution)
    out["af_id"]               = str(uuid.uuid4()).replace("-", "")
    out["bundle_id"]           = BUNDLE_ID
    out["os"]                  = "Android"
    out["store_id"]            = BUNDLE_ID
    out["locale"]              = LOCALE
    out["firebase_project_id"] = FIREBASE_PROJECT
    # push_token intentionally omitted; the client omits it when empty.
    return out


def probe(attribution: dict) -> int:
    body = build_body(attribution)
    print(f"POST   {ENDPOINT}")
    print(f"IMP    {IMPERSONATE}")
    print("BODY   " + json.dumps(body, indent=2))
    print()

    try:
        resp = requests.post(
            ENDPOINT,
            json=body,
            impersonate=IMPERSONATE,
            timeout=20,
            allow_redirects=True,
        )
    except Exception as exc:
        print(f"TRANSPORT ERROR: {type(exc).__name__}: {exc}")
        return 2

    print(f"STATUS {resp.status_code} {resp.reason or ''}")
    print("HEADERS:")
    for k, v in resp.headers.items():
        print(f"  {k}: {v}")
    print()
    text = resp.text
    print("BODY:")
    print(text)
    print()

    # Replay ConfigClient.parseConfigBody classification.
    verdict = classify(resp.status_code, text)
    print(f"-> client would treat this as: {verdict}")
    return 0


def classify(status: int, body: str) -> str:
    if status == 0:
        return "UNREACHABLE (transport failure)"
    if not (200 <= status <= 299):
        return f"NATIVE (HTTP {status}, non-2xx)"
    try:
        j = json.loads(body)
    except Exception:
        return "NATIVE (2xx but unparseable JSON)"
    ok  = bool(j.get("ok", False))
    url = (j.get("url") or "").strip()
    if ok and url and (url.lower().startswith("http://") or url.lower().startswith("https://")):
        exp = j.get("expires", 0)
        return f"STREAM url={url!r} expires={exp}"
    return f"NATIVE (ok={ok}, url={url!r})"


def main() -> int:
    p = argparse.ArgumentParser(description="Probe stormreachhaven config endpoint.")
    p.add_argument("--organic", action="store_true", help="send organic (no media_source) payload")
    p.add_argument("--raw", help="override attribution with the given JSON string")
    args = p.parse_args()

    if args.raw:
        try:
            attribution = json.loads(args.raw)
        except Exception as exc:
            print(f"--raw is not valid JSON: {exc}", file=sys.stderr)
            return 2
    elif args.organic:
        attribution = organic_attribution()
    else:
        attribution = non_organic_attribution()

    return probe(attribution)


if __name__ == "__main__":
    sys.exit(main())
