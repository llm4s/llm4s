#!/usr/bin/env -S scala-cli shebang

//> using scala "3.3.1"

import scala.io.Source
import scala.util.Using
import java.io.PrintWriter
import scala.util.matching.Regex

case class Project(
  number: Int,
  title: String,
  mentor: String,
  coMentor: String,
  difficulty: String,
  hours: Int
)

object GenerateGSoCSummary {

  def main(args: Array[String]): Unit = {
    val sourceFile = "Google Summer of Code/Project Ideas/2026.md"
    val outputFile = "Google Summer of Code/GSOC-2026-SUMMARY.md"
    
    println(s"Reading: $sourceFile")
    val content = Using(Source.fromFile(sourceFile))(_.mkString).get
    
    println("Parsing projects...")
    val projects = extractProjects(content)
    println(s"✓ Found ${projects.length} projects")
    
    println("Generating summary...")
    val summary = generateSummary(projects)
    
    println(s"Writing: $outputFile")
    Using(new PrintWriter(outputFile)) { writer =>
      writer.write(summary)
    }
    
    println("✓ Summary generated successfully!")
    println(s"   Total: ${projects.length} projects")
    println(s"   Easy: ${projects.count(_.difficulty == "Easy")}")
    println(s"   Medium: ${projects.count(_.difficulty == "Medium")}")
    println(s"   Hard: ${projects.count(_.difficulty == "Hard")}")
  }

  def extractProjects(content: String): List[Project] = {
    val sections = content.split("(?=\n### )").toList.tail // Skip header before first ###
    var projectNumber = 0
    var tripperNumber = 48
    
    sections.flatMap { section =>
      val lines = section.split("\n").toList
      val headerLine = lines.head.stripPrefix("### ").trim
      
      // Skip template
      if (headerLine.startsWith("Template:")) {
        None
      } else {
        // Parse project number or generate for tripper
        val (number, title) = if (headerLine.matches("^\\d+\\..*")) {
          val parts = headerLine.split("\\.", 2)
          (parts(0).toInt, if (parts.length > 1) parts(1).trim else "")
        } else if (headerLine.contains("llm4s-tripper")) {
          tripperNumber += 1
          (tripperNumber, headerLine)
        } else {
          projectNumber += 1
          (projectNumber, headerLine)
        }
        
        // Extract table data
        val mentor = extractTableValue(lines, "Mentor")
        val coMentor = extractTableValue(lines, "Co-mentor")
        val difficulty = extractTableValue(lines, "Difficulty").getOrElse("Medium")
        val hours = extractTableValue(lines, "Expected Hours").flatMap(_.toIntOption).getOrElse(350)
        
        Some(Project(number, title, mentor.getOrElse("TBD"), coMentor.getOrElse(""), difficulty, hours))
      }
    }.sortBy(_.number)
  }

  def extractTableValue(lines: List[String], key: String): Option[String] = {
    lines.find(_.contains(s"| $key")) match {
      case Some(line) =>
        val parts = line.split("\\|").map(_.trim)
        if (parts.length >= 3) {
          val value = parts(2)
          // Clean up markdown links
          val cleaned = value
            .replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1")
            .replaceAll("Email:\\s*", "")
            .replaceAll("LinkedIn:\\s*", "")
            .trim
          Some(cleaned)
        } else None
      case None => None
    }
  }

  def toAnchor(title: String): String = {
    val withNumber = if (title.matches("^\\d+\\..*")) title else s"${title}"
    withNumber.toLowerCase
      .replaceAll("[^a-z0-9\\s-]", "")
      .replaceAll("\\s+", "-")
      .replaceAll("-+", "-")
      .replaceAll("^-|-$", "")
  }

