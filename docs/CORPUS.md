# Where the corpus comes from

`sail-features/` is a git submodule of [lakehq/sail](https://github.com/lakehq/sail),
pinned to the release named in `versions.json`. The feature files live under
`sail-features/python/pysail/tests/spark/`.

## Why it is pinned, and why that matters

The corpus and the `pysail` binary **must come from the same release**. Running
a newer corpus against an older binary reports every fix that landed in between
as though it were a bug.

That is not hypothetical. Running HEAD's corpus against pysail 0.7.0 produced
96 "divergences", including `sum` over strings — which had been fixed ten days
after the release, in #2410, 64 commits earlier. With the corpus pinned to
v0.7.0, those 96 went to zero.

So bumping Sail means bumping **both**: the submodule and `pysail` in
`versions.json`, together.

## Do not edit anything in there

It is a verbatim copy. The moment a file is touched, a difference stops meaning
"Sail and Spark disagree" and starts meaning "we edited the question".

## What is run, and what is not

Only `spark/function/` — 4.972 scenarios of pure SQL in, rows or schema out.
Counted as Cucumber runs them, one per Examples row: 2.960 are declared in the
files, and the corpus as a whole is 5.522 against 3.494. Every figure in this
repo uses the expanded basis, because that is the number a report prints.
The rest (delta, iceberg, catalog, dml) drives real tables, temporary
directories and external catalogs like Glue and Unity through steps such as
`Given variable location for temporary directory ...`. Running those without
that harness would report infrastructure we never set up as though Sail
disagreed with Spark, which is worse than not reporting them at all.
