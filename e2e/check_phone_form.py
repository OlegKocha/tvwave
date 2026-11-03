#!/usr/bin/env python3
"""Check a real QR session without accepting a URL or changing TV configuration.
Then submit through an actual browser and verify the TV editor (see test plan).
"""
import argparse, json, urllib.request, urllib.error
from pathlib import Path
p=argparse.ArgumentParser()
p.add_argument('--url-file', required=True)
p.add_argument('--output', required=True)
a=p.parse_args()
url=Path(a.url_file).read_text().strip()
results={}
with urllib.request.urlopen(url, timeout=8) as response:
    policy=response.headers.get('Referrer-Policy')
    results['referrer_policy']=policy
    results['form_present']='<form method="post">' in response.read().decode()
# no-referrer makes regular browser form POSTs send Origin:null, rejected by the TV.
results['browser_origin_preserved']=policy=='same-origin'
for origin in ['null', 'http://evil.invalid']:
    req=urllib.request.Request(url, data=b'url=https%3A%2F%2Fexample.com%2Fepg.xml',
        headers={'Content-Type':'application/x-www-form-urlencoded', 'Origin':origin})
    try:
        with urllib.request.urlopen(req, timeout=8) as response: status=response.status
    except urllib.error.HTTPError as error: status=error.code
    results['reject_'+origin]=status==403
Path(a.output).write_text(json.dumps(results, indent=2)+'\n')
print(json.dumps(results, indent=2))
assert results['form_present'] and results['browser_origin_preserved']
assert results['reject_null'] and results['reject_http://evil.invalid']