  def generateSummary(projects: List[Project]): String = {
    val totalProjects = projects.length
    val easyCount = projects.count(_.difficulty == "Easy")
    val mediumCount = projects.count(_.difficulty == "Medium")
    val hardCount = projects.count(_.difficulty == "Hard")
    val hours175 = projects.count(_.hours == 175)
    val hours350 = projects.count(_.hours == 350)
    
    val easyPct = f"${easyCount * 100.0 / totalProjects}%.1f"
    val mediumPct = f"${mediumCount * 100.0 / totalProjects}%.1f"
    val hardPct = f"${hardCount * 100.0 / totalProjects}%.1f"
    val hours175Pct = f"${hours175 * 100.0 / totalProjects}%.1f"
    val hours350Pct = f"${hours350 * 100.0 / totalProjects}%.1f"
    
    val projectRows = projects.map { p =>
      val anchor = toAnchor(s"${p.number}-${p.title}")
      val url = s"https://github.com/llm4s/llm4s/blob/main/Google%20Summer%20of%20Code/Project%20Ideas/2026.md#$anchor"
      s"| ${p.number} | [${p.title}]($url) | ${p.mentor} | ${p.coMentor} | ${p.difficulty} | ${p.hours} |"
    }.mkString("\n")

    s"""# Google Summer of Code 2026 - LLM4S Organization Summary

Welcome to the **LLM4S Google Summer of Code 2026** program! We're excited to offer **$totalProjects innovative projects** across multiple domains including AI agents, RAG systems, data pipelines, tooling, and applications. Our projects span from beginner-friendly to advanced challenges, providing opportunities for contributors at all skill levels.

---

## Quick Navigation

- [Summary Statistics](#summary-statistics)
- [All Projects Table](#all-projects-table)
- [Mentors & Contact Information](#mentors--contact-information)
- [Project Statistics](#project-statistics)
- [Project Repositories](#project-repositories)
- [Contact & Community](#contact--community)

---

## Summary Statistics

- **Total Projects**: $totalProjects
- **Project Sizes**: 
  - Large (350 hours): $hours350 projects
  - Medium (175 hours): $hours175 projects
- **Difficulty Levels**:
  - Easy: $easyCount projects
  - Medium: $mediumCount projects
  - Hard: $hardCount projects
- **Primary Technology**: Scala, LLMs, AI/ML
- **Main Repositories**: LLM4S, llm4s-tripper, Trumbo

---

## All Projects Table

| # | Project Title | Mentors | Co-mentors | Difficulty | Hours |
|---|--------------|---------|------------|------------|-------|
$projectRows

---

## Mentors & Contact Information

Our mentors are experienced developers and researchers passionate about LLMs, Scala, and AI systems. Below is the complete list of mentors with their contact details:

### Primary Mentors

1. **Kannupriya Kalra** *(GSoC Org Admin)*
   - LinkedIn: [linkedin.com/in/kannupriyakalra](https://www.linkedin.com/in/kannupriyakalra/)
   - Email: kannupriyakalra@gmail.com
   - Discord: `kannupriyakalra_46520`
   - Focus: RAG systems, evaluation, llm4s-tripper projects

2. **Rory Graves** *(GSoC Org Admin)*
   - LinkedIn: [linkedin.com/in/roryjgraves](https://www.linkedin.com/in/roryjgraves/)
   - Email: rory.graves@fieldmark.co.uk
   - Discord: `rorybot1`
   - Focus: Agent frameworks, core infrastructure, security

3. **Vitthal Mirji**
   - LinkedIn: [linkedin.com/in/vitthal10](https://www.linkedin.com/in/vitthal10/)
   - Email: vitthalmirji@gmail.com
   - Focus: Data pipelines, GCP integrations, type systems

4. **Giovanni Ruggiero**
   - LinkedIn: [linkedin.com/in/giovanniruggiero](https://www.linkedin.com/in/giovanniruggiero/)
   - Email: giovanni.ruggiero@gmail.com
   - Focus: Multi-agent systems, planning, llm4s-tripper

5. **Iyad**
   - LinkedIn: [linkedin.com/in](https://www.linkedin.com/in)
   - Focus: Agent evaluation, multi-agent systems, security

6. **Atul S Khot**
   - LinkedIn: [linkedin.com/in/atul-s-khot-035b4b1](https://www.linkedin.com/in/atul-s-khot-035b4b1/)
   - Email: atulkhot@gmail.com
   - Focus: Memory systems, data engineering, functional programming

7. **Dmitry Mamonov**
   - LinkedIn: [linkedin.com/in/dmamonov](https://www.linkedin.com/in/dmamonov/)
   - Email: dmitry.s.mamonov@gmail.com
   - Focus: Browser agents, Trumbo screenwriting IDE, gaming platforms

8. **Vamshi Salagala**
   - LinkedIn: [linkedin.com/in/pavanvamsi3](https://www.linkedin.com/in/pavanvamsi3/)
   - Email: pavanvamsi3@gmail.com
   - Focus: Hallucination detection, CI/CD, agent evaluation

9. **Prasad Pramod Shimpatwar**
   - LinkedIn: [linkedin.com/in/prasad-pramod-shimpatwar](https://www.linkedin.com/in/prasad-pramod-shimpatwar/)
   - Email: prasadshimpatwar26@gmail.com
   - Focus: Prompt engineering, migration tools, GraphRAG

10. **Pritish Yuvraj**
    - LinkedIn: [linkedin.com/in/pritishyuvraj](https://www.linkedin.com/in/pritishyuvraj/)
    - Email: pritish.yuvi@gmail.com
    - Focus: Debugging tools, embedded systems

11. **Satvik Kumar**
    - LinkedIn: [linkedin.com/in/satvik-kumar](https://www.linkedin.com/in/satvik-kumar/)
    - Email: satvik.kumar.us@gmail.com
    - Focus: Self-documenting agents, semantic chunking

12. **Debarshi Kundu**
    - LinkedIn: [linkedin.com/in/debarshikundu](https://www.linkedin.com/in/debarshikundu/)
    - Email: debarshi.iiest@gmail.com
    - Focus: RAG evaluation, GraphRAG, multimodal systems

11. **Giovanni Ruggiero**
    - LinkedIn: [linkedin.com/in/giovanniruggiero](https://www.linkedin.com/in/giovanniruggiero/)
    - Email: giovanni.ruggiero@gmail.com
    - Focus: Multi-agent systems, planning, llm4s-tripper

12. **Iyad**
    - LinkedIn: [linkedin.com/in](https://www.linkedin.com/in)
    - Focus: Agent evaluation, multi-agent systems, security

---

## Project Statistics

### By Difficulty Level

| Difficulty | Count | Percentage |
|------------|-------|------------|
| Easy | $easyCount | $easyPct% |
| Medium | $mediumCount | $mediumPct% |
| Hard | $hardCount | $hardPct% |

### By Project Duration

| Duration | Count | Percentage |
|----------|-------|------------|
| 175 hours (Medium) | $hours175 | $hours175Pct% |
| 350 hours (Large) | $hours350 | $hours350Pct% |

### By Focus Area

| Focus Area | Project Count |
|------------|---------------|
| **Core LLM4S Framework** | 20 projects |
| **RAG & Vector Search** | 8 projects |
| **Data Pipelines & Engineering** | 18 projects |
| **llm4s-tripper (Travel Planning)** | 15 projects |
| **Trumbo/Screenwriting IDE** | 5 projects |
| **Tooling & Developer Experience** | 7 projects |

### GCP/Enterprise Integration Projects

18 projects focus on Google Cloud Platform integrations including:
- Vertex AI, BigQuery, Dataflow, Dataplex
- Data lineage, governance, and observability
- Maps API and location-based services

---

## Project Repositories

### Main Repositories

1. **[LLM4S Core](https://github.com/llm4s/llm4s)**
   - Main framework repository
   - Projects: 1-25, 31-48
   - Agents, RAG, data pipelines, GCP integrations

2. **llm4s-tripper**
   - AI-powered travel planning application
   - Projects: 49-63
   - Real-world demonstration of LLM4S capabilities

3. **Trumbo Screenwriting IDE**
   - AI-assisted screenwriting tool
   - Projects: 26-30
   - Script analysis, formatting, and production workflows

---

## Contact & Community

### GSoC Organization Admins

**Kannupriya Kalra**
- Role: GSoC Org Admin
- LinkedIn: [linkedin.com/in/kannupriyakalra](https://www.linkedin.com/in/kannupriyakalra/)
- Email: kannupriyakalra@gmail.com
- Discord: `kannupriyakalra_46520`

**Rory Graves**
- Role: GSoC Org Admin
- LinkedIn: [linkedin.com/in/roryjgraves](https://www.linkedin.com/in/roryjgraves/)
- Email: rory.graves@fieldmark.co.uk
- Discord: `rorybot1`

---

**For more information:**
- Review the [complete project details](./Project%20Ideas/2026.md)
- Join our [Discord community](https://discord.gg/YRdyYfEw)
- Attend weekly LLM4S Dev Hours (Sundays 9am London time)
- Register in the [GSoC 2026 Aspirants Sheet](https://docs.google.com/spreadsheets/d/1GzQDQaFCp4IJz8WlMwiooVDk0JWPh6hvODWRzEZRHxY/edit?gid=0#gid=0)
"""
  }
}
