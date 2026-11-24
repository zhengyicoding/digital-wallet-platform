"""OPS-4..7 / SEC-7. Run against an isolated stack; never resets data or prints secrets.

python3 infra/observability/smoke.py wallet-m12-check http://127.0.0.1:19082
Requires docker CLI access. Evidence is printed as JSON, including verified Tempo span IDs.
"""
import base64
import json
import math
import pathlib
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

project, base = sys.argv[1:]
assert project.startswith("wallet-m12-"), "Use a dedicated M12 Compose project"
secrets = []


def request(method, path, body=None, token=None, status=200, key=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if key:
        headers["Idempotency-Key"] = key
    req = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(),
                                 headers=headers, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    assert response.code == status, (method, path, response.code, status)
    raw = response.read()
    return json.loads(raw) if raw else None


def docker(service, *args):
    return subprocess.check_output(["docker", "exec", f"{project}-{service}-1", *args], text=True)


def internal(service, port, path):
    return json.loads(docker(service, "wget", "-qO-", f"http://127.0.0.1:{port}{path}"))


def prom(query, grafana=False):
    path = "/api/v1/query?query=" + urllib.parse.quote(query, safe="")
    result = internal("grafana", 3000, "/api/datasources/proxy/uid/prometheus" + path) if grafana else internal("prometheus", 9090, path)
    assert result["status"] == "success", result
    return result["data"]["result"]


def eventually(fn, seconds=90):
    deadline = time.monotonic() + seconds
    while True:
        try:
            return fn()
        except (AssertionError, KeyError, subprocess.CalledProcessError):
            if time.monotonic() >= deadline:
                raise
            time.sleep(2)


def positive(query):
    rows = prom(query)
    assert rows and any(float(row["value"][1]) > 0 for row in rows), (query, rows)
    return rows


def logs(service):
    result = subprocess.run(["docker", "logs", f"{project}-{service}-1"], capture_output=True, text=True, check=True)
    raw = result.stdout + result.stderr
    assert all(secret not in raw for secret in secrets), f"SEC-7 secret in {service} logs"
    events = [json.loads(line) for line in raw.splitlines() if line.strip()]
    assert events and all(e["service.name"] == service for e in events), service
    return events


def trace_spans(trace):
    for batch in trace.get("batches", trace.get("resourceSpans", [])):
        attributes = batch["resource"]["attributes"]
        service = next(a["value"]["stringValue"] for a in attributes if a["key"] == "service.name")
        for scope in batch.get("scopeSpans", batch.get("instrumentationLibrarySpans", [])):
            for span in scope["spans"]:
                yield service, span


def span_id(value):
    return value.lower() if re.fullmatch(r"[0-9a-fA-F]{16}", value) else base64.b64decode(value).hex()


# Resolve recreated upstream containers when repeating this check against the same isolated project.
docker("web", "nginx", "-s", "reload")

# Keep credentials and tokens entirely out of evidence, exceptions and log output.
password = "m12-password-" + uuid.uuid4().hex
secrets.append(password)
users = []
for suffix in ("a", "b"):
    username = "m12_" + uuid.uuid4().hex[:10] + suffix
    profile = request("POST", "/api/v1/auth/register", {"username": username, "password": password, "displayName": "M12 " + suffix}, status=201)
    token = request("POST", "/api/v1/auth/login", {"username": username, "password": password})["accessToken"]
    secrets.append(token)
    wallet = request("POST", "/api/v1/wallets", token=token, status=201)
    users.append((profile["id"], token, wallet["id"]))
user, token, wallet = users[0]
request("PUT", "/bank/admin/config", {"settleDelayMs": 20000, "responseDelayMs": 0, "webhookCopies": 1})
operation = request("POST", "/api/v1/deposits", {"amount": 10000}, token, status=202, key=uuid.uuid4().hex)


def pending_metric():
    return positive('wallet_pending_operations{type="DEPOSIT"}')

# A scrape can take 15s; prolong bank settlement to capture a real pending row.
# Query the internal endpoint immediately as well to verify gauges before settlement.
metrics = docker("wallet-service", "wget", "-qO-", "http://127.0.0.1:8082/actuator/prometheus")
assert re.search(r'wallet_pending_operations\{[^\n]*type="DEPOSIT"[^\n]*\} 1.0', metrics)


def settled():
    result = request("GET", "/api/v1/deposits/" + operation["id"], token=token)
    assert result["status"] == "SUCCEEDED", result["status"]
    return result


pending_evidence = eventually(pending_metric, 18)
eventually(lambda: positive('wallet_pending_oldest_age_seconds{type="DEPOSIT"}'), 18)
eventually(settled, 45)
request("PUT", "/bank/admin/config", {"settleDelayMs": 500, "responseDelayMs": 0, "webhookCopies": 1})
transfer = request("POST", "/api/v1/transfers", {"toWalletId": users[1][2], "amount": 1250, "note": "M12 traffic"}, token, status=201, key=uuid.uuid4().hex)
request("POST", "/api/v1/transfers", {"toWalletId": users[1][2], "amount": 200000}, token, status=422, key=uuid.uuid4().hex)
withdrawal = request("POST", "/api/v1/withdrawals", {"amount": 2000}, token, status=202, key=uuid.uuid4().hex)


def withdrawal_settled():
    result = request("GET", "/api/v1/withdrawals/" + withdrawal["id"], token=token)
    assert result["status"] == "SUCCEEDED"


eventually(withdrawal_settled, 25)
request("GET", "/api/v1/notifications", token=token)
# Generate traffic across two scrape intervals so dashboard rate and p95 queries return finite data.
for _ in range(12):
    for path in ("/api/v1/wallets/me", "/api/v1/auth/me", "/api/v1/notifications", "/bank/admin/config"):
        request("GET", path, token=token)
    time.sleep(2)


def all_targets():
    targets = internal("prometheus", 9090, "/api/v1/targets")["data"]["activeTargets"]
    assert len(targets) == 4 and all(t["health"] == "up" for t in targets)
    return sorted(t["labels"]["job"] for t in targets)


evidence = {"scrapes": eventually(all_targets), "pending_scrape_count": float(pending_evidence[0]["value"][1])}
for query in ('wallet_operations_total{type="TRANSFER",result="COMPLETED"}',
              'wallet_operations_total{type="DEPOSIT",result="SUCCEEDED"}',
              'wallet_operations_total{type="WITHDRAWAL",result="SUCCEEDED"}',
              'wallet_bank_calls_total{outcome="SUCCESS"}',
              'notification_events_processed_total',
              'http_server_requests_seconds_count{uri="/api/v1/transfers",status="422"}'):
    eventually(lambda: positive(query))

# Validate every provisioned panel through Grafana's real datasource proxy.
dashboard = internal("grafana", 3000, "/api/dashboards/uid/digital-wallet")["dashboard"]
panel_evidence = {}
for panel in dashboard["panels"]:
    expr = panel["targets"][0]["expr"]

    def panel_has_data():
        rows = prom(expr, grafana=True)
        assert rows and any(math.isfinite(float(r["value"][1])) for r in rows), panel["title"]
        return len(rows)

    panel_evidence[panel["title"]] = eventually(panel_has_data)
evidence["grafana_panels_with_data"] = panel_evidence

# Correlate a real wallet webhook server log with Tempo, then check its remote parent.
webhooks = [e for e in logs("wallet-service") if e.get("event") == "http.request" and e.get("route") == "/internal/webhooks/bank" and e.get("status") == 200]
assert webhooks, "No successful webhook logged"
webhook = webhooks[-1]
trace_id = webhook["traceId"]


def webhook_trace():
    trace = internal("tempo", 3200, "/api/traces/" + trace_id)
    spans = list(trace_spans(trace))
    server = next((s for svc, s in spans if svc == "wallet-service"
                   and s["kind"] in (2, "SPAN_KIND_SERVER")
                   and "/internal/webhooks/bank" in s.get("name", "")), None)
    assert server and server["kind"] in (2, "SPAN_KIND_SERVER")
    parent = next((s for svc, s in spans if svc == "bank-simulator" and span_id(s["spanId"]) == span_id(server["parentSpanId"])), None)
    assert parent and parent["kind"] in (3, "SPAN_KIND_CLIENT"), "Missing bank client parent"
    return {"traceId": trace_id, "bankClientSpanId": span_id(parent["spanId"]), "walletServerSpanId": span_id(server["spanId"])}


evidence["webhook_trace"] = eventually(webhook_trace)
# Existing example secrets must not be logged either. Do not print their values.
for line in pathlib.Path(".env.example").read_text().splitlines():
    if "=" in line and any(word in line.split("=", 1)[0] for word in ("PASSWORD", "SECRET", "KEY")):
        secrets.append(line.split("=", 1)[1])
log_counts = {}
for service in evidence["scrapes"]:
    events = logs(service)
    requests = [e for e in events if e.get("event") == "http.request"]
    assert requests and all(re.fullmatch(r"[0-9a-f]{32}", e.get("traceId", "")) and re.fullmatch(r"[0-9a-f]{16}", e.get("spanId", "")) for e in requests)
    if service in ("auth-service", "wallet-service", "notification-service"):
        assert any(e.get("userId") == user for e in requests), service
    log_counts[service] = len(events)
wallet_events = logs("wallet-service")
for op, outcome in ((transfer, "COMPLETED"), (operation, "SUCCEEDED"), (withdrawal, "SUCCEEDED")):
    assert any(e.get("operationId") == op["id"] and e.get("outcome") == outcome and e.get("userId") == user for e in wallet_events)
evidence["json_events_checked"] = log_counts
evidence["settled_amounts_cents"] = {"deposit": 10000, "transfer": 1250, "withdrawal": 2000}
evidence["final_balance_cents"] = request("GET", "/api/v1/wallets/me", token=token)["balance"]
assert evidence["final_balance_cents"] == 6750
# Runtime bindings must leave metrics, JWKS, bank API and webhook reachable only on the service network.
for service in evidence["scrapes"]:
    bindings = json.loads(subprocess.check_output(["docker", "inspect", "--format", "{{json .HostConfig.PortBindings}}", f"{project}-{service}-1"], text=True))
    assert not bindings, service
evidence["internal_only"] = True
print(json.dumps(evidence, indent=2))
