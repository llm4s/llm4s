#!/usr/bin/env bash
# Fails when platform-sensitive code would not be tested on Windows.
#
# CI's `Test (windows-latest, 21)` job runs `sbt testPlatform`, not `sbt test`: Windows runs the same JVM as Linux,
# so it runs only the tests of what differs between platforms (project/PlatformTests.scala) - every suite of the
# modules in `PlatformTests.wholeModules`, and the suites `PlatformTests.suites` names in other modules. Leaving the
# rest to ubuntu is only safe while every piece of platform-sensitive code is in one of those. This checks, against
# the build sbt loaded (the build model, project/BuildModel.scala):
#
#   - every main source file under modules/ that matches PATTERN below (processes, path separators, permissions and
#     links, temporary files, writing, moving or deleting files, file locks, SQLite, line separators, the OS name,
#     the user's home) is in a whole module, or is listed in the sources file with what covers it;
#   - every test source file that asks which OS it runs on (`os.name`, `isWin`) runs on Windows, since it was written
#     to behave differently there;
#   - every line of the sources file names an existing file, and coverage that resolves: `<module>:<suite>` must be a
#     suite name `PlatformTests.suites` lists for that module, and `none: <reason>` must give a reason; a file in a
#     whole module needs no line, so one is reported as redundant;
#   - every module the lists name is a project of the build, and the workflow's Windows test step runs testPlatform.
#
# `sbt testPlatform` itself fails when a listed suite name matches no compiled suite (a rename), so this does not
# repeat that. No network, no sbt when given a model. Needs ruby (on GitHub's runners).
#
# Usage: scripts/check-platform-tests.sh [--model FILE] [--sources FILE] [--root DIR] [--workflow FILE]
#   --model     the build model (default target/build-model.json; write it with `sbt "dumpBuildModel FILE"`)
#   --sources   the platform-sensitive sources list (default scripts/platform-sensitive-sources.txt)
#   --root      the repository whose modules/ are scanned (default this repository)
#   --workflow  the workflow holding the `test` job (default .github/workflows/ci.yml)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODEL="$REPO_ROOT/target/build-model.json"
SOURCES="$REPO_ROOT/scripts/platform-sensitive-sources.txt"
ROOT="$REPO_ROOT"
WORKFLOW="$REPO_ROOT/.github/workflows/ci.yml"
while [ $# -gt 0 ]; do
  case "$1" in
    --model) MODEL="$2"; shift 2 ;;
    --sources) SOURCES="$2"; shift 2 ;;
    --root) ROOT="$2"; shift 2 ;;
    --workflow) WORKFLOW="$2"; shift 2 ;;
    -h|--help) sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed '$d'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
command -v ruby >/dev/null || { echo "ruby is needed to run this check" >&2; exit 2; }
for f in "$MODEL" "$SOURCES" "$WORKFLOW"; do
  [ -f "$f" ] || { echo "No such file: $f" >&2; exit 2; }
done
[ -d "$ROOT/modules" ] || { echo "No modules/ directory in $ROOT" >&2; exit 2; }

ruby -ryaml -rjson - "$MODEL" "$SOURCES" "$ROOT" "$WORKFLOW" <<'RUBY'
model_file, sources_file, root, workflow_file = ARGV

# A call whose behaviour differs between Linux, macOS and Windows. Reading a file is left out: it is everywhere and
# behaves the same; creating, writing, moving and deleting are in, because Windows refuses them on an open file.
PATTERN = Regexp.new([
  'isWin', 'os\.name', 'File\.separator', 'pathSeparator', 'separatorChar',
  'ProcessBuilder', 'sys\.process', 'Runtime\.getRuntime\.exec',
  'lineSeparator', 'FileChannel', 'FileLock', 'tryLock',
  'PosixFilePermission', 'setExecutable', 'setReadable', 'setWritable', 'toRealPath', 'LinkOption',
  'createSymbolicLink', 'createTemp', 'java\.io\.tmpdir', 'user\.home', 'user\.dir', 'jdbc:sqlite',
  # Whole method names, each ended by \b: a prefix such as `delete` or `createDirector` would miss
  # `deleteIfExists` and `createDirectory`/`createDirectories`.
  'Files\.(?:delete|deleteIfExists|move|copy|createDirectory|createDirectories|createFile|createLink|' \
    'createTempFile|createTempDirectory|newOutputStream|newBufferedWriter|write|writeString|' \
    'setPosixFilePermissions|setAttribute|setLastModifiedTime|setOwner)\b',
  # java.io.File and its streams: the same operations without java.nio.file.Files.
  # `delete()` is File's: it takes no argument, unlike a store's or HTTP client's `delete(id)`.
  '\.delete\(\)', '\.(?:renameTo|mkdirs?|deleteOnExit|createNewFile|setLastModified|setReadOnly)\(',
  'FileOutputStream', 'FileWriter', 'RandomAccessFile'
].join('|'))
# A test that asks which OS it is on was written to behave differently on Windows.
OS_PROBE = /os\.name|isWin|IS_OS_WINDOWS/

errors = []
model = JSON.parse(File.read(model_file))
platform = model['platformTests'] or abort "The build model has no platformTests field; rewrite it with this " \
                                            "build's dumpBuildModel"
