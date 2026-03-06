#!/usr/bin/env python3
"""
Generate a markdown summary of coverage gaps for GitHub Actions workflow summary.

This script reads the coverage gaps report and formats it for display in the
GitHub Actions workflow summary page.
"""

import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Dict, List


def parse_scoverage_summary() -> Dict:
    """Parse scoverage XML files and extract summary statistics."""
    coverage_files = list(Path(".").glob("**/scoverage.xml"))
    
    if not coverage_files:
        return {
            "error": "No scoverage.xml files found",
            "coverage": 0,
            "uncovered": 0,
            "files": 0
        }
    
    total_statements = 0
    total_covered = 0
    module_stats = {}
    
    for xml_file in coverage_files:
        try:
            tree = ET.parse(xml_file)
            root = tree.getroot()
            
            for package in root.findall(".//package"):
                package_name = package.get("name", "core")
                if package_name not in module_stats:
                    module_stats[package_name] = {"statements": 0, "covered": 0}
                
                for cls in package.findall(".//class"):
                    for line in cls.findall(".//line"):
                        statements = 1
                        hits = int(line.get("hits", "0") or "0")
                        covered = 1 if hits > 0 else 0
                        
                        total_statements += statements
                        total_covered += covered
                        module_stats[package_name]["statements"] += statements
                        module_stats[package_name]["covered"] += covered
        except Exception as e:
            print(f"Error parsing {xml_file}: {e}", file=sys.stderr)
    
    coverage_pct = (total_covered / total_statements * 100) if total_statements > 0 else 0
    
    return {
        "coverage": coverage_pct,
        "statements": total_statements,
        "covered": total_covered,
        "uncovered": total_statements - total_covered,
        "modules": module_stats,
        "files": len(coverage_files)
    }


def generate_summary_md() -> str:
    """Generate markdown summary for GitHub Actions."""
    stats = parse_scoverage_summary()
    
    if "error" in stats:
        return f"## 📊 Coverage Analysis\n\n⚠️ {stats['error']}\n"
    
    lines = []
    lines.append("## 📊 Test Coverage Analysis Summary\n")
    
    # Overall coverage badge
    coverage = stats["coverage"]
    if coverage >= 80:
        badge = "🟢 Excellent"
    elif coverage >= 60:
        badge = "🟡 Good"
    else:
        badge = "🔴 Needs Improvement"
    
    lines.append(f"**Overall Coverage**: {coverage:.1f}% {badge}\n")
    
    # Key metrics
    lines.append("### Key Metrics")
    lines.append(f"- **Covered Statements**: {stats['covered']:,} / {stats['statements']:,}")
    lines.append(f"- **Uncovered Lines**: {stats['uncovered']:,}")
    lines.append("")
    
    # Module breakdown
    if stats.get("modules"):
        lines.append("### Coverage by Module")
        lines.append("| Module | Coverage | Statements |")
        lines.append("|--------|----------|-----------|")
        
        for module, data in sorted(stats["modules"].items(), key=lambda x: x[1]["covered"] / max(x[1]["statements"], 1), reverse=True):
            if data["statements"] > 0:
                pct = (data["covered"] / data["statements"] * 100)
                indicator = "🟢" if pct >= 80 else "🟡" if pct >= 60 else "🔴"
                lines.append(f"| {module:30} | {pct:6.1f}% {indicator} | {data['statements']:4} |")
        lines.append("")
    
    # Next steps
    lines.append("### Next Steps")
    lines.append("- Check detailed report in **Coverage Analysis** artifact")
    lines.append("- Focus tests on lowest-coverage modules")
    lines.append("- Run locally: `sbt coverage core/test coverageReport`")
    lines.append("- See [Testing Guide](https://llm4s.org/reference/testing-guide.html)")
    
    return "\n".join(lines)


if __name__ == "__main__":
    summary = generate_summary_md()
    with open("coverage-summary.md", "w") as f:
        f.write(summary)
    print(summary)
