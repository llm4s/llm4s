#!/usr/bin/env bash
# Fails when a GitHub Actions workflow loses the hardening OpenSSF Scorecard scores (#2306):
#   - Token-Permissions: every workflow declares a top-level `permissions:` block, and that block grants no write
#     access (`write-all`, or any scope set to `write`). A job that needs more declares it on the job.
#   - Pinned-Dependencies: every `uses:` that is not a local action (`./...`) is pinned by a full 40-character commit
#     SHA with the version as a trailing comment (`owner/repo@<sha> # v1.2.3`), which is the form Dependabot keeps
#     current; a `docker://` image is pinned by `@sha256:` digest.
#
# No network. Usage: scripts/check-workflow-security.sh [workflow-dir]     (needs ruby, on GitHub's runners)
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WF_DIR="${1:-$REPO_ROOT/.github/workflows}"
command -v ruby >/dev/null || { echo "FAIL: ruby is needed to run this check"; exit 1; }
[ -d "$WF_DIR" ] || { echo "FAIL: no workflow directory at $WF_DIR"; exit 1; }

ruby -ryaml - "$WF_DIR" <<'RUBY'
dir = ARGV[0]
files = Dir.glob(File.join(dir, '*.{yml,yaml}')).sort
errors = []
abort("FAIL: no workflow files in #{dir}") if files.empty?

SHA_PIN    = %r{\A[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(/[^@\s]+)?@[0-9a-f]{40}\z}
DOCKER_PIN = %r{\Adocker://[^@\s]+@sha256:[0-9a-f]{64}\z}

files.each do |path|
  name = File.basename(path)
  begin
    wf = YAML.safe_load(File.read(path), aliases: true)
  rescue Psych::Exception => e
    errors << "#{name}: is not valid YAML (#{e.message.lines.first.strip})"
    next
  end
  unless wf.is_a?(Hash)
    errors << "#{name}: is not a workflow (no top-level mapping)"
    next
  end

  # Token-Permissions
  if !wf.key?('permissions')
    errors << "#{name}: has no top-level `permissions:` block; add `permissions: contents: read` and grant more on the jobs that need it"
  else
    perms = wf['permissions']
    writes =
      case perms
      when 'write-all' then ['write-all']
      when Hash then perms.select { |_, v| v.to_s == 'write' }.keys
      else []
      end
    unless writes.empty?
      errors << "#{name}: the top-level `permissions:` grants write access (#{writes.join(', ')}); move it to the job that needs it"
    end
  end

  # Pinned-Dependencies, from the parsed workflow: job-level `uses:` (a reusable workflow) and step-level `uses:`.
  refs = []
  (wf['jobs'] || {}).each do |job_id, job|
    next unless job.is_a?(Hash)
    refs << [job_id, job['uses']] if job['uses']
    (job['steps'] || []).each { |s| refs << [job_id, s['uses']] if s.is_a?(Hash) && s['uses'] }
  end
  refs.each do |job_id, ref|
    ref = ref.to_s.strip
    next if ref.start_with?('./')
    next if ref.match?(DOCKER_PIN)
    unless ref.match?(SHA_PIN)
      errors << "#{name} (job #{job_id}): `uses: #{ref}` is not pinned by a full commit SHA; use `#{ref.split('@').first}@<40-character sha> # <version>`"
    end
  end

  # The version comment, from the raw lines: YAML drops comments, and Dependabot needs it to report and bump the pin.
  File.readlines(path).each_with_index do |line, i|
    next unless line =~ /^\s*(-\s+)?uses:\s*['"]?([^'"\s#]+)['"]?\s*(#\s*(\S+))?/
    ref, comment = $2, $4
    next unless ref.match?(SHA_PIN)
    if comment.nil? || comment.empty?
      errors << "#{name}:#{i + 1}: `uses: #{ref}` has no version comment; add `# <version>` (for example `# v1.2.3`)"
    end
  end
end

if errors.empty?
  puts "ok   #{files.size} workflow(s): top-level read-only permissions, every action pinned by SHA with its version"
  exit 0
end
errors.each { |e| puts "FAIL #{e}" }
puts
puts "#{errors.size} problem(s). See CONTRIBUTING.md, \"Workflow security\"."
exit 1
RUBY
