#! /usr/bin/env python
# -*- coding: utf-8 -*-

from __future__ import unicode_literals
import logging
from peewee import Model, SqliteDatabase,\
    CharField, IntegerField, TextField, ForeignKeyField, BooleanField


logging.basicConfig(level=logging.INFO, format="%(message)s")

# Database variables for each unique package database.
npm_db = SqliteDatabase('npm_packages.db')
pypi_db = SqliteDatabase('pypi_packages.db')


# Classes unique to each kind of package.
class NPMPackage(Model):
    name = CharField(index=True)
    repository_url = CharField(null=True, default=None)
    page_no = IntegerField(null=True, default=None)
    readme = TextField(null=True, default=None)

    # NPM data
    description = CharField(null=True, default=None)
    dependents = CharField(null=True, default=None)
    dependencies = CharField(null=True, default=None)
    day_download_count = IntegerField(null=True, default=None)
    week_download_count = IntegerField(null=True, default=None)
    month_download_count = IntegerField(null=True, default=None)

    # Github fields
    stargazers_count = IntegerField(null=True, default=None)
    forks_count = IntegerField(null=True, default=None)
    open_issues_count = IntegerField(null=True, default=None)
    has_wiki = BooleanField(null=True, default=None)
    subscribers_count = IntegerField(null=True, default=None)
    github_contributions_count = IntegerField(null=True, default=None)

    class Meta:
        database = npm_db


class PyPIPackage(Model):
    name = CharField(index=True)
    readme = TextField(null=True, default=None)

    # PyPI data
    description = CharField(null=True, default=None)
    day_download_count = IntegerField(null=True, default=None)
    week_download_count = IntegerField(null=True, default=None)
    month_download_count = IntegerField(null=True, default=None)

    class Meta:
        database = pypi_db


# Base class for README analysis.
class ReadmeAnalysis(Model):
    code_count = IntegerField(default=None)
    word_count = IntegerField(default=None)


# Classes for README analysis unique to each kind of package.
class NPMReadmeAnalysis(ReadmeAnalysis):
    package = ForeignKeyField(NPMPackage)
    class Meta:
        database = npm_db


class PyPIReadmeAnalysis(ReadmeAnalysis):
    package = ForeignKeyField(PyPIPackage)
    class Meta:
        database = pypi_db


# Functions to initialize database tables for each unique package database.
def create_npm_tables():
    npm_db.create_tables([NPMPackage, NPMReadmeAnalysis], safe=True)


def create_pypi_tables():
    pypi_db.create_tables([PyPIPackage, PyPIReadmeAnalysis], safe=True)
