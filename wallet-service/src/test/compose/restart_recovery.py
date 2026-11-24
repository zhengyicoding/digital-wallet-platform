"""RES-8 / ACC-2 #11. Run against a dedicated, running Compose project.

python3 restart_recovery.py PROJECT EDGE_URL
Uses the project's PostgreSQL container and wallet-service. Restores bank settings.
"""
import json
import subprocess
import sys
import time
import uuid
from urllib.error import HTTPError
from urllib.request import Request, urlopen


project, edge = sys.argv[1:]


def docker(*args):
    return subprocess.check_output(["docker", *args], text=True).strip()


def container(service):
    rows = docker("ps", "-q", "--filter", f"label=com.docker.compose.project={project}",
                  "--filter", f"label=com.docker.compose.service={service}").splitlines()
    assert len(rows) == 1, service
    return rows[0]


def api(method, path, body=None, token=None, key=None):
    headers = {"Content-Type": "application/json"}
    if key:
        headers["Idempotency-Key"] = key
    if token:
        headers["Authorization"] = "Bearer " + token
    req = Request(edge + path, headers=headers, method=method,
                  data=json.dumps(body).encode() if body is not None else None)
    with urlopen(req, timeout=5) as res:
        return json.load(res)


def wait(probe, seconds=60):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            if probe():
                return
        except HTTPError as err:
            if err.code not in (502, 503):
                raise
        time.sleep(.25)
    raise AssertionError(f"Timed out waiting for {probe}")


def counts(reference):
    sql = f"""select
        (select count(*) from postings where reference_id='{reference}'),
        (select count(*) from outbox where aggregate_id='{reference}'),
        (select count(*) from ledger_entries e join postings p on p.id=e.posting_id
         where p.reference_id='{reference}')"""
    return docker("exec", postgres, "psql", "-U", "wallet_user", "-d", "wallet_db", "-Atc", sql)


wallet = container("wallet-service")
postgres = container("postgres")
bank = container("bank-simulator")
started = docker("inspect", "-f", "{{.State.StartedAt}}", postgres, bank)
config = api("GET", "/bank/admin/config")
stopped = False
try:
    api("PUT", "/bank/admin/config", {})
    credentials = {"username": "m10" + uuid.uuid4().hex[:12], "password": uuid.uuid4().hex}
    api("POST", "/api/v1/auth/register", dict(credentials, displayName="Recovery test"))
    token = api("POST", "/api/v1/auth/login", credentials)["accessToken"]
    api("POST", "/api/v1/wallets", {}, token)
    deposit = api("POST", "/api/v1/deposits", {"amount": 10000}, token, uuid.uuid4().hex)
    wait(lambda: api("GET", "/api/v1/deposits/" + deposit["id"], token=token)["status"] == "SUCCEEDED")
    api("PUT", "/bank/admin/config", {"hangRate": 1, "webhookCopies": 0})
    payout = api("POST", "/api/v1/withdrawals", {"amount": 2400}, token, uuid.uuid4().hex)
    ref = str(uuid.UUID(payout["id"]))
    detail = "/api/v1/withdrawals/" + ref
    incoming = api("POST", "/api/v1/deposits", {"amount": 600}, token, uuid.uuid4().hex)
    charge = str(uuid.UUID(incoming["id"]))
    charge_detail = "/api/v1/deposits/" + charge
    wait(lambda: {ref, charge}.issubset(t["reference"] for t in api("GET", "/bank/admin/transactions")
                                        if t["status"] == "SUCCEEDED"))
    time.sleep(3.2)  # Default 3s response timeout has elapsed.
    assert api("GET", detail, token=token)["status"] == "PENDING"
    assert api("GET", charge_detail, token=token)["status"] == "PENDING"
    assert api("GET", "/api/v1/wallets/me", token=token)["reserved"] == 2400
    docker("kill", wallet)
    stopped = True
    assert counts(ref) == "1|1|2"
    assert counts(charge) == "0|0|0"
    docker("start", wallet)
    stopped = False
    wait(lambda: api("GET", detail, token=token)["status"] == "SUCCEEDED")
    wait(lambda: api("GET", charge_detail, token=token)["status"] == "SUCCEEDED")
    funds = api("GET", "/api/v1/wallets/me", token=token)
    assert (funds["balance"], funds["reserved"], funds["sentToday"]) == (8200, 0, 2400)
    assert counts(ref) == "2|2|4"
    assert counts(charge) == "1|1|2"
    assert docker("inspect", "-f", "{{.State.StartedAt}}", postgres, bank) == started
    print(f"PASS payout {ref} and deposit {charge} recovered. Balance 8200, reserve 0, three postings and three events.")
finally:
    if stopped:
        docker("start", wallet)
    api("PUT", "/bank/admin/config", config)
