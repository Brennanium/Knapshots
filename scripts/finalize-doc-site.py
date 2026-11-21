#!/usr/bin/env python3
"""Connect the generated Dokka pages back to the guide site."""
import argparse
import html
import os
import re
from pathlib import Path


def finalize(site: Path) -> None:
    site = site.resolve()
    api = site / 'api'
    if not (site / 'index.html').is_file() or not (api / 'index.html').is_file():
        raise ValueError('The guide or generated API index is missing')
    if not any(api.glob('knapshots-*/index.html')):
        raise ValueError('Dokka did not generate module documentation')
    for page in api.rglob('*.html'):
        text = page.read_text(encoding='utf-8')
        if '<html' not in text.lower() or 'data-knapshots-guides' in text:
            continue
        guide = html.escape(Path(os.path.relpath(site / 'index.html', page.parent)).as_posix(), quote=True)
        link = f'<a data-knapshots-guides href="{guide}" style="margin-left: 1.5rem; color: inherit">Guides</a>'
        text, count = re.subn(r'(<a class="library-name--link"[^>]*>.*?</a>)', lambda match: match.group(1) + link, text, count=1, flags=re.S)
        if count != 1:
            raise ValueError(f'Cannot find the Dokka navigation in {page}')
        page.write_text(text, encoding='utf-8')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('site', type=Path)
    finalize(parser.parse_args().site)
