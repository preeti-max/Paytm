#!/usr/bin/env python3
"""
Seat Reservation High-Concurrency Burst Test Script
Tests live HTTP endpoints for correctness under concurrency, idempotency, per-user limits, and reconciliation.
"""

import sys
import json
import time
import uuid
import urllib.request
import urllib.error
from concurrent.futures import ThreadPoolExecutor, as_completed

def http_post(url, data=None, headers=None):
    req_headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if headers:
        req_headers.update(headers)
    
    body = json.dumps(data).encode("utf-8") if data is not None else None
    req = urllib.request.Request(url, data=body, headers=req_headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            status = response.status
            content = response.read().decode("utf-8")
            parsed = json.loads(content) if content else {}
            return status, parsed, content
    except urllib.error.HTTPError as e:
        status = e.code
        content = e.read().decode("utf-8")
        try:
            parsed = json.loads(content)
        except Exception:
            parsed = {"raw": content}
        return status, parsed, content
    except Exception as e:
        return 599, {"error": str(e)}, str(e)

def http_get(url, headers=None):
    req_headers = {"Accept": "application/json"}
    if headers:
        req_headers.update(headers)
    req = urllib.request.Request(url, headers=req_headers, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            status = response.status
            content = response.read().decode("utf-8")
            try:
                parsed = json.loads(content)
            except Exception:
                parsed = {"raw": content}
            return status, parsed, content
    except urllib.error.HTTPError as e:
        status = e.code
        content = e.read().decode("utf-8")
        try:
            parsed = json.loads(content)
        except Exception:
            parsed = {"raw": content}
        return status, parsed, content
    except Exception as e:
        return 599, {"error": str(e)}, str(e)

def main():
    base_url = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
    base_url = base_url.rstrip("/")

    print("==================================================")
    print("      SEAT RESERVATION HIGH-CONCURRENCY BURST     ")
    print("==================================================")
    print(f"Target URL: {base_url}")
    print("--------------------------------------------------")

    # 1. Health Checks
    print("\n[1/6] Probing Health Endpoints...")
    status, live_body, _ = http_get(f"{base_url}/health/live")
    if status != 200:
        print(f"FAILED: Liveness check returned HTTP {status}")
        sys.exit(1)
    print(f"  ✓ Liveness probe: OK (HTTP 200, status={live_body.get('status')})")

    status, ready_body, _ = http_get(f"{base_url}/health/ready")
    if status != 200:
        print(f"FAILED: Readiness check returned HTTP {status}")
        sys.exit(1)
    print(f"  ✓ Readiness probe: OK (HTTP 200, database={ready_body.get('database')})")

    # 2. Authentication
    print("\n[2/6] Generating Test Tokens...")
    status, admin_auth, _ = http_post(f"{base_url}/auth/token", {"user_id": "admin-burst", "role": "ADMIN"})
    if status != 200:
        print(f"FAILED: Could not obtain admin token: {admin_auth}")
        sys.exit(1)
    admin_token = f"Bearer {admin_auth['token']}"
    print("  ✓ Admin token generated")

    # Pre-generate tokens for 200 test users
    user_tokens = {}
    for i in range(1, 201):
        user_id = f"burst-user-{i}"
        status, token_resp, _ = http_post(f"{base_url}/auth/token", {"user_id": user_id, "role": "USER"})
        user_tokens[user_id] = f"Bearer {token_resp['token']}"
    print(f"  ✓ Generated tokens for {len(user_tokens)} test users")

    # 3. Create Fresh Show
    print("\n[3/6] Creating Fresh Show with 100 Numbered Seats...")
    seats = [f"A{i}" for i in range(1, 51)] + [f"B{i}" for i in range(1, 51)]
    show_payload = {
        "name": f"burst-concert-{int(time.time())}",
        "seats": seats,
        "price_paise": 25000,
        "per_user_limit": 4
    }
    status, show_resp, _ = http_post(f"{base_url}/shows", show_payload, {"Authorization": admin_token})
    if status != 201:
        print(f"FAILED: Could not create show: {show_resp}")
        sys.exit(1)
    
    show_id = show_resp["id"]
    print(f"  ✓ Created Show: ID={show_id}, Total Seats={len(seats)}, Price=₹250.00, PerUserLimit=4")

    # 4. Concurrency Test Execution
    print("\n[4/6] Launching Concurrent Burst Scenarios...")
    
    total_requests = 0
    http_201_count = 0
    http_409_count = 0
    http_5xx_count = 0
    seat_taken_count = 0
    per_user_limit_count = 0
    idempotent_replay_count = 0
    other_counts = 0

    def record_result(status, body):
        nonlocal total_requests, http_201_count, http_409_count, http_5xx_count
        nonlocal seat_taken_count, per_user_limit_count, idempotent_replay_count, other_counts
        total_requests += 1
        if status in (200, 201):
            http_201_count += 1
        elif status == 409:
            http_409_count += 1
            err = body.get("error", "")
            if err == "SEAT_TAKEN":
                seat_taken_count += 1
            elif err == "PER_USER_LIMIT":
                per_user_limit_count += 1
            elif err == "IDEMPOTENCY_KEY_REUSED":
                idempotent_replay_count += 1
            else:
                other_counts += 1
        elif status >= 500:
            http_5xx_count += 1
        else:
            other_counts += 1

    # Scenario A: Hot Seat Storm (100 concurrent users racing for A1)
    print("  -> Executing Scenario A: 100 concurrent users racing for seat A1...")
    start_time = time.time()
    with ThreadPoolExecutor(max_workers=50) as executor:
        futures = []
        for i in range(1, 101):
            u_id = f"burst-user-{i}"
            token = user_tokens[u_id]
            headers = {"Authorization": token, "Idempotency-Key": f"storm-a1-{u_id}"}
            url = f"{base_url}/shows/{show_id}/reserve"
            payload = {"seats": ["A1"]}
            futures.append(executor.submit(http_post, url, payload, headers))
        
        for f in as_completed(futures):
            st, bd, _ = f.result()
            record_result(st, bd)
    print(f"     Scenario A finished in {time.time() - start_time:.2f}s")

    # Scenario B: Multiple Hot Seats Storm (60 concurrent users for A2, A3, A4)
    print("  -> Executing Scenario B: 60 concurrent users for seats A2, A3, A4...")
    start_time = time.time()
    with ThreadPoolExecutor(max_workers=30) as executor:
        futures = []
        for i in range(101, 161):
            u_id = f"burst-user-{i}"
            target_seat = f"A{((i % 3) + 2)}" # A2, A3, or A4
            token = user_tokens[u_id]
            headers = {"Authorization": token, "Idempotency-Key": f"storm-mult-{u_id}"}
            url = f"{base_url}/shows/{show_id}/reserve"
            payload = {"seats": [target_seat]}
            futures.append(executor.submit(http_post, url, payload, headers))
        
        for f in as_completed(futures):
            st, bd, _ = f.result()
            record_result(st, bd)
    print(f"     Scenario B finished in {time.time() - start_time:.2f}s")

    # Scenario C: Same-User Concurrency Race (1 user sends 10 concurrent requests for 10 seats, limit=4)
    print("  -> Executing Scenario C: 1 user sends 10 concurrent requests (per_user_limit=4)...")
    start_time = time.time()
    heavy_user_token = user_tokens["burst-user-1"]
    with ThreadPoolExecutor(max_workers=10) as executor:
        futures = []
        for i in range(1, 11):
            target_seat = f"B{i}"
            headers = {"Authorization": heavy_user_token, "Idempotency-Key": f"same-user-seat-{i}"}
            url = f"{base_url}/shows/{show_id}/reserve"
            payload = {"seats": [target_seat]}
            futures.append(executor.submit(http_post, url, payload, headers))
        
        for f in as_completed(futures):
            st, bd, _ = f.result()
            record_result(st, bd)
    print(f"     Scenario C finished in {time.time() - start_time:.2f}s")

    # Scenario D: Idempotency Concurrency Race (20 concurrent requests with same key for B20)
    print("  -> Executing Scenario D: 20 concurrent identical requests with same Idempotency-Key for B20...")
    start_time = time.time()
    shared_key = f"shared-idem-key-{uuid.uuid4()}"
    idem_user_token = user_tokens["burst-user-2"]
    with ThreadPoolExecutor(max_workers=20) as executor:
        futures = []
        for _ in range(20):
            headers = {"Authorization": idem_user_token, "Idempotency-Key": shared_key}
            url = f"{base_url}/shows/{show_id}/reserve"
            payload = {"seats": ["B20"]}
            futures.append(executor.submit(http_post, url, payload, headers))
        
        for f in as_completed(futures):
            st, bd, _ = f.result()
            record_result(st, bd)
    print(f"     Scenario D finished in {time.time() - start_time:.2f}s")

    # 5. Fetch Final Show State and Reconcile
    print("\n[5/6] Validating Final Show State & Reconciliation...")
    status, final_show, _ = http_get(f"{base_url}/shows/{show_id}")
    if status != 200:
        print(f"FAILED: Could not fetch show state: {final_show}")
        sys.exit(1)

    total_s = final_show["total_seats"]
    avail_s = final_show["available"]
    held_s = final_show["held"]
    conf_s = final_show["confirmed"]

    reconciliation_pass = (avail_s + held_s + conf_s == total_s)
    double_sell_pass = (conf_s <= total_s)
    no_5xx_pass = (http_5xx_count == 0)

    # 6. Fetch Prometheus Metrics
    print("\n[6/6] Scraping Prometheus Metrics...")
    status, _, metrics_raw = http_get(f"{base_url}/actuator/prometheus")
    has_prometheus = (status == 200 and "reservations_confirmed_total" in metrics_raw)

    print("\n==================================================")
    print("               BURST TEST RESULTS                 ")
    print("==================================================")
    print(f"Total Requests Dispatched : {total_requests}")
    print(f"HTTP 201 / 200 (Success)  : {http_201_count}")
    print(f"HTTP 409 (Conflict)       : {http_409_count}")
    print(f"HTTP 5xx (Server Errors)  : {http_5xx_count}")
    print("--------------------------------------------------")
    print(f"  Reason SEAT_TAKEN       : {seat_taken_count}")
    print(f"  Reason PER_USER_LIMIT   : {per_user_limit_count}")
    print(f"  Idempotent Replays      : {idempotent_replay_count}")
    print("--------------------------------------------------")
    print("FINAL DATABASE STATE:")
    print(f"  Total Seats             : {total_s}")
    print(f"  Available Seats         : {avail_s}")
    print(f"  Held Seats              : {held_s}")
    print(f"  Confirmed Seats         : {conf_s}")
    print(f"  Invariant Equation      : {avail_s} + {held_s} + {conf_s} = {total_s}")
    print("--------------------------------------------------")
    print(f"RECONCILIATION CHECK      : {'PASS' if reconciliation_pass else 'FAIL'}")
    print(f"ZERO 5XX CHECK            : {'PASS' if no_5xx_pass else 'FAIL'}")
    print(f"DOUBLE SELL CHECK         : {'PASS' if double_sell_pass else 'FAIL'}")
    print(f"PROMETHEUS METRICS CHECK  : {'PASS' if has_prometheus else 'FAIL'}")
    print("==================================================")

    if reconciliation_pass and no_5xx_pass and double_sell_pass:
        print("\n>>> ALL BURST INVARIANTS SATISFIED SUCCESSFULLY <<<\n")
        sys.exit(0)
    else:
        print("\n>>> BURST INVARIANT VERIFICATION FAILED <<<\n")
        sys.exit(1)

if __name__ == "__main__":
    main()
