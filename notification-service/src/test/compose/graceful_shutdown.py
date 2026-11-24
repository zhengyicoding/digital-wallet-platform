"""OPS-8 process test. Run on a dedicated healthy Compose project after Maven verify.

python3 notification-service/src/test/compose/graceful_shutdown.py PROJECT EDGE_URL
Requires Docker CLI. Stops/restarts bank and notification containers, restores bank settings.
Checks a live HTTP request, DB-before-offset ordering, and redelivery of an unprocessed record.
"""
import concurrent.futures
import json
import subprocess
import sys
import time
import uuid
from urllib.request import Request, urlopen

project, edge = sys.argv[1:]


def docker(*args, data=None):
    return subprocess.check_output(['docker', *args], input=data, text=True, stderr=subprocess.STDOUT).strip()


def container(service):
    ids = docker('ps', '-aq', '--filter', f'label=com.docker.compose.project={project}',
                 '--filter', f'label=com.docker.compose.service={service}').splitlines()
    assert len(ids) == 1, (service, ids)
    return ids[0]


def api(method, path, body=None, timeout=45):
    request = Request(edge + path, method=method, headers={'Content-Type': 'application/json'},
                      data=None if body is None else json.dumps(body).encode())
    with urlopen(request, timeout=timeout) as response:
        return response.status, json.load(response)


def wait(probe, seconds=40):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        if probe():
            return
        time.sleep(.2)
    raise AssertionError('Timed out: ' + str(probe))


bank, notification, postgres, kafka = map(container, ['bank-simulator', 'notification-service', 'postgres', 'kafka'])


def sql(query):
    return docker('exec', postgres, 'psql', '-U', 'notification_user', '-d', 'notification_db', '-Atc', query)


def event():
    event_id, user, reference = (str(uuid.uuid4()) for _ in range(3))
    return event_id, json.dumps(dict(eventId=event_id, type='deposit.succeeded', version=1,
        occurredAt='2026-11-23T00:00:00Z', aggregateId=reference,
        payload=dict(depositId=reference, walletId=str(uuid.uuid4()), userId=user, amount=10, currency='USD')))


def produce(value):
    docker('exec', '-i', kafka, '/opt/kafka/bin/kafka-console-producer.sh', '--bootstrap-server', 'localhost:9092',
           '--topic', 'wallet.events', '--property', 'parse.key=true', '--property', 'key.separator=|',
           data='m11-shutdown|' + value + '\n')


def offsets():
    output = docker('exec', kafka, '/opt/kafka/bin/kafka-consumer-groups.sh', '--bootstrap-server', 'localhost:9092',
                    '--group', 'notification-service', '--describe')
    rows = [line.split() for line in output.splitlines() if 'wallet.events ' in line]
    return {int(row[2]): (0 if row[3] == '-' else int(row[3]), int(row[4])) for row in rows}


def count(event_id):
    return int(sql(f"select count(*) from processed_events where event_id='{event_id}'"))


config = api('GET', '/bank/admin/config')[1]
lock = None
with concurrent.futures.ThreadPoolExecutor() as pool:
    try:
        # BNK-6 gives a deterministic live request: creation precedes the 30s response hang.
        api('PUT', '/bank/admin/config', dict(hangRate=1, webhookCopies=0))
        reference = str(uuid.uuid4())
        # Keep the HTTP client in web so it survives bank termination. Pass the key via stdin.
        bank_key = docker('exec', bank, 'printenv', 'BANK_API_KEY')
        command = ['docker', 'exec', '-i', container('web'), 'sh', '-c',
                   'read -r bank_key; wget -qO- --header="X-Api-Key: $bank_key" --header="Content-Type: application/json" '
                   '--post-data=\'' + json.dumps(dict(reference=reference, type='CHARGE', amount=1)) +
                   '\' http://bank-simulator:8083/bank/v1/transactions']
        request = pool.submit(subprocess.check_output, command, input=bank_key + '\n', text=True)
        wait(lambda: any(t['reference'] == reference for t in api('GET', '/bank/admin/transactions')[1]))
        assert not request.done()
        stopping = pool.submit(docker, 'stop', '--time', '50', bank)
        result = json.loads(request.result(timeout=40))
        stopping.result(timeout=20)
        assert result['reference'] == reference and result['amount'] == 1
        assert docker('inspect', '-f', '{{.State.ExitCode}}', bank) in ('0', '143')
        assert 'Graceful shutdown complete' in docker('logs', '--tail', '35', bank)
        print('OPS-8 PASS: in-flight bank HTTP request returned its complete response during SIGTERM', flush=True)
        docker('start', bank)
        wait(lambda: docker('inspect', '-f', '{{.State.Health.Status}}', bank) == 'healthy')

        # Hold the consumer inside its transaction. A second same-partition record must be left for replay.
        lock = subprocess.Popen(['docker', 'exec', '-i', postgres, 'psql', '-U', 'notification_user',
                                 '-d', 'notification_db', '-At'], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        lock.stdin.write("begin; lock table processed_events in access exclusive mode; select 'locked';\n")
        lock.stdin.flush()
        while lock.stdout.readline().strip() != 'locked':
            assert lock.poll() is None
        first_id, first = event()
        second_id, second = event()
        before = offsets()
        produce(first)
        wait(lambda: int(sql("select count(*) from pg_stat_activity where datname='notification_db' "
                             "and wait_event_type='Lock' and query like 'insert into processed_events%'")) > 0)
        produce(second)
        blocked = offsets()
        assert {p: o[0] for p, o in blocked.items()} == {p: o[0] for p, o in before.items()}
        signal_since = str(int(time.time()) - 1)
        stopping = pool.submit(docker, 'stop', '--time', '50', notification)
        # Kafka stops at a higher lifecycle phase than HTTP; its drain blocks the HTTP shutdown log.
        wait(lambda: '"signal":"15"' in docker('events', '--since', signal_since,
             '--until', str(time.time()), '--filter', 'container=' + notification,
             '--filter', 'event=kill', '--format', '{{json .Actor.Attributes}}'), 10)
        assert not stopping.done()
        lock.stdin.write('commit;\n\\q\n'); lock.stdin.flush()
        lock.wait(timeout=5); lock = None
        stopping.result(timeout=45)
        assert count(first_id) == 1 and count(second_id) == 0
        after = offsets()
        changed = [p for p in after if after[p][1] > before[p][1]]
        assert len(changed) == 1, (before, after)
        partition = changed[0]
        assert after[partition] == (before[partition][1] + 1, before[partition][1] + 2), (before, after)
        print('OPS-8 PASS: DB commit preceded offset commit; shutdown committed the processed record only', flush=True)
        docker('start', notification)
        wait(lambda: count(second_id) == 1)
        assert count(first_id) == 1
        assert int(sql(f"select count(*) from notifications where event_id in ('{first_id}','{second_id}')")) == 2
        print('OPS-8 PASS: unprocessed record redelivered after restart, one notification per event', flush=True)
    finally:
        if lock:
            lock.stdin.write('rollback;\n\\q\n'); lock.stdin.flush(); lock.wait(timeout=5)
        for service in (bank, notification):
            docker('start', service)
            wait(lambda: docker('inspect', '-f', '{{.State.Health.Status}}', service) == 'healthy', 60)
        # The bank process loses in-memory config on restart; restore the original test configuration.
        api('PUT', '/bank/admin/config', config)
