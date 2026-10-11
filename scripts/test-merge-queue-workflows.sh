#!/usr/bin/env bash
# Checks that the workflows providing main's required checks also run, and report, in a GitHub merge queue (#1638).
#
# A merge queue builds main plus the queued pull request(s) on a temporary branch and fires a `merge_group` event; it
# lands the group only when every required check has reported success on it. A required workflow without that trigger
# never reports, so the queue waits until its timeout and drops the pull request. A required job that is skipped, or
# that reads pull-request-only context (`github.event.pull_request.*`) without a pull_request gate, breaks the same way.
#
# For each required workflow this checks:
#   - it triggers on `merge_group` with `types: [checks_requested]`;
#   - each required check (a job name) exists, and neither it nor anything it needs is skipped by an event condition;
#   - nothing in it reads `github.event.pull_request` unless its step or job runs only on pull_request;
#   - its concurrency neither cancels a merge group nor shares a group with one (a group's ref is unique);
#   - the DCO job does run dco-check on pull requests and reports on merge groups (it needs the pull request);
#   - the secret scan scans the merge group's own range rather than the whole history.
#
# No network. Usage: scripts/test-merge-queue-workflows.sh [workflow-dir]     (needs ruby, on GitHub's runners)
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WF_DIR="${1:-$REPO_ROOT/.github/workflows}"
command -v ruby >/dev/null || { echo "FAIL: ruby is needed to run these checks"; exit 1; }

ruby -ryaml - "$WF_DIR" <<'RUBY'
dir = ARGV[0]
# workflow file => the job names a maintainer makes required (docs/reference/merge-queue.md lists the same three)
REQUIRED = {
  'ci.yml'          => ['All Tests Pass'],
  'dco.yml'         => ['Check DCO sign-off'],
  'secret-scan.yml' => ['TruffleHog Secret Scan'],
}
pass = 0
fail = 0
check = lambda do |label, ok, detail = nil|
  if ok
    pass += 1
    puts "ok   [#{label}]"
  else
    fail += 1
    puts "FAIL [#{label}]"
    puts "       #{detail}" if detail
  end
end

# Evaluates an `if:` as GitHub would on a merge_group event, on the queue's temporary branch. Only literals, the
# event name, the ref, the status functions and ==, !=, &&, ||, ! and parentheses are understood; anything else
# (another context, a function) cannot be decided here, so it counts as skipping: such a condition on a required job
# needs a look by hand, and this check should be taught it.
runs_on_merge_group = lambda do |cond|
  c = cond.to_s.strip
  next true if c.empty?
  c = c.sub(/\A\$\{\{(.*)\}\}\z/m, '\\1').strip
  e = c.gsub('github.event_name', "'merge_group'")
       .gsub('github.ref', "'refs/heads/gh-readonly-queue/main/pr-1-0000000'")
       .gsub(/\b(always|success)\(\)/, 'true').gsub(/\b(failure|cancelled)\(\)/, 'false')
  next false unless e.gsub(/'[^']*'/, '').match?(/\A[\s()!=&|truefals]*\z/)
  begin
    eval(e) == true # rubocop:disable Security/Eval -- the token check above admits only literals and operators
  rescue SyntaxError, StandardError
    false
  end
end
skips_merge_group = ->(cond) { !runs_on_merge_group.call(cond) }
pr_only = ->(cond) { c = cond.to_s; c.include?("github.event_name == 'pull_request'") && !c.include?('||') }

