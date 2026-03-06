#!/usr/bin/env python3
"""
Analyze test coverage data and identify gaps.

This script processes scoverage XML reports to identify which files,
functions, and lines have the lowest test coverage, providing actionable
insights for contributors.

Usage:
    # Analyze local coverage report
    python3 scripts/analyze-coverage.py --local --output coverage-gaps.txt

    # Analyze with Codecov API (requires CODECOV_TOKEN env var)
    python3 scripts/analyze-coverage.py --codecov --repo llm4s/llm4s
"""

import argparse
import json
import os
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Tuple
import urllib.request
import urllib.error


@dataclass
class FileCoverage:
    """Coverage statistics for a single file."""
    path: str
    statements: int
    covered: int
    coverage_percent: float
    uncovered_lines: List[int]
    uncovered_functions: List[str]

    @property
    def uncovered(self) -> int:
        return self.statements - self.covered


@dataclass
class ModuleCoverage:
    """Coverage statistics for a module."""
    name: str
    files: List[FileCoverage]
    total_statements: int
    total_covered: int

    @property
    def coverage_percent(self) -> float:
        if self.total_statements == 0:
            return 0.0
        return (self.total_covered / self.total_statements) * 100

    @property
    def total_uncovered(self) -> int:
        return self.total_statements - self.total_covered


