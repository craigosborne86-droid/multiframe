#!/usr/bin/env python3
"""Fail-closed gate for an externally measured, fully assembled request."""
import argparse
import json


def nonnegative(value):
    number = int(value)
    if number < 0:
        raise argparse.ArgumentTypeError('token counts must be nonnegative')
    return number


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input-tokens', required=True, type=nonnegative,
                        help='Authoritative count including instructions, history, tools and media')
    parser.add_argument('--reserve', type=nonnegative, default=8192)
    args = parser.parse_args()
    projected = args.input_tokens + args.reserve
    blocked = projected >= 272000
    warning = projected >= 240000
    print(json.dumps({'input_tokens': args.input_tokens, 'reserve': args.reserve,
                      'projected_input_tokens': projected,
                      'status': 'blocked' if blocked else 'warning' if warning else 'ok',
                      'action': 'Use one subsystem, bounded excerpts, or a compact handoff; recount before sending.'
                      if warning else 'Proceed without adding uncounted content.'}))
    return 2 if blocked else 0


if __name__ == '__main__':
    raise SystemExit(main())
