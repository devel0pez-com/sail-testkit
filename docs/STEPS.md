# Step definitions: what mirrors what

The `.feature` files come from Sail, as a submodule pinned in `versions.json`.
The step definitions do not — they are reimplemented in Scala in
`src/test/scala/com/devel0pez/sail/testkit/QuerySteps.scala`.

That is the price of driving their corpus from the JVM, and it has a failure
mode worth naming: **a step whose meaning changes upstream does not break, it
just starts answering a different question**. A missing step shows up in the
report as "step not implemented here"; a step that quietly changed semantics
shows up as a pile of divergences that look like engine bugs.

So when the submodule is bumped, diff these four files first:

| upstream | what to check |
| --- | --- |
| `python/pysail/testing/spark/steps/sql.py` | the SQL steps below |
| `python/pysail/tests/spark/conftest.py` | `pytest_bdd_apply_tag`: what each tag means |
| `python/pysail/testing/spark/session.py` | `configure_spark_session`: session config |
| `python/pysail/testing/spark/utils/sql.py` | `parse_show_string`: how a result table is read |

## The mapping

| step | upstream | ours |
| --- | --- | --- |
| `query` | `sql.py:query` — renders Jinja if `template`, does **not** execute | analysis only |
| `query schema` | `query_schema` → `assert_schema_tree` (`treeString`) | same |
| `query result [ordered]` | `query_result` → `_show_string` + `parse_show_string` | `show()` captured and parsed the same way |
| `query result collected [ordered]` | `query_result_collected` → `collect()`, NULL and lowercase booleans | same |
| `query error <regex>` | `query_error` → `pytest.raises(match=...)` over `collect()` | forces analysis and execution |
| `statement [template]` | `statement` | executed for its side effect |
| `statement with error <regex>` | `statement_with_error` | same |
| `final statement` | `final_statement` — runs after the scenario | `After` hook |
| `config K = V` | `spark_config_override` — **restores the old value afterwards** | same, unwound in reverse |
| `variable X for temporary directory Y` | `variable_for_temporary_directory` + `PathWrapper` | `.string`, `.sql`, `.uri`, `.file_uri` |
| `dataframe for <case>` | `dataframe_for` — a dict of 11 named DataFrames | ported case by case |
| `query plan matches snapshot` | `plan.py` + syrupy YAML keyed by the pytest test name | **not portable**, reported as pending |

## Tag semantics, which depend on the server

From `conftest.py:pytest_bdd_apply_tag`:

| tag | against Sail | against Spark |
| --- | --- | --- |
| `@sail-only` | runs | skipped (`-Dcucumber.filter.tags='not @sail-only'`) |
| `@sail-bug` | expected to fail | expected to **pass** |
| `@spark-X.Y` | skipped below that Spark version | same |

Upstream marks `@sail-bug` as `xfail(strict=True)`, so a fixed bug turns their
suite red until the tag goes. The report counts "`@sail-bug` passing"
separately for the same reason.

## Things that cost time to find out

- **`query result` does not collect.** It reads the table the *server* renders
  with `show()`. Collecting instead sends every value through the Arrow
  deserializer, so a type the JVM client cannot decode fails a scenario that
  was never about decoding — and every formatting difference counts as a
  divergence that is ours.
- **`config` must be restored.** A scenario that sets
  `spark.sql.session.timeZone = America/New_York` and leaves it set shifts
  every later result by hours. That was 113 failures.
- **An empty Gherkin cell is an empty string, not NULL.** Cucumber hands it
  over as `null`; turning that into `"NULL"` made every empty-string
  expectation look like a NULL the engine failed to produce.
- **Cucumber unescapes `\\` in Examples tables; pytest-bdd does not.** So
  `'(\\d+)'` arrives as `(\d+)`, Spark eats the backslash too, and the regex
  becomes `(d+)`. `QuerySteps.repararEscapes` reads the row back from the file
  to undo it. Docstrings are not unescaped by anyone, which is why the same
  patterns work there.
- **The JVM renders timestamps in its own zone.** Setting the session time zone
  only decides what the server computes; `-Duser.timezone=UTC` is what stops
  the report blaming Sail for the developer's locale.