projects = model.fetch('projects').to_h { |p| [p.fetch('id'), p.fetch('base')] }
whole = Array(platform['modules'])
suites = (platform['suites'] || {}).to_h { |m, names| [m, Array(names)] }

(whole + suites.keys).uniq.each do |m|
  errors << "PlatformTests lists #{m}, which is not a project of this build" unless projects.key?(m)
end
whole_bases = whole.map { |m| projects[m] }.compact.map { |b| "#{b}/" }
in_whole = ->(path) { whole_bases.any? { |b| path.start_with?(b) } }

def glob_regexp(glob)
  Regexp.new("\\A#{glob.split('*', -1).map { |s| Regexp.escape(s) }.join('.*')}\\z")
end

# The sources file: `<path> <coverage>`, `#` comments and blank lines ignored.
entries = {}
File.readlines(sources_file, chomp: true).each_with_index do |line, i|
  next if line.strip.empty? || line.lstrip.start_with?('#')
  path, coverage = line.strip.split(/\s+/, 2)
  where = "#{File.basename(sources_file)}:#{i + 1}"
  if coverage.nil?
    errors << "#{where}: #{path} names no coverage (`<module>:<suite>` or `none: <reason>`)"
    next
  end
  errors << "#{where}: #{path} is listed twice" if entries.key?(path)
  entries[path] = coverage
  target = File.join(root, path)
  unless path.end_with?('/') ? File.directory?(target) : File.file?(target)
    errors << "#{where}: #{path} does not exist: remove the line, or list the file's new path"
    next
  end
  if in_whole[path]
    errors << "#{where}: #{path} is in a module whose every suite runs on Windows; the line is redundant: remove it"
    next
  end
  case coverage
  when /\Anone:\s*\S/ then nil
  when /\Anone/ then errors << "#{where}: `none` for #{path} needs a reason: `none: <why Windows need not run it>`"
  when /\A([A-Za-z0-9_]+):(\S+)\z/
    m, name = Regexp.last_match(1), Regexp.last_match(2)
    if !suites.key?(m)
      errors << "#{where}: #{path} is covered by #{m}:#{name}, but PlatformTests.suites has no entry for #{m}"
    elsif !suites[m].include?(name)
      errors << "#{where}: #{path} is covered by #{m}:#{name}, which PlatformTests.suites does not list for #{m} " \
                "(it lists #{suites[m].join(', ')})"
    end
  else
    errors << "#{where}: cannot read the coverage of #{path}: '#{coverage}' (want `<module>:<suite>` or `none: <reason>`)"
  end
end
covered = ->(path) { entries.keys.any? { |e| e.end_with?('/') ? path.start_with?(e) : path == e } }

files = Dir.chdir(root) { Dir.glob('modules/**/src/{main,test}/**/*.{scala,java}') }.reject { |f| f.include?('/target/') }.sort
main_files, test_files = files.partition { |f| f.include?('/src/main/') }

uncovered = main_files.select { |f| File.read(File.join(root, f)).match?(PATTERN) }.reject { |f| in_whole[f] || covered[f] }
uncovered.each do |f|
  errors << "#{f} calls a platform-sensitive API (#{File.read(File.join(root, f))[PATTERN]}) but is in no module that " \
            "runs whole on Windows and is not in #{File.basename(sources_file)}: add a suite that tests it to " \
            "PlatformTests.suites and list the file with it (or `none: <reason>`)"
end

# Which suites a test file declares, as fully qualified names, to match against PlatformTests.suites.
def declared_suites(text)
  pkg = text[/^\s*package\s+([\w.]+)/, 1]
  text.scan(/^\s*(?:final\s+|abstract\s+)*class\s+(\w+)/).flatten.map { |c| pkg ? "#{pkg}.#{c}" : c }
end
base_of = projects.to_h { |id, b| ["#{b}/", id] }
test_files.each do |f|
  text = File.read(File.join(root, f))
  next unless text.match?(OS_PROBE)
  next if in_whole[f]
  owner = base_of.select { |b, _| f.start_with?(b) }.max_by { |b, _| b.size }&.last
  names = owner ? suites.fetch(owner, []) : []
  next if declared_suites(text).any? { |s| names.any? { |n| glob_regexp(n).match?(s) } }
  errors << "#{f} checks which OS it runs on, so it was written for Windows too, but Windows does not run it: " \
            "add its suite to PlatformTests.suites#{owner ? " under #{owner}" : ''} (project/PlatformTests.scala)"
end

wf = YAML.load_file(workflow_file)
steps = Array(((wf['jobs'] || {})['test'] || {})['steps'])
unless steps.any? { |s| s['run'].to_s.include?('testPlatform') && s['run'].to_s.match?(/Windows/i) }
  errors << "#{File.basename(workflow_file)}: no step of the `test` job runs `sbt testPlatform` on Windows"
end

if errors.empty?
  puts "Windows runs #{whole.size} modules whole and #{suites.values.sum(&:size)} named suites in " \
       "#{suites.size} others (project/PlatformTests.scala)"
  puts "OK: every platform-sensitive main source (#{main_files.count { |f| File.read(File.join(root, f)).match?(PATTERN) }} " \
       "files) and every OS-probing test is tested on Windows or listed with a reason"
else
  errors.each { |e| puts "FAIL: #{e}" }
  exit 1
end
RUBY
