#!/usr/bin/env python3
"""端点冒烟：枚举所有 GET 路由，在真实实例上逐个打一遍，只报告 5xx。

用途：回答「哪些接口从来没被真的调用过、其中有没有 500」。这是补 coverage 问题的第一步，
**不是覆盖率证明**——脚本会如实区分三桶结果：

  exercised   真的打到了，且没 5xx
  needs-fixture 路径里还有没法替换的占位（或需要先造数据），未验证
  failed      5xx 或业务码 50000（这些才是要修的）

刻意只打 GET：幂等、无副作用。POST/DELETE 需要构造请求体、且可能改数据，交给 verify_all.py 和集成测试。

用法：
  python3 scripts/smoke_endpoints.py --base http://127.0.0.1:8300 --user admin --password 123456
退出码：有 failed 时为 1，否则 0。
"""
import argparse
import io
import json
import os
import re
import sys
import urllib.error
import urllib.request

MAPPING = re.compile(r'@(Get|Post|Put|Delete|Patch)Mapping\b\s*(\(([^)]*)\))?')
QUOTED = re.compile(r'"([^"]*)"')
CLASS_LEVEL = re.compile(r'@RequestMapping\("([^"]+)"\)')

BASE = ''
TOKEN = ''


def call(method, path, body=None, token=None, timeout=20):
    headers = {'Content-Type': 'application/json'}
    tok = token if token is not None else TOKEN
    if tok:
        headers['Authorization'] = 'Bearer ' + tok
    data = json.dumps(body).encode('utf-8') if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read() or b'{}')
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw or b'{}')
        except Exception:
            return e.code, {'_raw': raw[:160].decode('utf-8', 'replace')}
    except Exception as e:
        return 0, {'_error': str(e)}


def data_of(body):
    return (body or {}).get('data')


def enumerate_get_endpoints(api_dir):
    """从控制器源码解析出所有 GET 路由（类级 @RequestMapping + 方法级 @GetMapping）。"""
    endpoints = []
    for fn in sorted(os.listdir(api_dir)):
        if not fn.endswith('Controller.java'):
            continue
        src = io.open(os.path.join(api_dir, fn), encoding='utf-8').read()
        cm = CLASS_LEVEL.search(src)
        base = cm.group(1) if cm else ''
        for mm in MAPPING.finditer(src):
            if mm.group(1).upper() != 'GET':
                continue
            args = mm.group(3) or ''
            q = QUOTED.search(args)
            path = base + (q.group(1) if q else '')
            if path and (path, fn) not in endpoints:
                endpoints.append((path, fn))
    return endpoints


def discover_ids():
    """找出可用于替换占位符的真实 id；拿不到的保持缺失，端点会落进 needs-fixture。"""
    ids = {}
    status, body = call('GET', '/api/v1/datasets?pageNumber=1&pageSize=50')
    rows = data_of(body) or []
    if isinstance(rows, dict):
        rows = rows.get('records') or []
    if rows:
        dataset = rows[0]['id']
        ids['datasetId'] = dataset
    status, body = call('GET', '/api/v1/evaluators?pageNumber=1&pageSize=20')
    rows = data_of(body) or []
    if isinstance(rows, dict):
        rows = rows.get('records') or []
    if rows:
        ids['evaluatorId'] = rows[0]['id']
    # trace 列表是 POST /traces/search（GET /traces 不存在），拿一条真实 traceId
    status, body = call('POST', '/api/v1/traces/search', {'pageNumber': 1, 'pageSize': 5})
    rows = data_of(body) or []
    if isinstance(rows, dict):
        rows = rows.get('records') or []
    if rows:
        ids['traceId'] = rows[0].get('traceId') or rows[0].get('id')
        if rows[0].get('sessionId'):
            ids['sessionId'] = rows[0]['sessionId']
    if 'datasetId' in ids:
        status, body = call('GET', '/api/v1/datasets/%s/runs?pageNumber=1&pageSize=5' % ids['datasetId'])
        rows = data_of(body) or []
        if isinstance(rows, dict):
            rows = rows.get('records') or []
        if rows:
            ids['runId'] = rows[0].get('id') or rows[0].get('runId')
    return {k: v for k, v in ids.items() if v}


def main():
    global BASE, TOKEN
    parser = argparse.ArgumentParser()
    parser.add_argument('--base', required=True)
    parser.add_argument('--user', default='admin')
    parser.add_argument('--password', default='123456')
    parser.add_argument('--api-dir', default='emonlens-server/src/main/java/io/emonlens/server/api')
    parser.add_argument('--show-skipped', action='store_true')
    args = parser.parse_args()
    BASE = args.base.rstrip('/')

    status, body = call('POST', '/api/v1/auth/login', {'username': args.user, 'password': args.password}, token='')
    token = data_of(body).get('token') if isinstance(data_of(body), dict) else None
    if not token:
        print('登录失败: %s' % body)
        return 2
    TOKEN = token

    ids = discover_ids()
    endpoints = enumerate_get_endpoints(args.api_dir)

    # {id} 在不同控制器里指不同实体：按控制器把 id 映射到对应实体
    id_for_controller = {
        'DatasetController.java': 'datasetId',
        'EvaluatorController.java': 'evaluatorId',
        'TraceController.java': 'traceId',
        'SessionController.java': 'sessionId',
    }

    exercised, needs_fixture, failed = [], [], []
    for path, controller in endpoints:
        concrete = path
        # 占位符名与实体名不一致时，按控制器补一次
        entity_key = id_for_controller.get(controller)
        if entity_key and entity_key in ids:
            concrete = concrete.replace('{id}', ids[entity_key])
        for key, val in ids.items():
            concrete = concrete.replace('{' + key + '}', val)
        if re.search(r'\{[^}]+\}', concrete):
            needs_fixture.append(path)
            continue
        status, body = call('GET', concrete)
        biz = body.get('code') if isinstance(body, dict) else None
        if status >= 500 or biz == 50000:
            failed.append((path, status, biz, str(body)[:200]))
        elif status == 404 or biz == 40401:
            # 404 多半是占位替换到了错误的 id（例如把数据集 id 当评估器 id）。不当通过也不当缺陷。
            needs_fixture.append(path)
        else:
            exercised.append(path)

    print('GET 端点总数: %d' % len(endpoints))
    print('  exercised     : %d' % len(exercised))
    print('  needs-fixture : %d' % len(needs_fixture))
    print('  failed        : %d' % len(failed))
    for path, status, biz, detail in failed:
        print('  FAIL %s -> http=%s code=%s %s' % (path, status, biz, detail))
    if args.show_skipped:
        print()
        print('needs-fixture 明细（缺真实 id 或需要先造数据；不代表接口有问题）：')
        for path in needs_fixture:
            print('  ', path)
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