class CoverageAnalyzer:
    """Analyze scoverage XML reports."""

    def __init__(self):
        self.modules: Dict[str, ModuleCoverage] = {}
        self.all_files: List[FileCoverage] = []

    def parse_scoverage_xml(self, xml_file: Path) -> None:
        """Parse a scoverage XML report file."""
        try:
            tree = ET.parse(xml_file)
            root = tree.getroot()

            for package in root.findall(".//package"):
                package_name = package.get("name", "")
                module_name = self._extract_module_name(package_name)

                if module_name not in self.modules:
                    self.modules[module_name] = ModuleCoverage(
                        name=module_name,
                        files=[],
                        total_statements=0,
                        total_covered=0
                    )

                for cls in package.findall(".//class"):
                    file_coverage = self._parse_class(cls, module_name)
                    if file_coverage:
                        self.modules[module_name].files.append(file_coverage)
                        self.all_files.append(file_coverage)
                        self.modules[module_name].total_statements += file_coverage.statements
                        self.modules[module_name].total_covered += file_coverage.covered

        except ET.ParseError as e:
            print(f"Error parsing {xml_file}: {e}", file=sys.stderr)

    def _extract_module_name(self, package_name: str) -> str:
        """Extract module name from package path."""
        if package_name.startswith("org.llm4s."):
            parts = package_name.split(".")
            if len(parts) > 2:
                return parts[2]
        return "core"

    def _parse_class(self, cls_elem: ET.Element, module_name: str) -> Optional[FileCoverage]:
        """Parse a class element and extract coverage info."""
        class_name = cls_elem.get("name", "")
        filename = cls_elem.get("filename", class_name)

        statements = 0
        covered = 0
        uncovered_lines = []
        uncovered_functions = []

        for method in cls_elem.findall(".//method"):
            method_name = method.get("name", "")
            method_coverage = int(method.get("lineRate", "0") or "0")

            for line in method.findall(".//line"):
                line_num = int(line.get("number", "0") or "0")
                hits = int(line.get("hits", "0") or "0")
                statements += 1

                if hits > 0:
                    covered += 1
                else:
                    uncovered_lines.append(line_num)
                    if method_name and method_name not in uncovered_functions:
                        uncovered_functions.append(method_name)

        if statements == 0:
            return None

        coverage_percent = (covered / statements * 100) if statements > 0 else 0

        return FileCoverage(
            path=filename,
            statements=statements,
            covered=covered,
            coverage_percent=coverage_percent,
            uncovered_lines=uncovered_lines,
            uncovered_functions=uncovered_functions
        )

    def get_lowest_coverage_files(self, limit: int = 20) -> List[FileCoverage]:
        """Get files with lowest coverage."""
        return sorted(self.all_files, key=lambda f: f.coverage_percent)[:limit]

    def get_uncovered_modules(self) -> List[ModuleCoverage]:
        """Get modules sorted by coverage percentage."""
        return sorted(self.modules.values(), key=lambda m: m.coverage_percent)

    def get_summary(self) -> Dict:
        """Get overall coverage summary."""
        total_statements = sum(m.total_statements for m in self.modules.values())
        total_covered = sum(m.total_covered for m in self.modules.values())
        overall_coverage = (total_covered / total_statements * 100) if total_statements > 0 else 0

        return {
            "overall_coverage": overall_coverage,
            "total_statements": total_statements,
            "total_covered": total_covered,
            "total_uncovered": total_statements - total_covered,
            "num_modules": len(self.modules),
            "num_files": len(self.all_files)
        }

    def generate_report(self, output_file: Optional[Path] = None) -> str:
        """Generate a comprehensive coverage report."""
        lines = []
        summary = self.get_summary()

        # Header
        lines.append("=" * 80)
        lines.append("TEST COVERAGE ANALYSIS REPORT")
        lines.append("=" * 80)
        lines.append("")

        # Summary
        lines.append("OVERALL SUMMARY")
        lines.append("-" * 80)
        lines.append(f"Overall Coverage:   {summary['overall_coverage']:.1f}%")
        lines.append(f"Total Statements:   {summary['total_statements']}")
        lines.append(f"Covered Statements: {summary['total_covered']}")
        lines.append(f"Uncovered Lines:    {summary['total_uncovered']}")
        lines.append(f"Modules Analyzed:   {summary['num_modules']}")
        lines.append(f"Files Analyzed:     {summary['num_files']}")
        lines.append("")

        # Module breakdown
        lines.append("MODULE COVERAGE BREAKDOWN (sorted by coverage)")
        lines.append("-" * 80)
        modules_by_coverage = self.get_uncovered_modules()
        for module in modules_by_coverage:
            coverage_color = self._get_coverage_indicator(module.coverage_percent)
            lines.append(
                f"{coverage_color} {module.name:20} | "
                f"Coverage: {module.coverage_percent:6.1f}% | "
                f"Uncovered: {module.total_uncovered:4} lines | "
                f"Files: {len(module.files)}"
            )
        lines.append("")

        # Files with lowest coverage
        lines.append("FILES WITH LOWEST COVERAGE (Top 20)")
        lines.append("-" * 80)
        low_coverage_files = self.get_lowest_coverage_files(limit=20)
        for i, file_cov in enumerate(low_coverage_files, 1):
            coverage_color = self._get_coverage_indicator(file_cov.coverage_percent)
            lines.append(
                f"{i:2}. {coverage_color} {file_cov.path:50} | "
                f"{file_cov.coverage_percent:6.1f}% ({file_cov.uncovered} uncovered)"
            )

            if file_cov.uncovered_functions and len(file_cov.uncovered_functions) <= 5:
                func_list = ", ".join(file_cov.uncovered_functions[:5])
                lines.append(f"    Functions without tests: {func_list}")

        lines.append("")

        # Recommendations
        lines.append("RECOMMENDATIONS FOR IMPROVING COVERAGE")
        lines.append("-" * 80)
        lowest_modules = sorted(self.modules.values(), key=lambda m: m.coverage_percent)[:3]
        for module in lowest_modules:
            if module.coverage_percent < 70:
                lines.append(f"• Focus on {module.name}: Currently at {module.coverage_percent:.1f}%")
                lowest_files = sorted(module.files, key=lambda f: f.coverage_percent)[:3]
                for file_cov in lowest_files:
                    lines.append(f"  - {Path(file_cov.path).name} ({file_cov.coverage_percent:.1f}%)")

        lines.append("")
        lines.append("=" * 80)

        report = "\n".join(lines)

        if output_file:
            with open(output_file, "w") as f:
                f.write(report)
            print(f"Report written to {output_file}")

        return report

    @staticmethod
    def _get_coverage_indicator(coverage: float) -> str:
        """Get visual indicator for coverage level."""
        if coverage >= 80:
            return "✓"  # Good
        elif coverage >= 60:
            return "⚠"  # Medium
        else:
            return "✗"  # Low


