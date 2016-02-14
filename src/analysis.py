#! /usr/bin/env python
# -*- coding: utf-8 -*-

from __future__ import unicode_literals
import argparse
import logging
import markdown
from bs4 import BeautifulSoup
import re

from models import create_npm_tables, NPMPackage, NPMReadmeAnalysis,
                   create_pypi_tables, PyPIPackage, PyPIReadmeAnalysis


logging.basicConfig(level=logging.DEBUG, format="%(message)s")


def npm_analysis():
    logging.info("Starting NPM package analysis.")
    for p in NPMPackage.select().where(NPMPackage.readme != ''):
        readme_text = p.readme
        html = markdown.markdown(readme_text)
        soup = BeautifulSoup(html, 'html.parser')

        # This is a heuristic for word-count.
        # It will be not be precisely correct, depending on your definition of word.
        # For example, a path like 'com.app.example' is split into three words here.
        word_count = len(re.findall('\w+', soup.text))

        # Another heuristic.  As it's typical that inline code examples occur in <pre>
        # blocks, especially in formatted markdown, we count code blocks based
        # on the appearance of <pre> tags.
        code_blocks = soup.find_all('pre')
        block_count = len(code_blocks)

        try:
            analysis = NPMReadmeAnalysis.get(NPMReadmeAnalysis.package == p)
        except NPMReadmeAnalysis.DoesNotExist:
            analysis = NPMReadmeAnalysis.create(
                package=p, code_count=block_count, word_count=word_count
            )
            logging.debug("Created README analysis for package %s", p.name)
        else:
            analysis.code_count = block_count
            analysis.word_count = word_count
            analysis.save()
            logging.debug("Updated README analysis for package %s", p.name)


def pypi_analysis():
    logging.info("Starting PyPI package analysis.")
    for p in PyPIPackage.select().where(PyPIPackage.readme != ''):
        # This is a heuristic for word-count.
        # It will be not be precisely correct, depending on your definition of word.
        # For example, a path like 'com.app.example' is split into three words here.
        word_count = len(re.findall('\w+', p.readme))

        # Another heuristic.
        # In reStructuredText (reST), code blocks are introduced by ending a paragraph
        # with a special marker ::. The block must be indented and separated from the
        # surrounding paragraphs by blank lines. Thus, there must be at least two new line
        # characters after the special marker ::.

        # This may prove to be a broken heuristic. In that case, consider using Sphinx:
        # http://www.sphinx-doc.org/en/stable/index.html.
        block_count = len(re.findall('::.*\\n\\n', p.readme))

        try:
            analysis = PyPIReadmeAnalysis.get(PyPIReadmeAnalysis.package == p)
        except PyPIReadmeAnalysis.DoesNotExist:
            analysis = PyPIReadmeAnalysis.create(
                package=p, code_count=block_count, word_count=word_count
            )
            logging.debug("Created README analysis for package %s", p.name)
        else:
            analysis.code_count = block_count
            analysis.word_count = word_count
            analysis.save()
            logging.debug("Updated README analysis for package %s", p.name)

    logging.info("Finished analyzing READMEs.")


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description="Download package stats for NPM or PyPI")
    parser.add_argument(
        '--package-list',
        action='store_true',
        help="package database to fetch packges from; valid arguments are 'npm' or 'pypi'"
    )
    args = parser.parse_args()

    if args.package_list == 'npm':
        create_npm_tables()
        npm_analysis()
    elif args.package_list == 'pypi':
        create_pypi_tables()
        pypi_analysis()
    else:
        print "Please provide a valid argument to package-list: 'npm' or 'pypi'"
