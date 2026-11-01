#!/usr/bin/env python3
"""Prefetch the nearest eligible image for a platform, without contacting a speaker."""
import argparse,json,time
from control import Control,CUTOFF
p=argparse.ArgumentParser();p.add_argument('platform');args=p.parse_args()
c=Control();c.prepare(args.platform)
previous = None
while args.platform in c.preparing:
 time.sleep(1)
 for key,value in list(c.downloads.items()):
  if key.startswith(args.platform+'/'):
   message = json.dumps(dict(key=key,**value))
   if message != previous: print(message,flush=True); previous = message
selected=c.selections.get(args.platform)
if not selected:raise SystemExit('No verified image selected; inspect log/events.jsonl')
print(json.dumps({'cutoff':str(CUTOFF),'selected':selected}),flush=True)
