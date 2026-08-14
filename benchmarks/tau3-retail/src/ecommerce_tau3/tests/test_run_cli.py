"""CLI help ASCII-safety tests.

Windows consoles default to the GBK code page (cp936), which cannot encode
superscript characters. `python -m ecommerce_tau3.run --help` used to embed
such characters in the argparse description and raised UnicodeEncodeError on
GBK consoles. This module formats the parser's help text only - it never
imports tau2 and never touches an LLM.
"""

from __future__ import annotations

from ecommerce_tau3.run import build_parser


def test_cli_help_is_ascii_safe():
    """Regression: formatting --help must not emit non-ASCII characters.

    Encoding under gbk (the Windows console code page) is the failure mode
    that regressed; isascii() is the stronger, codec-independent guarantee.
    """
    help_text = build_parser().format_help()
    assert help_text.isascii()
    help_text.encode("gbk")  # must not raise UnicodeEncodeError


def test_cli_help_lists_driver_flags():
    """Sanity: the documented driver flags survive the ASCII rewrite."""
    help_text = build_parser().format_help()
    for flag in (
        "--config",
        "--stage",
        "--agent",
        "--save-to",
        "--agent-llm",
        "--num-trials",
        "--seed",
        "--task-ids",
        "--num-tasks",
    ):
        assert flag in help_text