REQUIRED.each do |file, checks|
  path = File.join(dir, file)
  unless File.exist?(path)
    check.call("#{file} exists", false, path)
    next
  end
  wf = YAML.load_file(path)
  trig = wf[true] || wf['on'] || {}           # YAML 1.1 reads the key `on` as true
  mg = trig.is_a?(Hash) ? trig['merge_group'] : nil
  check.call("#{file}: triggers on merge_group (checks_requested)",
             trig.is_a?(Hash) && trig.key?('merge_group') && mg.is_a?(Hash) && mg['types'] == ['checks_requested'],
             "on: #{trig.inspect}")

  jobs = wf['jobs'] || {}
  by_name = jobs.to_h { |id, j| [(j['name'] || id), id] }
  checks.each do |name|
    id = by_name[name]
    check.call("#{file}: required check '#{name}' is a job", !id.nil?, "jobs: #{by_name.keys.inspect}")
    next unless id
    # the required job and everything it needs, transitively, must run on a merge group
    seen = []
    todo = [id]
    until todo.empty?
      j = todo.shift
      next if seen.include?(j)
      seen << j
      todo.concat(Array(jobs[j]['needs']))
    end
    skipped = seen.select { |j| skips_merge_group.call(jobs[j]['if']) }
    check.call("#{file}: '#{name}' and the #{seen.size - 1} job(s) it needs run on a merge group", skipped.empty?,
               "skipped by their if: #{skipped.map { |j| "#{j} (#{jobs[j]['if']})" }.join(', ')}")
  end

  # pull-request-only context must be gated to pull_request
  leaks = []
  jobs.each do |jid, job|
    job_gated = pr_only.call(job['if'])
    (job['steps'] || []).each_with_index do |st, i|
      next if job_gated || pr_only.call(st['if'])
      leaks << "#{jid} step #{st['name'] || i}" if st.to_s.include?('github.event.pull_request')
    end
    rest = job.reject { |k, _| k == 'steps' }
    leaks << "#{jid} (job level)" if !job_gated && rest.to_s.include?('github.event.pull_request')
  end
  check.call("#{file}: github.event.pull_request is read only under a pull_request gate", leaks.empty?, leaks.join('; '))

  conc = wf['concurrency']
  if conc
    cancel = conc['cancel-in-progress'].to_s
    check.call("#{file}: concurrency never cancels a merge group",
               cancel == 'false' || cancel == "${{ github.event_name == 'pull_request' }}", "cancel-in-progress: #{cancel}")
    group = conc['group'].to_s
    check.call("#{file}: a merge group's concurrency group falls through to its unique ref",
               group.include?('github.ref') && !group.include?('merge_group'), "group: #{group}")
  end
end

dco = YAML.load_file(File.join(dir, 'dco.yml'))
steps = dco.dig('jobs', 'check_dco', 'steps') || []
runner = steps.find { |s| s['uses'].to_s.start_with?('christophebedard/dco-check') }
check.call('dco.yml: dco-check still runs on every pull request',
           runner && runner['if'] == "github.event_name == 'pull_request'", runner && runner['if'].inspect)
reporter = steps.find { |s| s['if'] == "github.event_name == 'merge_group'" }
check.call('dco.yml: a merge group gets a step of its own that reports the check', !reporter.nil? && reporter['run'].to_s.strip != '')
check.call('dco.yml: no step runs unconditionally (each event runs exactly one)', steps.all? { |s| s['if'] })

scan = YAML.load_file(File.join(dir, 'secret-scan.yml'))
th = (scan.dig('jobs', 'trufflehog-scan', 'steps') || []).find { |s| s['uses'].to_s.start_with?('trufflesecurity/trufflehog') }
w = (th && th['with']) || {}
check.call("secret-scan.yml: a merge group scans its own base..head",
           w['base'] == '${{ github.event.merge_group.base_sha }}' && w['head'] == '${{ github.event.merge_group.head_sha }}',
           "with: #{w.inspect}")
checkout = (scan.dig('jobs', 'trufflehog-scan', 'steps') || []).find { |s| s['uses'].to_s.start_with?('actions/checkout') }
check.call('secret-scan.yml: the checkout has the history the range needs (fetch-depth: 0)',
           checkout && checkout.dig('with', 'fetch-depth') == 0)

puts
puts "#{pass} passed, #{fail} failed"
exit(fail.zero? ? 0 : 1)
RUBY
