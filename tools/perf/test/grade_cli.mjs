// For test_summary.py's parity test: stdin {"summaries": [...]} -> stdout [gradeSummary(...)].
import { readFileSync } from "node:fs";
import { gradeSummary } from "../grade.ts";

const rubric = JSON.parse(readFileSync(new URL("../page/rubric.json", import.meta.url)));
const input = JSON.parse(readFileSync(0, "utf8"));
process.stdout.write(JSON.stringify(input.summaries.map((s) => gradeSummary(s, rubric))));
