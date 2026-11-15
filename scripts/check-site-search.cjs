#!/usr/bin/env node
// Runs the representative searches in scripts/search-queries.tsv against the built site's search index and fails when a
// query's expected page is not among its first three results (#2291).
//
//   node scripts/check-site-search.cjs <site-dir> [queries-file]
//
// It searches the way the site's search box does: the index is built from assets/js/search-data.json (guides plus the
// Scaladoc types scripts/add-scaladoc-to-search.py adds) with the lunr the site ships (assets/js/vendor/lunr.min.js),
// the fields, boosts and tokenizer of the rendered assets/js/just-the-docs.js, and the same query: each token exact
// (boost 10) or as a prefix, then, when nothing matches, with an edit distance.
"use strict";
const fs = require("fs");
const path = require("path");

function fail(msg) {
  console.error(`::error::${msg}`);
  process.exit(1);
}

const [site, queriesArg] = process.argv.slice(2);
if (!site) fail("usage: check-site-search.cjs <site-dir> [queries-file]");
const queriesFile = queriesArg || path.join(__dirname, "search-queries.tsv");
const file = (p) => {
  const f = path.join(site, p);
  if (!fs.existsSync(f)) fail(`${f} is missing: build the site first`);
  return f;
};

const lunr = require(path.resolve(file("assets/js/vendor/lunr.min.js")));
const jtd = fs.readFileSync(file("assets/js/just-the-docs.js"), "utf8");
const sep = jtd.match(/lunr\.tokenizer\.separator\s*=\s*\/(.+?)\/\s*\n/);
if (!sep) fail("assets/js/just-the-docs.js sets no lunr.tokenizer.separator: has just-the-docs changed its search?");
lunr.tokenizer.separator = new RegExp(sep[1]);
const fields = [...jtd.matchAll(/this\.field\('(\w+)'(?:,\s*\{\s*boost:\s*(\d+)\s*\})?\)/g)].map((m) => [m[1], m[2]]);
if (!fields.some(([f]) => f === "title")) fail("assets/js/just-the-docs.js indexes no title field: has its search changed?");

const docs = JSON.parse(fs.readFileSync(file("assets/js/search-data.json"), "utf8"));
const index = lunr(function () {
  this.ref("id");
  for (const [f, boost] of fields) this.field(f, boost ? { boost: Number(boost) } : undefined);
  this.metadataWhitelist = ["position"];
  for (const i in docs) {
    const d = { id: i };
    for (const [f] of fields) d[f] = docs[i][f];
    this.add(d);
  }
});

function search(input) {
  let results = index.query((q) => {
    const tokens = lunr.tokenizer(input);
    q.term(tokens, { boost: 10 });
    q.term(tokens, { wildcard: lunr.Query.wildcard.TRAILING });
  });
  if (results.length === 0 && input.length > 2) {
    const tokens = lunr.tokenizer(input).filter((t) => t.str.length < 20);
    if (tokens.length > 0) {
      results = index.query((q) => {
        q.term(tokens, { editDistance: Math.round(Math.sqrt(input.length / 2 - 1)) });
      });
    }
  }
  return results.map((r) => docs[r.ref].relUrl);
}

const page = (u) => u.split("#")[0].replace(/\.html$/, "").replace(/\/index$/, "/");
const queries = fs
  .readFileSync(queriesFile, "utf8")
  .split("\n")
  .filter((l) => l.trim() && !l.startsWith("#"))
  .map((l) => l.split("\t").map((s) => s.trim()));
if (queries.length === 0) fail(`${queriesFile} lists no queries`);

let missing = 0;
for (const q of queries) {
  if (q.length !== 2 || !q[0] || !q[1]) fail(`${queriesFile}: want "<query><TAB><expected page>", got: ${q.join(" | ")}`);
  const [query, expected] = q;
  const top = search(query).slice(0, 3);
  const rank = top.findIndex((u) => page(u) === page(expected));
  if (rank < 0) {
    missing++;
    console.log(`::error::"${query}": ${expected} is not in the top 3: ${top.join(", ") || "no results"}`);
  } else {
    console.log(`ok: "${query}" -> ${expected} (#${rank + 1})`);
  }
}
if (missing > 0) fail(`${missing} of ${queries.length} searches miss their expected page in the top 3`);
console.log(`All ${queries.length} searches find their expected page in the top 3.`);