class CodecovAnalyzer:
    """Analyze coverage via Codecov API."""

    def __init__(self, token: str, repo: str):
        self.token = token
        self.repo = repo
        self.base_url = "https://api.codecov.io"

    def get_commit_info(self, branch: str = "main") -> Optional[Dict]:
        """Get coverage info for latest commit on a branch."""
        url = f"{self.base_url}/api/v2/repos/{self.repo}"
        try:
            request = urllib.request.Request(
                url,
                headers={"Authorization": f"token {self.token}"}
            )
            with urllib.request.urlopen(request, timeout=10) as response:
                return json.loads(response.read().decode())
        except urllib.error.URLError as e:
            print(f"Error fetching from Codecov API: {e}", file=sys.stderr)
            return None

    def get_files_report(self, commit_sha: Optional[str] = None) -> Optional[Dict]:
        """Get detailed file coverage report."""
        if not commit_sha:
            commit_info = self.get_commit_info()
            if not commit_info or "commit" not in commit_info:
                return None
            commit_sha = commit_info["commit"]["commitid"]

        url = f"{self.base_url}/api/v2/repos/{self.repo}/commits/{commit_sha}/files"
        try:
            request = urllib.request.Request(
                url,
                headers={"Authorization": f"token {self.token}"}
            )
            with urllib.request.urlopen(request, timeout=10) as response:
                return json.loads(response.read().decode())
        except urllib.error.URLError as e:
            print(f"Error fetching files report from Codecov API: {e}", file=sys.stderr)
            return None


def find_coverage_files(root_dir: Path) -> List[Path]:
    """Find all scoverage XML report files."""
    pattern = "**/scoverage.xml"
    return list(root_dir.glob(pattern))


def main():
    parser = argparse.ArgumentParser(
        description="Analyze test coverage and identify gaps"
    )
    parser.add_argument(
        "--local",
        action="store_true",
        help="Analyze local scoverage XML reports"
    )
    parser.add_argument(
        "--codecov",
        action="store_true",
        help="Analyze via Codecov API (requires CODECOV_TOKEN env var)"
    )
    parser.add_argument(
        "--repo",
        default="llm4s/llm4s",
        help="Repository (org/name) for Codecov API"
    )
    parser.add_argument(
        "--output",
        type=Path,
        help="Output file for the report"
    )
    parser.add_argument(
        "--root",
        type=Path,
        default=Path.cwd(),
        help="Root directory to search for coverage files"
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Output as JSON instead of formatted report"
    )

    args = parser.parse_args()

    if args.local:
        analyzer = CoverageAnalyzer()
        coverage_files = find_coverage_files(args.root)

        if not coverage_files:
            print("No scoverage.xml files found. Run 'sbt coverage test coverageAggregate' first.")
            sys.exit(1)

        print(f"Found {len(coverage_files)} coverage file(s)")
        for coverage_file in coverage_files:
            print(f"  Analyzing: {coverage_file}")
            analyzer.parse_scoverage_xml(coverage_file)

        if args.json:
            summary = analyzer.get_summary()
            print(json.dumps(summary, indent=2))
        else:
            report = analyzer.generate_report(args.output)
            print(report)

    elif args.codecov:
        token = os.getenv("CODECOV_TOKEN")
        if not token:
            print(
                "Error: CODECOV_TOKEN environment variable not set",
                file=sys.stderr
            )
            sys.exit(1)

        analyzer = CodecovAnalyzer(token, args.repo)
        files_report = analyzer.get_files_report()

        if files_report:
            print(json.dumps(files_report, indent=2))
        else:
            print("Failed to retrieve Codecov data")
            sys.exit(1)

    else:
        parser.print_help()
        sys.exit(1)


if __name__ == "__main__":
    main()
