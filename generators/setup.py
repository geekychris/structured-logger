#!/usr/bin/env python3
"""Setup script for structured logger generator."""

from setuptools import setup, find_packages

setup(
    name="structured-logger-generator",
    version="1.0.0",
    description="Generate type-safe structured loggers with envelope format from JSON configs",
    author="Your Organization",
    author_email="dev@yourorg.com",
    py_modules=["generate_loggers"],
    entry_points={
        "console_scripts": [
            "structured-logger-generate=generate_loggers:main",
        ],
    },
    python_requires=">=3.7",
    classifiers=[
        "Development Status :: 4 - Beta",
        "Intended Audience :: Developers",
        "Programming Language :: Python :: 3",
        "Programming Language :: Python :: 3.7",
        "Programming Language :: Python :: 3.8",
        "Programming Language :: Python :: 3.9",
        "Programming Language :: Python :: 3.10",
        "Programming Language :: Python :: 3.11",
    ],
)
